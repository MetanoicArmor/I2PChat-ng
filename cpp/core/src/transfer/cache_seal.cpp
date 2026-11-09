#include "i2pchat/transfer/cache_seal.hpp"

#include <sodium.h>

#include <cstdint>
#include <fstream>
#include <limits>
#include <optional>
#include <system_error>

#include "i2pchat/crypto.hpp"
#include "i2pchat/encoding.hpp"

namespace i2pchat::transfer {
namespace {

constexpr std::string_view kMagic = "I2PCSEAL1";
constexpr std::uint32_t kMaxCipherChunk = 8u * 1024u * 1024u;

void write_u32_be(std::ostream& out, std::uint32_t value) {
    const unsigned char bytes[4] = {
        static_cast<unsigned char>((value >> 24) & 0xff),
        static_cast<unsigned char>((value >> 16) & 0xff),
        static_cast<unsigned char>((value >> 8) & 0xff),
        static_cast<unsigned char>(value & 0xff),
    };
    out.write(reinterpret_cast<const char*>(bytes), 4);
}

std::optional<std::uint32_t> read_u32_be(std::istream& in) {
    unsigned char bytes[4];
    in.read(reinterpret_cast<char*>(bytes), 4);
    if (in.gcount() != 4) {
        return std::nullopt;
    }
    return (std::uint32_t{bytes[0]} << 24) | (std::uint32_t{bytes[1]} << 16) |
           (std::uint32_t{bytes[2]} << 8) | std::uint32_t{bytes[3]};
}

class SealedWriter final : public CacheSink {
public:
    SealedWriter(std::ofstream out, crypto_secretstream_xchacha20poly1305_state state)
        : out_(std::move(out)), state_(state), open_(true) {}

    ~SealedWriter() override { sodium_memzero(&state_, sizeof state_); }

    bool write(ByteView plain) override {
        if (!open_ || plain.size() > kMaxCipherChunk) {
            return false;
        }
        Bytes cipher(plain.size() + crypto_secretstream_xchacha20poly1305_ABYTES);
        unsigned long long clen = 0;
        if (crypto_secretstream_xchacha20poly1305_push(
                &state_, cipher.data(), &clen, plain.data(), plain.size(), nullptr, 0,
                crypto_secretstream_xchacha20poly1305_TAG_MESSAGE) != 0) {
            return false;
        }
        return emit(ByteView(cipher.data(), static_cast<std::size_t>(clen)));
    }

    bool finish() override {
        if (!open_) {
            return false;
        }
        unsigned char cipher[crypto_secretstream_xchacha20poly1305_ABYTES];
        unsigned long long clen = 0;
        if (crypto_secretstream_xchacha20poly1305_push(
                &state_, cipher, &clen, nullptr, 0, nullptr, 0,
                crypto_secretstream_xchacha20poly1305_TAG_FINAL) != 0) {
            open_ = false;
            return false;
        }
        const bool ok = emit(ByteView(cipher, static_cast<std::size_t>(clen)));
        out_.flush();
        const bool flushed = static_cast<bool>(out_);
        out_.close();
        open_ = false;
        sodium_memzero(&state_, sizeof state_);
        // Check before close: a closed stream's failbit is not portable.
        return ok && flushed;
    }

private:
    bool emit(ByteView cipher) {
        if (cipher.size() > std::numeric_limits<std::uint32_t>::max()) {
            return false;
        }
        write_u32_be(out_, static_cast<std::uint32_t>(cipher.size()));
        out_.write(reinterpret_cast<const char*>(cipher.data()),
                   static_cast<std::streamsize>(cipher.size()));
        return static_cast<bool>(out_);
    }

