#include <catch2/catch_test_macros.hpp>

#include <filesystem>

#include "options.hpp"

using namespace i2pchat::tui;

TEST_CASE("tui option parsing") {
    SECTION("defaults") {
        const ParseResult result = parse_options({});
        CHECK(result.error.empty());
        CHECK(result.options.profile == "default");
        CHECK(result.options.sam_port == 7656);
        CHECK(result.options.help == false);
        CHECK_FALSE(result.options.app_root.empty());
    }

    SECTION("help and version short-circuit") {
        CHECK(parse_options({"-h"}).options.help);
        CHECK(parse_options({"--version"}).options.version);
    }

    SECTION("profile and sam") {
        const ParseResult result =
            parse_options({"-p", "work", "--sam-host", "10.0.0.1", "--sam-port", "9000"});
        CHECK(result.error.empty());
        CHECK(result.options.profile == "work");
        CHECK(result.options.sam_host == "10.0.0.1");
        CHECK(result.options.sam_port == 9000);
        CHECK(result.options.sam_overridden);
    }

    SECTION("replicas accept a comma-separated list") {
        const ParseResult result =
            parse_options({"--replica", "127.0.0.1:14000,127.0.0.1:14001", "--replica-direct"});
        CHECK(result.error.empty());
        REQUIRE(result.options.replicas.size() == 2);
        CHECK(result.options.replicas[0] == "127.0.0.1:14000");
        CHECK_FALSE(result.options.blindbox_over_sam);
    }

    SECTION("unknown flags and missing values fail") {
        CHECK_FALSE(parse_options({"--nope"}).error.empty());
        CHECK_FALSE(parse_options({"--profile"}).error.empty());
        CHECK_FALSE(parse_options({"--sam-port", "nope"}).error.empty());
    }

    SECTION("usage mentions the commands that matter") {
        const std::string usage = usage_text();
        CHECK(usage.find("--profile") != std::string::npos);
        CHECK(usage.find("/help") != std::string::npos);
        CHECK(usage.find("/sam") != std::string::npos);
    }
}

TEST_CASE("sam endpoint text") {
    const SamEndpointSpec host_port = parse_sam_endpoint("127.0.0.1:9000", "127.0.0.1", 7656);
    CHECK(host_port.error.empty());
    CHECK(host_port.host == "127.0.0.1");
    CHECK(host_port.port == 9000);

    const SamEndpointSpec split = parse_sam_endpoint("localhost 7657", "127.0.0.1", 7656);
    CHECK(split.error.empty());
    CHECK(split.host == "localhost");
    CHECK(split.port == 7657);

    const SamEndpointSpec only_port = parse_sam_endpoint("18000", "127.0.0.1", 7656);
    CHECK(only_port.error.empty());
    CHECK(only_port.host == "127.0.0.1");
    CHECK(only_port.port == 18000);

    const SamEndpointSpec v6 = parse_sam_endpoint("[::1]:7656", "127.0.0.1", 7656);
    CHECK(v6.error.empty());
    CHECK(v6.host == "::1");
    CHECK(v6.port == 7656);

    CHECK_FALSE(parse_sam_endpoint("127.0.0.1:nope", "127.0.0.1", 7656).error.empty());
    CHECK(sam_host_permitted("127.0.0.1"));
    CHECK(sam_host_permitted("localhost"));
    CHECK(sam_host_permitted("::1"));
}

TEST_CASE("saved sam address round trip") {
    const std::filesystem::path root =
        std::filesystem::temp_directory_path() / "i2pchat-tui-sam-test";
    std::filesystem::remove_all(root);
    std::filesystem::create_directories(root);

    CHECK_FALSE(load_saved_sam(root).found);
    save_saved_sam(root, "127.0.0.1", 18000);
    const SavedSam saved = load_saved_sam(root);
    CHECK(saved.found);
    CHECK(saved.host == "127.0.0.1");
    CHECK(saved.port == 18000);

    std::filesystem::remove_all(root);
}
