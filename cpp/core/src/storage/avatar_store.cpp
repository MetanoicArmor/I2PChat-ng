#include "i2pchat/storage/avatar_store.hpp"

#include <chrono>
#include <stdexcept>

#include "i2pchat/crypto.hpp"
#include "i2pchat/encoding.hpp"
#include "i2pchat/storage/atomic_write.hpp"
#include "i2pchat/transfer/files.hpp"

namespace i2pchat::storage {
namespace {

std::string peer_key(std::string_view peer_addr) { return history_peer_key(peer_addr); }

AvatarMeta meta_from_json(const nlohmann::json& entry) {
    AvatarMeta meta;
    meta.sha256 = entry.value("sha256", "");
    meta.fmt = entry.value("fmt", "");
    meta.updated_ts = entry.value("updated_ts", std::int64_t{0});
    meta.bytes = entry.value("bytes", std::uint64_t{0});
    return meta;
}

nlohmann::json meta_to_json(const AvatarMeta& meta) {
    return nlohmann::json{{"sha256", meta.sha256},
                          {"fmt", meta.fmt},
                          {"updated_ts", meta.updated_ts},
                          {"bytes", meta.bytes}};
}

}  // namespace

std::string_view avatar_delivery_name(AvatarDeliveryState state) {
    switch (state) {
        case AvatarDeliveryState::Pending:
            return "pending";
        case AvatarDeliveryState::Sent:
            return "sent";
        case AvatarDeliveryState::Acked:
            return "acked";
    }
    return "pending";
}

AvatarDeliveryState parse_avatar_delivery(std::string_view text) {
    if (text == "sent") {
        return AvatarDeliveryState::Sent;
    }
    if (text == "acked") {
        return AvatarDeliveryState::Acked;
    }
    return AvatarDeliveryState::Pending;
}

std::optional<std::string> detect_avatar_format(ByteView data) {
    return transfer::detect_inline_image_format(data);
}

std::string sha256_hex_bytes(ByteView data) {
    return encoding::hex_encode(ByteView(crypto::sha256(data)));
}

AvatarState parse_avatar_state(const nlohmann::json& payload) {
    AvatarState state;
    if (!payload.is_object()) {
        return state;
    }
    state.version = payload.value("version", 1);
    if (payload.contains("self") && payload["self"].is_object()) {
        AvatarMeta meta = meta_from_json(payload["self"]);
        if (!meta.sha256.empty()) {
            state.self = std::move(meta);
        }
    }
    if (payload.contains("peers") && payload["peers"].is_object()) {
        for (auto it = payload["peers"].begin(); it != payload["peers"].end(); ++it) {
            if (!it.value().is_object()) {
                continue;
            }
            AvatarMeta meta = meta_from_json(it.value());
            if (!meta.sha256.empty()) {
                state.peers[it.key()] = std::move(meta);
            }
        }
    }
    if (payload.contains("delivery") && payload["delivery"].is_object()) {
        for (auto it = payload["delivery"].begin(); it != payload["delivery"].end();
             ++it) {
            if (!it.value().is_object()) {
                continue;
            }
            AvatarDelivery delivery;
            delivery.sha256 = it.value().value("sha256", "");
            delivery.state =
                parse_avatar_delivery(it.value().value("state", std::string{"pending"}));
            delivery.updated_ts = it.value().value("updated_ts", std::int64_t{0});
            if (!delivery.sha256.empty()) {
                state.delivery[it.key()] = std::move(delivery);
            }
        }
    }
    return state;
}

nlohmann::json avatar_state_to_json(const AvatarState& state) {
    nlohmann::json out{{"version", state.version},
                       {"peers", nlohmann::json::object()},
                       {"delivery", nlohmann::json::object()}};
    if (state.self) {
        out["self"] = meta_to_json(*state.self);
    } else {
        out["self"] = nullptr;
    }
    for (const auto& [peer, meta] : state.peers) {
        out["peers"][peer] = meta_to_json(meta);
    }
    for (const auto& [peer, delivery] : state.delivery) {
        out["delivery"][peer] = nlohmann::json{
            {"sha256", delivery.sha256},
            {"state", std::string(avatar_delivery_name(delivery.state))},
            {"updated_ts", delivery.updated_ts}};
    }
    return out;
}

AvatarState load_avatar_state(const ProfilePaths& paths, ByteView identity_key) {
    const auto path = paths.avatar_state();
    std::error_code ec;
    if (!std::filesystem::exists(path, ec)) {
        return {};
    }
    return parse_avatar_state(read_sealed_json(path, identity_key, kAvatarStateFormat));
}

void save_avatar_state(const ProfilePaths& paths, const AvatarState& state,
                       ByteView identity_key) {
    write_sealed_json(paths.avatar_state(), avatar_state_to_json(state), identity_key,
                      kAvatarStateFormat);
}

AvatarState export_self_avatar_state(const AvatarState& state) {
    AvatarState out;
    out.version = state.version;
    out.self = state.self;
    return out;
}

AvatarStore::AvatarStore(ProfilePaths paths, Bytes identity_key)
    : paths_(std::move(paths)), identity_key_(std::move(identity_key)) {}

void AvatarStore::load() {
    state_ = load_avatar_state(paths_, ByteView(identity_key_));
}

void AvatarStore::save() const {
    save_avatar_state(paths_, state_, ByteView(identity_key_));
}

SealedBlobFormat AvatarStore::self_blob_format() const {
    return avatar_blob_format("self");
}

SealedBlobFormat AvatarStore::peer_blob_format(std::string_view peer_addr) const {
    return avatar_blob_format(peer_file_id(peer_addr));
}

std::optional<Bytes> AvatarStore::self_bytes() const {
    if (!state_.self) {
        return std::nullopt;
    }
    const auto path = paths_.self_avatar();
    std::error_code ec;
    if (!std::filesystem::exists(path, ec)) {
        return std::nullopt;
    }
    try {
        return read_sealed_blob(path, ByteView(identity_key_), self_blob_format());
    } catch (const SealedJsonError&) {
        return std::nullopt;
    }
}

std::optional<Bytes> AvatarStore::peer_bytes(std::string_view peer_addr) const {
    const std::string key = peer_key(peer_addr);
    if (!state_.peers.contains(key)) {
        return std::nullopt;
    }
    const auto path = paths_.peer_avatar(peer_addr);
    std::error_code ec;
    if (!std::filesystem::exists(path, ec)) {
        return std::nullopt;
    }
    try {
        return read_sealed_blob(path, ByteView(identity_key_), peer_blob_format(peer_addr));
    } catch (const SealedJsonError&) {
        return std::nullopt;
    }
}

void AvatarStore::write_view_file(const std::filesystem::path& path, ByteView data,
                                  std::string_view fmt) const {
    std::error_code ec;
    std::filesystem::create_directories(path.parent_path(), ec);
    atomic_write_bytes(path, data);
    (void)fmt;
}

std::optional<std::filesystem::path> AvatarStore::materialize_self_view() const {
    const auto data = self_bytes();
    if (!data || !state_.self) {
        return std::nullopt;
    }
    const std::string ext = state_.self->fmt.empty() ? "bin" : state_.self->fmt;
    const auto path = paths_.avatar_view_dir() / ("self." + ext);
    write_view_file(path, ByteView(*data), ext);
    return path;
}

std::optional<std::filesystem::path> AvatarStore::materialize_peer_view(
    std::string_view peer_addr) const {
    const auto data = peer_bytes(peer_addr);
    if (!data) {
        return std::nullopt;
    }
    const std::string key = peer_key(peer_addr);
    std::string ext = "bin";
    if (const auto it = state_.peers.find(key); it != state_.peers.end() && !it->second.fmt.empty()) {
        ext = it->second.fmt;
    }
    const auto path = paths_.avatar_view_dir() / (peer_file_id(peer_addr) + "." + ext);
    write_view_file(path, ByteView(*data), ext);
    return path;
}

void AvatarStore::set_self_avatar(ByteView data,
                                  const std::vector<std::string>& trusted_peers,
                                  std::int64_t now_seconds) {
    if (data.size() == 0 || data.size() > kMaxAvatarBytes) {
        throw std::length_error("Avatar exceeds the maximum allowed size");
    }
    const auto fmt = detect_avatar_format(data);
    if (!fmt) {
        throw std::invalid_argument("Avatar must be PNG, JPEG, or WebP");
    }
    const std::string sha = sha256_hex_bytes(data);
    std::error_code ec;
    std::filesystem::create_directories(paths_.data_dir(), ec);
    write_sealed_blob(paths_.self_avatar(), data, ByteView(identity_key_),
                      self_blob_format());

    AvatarMeta meta;
    meta.sha256 = sha;
    meta.fmt = *fmt;
    meta.updated_ts = now_seconds;
    meta.bytes = static_cast<std::uint64_t>(data.size());
    state_.self = meta;

    for (const std::string& peer : trusted_peers) {
        const std::string key = peer_key(peer);
        if (key.empty()) {
            continue;
        }
        mark_delivery_pending(key, sha, now_seconds);
    }
    save();
    (void)materialize_self_view();
}

void AvatarStore::clear_self_avatar() {
    state_.self.reset();
    state_.delivery.clear();
    std::error_code ec;
    std::filesystem::remove(paths_.self_avatar(), ec);
    std::filesystem::remove(paths_.avatar_view_dir() / "self.png", ec);
    std::filesystem::remove(paths_.avatar_view_dir() / "self.jpeg", ec);
    std::filesystem::remove(paths_.avatar_view_dir() / "self.webp", ec);
    std::filesystem::remove(paths_.avatar_view_dir() / "self.bin", ec);
    save();
}

bool AvatarStore::store_peer_avatar(std::string_view peer_addr, ByteView data,
                                    std::int64_t now_seconds) {
    if (data.size() == 0 || data.size() > kMaxAvatarBytes) {
        return false;
    }
    const auto fmt = detect_avatar_format(data);
    if (!fmt) {
        return false;
    }
    const std::string key = peer_key(peer_addr);
    if (key.empty()) {
        return false;
    }
    const std::string sha = sha256_hex_bytes(data);
    if (const auto it = state_.peers.find(key);
        it != state_.peers.end() && it->second.sha256 == sha) {
        return false;
    }
    std::error_code ec;
    std::filesystem::create_directories(paths_.avatars_dir(), ec);
    write_sealed_blob(paths_.peer_avatar(peer_addr), data, ByteView(identity_key_),
                      peer_blob_format(peer_addr));
    AvatarMeta meta;
    meta.sha256 = sha;
    meta.fmt = *fmt;
    meta.updated_ts = now_seconds;
    meta.bytes = static_cast<std::uint64_t>(data.size());
    state_.peers[key] = std::move(meta);
    save();
    (void)materialize_peer_view(peer_addr);
    return true;
}

void AvatarStore::clear_peer_avatars() {
    state_.peers.clear();
    std::error_code ec;
    const auto dir = paths_.avatars_dir();
    if (std::filesystem::is_directory(dir, ec)) {
        for (const auto& entry : std::filesystem::directory_iterator(dir, ec)) {
            if (entry.path().extension() == ".enc") {
                std::filesystem::remove(entry.path(), ec);
            }
        }
    }
    const auto view = paths_.avatar_view_dir();
    if (std::filesystem::is_directory(view, ec)) {
        for (const auto& entry : std::filesystem::directory_iterator(view, ec)) {
            const std::string name = entry.path().filename().string();
            if (!name.starts_with("self.")) {
                std::filesystem::remove(entry.path(), ec);
            }
        }
    }
    save();
}

void AvatarStore::mark_delivery_pending(std::string_view peer_addr, std::string_view sha,
                                        std::int64_t now_seconds) {
    const std::string key = peer_key(peer_addr);
    if (key.empty() || sha.empty()) {
        return;
    }
    state_.delivery[key] = AvatarDelivery{std::string(sha), AvatarDeliveryState::Pending,
                                          now_seconds};
}

void AvatarStore::mark_delivery_sent(std::string_view peer_addr, std::string_view sha,
                                     std::int64_t now_seconds) {
    const std::string key = peer_key(peer_addr);
    if (key.empty() || sha.empty()) {
        return;
    }
    state_.delivery[key] =
        AvatarDelivery{std::string(sha), AvatarDeliveryState::Sent, now_seconds};
    save();
}

void AvatarStore::mark_delivery_acked(std::string_view peer_addr, std::string_view sha,
                                      std::int64_t now_seconds) {
    const std::string key = peer_key(peer_addr);
    if (key.empty() || sha.empty()) {
        return;
    }
    auto it = state_.delivery.find(key);
    if (it == state_.delivery.end() || it->second.sha256 != sha) {
        return;
    }
    it->second.state = AvatarDeliveryState::Acked;
    it->second.updated_ts = now_seconds;
    save();
}

bool AvatarStore::needs_push(std::string_view peer_addr) const {
    if (!state_.self) {
        return false;
    }
    const std::string key = peer_key(peer_addr);
    const auto it = state_.delivery.find(key);
    if (it == state_.delivery.end()) {
        return true;
    }
    if (it->second.sha256 != state_.self->sha256) {
        return true;
    }
    return it->second.state != AvatarDeliveryState::Acked;
}

bool AvatarStore::needs_peer_fetch(std::string_view peer_addr,
                                   std::string_view remote_sha) const {
    const std::string key = peer_key(peer_addr);
    const auto it = state_.peers.find(key);
    if (it == state_.peers.end()) {
        return true;
    }
    if (remote_sha.empty()) {
        return false;
    }
    return it->second.sha256 != remote_sha;
}

}  // namespace i2pchat::storage
