#include <jni.h>

#include <boost/asio/co_spawn.hpp>
#include <boost/asio/detached.hpp>
#include <boost/asio/io_context.hpp>
#include <boost/asio/post.hpp>
#include <nlohmann/json.hpp>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <exception>
#include <filesystem>
#include <functional>
#include <future>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <type_traits>
#include <utility>
#include <vector>

#include "i2pchat/bytes.hpp"
#include "i2pchat/encoding.hpp"

#include "i2pchat/groups/coordinator.hpp"
#include "i2pchat/groups/models.hpp"
#include "i2pchat/groups/store.hpp"
#include "i2pchat/router/i2pd.hpp"
#include "i2pchat/runtime/chat_service.hpp"
#include "i2pchat/runtime/identity.hpp"
#include "i2pchat/sam/destination.hpp"
#include "i2pchat/session/manager.hpp"
#include "i2pchat/session/peer_session.hpp"
#include "i2pchat/session/trust_store.hpp"
#include "i2pchat/storage/atomic_write.hpp"
#include "i2pchat/storage/chat_history.hpp"
#include "i2pchat/storage/compose_drafts.hpp"
#include "i2pchat/storage/contacts.hpp"
#include "i2pchat/storage/keyring.hpp"
#include "i2pchat/storage/profile_backup.hpp"
#include "i2pchat/storage/profile_dat.hpp"
#include "i2pchat/storage/profile_paths.hpp"
#include "i2pchat/storage/replica_settings.hpp"
#include "storage/keyring_backend.hpp"

namespace asio = boost::asio;
namespace fs = std::filesystem;
using i2pchat::runtime::ChatService;
using i2pchat::runtime::ChatServiceConfig;
using i2pchat::runtime::ChatEvents;
using i2pchat::runtime::DeliveryReport;
using i2pchat::runtime::kTransientProfile;

