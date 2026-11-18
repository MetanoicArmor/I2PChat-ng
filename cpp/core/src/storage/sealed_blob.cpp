#include "i2pchat/storage/sealed_blob.hpp"

#include <algorithm>
#include <fstream>

#include "i2pchat/crypto.hpp"
#include "i2pchat/storage/atomic_write.hpp"

namespace i2pchat::storage {
namespace {

std::optional<Bytes> read_header(const std::filesystem::path& path,
                                 std::string_view magic) {
    std::ifstream stream(path, std::ios::binary);
    if (!stream) {
        return std::nullopt;
    }
    Bytes header(kSealedJsonHeaderSize);
    stream.read(reinterpret_cast<char*>(header.data()),
                static_cast<std::streamsize>(header.size()));
    if (stream.gcount() != static_cast<std::streamsize>(kSealedJsonHeaderSize)) {
        return std::nullopt;
    }
    if (!std::equal(magic.begin(), magic.end(), header.begin())) {
        return std::nullopt;
    }
    return header;
}

}  // namespace

bool is_sealed_blob_file(const std::filesystem::path& path, std::string_view magic) {
    return read_header(path, magic).has_value();
}

Bytes read_sealed_blob(const std::filesystem::path& path, ByteView identity_key,
                       const SealedBlobFormat& format) {
    const Bytes raw = read_file(path);
    const std::string_view magic = format.magic;
    if (raw.size() < kSealedJsonHeaderSize ||
        !std::equal(magic.begin(), magic.end(), raw.begin())) {
        throw SealedJsonError("Sealed blob magic mismatch or truncated");
    }
    const std::uint16_t version = read_u16_be(ByteView(raw).subspan(4, 2));
    if (version != format.version) {
        throw SealedJsonError("Unsupported sealed blob version " +
                              std::to_string(version));
    }
    const ByteView salt = ByteView(raw).subspan(6, kSealedJsonSaltSize);
    const ByteView ciphertext = ByteView(raw).subspan(kSealedJsonHeaderSize);
    const Bytes file_key =
        derive_sealed_file_key(identity_key, salt, format.domain, format.scope);
    const std::optional<Bytes> plaintext =
        crypto::decrypt_message(ByteView(file_key), ciphertext);
    if (!plaintext.has_value()) {
        throw SealedJsonError("Sealed blob decryption failed (wrong key or tampered)");
    }
    return *plaintext;
}

void write_sealed_blob(const std::filesystem::path& path, ByteView payload,
                       ByteView identity_key, const SealedBlobFormat& format) {
    Bytes salt;
    if (const std::optional<Bytes> existing = read_header(path, format.magic)) {
        salt.assign(existing->begin() + 6, existing->end());
    } else {
        salt = crypto::random_bytes(kSealedJsonSaltSize);
    }
    const Bytes file_key =
        derive_sealed_file_key(identity_key, ByteView(salt), format.domain, format.scope);
    const Bytes ciphertext = crypto::encrypt_message(ByteView(file_key), payload);

    Bytes blob;
    blob.reserve(kSealedJsonHeaderSize + ciphertext.size());
    append(blob, format.magic);
    append_u16_be(blob, format.version);
    append(blob, ByteView(salt));
    append(blob, ByteView(ciphertext));
    atomic_write_bytes(path, ByteView(blob));
}

}  // namespace i2pchat::storage