    std::ofstream out_;
    crypto_secretstream_xchacha20poly1305_state state_{};
    bool open_ = false;
};

}  // namespace

SessionSeal::SessionSeal() {
    crypto::init();
    key_.assign(crypto_secretstream_xchacha20poly1305_KEYBYTES, 0);
    randombytes_buf(key_.data(), key_.size());
    locked_ = sodium_mlock(key_.data(), key_.size()) == 0;
    live_ = true;
}

SessionSeal::~SessionSeal() { wipe(); }

void SessionSeal::wipe() {
    if (key_.empty()) {
        live_ = false;
        return;
    }
    if (locked_) {
        sodium_munlock(key_.data(), key_.size());
        locked_ = false;
    }
    sodium_memzero(key_.data(), key_.size());
    key_.clear();
    live_ = false;
}

std::string new_seal_id() {
    crypto::init();
    return "seal_" + encoding::hex_encode(crypto::random_bytes(16));
}

std::unique_ptr<CacheSink> open_sealed_writer(const std::filesystem::path& path, ByteView key) {
    if (key.size() != crypto_secretstream_xchacha20poly1305_KEYBYTES) {
        return nullptr;
    }
    std::ofstream out(path, std::ios::binary | std::ios::out | std::ios::trunc);
    if (!out.is_open()) {
        return nullptr;
    }
    crypto_secretstream_xchacha20poly1305_state state{};
    unsigned char header[crypto_secretstream_xchacha20poly1305_HEADERBYTES];
    if (crypto_secretstream_xchacha20poly1305_init_push(&state, header, key.data()) != 0) {
        sodium_memzero(&state, sizeof state);
        return nullptr;
    }
    out.write(kMagic.data(), static_cast<std::streamsize>(kMagic.size()));
    out.write(reinterpret_cast<const char*>(header), sizeof header);
    if (!out) {
        sodium_memzero(&state, sizeof state);
        return nullptr;
    }
    auto writer = std::make_unique<SealedWriter>(std::move(out), state);
    sodium_memzero(&state, sizeof state);
    return writer;
}

bool unseal_file(ByteView key, const std::filesystem::path& src, const std::filesystem::path& dest) {
    if (key.size() != crypto_secretstream_xchacha20poly1305_KEYBYTES) {
        return false;
    }
    std::ifstream in(src, std::ios::binary);
    if (!in.is_open()) {
        return false;
    }
    char magic[kMagic.size()];
    in.read(magic, static_cast<std::streamsize>(kMagic.size()));
    if (in.gcount() != static_cast<std::streamsize>(kMagic.size()) ||
        std::string_view(magic, kMagic.size()) != kMagic) {
        return false;
    }
    unsigned char header[crypto_secretstream_xchacha20poly1305_HEADERBYTES];
    in.read(reinterpret_cast<char*>(header), sizeof header);
    if (in.gcount() != static_cast<std::streamsize>(sizeof header)) {
        return false;
    }
    crypto_secretstream_xchacha20poly1305_state state{};
    if (crypto_secretstream_xchacha20poly1305_init_pull(&state, header, key.data()) != 0) {
        sodium_memzero(&state, sizeof state);
        return false;
    }

    std::ofstream out(dest, std::ios::binary | std::ios::out | std::ios::trunc);
    if (!out.is_open()) {
        sodium_memzero(&state, sizeof state);
        return false;
    }

    bool finalized = false;
    while (!finalized) {
        const std::optional<std::uint32_t> clen = read_u32_be(in);
        if (!clen || *clen < crypto_secretstream_xchacha20poly1305_ABYTES ||
            *clen > kMaxCipherChunk + crypto_secretstream_xchacha20poly1305_ABYTES) {
            break;
        }
        Bytes cipher(*clen);
        in.read(reinterpret_cast<char*>(cipher.data()), static_cast<std::streamsize>(*clen));
        if (in.gcount() != static_cast<std::streamsize>(*clen)) {
            break;
        }
        Bytes plain(*clen);
        unsigned long long mlen = 0;
        unsigned char tag = 0;
        if (crypto_secretstream_xchacha20poly1305_pull(&state, plain.data(), &mlen, &tag,
                                                       cipher.data(), cipher.size(), nullptr,
                                                       0) != 0) {
            break;
        }
        if (mlen > 0) {
            out.write(reinterpret_cast<const char*>(plain.data()),
                      static_cast<std::streamsize>(mlen));
            if (!out) {
                break;
            }
        }
        if (tag == crypto_secretstream_xchacha20poly1305_TAG_FINAL) {
            finalized = true;
        }
    }

    sodium_memzero(&state, sizeof state);
    out.flush();
    out.close();
    if (!finalized || !out) {
        std::error_code ignored;
        std::filesystem::remove(dest, ignored);
        return false;
    }
    return true;
}

}  // namespace i2pchat::transfer