namespace {

/// A trust decision the user already made for one (peer, key) pair, consumed
/// by the next handshake with that peer. Prompts never block the io thread:
/// the handshake that raised the question is refused with Deferred, the
/// answer is recorded here, and the peer is dialled again on Accept.
struct TrustAnswer {
    std::string key_hex;
    i2pchat::session::TrustDecision decision = i2pchat::session::TrustDecision::Reject;
};

std::string jstring_to_utf8(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return out;
}

jstring utf8_to_jstring(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

nlohmann::json history_json(const i2pchat::storage::HistoryEntry& entry) {
    nlohmann::json json = {
        {"kind", entry.kind},
        {"text", entry.text},
        {"ts", entry.ts},
        {"deliveryHint", entry.delivery_hint},
        {"deliveryReason", entry.delivery_reason},
        {"retryable", entry.retryable},
    };
    if (entry.message_id) {
        json["messageId"] = *entry.message_id;
    }
    if (entry.delivery_state) {
        json["deliveryState"] = *entry.delivery_state;
    }
    if (entry.delivery_route) {
        json["deliveryRoute"] = *entry.delivery_route;
    }
    return json;
}

nlohmann::json group_history_json(const i2pchat::groups::HistoryEntry& entry) {
    return {
        {"kind", entry.kind},
        {"senderId", entry.sender_id},
        {"text", entry.text},
        {"msgId", entry.msg_id},
        {"groupSeq", entry.group_seq},
        {"epoch", entry.epoch},
        {"createdAt", entry.created_at},
        {"sourcePeer", entry.source_peer},
    };
}

/// Members arrive from Kotlin as a JSON array; anything that is not a string
/// is dropped rather than letting nlohmann throw through the JNI boundary.
std::vector<std::string> parse_member_list(const std::string& raw) {
    std::vector<std::string> list;
    const auto members = nlohmann::json::parse(raw, nullptr, false);
    if (members.is_array()) {
        for (const auto& item : members) {
            if (item.is_string()) {
                list.push_back(item.get<std::string>());
            }
        }
    }
    return list;
}

nlohmann::json group_state_json(const i2pchat::groups::GroupState& state) {
    return {
        {"groupId", state.group_id()},
        {"title", state.title()},
        {"epoch", state.epoch()},
        {"members", state.members()},
    };
}

struct Engine {
    JavaVM* vm = nullptr;
    jobject listener = nullptr;
    asio::io_context io;
    std::unique_ptr<asio::executor_work_guard<asio::io_context::executor_type>> work;
    std::thread thread;
    std::unique_ptr<ChatService> service;
    // Touched only on the io thread (prompt handler, on_core lambdas).
    std::map<std::string, TrustAnswer> trust_answers;
    // Peers whose prompt is currently shown, so a reconnecting peer does not
    // re-raise the same dialog every attempt.
    std::map<std::string, std::string> trust_asked;
    std::atomic<bool> stopping{false};

    JNIEnv* env() {
        JNIEnv* local = nullptr;
        if (vm->GetEnv(reinterpret_cast<void**>(&local), JNI_VERSION_1_6) == JNI_OK) {
            return local;
        }
        if (vm->AttachCurrentThread(&local, nullptr) != JNI_OK) {
            return nullptr;
        }
        return local;
    }

    void call_void(const char* name, const char* sig, auto&&... args) {
        JNIEnv* local = env();
        if (local == nullptr || listener == nullptr) {
            return;
        }
        jclass cls = local->GetObjectClass(listener);
        if (cls == nullptr) {
            return;
        }
        jmethodID method = local->GetMethodID(cls, name, sig);
        if (method != nullptr) {
            local->CallVoidMethod(listener, method, std::forward<decltype(args)>(args)...);
            if (local->ExceptionCheck()) {
                local->ExceptionClear();
            }
        }
        local->DeleteLocalRef(cls);
    }

    void emit_string(const char* name, const std::string& a) {
        JNIEnv* local = env();
        if (local == nullptr) {
            return;
        }
        if (local->ExceptionCheck()) {
            local->ExceptionClear();
        }
        jstring ja = utf8_to_jstring(local, a);
        call_void(name, "(Ljava/lang/String;)V", ja);
        local->DeleteLocalRef(ja);
    }

    void emit_2string(const char* name, const std::string& a, const std::string& b) {
        JNIEnv* local = env();
        if (local == nullptr) {
            return;
        }
        jstring ja = utf8_to_jstring(local, a);
        jstring jb = utf8_to_jstring(local, b);
        call_void(name, "(Ljava/lang/String;Ljava/lang/String;)V", ja, jb);
        local->DeleteLocalRef(ja);
        local->DeleteLocalRef(jb);
    }

    void emit_3string(const char* name, const std::string& a, const std::string& b,
                      const std::string& c) {
        JNIEnv* local = env();
        if (local == nullptr) {
            return;
        }
        jstring ja = utf8_to_jstring(local, a);
        jstring jb = utf8_to_jstring(local, b);
        jstring jc = utf8_to_jstring(local, c);
        call_void(name, "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", ja, jb, jc);
        local->DeleteLocalRef(ja);
        local->DeleteLocalRef(jb);
        local->DeleteLocalRef(jc);
    }

    void start_io() {
        io.restart();
        work = std::make_unique<asio::executor_work_guard<asio::io_context::executor_type>>(
            asio::make_work_guard(io));
        thread = std::thread([this] { io.run(); });
    }

    void join_io() {
        work.reset();
        io.stop();
        if (thread.joinable()) {
            thread.join();
        }
    }

    template <typename T>
    T on_core(std::function<T()> fn) {
        std::promise<T> promise;
        asio::post(io, [&] {
            try {
                if constexpr (std::is_void_v<T>) {
                    fn();
                    promise.set_value();
                } else {
                    promise.set_value(fn());
                }
            } catch (...) {
                promise.set_exception(std::current_exception());
            }
        });
        return promise.get_future().get();
    }

    template <typename T>
    T await_core(std::function<asio::awaitable<T>()> fn) {
        std::promise<T> promise;
        asio::co_spawn(
            io,
            [&]() -> asio::awaitable<void> {
                try {
                    if constexpr (std::is_void_v<T>) {
                        co_await fn();
                        promise.set_value();
                    } else {
                        promise.set_value(co_await fn());
                    }
                } catch (...) {
                    promise.set_exception(std::current_exception());
                }
            },
            asio::detached);
        return promise.get_future().get();
    }
};

Engine g_engine;

nlohmann::json profile_error(const char* code) {
    return {{"ok", false}, {"code", code}};
}

/// A saved profile is one directory directly under `profiles/`, never a symlink
/// and never a path that canonicalises outside that directory.
std::optional<fs::path> saved_profile_dir(const fs::path& root, const std::string& name) {
    if (!i2pchat::storage::is_valid_profile_name(name) || name == kTransientProfile) {
        return std::nullopt;
    }
    std::error_code ec;
    const fs::path profiles = fs::weakly_canonical(root / "profiles", ec);
    if (ec) {
        return std::nullopt;
    }
    const fs::path link = profiles / name;
    if (fs::is_symlink(link, ec)) {
        return std::nullopt;
    }
    const fs::path dir = fs::weakly_canonical(link, ec);
    if (ec || dir.parent_path() != profiles || !fs::is_directory(dir)) {
        return std::nullopt;
    }
    return dir;
}

bool profile_is_open(const std::string& name) {
    return g_engine.service && g_engine.service->paths().profile() == name;
}

void rename_prefixed_files(const fs::path& dir, const std::string& from, const std::string& to) {
    if (!fs::is_directory(dir)) {
        return;
    }
    std::vector<std::string> names;
    for (const auto& entry : fs::directory_iterator(dir)) {
        std::error_code ec;
        if (entry.is_directory(ec)) {
            continue;
        }
        const std::string fname = entry.path().filename().string();
        if (fname.starts_with(from + ".")) {
            names.push_back(fname);
        }
    }
    for (const auto& fname : names) {
        const fs::path dest = dir / (to + fname.substr(from.size()));
        if (fs::exists(dest)) {
            throw std::runtime_error("exists");
        }
        fs::rename(dir / fname, dest);
    }
}

struct MovedSecret {
    std::string from;
    std::string to;
    std::string value;
};

void move_keyring_account(const std::string& from, const std::string& to,
                          std::vector<MovedSecret>& moved) {
    const std::optional<std::string> value =
        i2pchat::storage::keyring::get(i2pchat::storage::kKeyringService, from);
    if (!value.has_value()) {
        return;
    }
    if (!i2pchat::storage::keyring::set(i2pchat::storage::kKeyringService, to, *value)) {
        throw std::runtime_error("keyring");
    }
    i2pchat::storage::keyring::erase(i2pchat::storage::kKeyringService, from);
    moved.push_back({from, to, *value});
}

void restore_keyring(const std::vector<MovedSecret>& moved) {
    for (auto it = moved.rbegin(); it != moved.rend(); ++it) {
        i2pchat::storage::keyring::set(i2pchat::storage::kKeyringService, it->from, it->value);
        i2pchat::storage::keyring::erase(i2pchat::storage::kKeyringService, it->to);
    }
}

std::string catch_message(const std::exception_ptr& ptr) {
    try {
        if (ptr) {
            std::rethrow_exception(ptr);
        }
    } catch (const std::exception& ex) {
        return ex.what();
    } catch (...) {
        return "unknown error";
    }
    return {};
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_engine.vm = vm;
    i2pchat::storage::keyring::backend::set_java_vm(vm);
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        i2pchat::storage::keyring::backend::init_jni(env);
    }
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_org_i2pchat_android_NativeEngine_nativeInitKeyring(JNIEnv* env, jclass) {
    i2pchat::storage::keyring::backend::init_jni(env);
}

extern "C" JNIEXPORT void JNICALL
Java_org_i2pchat_android_NativeEngine_nativeSetListener(JNIEnv* env, jclass, jobject listener) {
    if (g_engine.listener != nullptr) {
        env->DeleteGlobalRef(g_engine.listener);
        g_engine.listener = nullptr;
    }
    if (listener != nullptr) {
        g_engine.listener = env->NewGlobalRef(listener);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeListProfiles(JNIEnv* env, jclass, jstring j_root) {
    const fs::path root = jstring_to_utf8(env, j_root);
    nlohmann::json names = nlohmann::json::array();
    const fs::path dir = root / "profiles";
    std::error_code ec;
    try {
        if (fs::exists(dir, ec)) {
            for (const auto& entry : fs::directory_iterator(dir, ec)) {
                std::error_code entry_ec;
                if (!entry.is_directory(entry_ec)) {
                    continue;
                }
                const std::string name = entry.path().filename().string();
                if (name.empty() || name == kTransientProfile ||
                    !i2pchat::storage::is_valid_profile_name(name)) {
                    continue;
                }
                std::error_code dat_ec;
                if (fs::is_regular_file(entry.path() / (name + ".dat"), dat_ec)) {
                    names.push_back(name);
                }
            }
        }
    } catch (...) {
        // A directory that cannot be listed yields an empty profile list.
    }
    return utf8_to_jstring(env, names.dump());
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeProfileAddress(
    JNIEnv* env, jclass, jstring j_root, jstring j_profile, jstring j_password) {
    try {
        const std::string name = jstring_to_utf8(env, j_profile);
        const std::string password = jstring_to_utf8(env, j_password);
        if (name == kTransientProfile) {
            return utf8_to_jstring(env, profile_error("transient").dump());
        }
        if (!i2pchat::storage::is_valid_profile_name(name)) {
            return utf8_to_jstring(env, profile_error("invalid_name").dump());
        }
        const auto dir = saved_profile_dir(jstring_to_utf8(env, j_root), name);
        if (!dir.has_value()) {
            return utf8_to_jstring(env, profile_error("missing").dump());
        }
        const fs::path dat = *dir / (name + ".dat");
        if (!fs::is_regular_file(dat)) {
            return utf8_to_jstring(env, profile_error("missing").dump());
        }
        const i2pchat::Bytes raw = i2pchat::storage::read_file(dat);
        if (i2pchat::storage::profile_dat_version(i2pchat::ByteView(raw)) ==
                i2pchat::storage::kProfileDatVersionPassword &&
            password.empty()) {
            return utf8_to_jstring(env, profile_error("needs_password").dump());
        }
        const i2pchat::storage::ProfileDatContents contents =
            i2pchat::storage::read_profile_dat_file(
                dat, name, *dir,
                [](const std::string& line) { return line.size() < 600; }, false, password);
        if (!contents.private_key_base64.has_value() || contents.private_key_base64->empty()) {
            return utf8_to_jstring(env, profile_error("missing").dump());
        }
        const std::string address =
            i2pchat::sam::Destination::from_private_base64(*contents.private_key_base64).base32();
        return utf8_to_jstring(
            env, nlohmann::json{{"ok", true}, {"address", address}}.dump());
    } catch (const i2pchat::storage::ProfileDatError& ex) {
        const std::string message = ex.what();
        if (message.find("Wrong profile password") != std::string::npos) {
            return utf8_to_jstring(env, profile_error("wrong_password").dump());
        }
        if (message.find("password is required") != std::string::npos) {
            return utf8_to_jstring(env, profile_error("needs_password").dump());
        }
        nlohmann::json json = profile_error("failed");
        json["error"] = message;
        return utf8_to_jstring(env, json.dump());
    } catch (const std::exception& ex) {
        nlohmann::json json = profile_error("failed");
        json["error"] = ex.what();
        return utf8_to_jstring(env, json.dump());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeRenameProfile(
    JNIEnv* env, jclass, jstring j_root, jstring j_from, jstring j_to) {
    const std::string from = jstring_to_utf8(env, j_from);
    const std::string to = jstring_to_utf8(env, j_to);
    if (from == kTransientProfile || to == kTransientProfile) {
        return utf8_to_jstring(env, profile_error("reserved").dump());
    }
    if (!i2pchat::storage::is_valid_profile_name(from) ||
        !i2pchat::storage::is_valid_profile_name(to)) {
        return utf8_to_jstring(env, profile_error("invalid_name").dump());
    }
    if (from == to) {
        return utf8_to_jstring(env, nlohmann::json{{"ok", true}}.dump());
    }
    if (profile_is_open(from)) {
        return utf8_to_jstring(env, profile_error("busy").dump());
    }
    const fs::path root = jstring_to_utf8(env, j_root);
    const auto source = saved_profile_dir(root, from);
    if (!source.has_value() || !fs::is_regular_file(*source / (from + ".dat"))) {
        return utf8_to_jstring(env, profile_error("missing").dump());
    }
    std::error_code ec;
    const fs::path profiles = fs::weakly_canonical(root / "profiles", ec);
    const fs::path dest = profiles / to;
    if (ec || fs::exists(dest)) {
        return utf8_to_jstring(env, profile_error("exists").dump());
    }
    bool renamed_dir = false;
    std::vector<MovedSecret> moved;
    try {
        fs::rename(*source, dest);
        renamed_dir = true;
        rename_prefixed_files(dest, from, to);
        if (fs::is_directory(dest / "data")) {
            rename_prefixed_files(dest / "data", from, to);
        }
        move_keyring_account(i2pchat::storage::dat_wrap_keyring_account(from),
                             i2pchat::storage::dat_wrap_keyring_account(to), moved);
        move_keyring_account(i2pchat::runtime::signing_keyring_account(from),
                             i2pchat::runtime::signing_keyring_account(to), moved);
        return utf8_to_jstring(env, nlohmann::json{{"ok", true}}.dump());
    } catch (const std::exception& ex) {
        restore_keyring(moved);
        std::error_code ignore;
        if (renamed_dir) {
            rename_prefixed_files(dest, to, from);
            if (fs::is_directory(dest / "data")) {
                rename_prefixed_files(dest / "data", to, from);
            }
            fs::rename(dest, *source, ignore);
        }
        const std::string message = ex.what();
        if (message == "exists") {
            return utf8_to_jstring(env, profile_error("exists").dump());
        }
        nlohmann::json json = profile_error("failed");
        json["error"] = message;
        return utf8_to_jstring(env, json.dump());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeDeleteProfile(
    JNIEnv* env, jclass, jstring j_root, jstring j_profile) {
    const std::string name = jstring_to_utf8(env, j_profile);
    if (name == kTransientProfile) {
        return utf8_to_jstring(env, profile_error("reserved").dump());
    }
    if (!i2pchat::storage::is_valid_profile_name(name)) {
        return utf8_to_jstring(env, profile_error("invalid_name").dump());
    }
    if (profile_is_open(name)) {
        return utf8_to_jstring(env, profile_error("busy").dump());
    }
    const auto dir = saved_profile_dir(jstring_to_utf8(env, j_root), name);
    if (!dir.has_value()) {
        return utf8_to_jstring(env, profile_error("missing").dump());
    }
    std::error_code ec;
    fs::remove_all(*dir, ec);
    if (ec || fs::exists(*dir)) {
        return utf8_to_jstring(env, profile_error("failed").dump());
    }
    i2pchat::storage::keyring::erase(i2pchat::storage::kKeyringService,
                                     i2pchat::storage::dat_wrap_keyring_account(name));
    i2pchat::storage::keyring::erase(i2pchat::storage::kKeyringService,
                                     i2pchat::runtime::signing_keyring_account(name));
    return utf8_to_jstring(env, nlohmann::json{{"ok", true}}.dump());
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativePrepareRouter(
    JNIEnv* env, jclass, jstring j_dir, jstring j_host, jint sam_port, jint http_proxy,
    jint socks_proxy, jint control_http) {
    try {
        i2pchat::router::RouterRuntime runtime;
        runtime.sam_host = jstring_to_utf8(env, j_host);
        runtime.sam_port = static_cast<std::uint16_t>(sam_port);
        runtime.http_proxy_port = static_cast<std::uint16_t>(http_proxy);
        runtime.socks_proxy_port = static_cast<std::uint16_t>(socks_proxy);
        runtime.control_http_port = static_cast<std::uint16_t>(control_http);
        runtime.data_dir = jstring_to_utf8(env, j_dir);
        runtime.conf_path = runtime.data_dir / "i2pd.conf";
        runtime.log_path = runtime.data_dir / "i2pd.log";
        i2pchat::router::I2pdManager::Config cfg;
        cfg.data_dir = runtime.data_dir;
        cfg.runtime = runtime;
        i2pchat::router::I2pdManager manager(std::move(cfg));
        manager.prepare_data_dir();
        nlohmann::json json = {{"ok", true},
                               {"conf", runtime.conf_path.string()},
                               {"log", runtime.log_path.string()}};
        return utf8_to_jstring(env, json.dump());
    } catch (const std::exception& ex) {
        nlohmann::json json = {{"ok", false}, {"error", ex.what()}};
        return utf8_to_jstring(env, json.dump());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeStart(JNIEnv* env, jclass, jstring j_root,
                                                  jstring j_profile, jstring j_host, jint port,
                                                  jint max_messages, jint max_age_days,
                                                  jint tunnel_length, jstring j_files,
                                                  jstring j_images, jstring j_password) {
    try {
        if (g_engine.thread.joinable()) {
            g_engine.join_io();
        }
        g_engine.stopping = false;
        g_engine.service.reset();
        g_engine.start_io();

        ChatServiceConfig config;
        config.app_root = jstring_to_utf8(env, j_root);
        config.profile = jstring_to_utf8(env, j_profile);
        config.sam.host = jstring_to_utf8(env, j_host);
        config.sam.port = static_cast<std::uint16_t>(port);
        const std::string hops = std::to_string(std::clamp<jint>(tunnel_length, 1, 7));
        for (auto& [key, value] : config.sam_options) {
            if (key == "inbound.length" || key == "outbound.length") {
                value = hops;
            }
        }
        config.retention.max_messages = static_cast<std::size_t>(std::max(0, max_messages));
        config.retention.max_age_days = static_cast<unsigned>(std::max(0, max_age_days));
        const std::string files_dir = jstring_to_utf8(env, j_files);
        const std::string images_dir = jstring_to_utf8(env, j_images);
        if (!files_dir.empty()) {
            config.downloads_dir = files_dir;
        }
        if (!images_dir.empty()) {
            config.images_dir = images_dir;
        }
        config.profile_password = jstring_to_utf8(env, j_password);
        // An unknown peer stays out until the Trust dialog accepts the key.
        config.trust_auto_accept_first_sighting = false;
        fs::create_directories(config.app_root / "profiles" / config.profile);

        ChatEvents events;
        events.on_notice = [](const i2pchat::runtime::Notice& notice) {
            nlohmann::json json = {{"code", notice.code},
                                   {"level", notice.level},
                                   {"arg", notice.arg},
                                   {"text", notice.text}};
            g_engine.emit_string("onNotice", json.dump());
        };
        events.on_system = [](const std::string& message) {
            g_engine.emit_string("onSystem", message);
        };
        events.on_error = [](const std::string& message) {
            g_engine.emit_string("onError", message);
        };
        events.on_history = [](const std::string& peer, const i2pchat::storage::HistoryEntry& entry) {
            g_engine.emit_2string("onHistory", peer, history_json(entry).dump());
        };
        events.on_delivery = [](const DeliveryReport& report) {
            nlohmann::json json = {{"peer", report.peer},
                                   {"msgId", report.msg_id},
                                   {"state", std::string(i2pchat::runtime::delivery_state_name(report.state))},
                                   {"route", report.route},
                                   {"reason", report.reason}};
            g_engine.emit_string("onDelivery", json.dump());
        };
        events.on_peer_state = [](const std::string& peer, i2pchat::session::PeerState state,
                                  const std::string& reason) {
            g_engine.emit_3string("onPeerState", peer,
                                  std::string(i2pchat::session::peer_state_name(state)), reason);
        };
        events.on_transport_state = [](i2pchat::session::TransportState state,
                                       const std::string& reason) {
            g_engine.emit_2string("onTransportState",
                                  std::string(i2pchat::session::transport_state_name(state)), reason);
        };
        events.on_transfer = [](const std::string& peer, const i2pchat::transfer::Progress& progress) {
            nlohmann::json json = {{"name", progress.name},
                                   {"path", progress.path.string()},
                                   {"size", progress.size},
                                   {"transferred", progress.transferred},
                                   {"incoming", progress.direction == i2pchat::transfer::Direction::Incoming},
                                   {"image", progress.inline_image}};
            g_engine.emit_2string("onTransfer", peer, json.dump());
        };
        events.on_file_received = [](const std::string& peer, const fs::path& path) {
            g_engine.emit_2string("onFileReceived", peer, path.string());
        };
        events.on_image_received = [](const std::string& peer, const fs::path& path) {
            g_engine.emit_2string("onImageReceived", peer, path.string());
        };
        events.on_image_text = [](const std::string& peer, const std::string& text) {
            g_engine.emit_2string("onImageText", peer, text);
        };
        events.on_contacts_changed = [] { g_engine.call_void("onContactsChanged", "()V"); };
        events.on_group_message = [](const std::string& group_id) {
            g_engine.emit_string("onGroupMessage", group_id);
        };
        events.on_local_address = [](const std::string& addr) {
            g_engine.emit_string("onLocalAddress", addr);
        };
        events.on_trust_prompt = [](i2pchat::session::TrustPrompt prompt, const std::string& peer,
                                    const std::string& neu, const std::string& old) {
            // Runs on the io thread inside the handshake. Never wait here:
            // one unanswered dialog would stall every other session.
            if (const auto answered = g_engine.trust_answers.find(peer);
                answered != g_engine.trust_answers.end() && answered->second.key_hex == neu) {
                const auto decision = answered->second.decision;
                if (decision == i2pchat::session::TrustDecision::Accept) {
                    // Consumed: a later key change must ask again.
                    g_engine.trust_answers.erase(answered);
                }
                return decision;
            }
            if (const auto asked = g_engine.trust_asked.find(peer);
                asked != g_engine.trust_asked.end() && asked->second == neu) {
                // Already on screen; the peer is just retrying.
                return i2pchat::session::TrustDecision::Deferred;
            }
            g_engine.trust_asked[peer] = neu;
            const char* kind =
                prompt == i2pchat::session::TrustPrompt::KeyChanged ? "keyChanged" : "firstSighting";
            JNIEnv* local = g_engine.env();
            if (local != nullptr) {
                jstring j_kind = utf8_to_jstring(local, kind);
                jstring j_peer = utf8_to_jstring(local, peer);
                jstring j_new = utf8_to_jstring(local, neu);
                jstring j_old = utf8_to_jstring(local, old);
                g_engine.call_void(
                    "onTrustPrompt",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                    j_kind, j_peer, j_new, j_old);
                local->DeleteLocalRef(j_kind);
                local->DeleteLocalRef(j_peer);
                local->DeleteLocalRef(j_new);
                local->DeleteLocalRef(j_old);
            }
            return i2pchat::session::TrustDecision::Deferred;
        };
        events.offer_file = [](const std::string& peer, const std::string& name,
                               std::uint64_t size) {
            JNIEnv* local = g_engine.env();
            if (local == nullptr) {
                return i2pchat::transfer::OfferDecision::Reject;
            }
            jstring j_peer = utf8_to_jstring(local, peer);
            jstring j_name = utf8_to_jstring(local, name);
            g_engine.call_void("onFileOffer", "(Ljava/lang/String;Ljava/lang/String;J)V", j_peer,
                               j_name, static_cast<jlong>(std::min<std::uint64_t>(
                                           size, static_cast<std::uint64_t>(INT64_MAX))));
            local->DeleteLocalRef(j_peer);
            local->DeleteLocalRef(j_name);
            // The bytes are spooled to a `.pending` file in the app-private
            // downloads directory while the dialog is up; they only get the
            // real name (and the ACK goes out) once the user says yes.
            return i2pchat::transfer::OfferDecision::Deferred;
        };

        g_engine.service = std::make_unique<ChatService>(g_engine.io.get_executor(),
                                                         std::move(config), std::move(events));
        g_engine.await_core<void>([]() -> asio::awaitable<void> {
            co_await g_engine.service->start();
        });
        g_engine.call_void("onStarted", "()V");
        nlohmann::json json = {{"ok", true}, {"localAddr", g_engine.service->local_addr()}};
        return utf8_to_jstring(env, json.dump());
    } catch (const std::exception& ex) {
        try {
            g_engine.service.reset();
            g_engine.join_io();
        } catch (...) {
        }
        g_engine.emit_string("onStartFailed", ex.what());
        nlohmann::json json = {{"ok", false}, {"error", ex.what()}};
        return utf8_to_jstring(env, json.dump());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_i2pchat_android_NativeEngine_nativeStop(JNIEnv*, jclass) {
    g_engine.stopping = true;
    try {
        if (g_engine.service) {
            g_engine.await_core<void>([]() -> asio::awaitable<void> {
                co_await g_engine.service->stop();
            });
        }
    } catch (...) {
    }
    g_engine.service.reset();
    g_engine.join_io();
    g_engine.trust_answers.clear();
    g_engine.trust_asked.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_org_i2pchat_android_NativeEngine_nativeTrustRespond(JNIEnv* env, jclass, jstring j_peer,
                                                         jstring j_key_hex, jboolean accept) {
    if (!g_engine.service) {
        return;
    }
    const std::string peer = jstring_to_utf8(env, j_peer);
    const std::string key_hex = jstring_to_utf8(env, j_key_hex);
    const bool yes = accept == JNI_TRUE;
    try {
        g_engine.on_core<void>([peer, key_hex, yes] {
            g_engine.trust_asked.erase(peer);
            g_engine.trust_answers[peer] =
                TrustAnswer{key_hex, yes ? i2pchat::session::TrustDecision::Accept
                                         : i2pchat::session::TrustDecision::Reject};
            if (!yes) {
                // Remembered so the peer's retries stay silent until the
                // user dials them deliberately (nativeConnectPeer clears it).
                return;
            }
            // Pin now, so the reconnect below sees a known key and no second
            // prompt. verify_or_pin routes a key change through the prompt
            // handler, which finds the recorded answer.
            const std::optional<i2pchat::Bytes> key = i2pchat::encoding::hex_decode(key_hex);
            if (key.has_value()) {
                (void)g_engine.service->trust().verify_or_pin(peer, i2pchat::ByteView(*key),
                                                              /*auto_accept_first_sighting=*/true);
            }
            g_engine.trust_answers.erase(peer);
            asio::co_spawn(
                g_engine.io,
                [peer]() -> asio::awaitable<void> {
                    if (g_engine.service) {
                        (void)co_await g_engine.service->connect_peer(peer);
                    }
                },
                asio::detached);
        });
    } catch (...) {
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_i2pchat_android_NativeEngine_nativeFileRespond(JNIEnv* env, jclass, jstring j_peer,
                                                        jboolean accept) {
    if (!g_engine.service) {
        return JNI_FALSE;
    }
    const std::string peer = jstring_to_utf8(env, j_peer);
    const bool yes = accept == JNI_TRUE;
    try {
        return g_engine.on_core<bool>(
                   [peer, yes] { return g_engine.service->respond_file_offer(peer, yes); })
                   ? JNI_TRUE
                   : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_i2pchat_android_NativeEngine_nativeConnectPeer(JNIEnv* env, jclass, jstring j_peer) {
    if (!g_engine.service) {
        return JNI_FALSE;
    }
    const std::string peer = jstring_to_utf8(env, j_peer);
    try {
        return g_engine.await_core<bool>([peer]() -> asio::awaitable<bool> {
            // A deliberate dial re-opens the question for a peer the user
            // previously declined.
            if (const auto answer = g_engine.trust_answers.find(
                    std::string(i2pchat::sam::normalize_peer_address(peer)));
                answer != g_engine.trust_answers.end() &&
                answer->second.decision == i2pchat::session::TrustDecision::Reject) {
                g_engine.trust_answers.erase(answer);
            }
            co_return co_await g_engine.service->connect_peer(peer);
        })
                   ? JNI_TRUE
                   : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_i2pchat_android_NativeEngine_nativeDisconnectPeer(JNIEnv* env, jclass, jstring j_peer) {
    if (!g_engine.service) {
        return;
    }
    const std::string peer = jstring_to_utf8(env, j_peer);
    try {
        g_engine.on_core<void>([peer] { g_engine.service->disconnect_peer(peer); });
    } catch (...) {
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeSendText(JNIEnv* env, jclass, jstring j_peer,
                                                     jstring j_text) {
    if (!g_engine.service) {
        return utf8_to_jstring(env, "[]");
    }
    const std::string peer = jstring_to_utf8(env, j_peer);
    const std::string text = jstring_to_utf8(env, j_text);
    try {
        const auto ids = g_engine.await_core<std::vector<std::uint64_t>>(
            [peer, text]() -> asio::awaitable<std::vector<std::uint64_t>> {
                co_return co_await g_engine.service->send_text(peer, text);
            });
        return utf8_to_jstring(env, nlohmann::json(ids).dump());
    } catch (const std::exception& ex) {
        nlohmann::json json = {{"error", ex.what()}};
        return utf8_to_jstring(env, json.dump());
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_i2pchat_android_NativeEngine_nativeSendFile(JNIEnv* env, jclass, jstring j_peer,
                                                    jstring j_path, jboolean image) {
    if (!g_engine.service) {
        return JNI_FALSE;
    }
    const std::string peer = jstring_to_utf8(env, j_peer);
    const fs::path path = jstring_to_utf8(env, j_path);
    try {
        const bool ok = g_engine.await_core<bool>([peer, path, image]() -> asio::awaitable<bool> {
            if (image == JNI_TRUE) {
                co_return co_await g_engine.service->send_image(peer, path);
            }
            co_return co_await g_engine.service->send_file(peer, path);
        });
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_org_i2pchat_android_NativeEngine_nativePollBlindbox(JNIEnv*, jclass) {
    if (!g_engine.service) {
        return 0;
    }
    try {
        return static_cast<jint>(g_engine.await_core<std::size_t>([]() -> asio::awaitable<std::size_t> {
            co_return co_await g_engine.service->poll_blindbox();
        }));
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeSnapshot(JNIEnv* env, jclass) {
    if (!g_engine.service) {
        return utf8_to_jstring(env, "{}");
    }
    try {
        const std::string json = g_engine.on_core<std::string>([] {
            auto& service = *g_engine.service;
            nlohmann::json contacts = nlohmann::json::array();
            for (const auto& record : service.contacts().contacts()) {
                contacts.push_back({
                    {"addr", record.addr},
                    {"displayName", record.display_name},
                    {"note", record.note},
                    {"lastPreview", record.last_preview},
                    {"lastActivityTs", record.last_activity_ts},
                    {"live", service.live(record.addr)},
                    {"offlineReady", service.peer_offline_ready(record.addr)},
                });
            }
            nlohmann::json groups = nlohmann::json::array();
            for (const auto& group : service.list_groups()) {
                groups.push_back(group_state_json(group));
            }
            nlohmann::json snapshot = {
                {"localAddr", service.local_addr()},
                {"profile", service.paths().profile()},
                {"running", service.running()},
                {"blindboxReady", service.blindbox_ready()},
                {"blindboxEnabled", service.blindbox_enabled()},
                {"replicaSource", std::string(service.replica_source())},
                {"contacts", std::move(contacts)},
                {"groups", std::move(groups)},
                {"replicas", service.replica_settings().endpoints},
            };
            return snapshot.dump();
        });
        return utf8_to_jstring(env, json);
    } catch (const std::exception& ex) {
        nlohmann::json json = {{"error", ex.what()}};
        return utf8_to_jstring(env, json.dump());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeHistory(JNIEnv* env, jclass, jstring j_peer) {
    if (!g_engine.service) {
        return utf8_to_jstring(env, "[]");
    }
    const std::string peer = jstring_to_utf8(env, j_peer);
    try {
        const std::string json = g_engine.on_core<std::string>([peer] {
            nlohmann::json rows = nlohmann::json::array();
            for (const auto& entry : g_engine.service->history(peer)) {
                rows.push_back(history_json(entry));
            }
            return rows.dump();
        });
        return utf8_to_jstring(env, json);
    } catch (...) {
        return utf8_to_jstring(env, "[]");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeGroupHistory(JNIEnv* env, jclass, jstring j_id) {
    if (!g_engine.service) {
        return utf8_to_jstring(env, "[]");
    }
    const std::string id = jstring_to_utf8(env, j_id);
    try {
        const std::string json = g_engine.on_core<std::string>([id] {
            const auto loaded = g_engine.service->load_group(id);
            nlohmann::json rows = nlohmann::json::array();
            if (loaded) {
                for (const auto& entry : loaded->history) {
                    rows.push_back(group_history_json(entry));
                }
            }
            return rows.dump();
        });
        return utf8_to_jstring(env, json);
    } catch (...) {
        return utf8_to_jstring(env, "[]");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_i2pchat_android_NativeEngine_nativeSetPeerProfile(JNIEnv* env, jclass, jstring j_addr,
                                                          jstring j_name, jstring j_note) {
    if (!g_engine.service) {
        return;
    }
    const std::string addr = jstring_to_utf8(env, j_addr);
    const std::string name = jstring_to_utf8(env, j_name);
    const std::string note = jstring_to_utf8(env, j_note);
    try {
        g_engine.on_core<void>([addr, name, note] {
            g_engine.service->contacts().set_peer_profile(addr, name, note);
            g_engine.service->save_contacts();
        });
    } catch (const std::exception& ex) {
        g_engine.emit_string("onError", ex.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_i2pchat_android_NativeEngine_nativeRemovePeer(JNIEnv* env, jclass, jstring j_addr) {
    if (!g_engine.service) {
        return;
    }
    const std::string addr = jstring_to_utf8(env, j_addr);
    try {
        g_engine.on_core<void>([addr] {
            g_engine.service->contacts().remove_peer(addr);
            g_engine.service->save_contacts();
            i2pchat::storage::delete_history(g_engine.service->paths(), addr);
        });
    } catch (const std::exception& ex) {
        g_engine.emit_string("onError", ex.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_i2pchat_android_NativeEngine_nativeForgetPin(JNIEnv* env, jclass, jstring j_addr) {
    if (!g_engine.service) {
        return;
    }
    const std::string addr = jstring_to_utf8(env, j_addr);
    try {
        g_engine.on_core<void>([addr] { g_engine.service->trust().forget(addr); });
    } catch (const std::exception& ex) {
        g_engine.emit_string("onError", ex.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_i2pchat_android_NativeEngine_nativeClearHistory(JNIEnv* env, jclass, jstring j_addr) {
    if (!g_engine.service) {
        return;
    }
    const std::string addr = jstring_to_utf8(env, j_addr);
    try {
        g_engine.on_core<void>(
            [addr] { i2pchat::storage::delete_history(g_engine.service->paths(), addr); });
    } catch (const std::exception& ex) {
        g_engine.emit_string("onError", ex.what());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeCreateGroup(JNIEnv* env, jclass, jstring j_title,
                                                       jstring j_members) {
    if (!g_engine.service) {
        return utf8_to_jstring(env, "{}");
    }
    const std::string title = jstring_to_utf8(env, j_title);
    try {
        const std::vector<std::string> list =
            parse_member_list(jstring_to_utf8(env, j_members));
        const std::string json = g_engine.on_core<std::string>([title, list] {
            return group_state_json(g_engine.service->create_group(title, list)).dump();
        });
        return utf8_to_jstring(env, json);
    } catch (const std::exception& ex) {
        return utf8_to_jstring(env, nlohmann::json{{"error", ex.what()}}.dump());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeUpdateGroup(JNIEnv* env, jclass, jstring j_id,
                                                       jstring j_title, jstring j_members) {
    if (!g_engine.service) {
        return utf8_to_jstring(env, "{}");
    }
    const std::string id = jstring_to_utf8(env, j_id);
    const std::string title = jstring_to_utf8(env, j_title);
    try {
        const std::vector<std::string> list =
            parse_member_list(jstring_to_utf8(env, j_members));
        const std::string json = g_engine.on_core<std::string>([id, title, list] {
            return group_state_json(g_engine.service->update_group(id, title, list)).dump();
        });
        return utf8_to_jstring(env, json);
    } catch (const std::exception& ex) {
        return utf8_to_jstring(env, nlohmann::json{{"error", ex.what()}}.dump());
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_i2pchat_android_NativeEngine_nativeDeleteGroup(JNIEnv* env, jclass, jstring j_id) {
    if (!g_engine.service) {
        return JNI_FALSE;
    }
    const std::string id = jstring_to_utf8(env, j_id);
    try {
        return g_engine.on_core<bool>([id] { return g_engine.service->delete_group(id); })
                   ? JNI_TRUE
                   : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeJoinGroup(JNIEnv* env, jclass, jstring j_token) {
    if (!g_engine.service) {
        return utf8_to_jstring(env, "{}");
    }
    const std::string token = jstring_to_utf8(env, j_token);
    try {
        const std::string json = g_engine.on_core<std::string>([token] {
            return group_state_json(g_engine.service->join_group_invite(token)).dump();
        });
        return utf8_to_jstring(env, json);
    } catch (const std::exception& ex) {
        return utf8_to_jstring(env, nlohmann::json{{"error", ex.what()}}.dump());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_i2pchat_android_NativeEngine_nativeSendGroupText(JNIEnv* env, jclass, jstring j_id,
                                                         jstring j_text) {
    if (!g_engine.service) {
        return;
    }
    const std::string id = jstring_to_utf8(env, j_id);
    const std::string text = jstring_to_utf8(env, j_text);
    try {
        g_engine.await_core<void>([id, text]() -> asio::awaitable<void> {
            co_await g_engine.service->send_group_text(id, text);
        });
    } catch (...) {
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeEncodeInvite(JNIEnv* env, jclass, jstring j_id) {
    if (!g_engine.service) {
        return utf8_to_jstring(env, "");
    }
    const std::string id = jstring_to_utf8(env, j_id);
    try {
        const std::string token = g_engine.on_core<std::string>(
            [id] { return g_engine.service->encode_group_invite(id); });
        return utf8_to_jstring(env, token);
    } catch (...) {
        return utf8_to_jstring(env, "");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeGroupTopology(JNIEnv* env, jclass, jstring j_id) {
    if (!g_engine.service) {
        return utf8_to_jstring(env, "{}");
    }
    const std::string id = jstring_to_utf8(env, j_id);
    try {
    const std::string json = g_engine.on_core<std::string>([id] {
        const auto snap = g_engine.service->group_topology(id);
        if (!snap) {
            return nlohmann::json::object().dump();
        }
        nlohmann::json nodes = nlohmann::json::array();
        for (const auto& node : snap->nodes) {
            nodes.push_back({{"id", node.member_id},
                             {"label", node.label},
                             {"local", node.is_local},
                             {"peerState", node.peer_state},
                             {"live", node.live_ready},
                             {"blindbox", node.blindbox_ready}});
        }
        nlohmann::json edges = nlohmann::json::array();
        for (const auto& edge : snap->edges) {
            edges.push_back({{"source", edge.source_id},
                             {"target", edge.target_id},
                             {"state", std::string(i2pchat::groups::link_state_name(edge.state))},
                             {"label", edge.label}});
        }
        return nlohmann::json{{"groupId", snap->group_id},
                              {"title", snap->title},
                              {"observedOnly", snap->observed_only},
                              {"nodes", std::move(nodes)},
                              {"edges", std::move(edges)}}
            .dump();
    });
    return utf8_to_jstring(env, json);
    } catch (...) {
        return utf8_to_jstring(env, "{}");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeBackup(JNIEnv* env, jclass, jstring j_op,
                                                  jstring j_root, jstring j_profile,
                                                  jstring j_path, jstring j_pass,
                                                  jboolean include_history) {
    const std::string op = jstring_to_utf8(env, j_op);
    const fs::path root = jstring_to_utf8(env, j_root);
    const std::string profile = jstring_to_utf8(env, j_profile);
    const fs::path path = jstring_to_utf8(env, j_path);
    const std::string pass = jstring_to_utf8(env, j_pass);
    try {
        if (op == "exportProfile") {
            const auto summary = i2pchat::storage::export_profile_bundle(
                path, root, profile, pass, include_history == JNI_TRUE);
            return utf8_to_jstring(env, nlohmann::json{{"ok", true},
                                                       {"files", summary.file_count},
                                                       {"history", summary.history_files}}
                                            .dump());
        }
        if (op == "exportHistory") {
            const auto summary =
                i2pchat::storage::export_history_bundle(path, root, profile, pass);
            return utf8_to_jstring(
                env, nlohmann::json{{"ok", true}, {"history", summary.history_files}}.dump());
        }
        if (op == "importProfile") {
            const auto summary =
                i2pchat::storage::import_profile_bundle(path, root, pass, profile);
            return utf8_to_jstring(env, nlohmann::json{{"ok", true},
                                                       {"profile", summary.target_profile},
                                                       {"files", summary.restored_files}}
                                            .dump());
        }
        if (op == "importHistory") {
            const auto summary =
                i2pchat::storage::import_history_bundle(path, root, profile, pass, false);
            return utf8_to_jstring(
                env, nlohmann::json{{"ok", true}, {"history", summary.history_files}}.dump());
        }
        return utf8_to_jstring(env, nlohmann::json{{"ok", false}, {"error", "unknown op"}}.dump());
    } catch (const std::exception& ex) {
        return utf8_to_jstring(env, nlohmann::json{{"ok", false}, {"error", ex.what()}}.dump());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeComposeDrafts(JNIEnv* env, jclass, jboolean save,
                                                         jstring j_json) {
    if (!g_engine.service) {
        return utf8_to_jstring(env, "{}");
    }
    try {
        const std::string json = g_engine.on_core<std::string>([save, raw = jstring_to_utf8(env, j_json)] {
            const auto key = g_engine.service->identity().identity_key;
            const auto path = g_engine.service->paths().compose_drafts();
            if (save == JNI_TRUE) {
                const auto parsed = nlohmann::json::parse(raw, nullptr, false);
                i2pchat::storage::ComposeDrafts drafts;
                if (parsed.is_object()) {
                    for (auto it = parsed.begin(); it != parsed.end(); ++it) {
                        if (it.value().is_string()) {
                            drafts[it.key()] = it.value().get<std::string>();
                        }
                    }
                }
                i2pchat::storage::save_compose_drafts(path, drafts, i2pchat::ByteView(key));
                return raw;
            }
            nlohmann::json out = nlohmann::json::object();
            for (const auto& [k, v] :
                 i2pchat::storage::load_compose_drafts(path, i2pchat::ByteView(key))) {
                out[k] = v;
            }
            return out.dump();
        });
        return utf8_to_jstring(env, json);
    } catch (...) {
        return utf8_to_jstring(env, "{}");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_i2pchat_android_NativeEngine_nativeSaveReplicas(JNIEnv* env, jclass, jstring j_json) {
    if (!g_engine.service) {
        return;
    }
    try {
        const auto parsed = nlohmann::json::parse(jstring_to_utf8(env, j_json), nullptr, false);
        i2pchat::storage::ReplicaSettings settings;
        if (parsed.is_object() && parsed.contains("endpoints") && parsed["endpoints"].is_array()) {
            for (const auto& item : parsed["endpoints"]) {
                if (item.is_string()) {
                    settings.endpoints.push_back(item.get<std::string>());
                }
            }
        }
        if (parsed.is_object() && parsed.contains("auth") && parsed["auth"].is_object()) {
            for (auto it = parsed["auth"].begin(); it != parsed["auth"].end(); ++it) {
                if (it.value().is_string()) {
                    settings.auth[it.key()] = it.value().get<std::string>();
                }
            }
        }
        g_engine.on_core<void>([settings] { g_engine.service->save_replica_settings(settings); });
    } catch (const std::exception& ex) {
        g_engine.emit_string("onError", ex.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_i2pchat_android_NativeEngine_nativeSetRetention(JNIEnv*, jclass, jint max_messages,
                                                        jint max_age_days) {
    if (!g_engine.service) {
        return;
    }
    i2pchat::storage::RetentionPolicy policy;
    policy.max_messages = static_cast<std::size_t>(std::max(0, max_messages));
    policy.max_age_days = static_cast<unsigned>(std::max(0, max_age_days));
    try {
        g_engine.on_core<void>([policy] { g_engine.service->set_retention(policy); });
    } catch (...) {
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeSetProfilePassword(JNIEnv* env, jclass,
                                                              jstring j_password) {
    if (!g_engine.service) {
        return utf8_to_jstring(env, "The profile is not open");
    }
    try {
        const std::string password = jstring_to_utf8(env, j_password);
        g_engine.on_core<void>([password] { g_engine.service->set_profile_password(password); });
        return utf8_to_jstring(env, "");
    } catch (const std::exception& ex) {
        return utf8_to_jstring(env, ex.what());
    }
}
