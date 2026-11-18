#include <catch2/catch_test_macros.hpp>

#include <array>
#include <chrono>
#include <cstring>

#include "i2pchat/crypto.hpp"
#include "i2pchat/encoding.hpp"
#include "i2pchat/storage/avatar_store.hpp"
#include "temp_dir.hpp"

using namespace i2pchat;
using i2pchat::testing::TempDir;

namespace {

/// Minimal valid 1x1 PNG.
Bytes tiny_png() {
    static constexpr unsigned char kPng[] = {
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48,
        0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00,
        0x00, 0x90, 0x77, 0x53, 0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54, 0x08,
        0xD7, 0x63, 0xF8, 0xCF, 0xC0, 0x00, 0x00, 0x00, 0x03, 0x00, 0x01, 0x00, 0x05, 0xFE,
        0xD4, 0xEF, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82};
    return Bytes(std::begin(kPng), std::end(kPng));
}

std::int64_t now() {
    return std::chrono::duration_cast<std::chrono::seconds>(
               std::chrono::system_clock::now().time_since_epoch())
        .count();
}

}  // namespace

TEST_CASE("sealed avatar blob roundtrips", "[storage][avatar]") {
    crypto::init();
    TempDir dir;
    const Bytes identity = crypto::random_bytes(32);
    const auto path = dir.path() / "self.avatar.enc";
    const Bytes png = tiny_png();
    storage::write_sealed_blob(path, ByteView(png), ByteView(identity),
                               storage::avatar_blob_format("self"));
    const Bytes out =
        storage::read_sealed_blob(path, ByteView(identity), storage::avatar_blob_format("self"));
    CHECK(out == png);
}

TEST_CASE("avatar store self set and peer delivery", "[storage][avatar]") {
    crypto::init();
    TempDir dir;
    const Bytes identity = crypto::random_bytes(32);
    storage::ProfilePaths paths(dir.path(), "alice");
    storage::AvatarStore store(paths, identity);
    store.load();

    const Bytes png = tiny_png();
    const std::string peer = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    store.set_self_avatar(ByteView(png), {peer}, now());

    REQUIRE(store.state().self.has_value());
    CHECK(store.state().self->fmt == "png");
    CHECK(store.needs_push(peer));
    CHECK(store.self_bytes().has_value());
    CHECK(store.self_bytes()->size() == png.size());

    const auto view = store.materialize_self_view();
    REQUIRE(view.has_value());
    CHECK(std::filesystem::exists(*view));

    store.mark_delivery_sent(peer, store.state().self->sha256, now());
    store.mark_delivery_acked(peer, store.state().self->sha256, now());
    CHECK_FALSE(store.needs_push(peer));

    REQUIRE(store.store_peer_avatar(peer, ByteView(png), now()));
    CHECK_FALSE(store.needs_peer_fetch(peer, store.state().peers.at(storage::history_peer_key(peer)).sha256));
    CHECK(store.peer_bytes(peer).has_value());

    const storage::AvatarState exported = storage::export_self_avatar_state(store.state());
    CHECK(exported.self.has_value());
    CHECK(exported.peers.empty());
    CHECK(exported.delivery.empty());
}

TEST_CASE("avatar store rejects oversize and bad format", "[storage][avatar]") {
    crypto::init();
    TempDir dir;
    storage::AvatarStore store(storage::ProfilePaths(dir.path(), "bob"), crypto::random_bytes(32));
    store.load();
    Bytes junk(16, 0x41);
    CHECK_THROWS_AS(store.set_self_avatar(ByteView(junk), {}, now()), std::invalid_argument);
    Bytes huge(storage::kMaxAvatarBytes + 1, 0x89);
    CHECK_THROWS_AS(store.set_self_avatar(ByteView(huge), {}, now()), std::length_error);
}
