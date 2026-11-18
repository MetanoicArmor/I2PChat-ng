#pragma once

#include <cstdint>
#include <filesystem>
#include <map>
#include <nlohmann/json.hpp>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "i2pchat/bytes.hpp"
#include "i2pchat/storage/profile_paths.hpp"
#include "i2pchat/storage/sealed_blob.hpp"
#include "i2pchat/storage/sealed_json.hpp"

/// Per-profile self avatar, peer avatar cache, and delivery tracking.
///
/// Blobs are sealed with magic `I2AV`. Metadata and delivery live in a sealed
/// JSON file (`I2AS` / domain `I2PCHAT-AVATAR-STATE`).
namespace i2pchat::storage {

inline constexpr std::uint64_t kMaxAvatarBytes = 256ull * 1024;
inline constexpr std::string_view kAvatarBlobMagic = "I2AV";
inline constexpr std::string_view kAvatarBlobDomain = "I2PCHAT-AVATAR-BLOB";

inline const SealedJsonFormat kAvatarStateFormat{"I2AS", "I2PCHAT-AVATAR-STATE", 1, {}};

inline SealedBlobFormat avatar_blob_format(std::string_view scope = {}) {
    return SealedBlobFormat{kAvatarBlobMagic, kAvatarBlobDomain, kSealedBlobVersion,
                            std::string(scope)};
}

enum class AvatarDeliveryState {
    Pending,
    Sent,
    Acked,
};

[[nodiscard]] std::string_view avatar_delivery_name(AvatarDeliveryState state);
[[nodiscard]] AvatarDeliveryState parse_avatar_delivery(std::string_view text);

struct AvatarMeta {
    std::string sha256;
    std::string fmt;
    std::int64_t updated_ts = 0;
    std::uint64_t bytes = 0;
};

struct AvatarDelivery {
    std::string sha256;
    AvatarDeliveryState state = AvatarDeliveryState::Pending;
    std::int64_t updated_ts = 0;
};

struct AvatarState {
    int version = 1;
    std::optional<AvatarMeta> self;
    std::map<std::string, AvatarMeta> peers;
    std::map<std::string, AvatarDelivery> delivery;
};

/// Sniff PNG/JPEG/WebP; empty when unrecognised.
[[nodiscard]] std::optional<std::string> detect_avatar_format(ByteView data);

[[nodiscard]] std::string sha256_hex_bytes(ByteView data);

[[nodiscard]] AvatarState parse_avatar_state(const nlohmann::json& payload);
[[nodiscard]] nlohmann::json avatar_state_to_json(const AvatarState& state);

[[nodiscard]] AvatarState load_avatar_state(const ProfilePaths& paths,
                                            ByteView identity_key);
void save_avatar_state(const ProfilePaths& paths, const AvatarState& state,
                       ByteView identity_key);

/// Export-safe state: self meta only (no peer cache, no delivery).
[[nodiscard]] AvatarState export_self_avatar_state(const AvatarState& state);

class AvatarStore {
public:
    AvatarStore() = default;
    AvatarStore(ProfilePaths paths, Bytes identity_key);

    void load();
    void save() const;

    [[nodiscard]] const AvatarState& state() const noexcept { return state_; }
    [[nodiscard]] const ProfilePaths& paths() const noexcept { return paths_; }

    [[nodiscard]] std::optional<Bytes> self_bytes() const;
    [[nodiscard]] std::optional<Bytes> peer_bytes(std::string_view peer_addr) const;

    /// Decrypt to a plaintext view file for UI thumbnails. Returns empty when
    /// there is no avatar. Path is under `avatars/view/`.
    [[nodiscard]] std::optional<std::filesystem::path> materialize_self_view() const;
    [[nodiscard]] std::optional<std::filesystem::path> materialize_peer_view(
        std::string_view peer_addr) const;

    /// Replace the self avatar. Throws when oversize or format unknown.
    /// Marks every `trusted_peers` entry as delivery pending for the new hash.
    void set_self_avatar(ByteView data, const std::vector<std::string>& trusted_peers,
                         std::int64_t now_seconds);

    void clear_self_avatar();

    /// Store a peer avatar after a trusted transfer. Returns true when the
    /// cache changed.
    bool store_peer_avatar(std::string_view peer_addr, ByteView data,
                           std::int64_t now_seconds);

    void clear_peer_avatars();

    void mark_delivery_pending(std::string_view peer_addr, std::string_view sha,
                               std::int64_t now_seconds);
    void mark_delivery_sent(std::string_view peer_addr, std::string_view sha,
                            std::int64_t now_seconds);
    void mark_delivery_acked(std::string_view peer_addr, std::string_view sha,
                             std::int64_t now_seconds);

    /// True when we should announce/push our avatar to this trusted peer.
    [[nodiscard]] bool needs_push(std::string_view peer_addr) const;

    /// True when we have no cache (or a different hash) for this peer.
    [[nodiscard]] bool needs_peer_fetch(std::string_view peer_addr,
                                        std::string_view remote_sha) const;

private:
    [[nodiscard]] SealedBlobFormat self_blob_format() const;
    [[nodiscard]] SealedBlobFormat peer_blob_format(std::string_view peer_addr) const;
    void write_view_file(const std::filesystem::path& path, ByteView data,
                         std::string_view fmt) const;

    ProfilePaths paths_{{}, {}};
    Bytes identity_key_;
    AvatarState state_;
};

}  // namespace i2pchat::storage
