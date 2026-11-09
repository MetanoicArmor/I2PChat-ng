#pragma once

#include <filesystem>
#include <memory>
#include <string>

#include "i2pchat/bytes.hpp"

/// Local cache encryption for "Do not keep files".
///
/// The key is random, lives only in this process, and is wiped when the chat
/// stops. Ciphertext left on flash cannot be opened after that. This is not a
/// wire format and is not part of the handshake.
namespace i2pchat::transfer {

class CacheSink {
public:
    virtual ~CacheSink() = default;
    virtual bool write(ByteView plain) = 0;
    /// Writes the terminating chunk and closes the file.
    virtual bool finish() = 0;
};

/// 32-byte key. `wipe` is idempotent and zeroes the bytes.
class SessionSeal {
public:
    SessionSeal();
    ~SessionSeal();

    SessionSeal(const SessionSeal&) = delete;
    SessionSeal& operator=(const SessionSeal&) = delete;

    void wipe();
    [[nodiscard]] bool live() const noexcept { return live_; }
    [[nodiscard]] ByteView key() const noexcept { return key_; }

private:
    Bytes key_;
    bool live_ = false;
    bool locked_ = false;
};

/// `seal_` plus 32 hex characters. The on-disk name is this id with `.bin`.
[[nodiscard]] std::string new_seal_id();

[[nodiscard]] std::unique_ptr<CacheSink> open_sealed_writer(const std::filesystem::path& path,
                                                           ByteView key);

/// Decrypts `src` into `dest`. Returns false when the key is wrong, the file
/// is truncated, or the write fails. A failed `dest` is removed.
[[nodiscard]] bool unseal_file(ByteView key, const std::filesystem::path& src,
                               const std::filesystem::path& dest);

}  // namespace i2pchat::transfer
