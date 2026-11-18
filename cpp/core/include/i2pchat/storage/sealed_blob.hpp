#pragma once

#include <filesystem>
#include <optional>
#include <string>
#include <string_view>

#include "i2pchat/bytes.hpp"
#include "i2pchat/storage/sealed_json.hpp"

/// Identity-keyed opaque blobs at rest.
///
/// On-disk layout matches sealed JSON:
///
///   magic(4) | version(uint16 BE) | salt(32) | SecretBox(payload)
///
/// Used for avatar image bytes (`I2AV`) where the plaintext is not JSON.
namespace i2pchat::storage {

inline constexpr std::uint16_t kSealedBlobVersion = 1;

struct SealedBlobFormat {
    std::string_view magic;
    std::string_view domain;
    std::uint16_t version = kSealedBlobVersion;
    std::string scope;
};

[[nodiscard]] bool is_sealed_blob_file(const std::filesystem::path& path,
                                       std::string_view magic);

/// Decrypt a sealed blob. Throws `SealedJsonError` on tamper / wrong key /
/// truncated header (reuses the sealed-JSON error type for one failure surface).
[[nodiscard]] Bytes read_sealed_blob(const std::filesystem::path& path,
                                     ByteView identity_key,
                                     const SealedBlobFormat& format);

/// Seal and write atomically with mode 0600. Reuses an existing salt when the
/// file is already sealed under the same magic.
void write_sealed_blob(const std::filesystem::path& path, ByteView payload,
                       ByteView identity_key, const SealedBlobFormat& format);

}  // namespace i2pchat::storage
