#pragma once

#include <chrono>
#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>
#include <vector>

namespace i2pchat::tui {

/// What the command line asked for.
struct Options {
    std::filesystem::path app_root;
    std::string profile = "default";
    bool profile_from_cli = false;
    std::string sam_host = "127.0.0.1";
    std::uint16_t sam_port = 7656;
    /// `--sam-host` or `--sam-port` was on the command line, so the saved
    /// router address is left alone for this run.
    bool sam_overridden = false;
    /// Start the bundled router from this binary before connecting. Empty means
    /// an already-running router is expected on the SAM endpoint.
    std::filesystem::path bundled_router;
    /// Dial this peer once the session is up.
    std::string connect;
    /// Replica endpoints, overriding whatever the profile stores.
    std::vector<std::string> replicas;
    bool blindbox_over_sam = true;
    std::chrono::seconds blindbox_poll{25};
    /// Print the help text and exit.
    bool help = false;
    bool version = false;
};

/// Where profile data lives when `--app-root` is not given: the same directory
/// the reference implementation uses, so the two clients see one set of
/// profiles.
[[nodiscard]] std::filesystem::path default_app_root();

struct ParseResult {
    Options options;
    /// Set when the arguments could not be understood. The caller prints it and
    /// exits non-zero.
    std::string error;
};

[[nodiscard]] ParseResult parse_options(const std::vector<std::string>& args);

[[nodiscard]] std::string usage_text();

/// One SAM bridge address, parsed from `/sam` or from `router_prefs.json`.
struct SamEndpointSpec {
    std::string host = "127.0.0.1";
    std::uint16_t port = 7656;
    /// Empty when `host` and `port` are usable.
    std::string error;
};

/// Parse what `/sam` was given.
///
/// Accepts `host`, `host:port`, `[ipv6]:port`, a bare port (the current host
/// is kept), or `host port`. An empty `text` asks for the usage line.
[[nodiscard]] SamEndpointSpec parse_sam_endpoint(std::string_view text,
                                                 std::string_view current_host,
                                                 std::uint16_t current_port);

/// Loopback is always allowed. Any other host needs `I2PCHAT_ALLOW_REMOTE_SAM`.
[[nodiscard]] bool sam_host_permitted(std::string_view host);

/// The address the GUI and the TUI share, in `router_prefs.json`.
/// `found` is false when the file is missing or has no address.
struct SavedSam {
    bool found = false;
    std::string host = "127.0.0.1";
    std::uint16_t port = 7656;
};

[[nodiscard]] SavedSam load_saved_sam(const std::filesystem::path& app_root);

/// Remember a system SAM address and select the system backend, keeping every
/// other preference already in the file.
void save_saved_sam(const std::filesystem::path& app_root, const std::string& host,
                    std::uint16_t port);

}  // namespace i2pchat::tui
