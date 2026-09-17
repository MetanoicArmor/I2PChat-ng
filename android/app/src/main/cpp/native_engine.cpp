#include <jni.h>

#include <boost/asio/co_spawn.hpp>
#include <boost/asio/detached.hpp>
#include <boost/asio/io_context.hpp>
#include <boost/asio/post.hpp>
#include <nlohmann/json.hpp>

#include <algorithm>
#include <condition_variable>
#include <cstdint>
#include <exception>
#include <filesystem>
#include <functional>
#include <future>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <type_traits>
#include <utility>
#include <vector>

#include "i2pchat/bytes.hpp"

#include "i2pchat/groups/coordinator.hpp"
#include "i2pchat/groups/models.hpp"
#include "i2pchat/groups/store.hpp"
#include "i2pchat/router/i2pd.hpp"
#include "i2pchat/runtime/chat_service.hpp"
#include "i2pchat/runtime/identity.hpp"
#include "i2pchat/session/manager.hpp"
#include "i2pchat/session/peer_session.hpp"
#include "i2pchat/session/trust_store.hpp"
#include "i2pchat/storage/chat_history.hpp"
#include "i2pchat/storage/compose_drafts.hpp"
#include "i2pchat/storage/contacts.hpp"
#include "i2pchat/storage/profile_backup.hpp"
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
    std::mutex trust_mu;
    std::condition_variable trust_cv;
    std::optional<i2pchat::session::TrustDecision> trust_decision;
    bool stopping = false;

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
    if (fs::exists(dir, ec)) {
        for (const auto& entry : fs::directory_iterator(dir, ec)) {
            if (!entry.is_directory()) {
                continue;
            }
            const std::string name = entry.path().filename().string();
            if (name.empty() || name == kTransientProfile) {
                continue;
            }
            std::error_code dat_ec;
            if (fs::is_regular_file(entry.path() / (name + ".dat"), dat_ec)) {
                names.push_back(name);
            }
        }
    }
    return utf8_to_jstring(env, names.dump());
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
                                                  jint max_messages, jint max_age_days) {
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
        config.retention.max_messages = static_cast<std::size_t>(std::max(0, max_messages));
        config.retention.max_age_days = static_cast<unsigned>(std::max(0, max_age_days));
        config.trust_auto_accept_first_sighting = true;
        fs::create_directories(config.app_root / "profiles" / config.profile);

        ChatEvents events;
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
            {
                std::lock_guard lock(g_engine.trust_mu);
                g_engine.trust_decision.reset();
            }
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
            std::unique_lock lock(g_engine.trust_mu);
            g_engine.trust_cv.wait(lock, [] {
                return g_engine.trust_decision.has_value() || g_engine.stopping;
            });
            return g_engine.trust_decision.value_or(i2pchat::session::TrustDecision::Reject);
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
    {
        std::lock_guard lock(g_engine.trust_mu);
        if (!g_engine.trust_decision) {
            g_engine.trust_decision = i2pchat::session::TrustDecision::Reject;
        }
        g_engine.trust_cv.notify_all();
    }
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
}

extern "C" JNIEXPORT void JNICALL
Java_org_i2pchat_android_NativeEngine_nativeTrustRespond(JNIEnv*, jclass, jboolean accept) {
    std::lock_guard lock(g_engine.trust_mu);
    g_engine.trust_decision = accept == JNI_TRUE ? i2pchat::session::TrustDecision::Accept
                                                 : i2pchat::session::TrustDecision::Reject;
    g_engine.trust_cv.notify_all();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_i2pchat_android_NativeEngine_nativeConnectPeer(JNIEnv* env, jclass, jstring j_peer) {
    if (!g_engine.service) {
        return JNI_FALSE;
    }
    const std::string peer = jstring_to_utf8(env, j_peer);
    try {
        return g_engine.await_core<bool>([peer]() -> asio::awaitable<bool> {
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
    g_engine.on_core<void>([peer] { g_engine.service->disconnect_peer(peer); });
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
    g_engine.on_core<void>([addr, name, note] {
        g_engine.service->contacts().set_peer_profile(addr, name, note);
        g_engine.service->save_contacts();
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_i2pchat_android_NativeEngine_nativeRemovePeer(JNIEnv* env, jclass, jstring j_addr) {
    if (!g_engine.service) {
        return;
    }
    const std::string addr = jstring_to_utf8(env, j_addr);
    g_engine.on_core<void>([addr] {
        g_engine.service->contacts().remove_peer(addr);
        g_engine.service->save_contacts();
        i2pchat::storage::delete_history(g_engine.service->paths(), addr);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_i2pchat_android_NativeEngine_nativeForgetPin(JNIEnv* env, jclass, jstring j_addr) {
    if (!g_engine.service) {
        return;
    }
    const std::string addr = jstring_to_utf8(env, j_addr);
    g_engine.on_core<void>([addr] { g_engine.service->trust().forget(addr); });
}

extern "C" JNIEXPORT void JNICALL
Java_org_i2pchat_android_NativeEngine_nativeClearHistory(JNIEnv* env, jclass, jstring j_addr) {
    if (!g_engine.service) {
        return;
    }
    const std::string addr = jstring_to_utf8(env, j_addr);
    g_engine.on_core<void>(
        [addr] { i2pchat::storage::delete_history(g_engine.service->paths(), addr); });
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeCreateGroup(JNIEnv* env, jclass, jstring j_title,
                                                       jstring j_members) {
    if (!g_engine.service) {
        return utf8_to_jstring(env, "{}");
    }
    const std::string title = jstring_to_utf8(env, j_title);
    const auto members = nlohmann::json::parse(jstring_to_utf8(env, j_members), nullptr, false);
    std::vector<std::string> list;
    if (members.is_array()) {
        list = members.get<std::vector<std::string>>();
    }
    try {
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
    const auto members = nlohmann::json::parse(jstring_to_utf8(env, j_members), nullptr, false);
    std::vector<std::string> list;
    if (members.is_array()) {
        list = members.get<std::vector<std::string>>();
    }
    try {
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
    return g_engine.on_core<bool>([id] { return g_engine.service->delete_group(id); }) ? JNI_TRUE
                                                                                      : JNI_FALSE;
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
    const std::string token =
        g_engine.on_core<std::string>([id] { return g_engine.service->encode_group_invite(id); });
    return utf8_to_jstring(env, token);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_i2pchat_android_NativeEngine_nativeGroupTopology(JNIEnv* env, jclass, jstring j_id) {
    if (!g_engine.service) {
        return utf8_to_jstring(env, "{}");
    }
    const std::string id = jstring_to_utf8(env, j_id);
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
    const auto parsed = nlohmann::json::parse(jstring_to_utf8(env, j_json), nullptr, false);
    i2pchat::storage::ReplicaSettings settings;
    if (parsed.contains("endpoints") && parsed["endpoints"].is_array()) {
        settings.endpoints = parsed["endpoints"].get<std::vector<std::string>>();
    }
    if (parsed.contains("auth") && parsed["auth"].is_object()) {
        for (auto it = parsed["auth"].begin(); it != parsed["auth"].end(); ++it) {
            if (it.value().is_string()) {
                settings.auth[it.key()] = it.value().get<std::string>();
            }
        }
    }
    g_engine.on_core<void>([settings] { g_engine.service->save_replica_settings(settings); });
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
    g_engine.on_core<void>([policy] { g_engine.service->set_retention(policy); });
}
