#include "options.hpp"

#include <cctype>
#include <charconv>
#include <cstdlib>
#include <fstream>

#include <nlohmann/json.hpp>

#include "i2pchat/storage/atomic_write.hpp"

namespace i2pchat::tui {
namespace {

std::filesystem::path home_dir() {
    if (const char* home = std::getenv("HOME"); home != nullptr && *home != '\0') {
        return std::filesystem::path(home);
    }
#ifdef _WIN32
    if (const char* profile = std::getenv("USERPROFILE");
        profile != nullptr && *profile != '\0') {
        return std::filesystem::path(profile);
    }
#endif
    return std::filesystem::current_path();
}

bool parse_port(std::string_view text, std::uint16_t& out) {
    unsigned value = 0;
    const char* begin = text.data();
    const char* end = text.data() + text.size();
    const std::from_chars_result result = std::from_chars(begin, end, value);
    if (result.ec != std::errc{} || result.ptr != end || value == 0 || value > 65535) {
        return false;
    }
    out = static_cast<std::uint16_t>(value);
    return true;
}

std::string lower_copy(std::string_view text) {
    std::string out;
    out.reserve(text.size());
    for (const char ch : text) {
        out.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(ch))));
    }
    return out;
}

std::string_view trim_view(std::string_view text) {
    const auto begin = text.find_first_not_of(" \t\r\n");
    if (begin == std::string_view::npos) {
        return {};
    }
    const auto end = text.find_last_not_of(" \t\r\n");
    return text.substr(begin, end - begin + 1);
}

bool all_digits(std::string_view text) {
    if (text.empty()) {
        return false;
    }
    for (const char ch : text) {
        if (std::isdigit(static_cast<unsigned char>(ch)) == 0) {
            return false;
        }
    }
    return true;
}

std::uint16_t json_port(const nlohmann::json& value, std::uint16_t fallback) {
    try {
        int raw = 0;
        if (value.is_number_integer()) {
            raw = value.get<int>();
        } else if (value.is_string()) {
            raw = std::stoi(value.get<std::string>());
        } else {
            return fallback;
        }
        if (raw < 1 || raw > 65535) {
            return fallback;
        }
        return static_cast<std::uint16_t>(raw);
    } catch (...) {
        return fallback;
    }
}

std::filesystem::path router_prefs_file(const std::filesystem::path& app_root) {
    return app_root / "router_prefs.json";
}

std::vector<std::string> split_commas(std::string_view text) {
    std::vector<std::string> parts;
    std::size_t start = 0;
    while (start <= text.size()) {
        const std::size_t comma = text.find(',', start);
        const std::string_view part = text.substr(
            start, comma == std::string_view::npos ? std::string_view::npos : comma - start);
        if (!part.empty()) {
            parts.emplace_back(part);
        }
        if (comma == std::string_view::npos) {
            break;
        }
        start = comma + 1;
    }
    return parts;
}

}  // namespace

std::filesystem::path default_app_root() {
#if defined(__APPLE__)
    return home_dir() / "Library" / "Application Support" / "I2PChat";
#elif defined(_WIN32)
    if (const char* appdata = std::getenv("APPDATA");
        appdata != nullptr && *appdata != '\0') {
        return std::filesystem::path(appdata) / "I2PChat";
    }
    return home_dir() / "I2PChat";
#else
    return home_dir() / ".i2pchat";
#endif
}

ParseResult parse_options(const std::vector<std::string>& args) {
    ParseResult result;
    result.options.app_root = default_app_root();

    for (std::size_t index = 0; index < args.size(); ++index) {
        const std::string& arg = args[index];
        const auto value_for = [&](std::string_view name) -> std::optional<std::string> {
            if (index + 1 >= args.size()) {
                result.error = std::string(name) + " needs a value";
                return std::nullopt;
            }
            return args[++index];
        };

        if (arg == "-h" || arg == "--help") {
            result.options.help = true;
            return result;
        }
        if (arg == "--version") {
            result.options.version = true;
            return result;
        }
        if (arg == "--profile" || arg == "-p") {
            const std::optional<std::string> value = value_for(arg);
            if (!value) {
                return result;
            }
            result.options.profile = *value;
            result.options.profile_from_cli = true;
            continue;
        }
        if (arg == "--app-root") {
            const std::optional<std::string> value = value_for(arg);
            if (!value) {
                return result;
            }
            result.options.app_root = *value;
            continue;
        }
        if (arg == "--sam-host") {
            const std::optional<std::string> value = value_for(arg);
            if (!value) {
                return result;
            }
            result.options.sam_host = *value;
            result.options.sam_overridden = true;
            continue;
        }
        if (arg == "--sam-port") {
            const std::optional<std::string> value = value_for(arg);
            if (!value) {
                return result;
            }
            if (!parse_port(*value, result.options.sam_port)) {
                result.error = "not a port number: " + *value;
                return result;
            }
            result.options.sam_overridden = true;
            continue;
        }
        if (arg == "--bundled-router") {
            const std::optional<std::string> value = value_for(arg);
            if (!value) {
                return result;
            }
            result.options.bundled_router = *value;
            continue;
        }
        if (arg == "--connect") {
            const std::optional<std::string> value = value_for(arg);
            if (!value) {
                return result;
            }
            result.options.connect = *value;
            continue;
        }
        if (arg == "--replica") {
            const std::optional<std::string> value = value_for(arg);
            if (!value) {
                return result;
            }
            for (std::string& endpoint : split_commas(*value)) {
                result.options.replicas.push_back(std::move(endpoint));
            }
            continue;
        }
        if (arg == "--replica-direct") {
            result.options.blindbox_over_sam = false;
            continue;
        }
        if (arg == "--poll-seconds") {
            const std::optional<std::string> value = value_for(arg);
            if (!value) {
                return result;
            }
            std::uint16_t seconds = 0;
            if (!parse_port(*value, seconds)) {
                result.error = "not a number of seconds: " + *value;
                return result;
            }
            result.options.blindbox_poll = std::chrono::seconds(seconds);
            continue;
        }
        result.error = "unknown option: " + arg;
        return result;
    }

    if (result.options.profile.empty()) {
        result.error = "the profile name cannot be empty";
    }
    return result;
}

std::string usage_text() {
    return R"(i2pchat-tui — I2PChat in the terminal

Usage: i2pchat-tui [options]

  -p, --profile NAME      Profile to open (default: default)
      --app-root DIR      Application data directory
      --sam-host HOST     SAM API host (default: saved address, else 127.0.0.1)
      --sam-port PORT     SAM API port (default: saved port, else 7656)
      --bundled-router BIN  Start this i2pd binary before connecting
      --connect ADDR      Dial a peer once the session is up
      --replica ENDPOINT  BlindBox replica, repeatable or comma-separated
      --replica-direct    Reach replicas over plain TCP instead of through I2P
      --poll-seconds N    How often to check for offline messages (default: 25)
  -h, --help              Show this text
      --version           Show the version

Inside the client, /help lists every command.
/sam <host> [port] switches to that SAM bridge and remembers it.
)";
}

SamEndpointSpec parse_sam_endpoint(std::string_view text, std::string_view current_host,
                                   std::uint16_t current_port) {
    SamEndpointSpec spec;
    spec.host = std::string(current_host.empty() ? std::string_view{"127.0.0.1"} : current_host);
    spec.port = current_port == 0 ? static_cast<std::uint16_t>(7656) : current_port;

    text = trim_view(text);
    if (text.empty()) {
        spec.error = "Usage: /sam <host> [port], /sam <host:port>, or /sam <port>";
        return spec;
    }

    const auto space = text.find_first_of(" \t");
    std::string_view host_token = text;
    std::string_view port_token;
    if (space != std::string_view::npos) {
        host_token = trim_view(text.substr(0, space));
        port_token = trim_view(text.substr(space + 1));
        if (port_token.empty() || port_token.find_first_of(" \t") != std::string_view::npos) {
            spec.error = "Usage: /sam <host> [port], /sam <host:port>, or /sam <port>";
            return spec;
        }
    }

    if (port_token.empty() && all_digits(host_token)) {
        if (!parse_port(host_token, spec.port)) {
            spec.error = "not a port number: " + std::string(host_token);
        }
        return spec;
    }

    if (!port_token.empty()) {
        if (!parse_port(port_token, spec.port)) {
            spec.error = "not a port number: " + std::string(port_token);
            return spec;
        }
    } else if (!host_token.empty() && host_token.front() == '[') {
        const auto bracket = host_token.find(']');
        if (bracket == std::string_view::npos || bracket == 1) {
            spec.error = "not a SAM address: " + std::string(host_token);
            return spec;
        }
        const std::string_view inside = host_token.substr(1, bracket - 1);
        if (bracket + 1 < host_token.size()) {
            if (host_token[bracket + 1] != ':' || bracket + 2 >= host_token.size() ||
                !parse_port(host_token.substr(bracket + 2), spec.port)) {
                spec.error = "not a SAM address: " + std::string(host_token);
                return spec;
            }
        }
        host_token = inside;
    } else {
        const auto colon = host_token.find(':');
        if (colon != std::string_view::npos && host_token.find(':', colon + 1) == std::string_view::npos) {
            if (colon == 0 || colon + 1 >= host_token.size() ||
                !parse_port(host_token.substr(colon + 1), spec.port)) {
                spec.error = "not a SAM address: " + std::string(host_token);
                return spec;
            }
            host_token = host_token.substr(0, colon);
        }
    }

    if (host_token.empty() || host_token.find_first_of(" \t/") != std::string_view::npos) {
        spec.error = "not a SAM host: " + std::string(host_token);
        return spec;
    }
    spec.host = std::string(host_token);
    return spec;
}

bool sam_host_permitted(std::string_view host) {
    const std::string lowered = lower_copy(host);
    if (lowered == "localhost" || lowered == "::1" || lowered.rfind("127.", 0) == 0) {
        return true;
    }
    const char* raw = std::getenv("I2PCHAT_ALLOW_REMOTE_SAM");
    if (raw == nullptr) {
        return false;
    }
    const std::string flag = lower_copy(raw);
    return flag == "1" || flag == "true" || flag == "yes" || flag == "on";
}

SavedSam load_saved_sam(const std::filesystem::path& app_root) {
    SavedSam saved;
    std::ifstream stream(router_prefs_file(app_root));
    if (!stream) {
        return saved;
    }
    try {
        nlohmann::json raw;
        stream >> raw;
        if (!raw.is_object()) {
            return saved;
        }
        const bool bundled = raw.value("backend", std::string{"system"}) == "bundled";
        if (bundled) {
            if (raw.contains("bundled_sam_host") && raw["bundled_sam_host"].is_string()) {
                saved.host = raw["bundled_sam_host"].get<std::string>();
            }
            saved.port = json_port(raw.value("bundled_sam_port", nlohmann::json{}),
                                   static_cast<std::uint16_t>(17656));
        } else {
            if (raw.contains("system_sam_host") && raw["system_sam_host"].is_string()) {
                saved.host = raw["system_sam_host"].get<std::string>();
            }
            saved.port = json_port(raw.value("system_sam_port", nlohmann::json{}),
                                   static_cast<std::uint16_t>(7656));
        }
        if (!saved.host.empty()) {
            saved.found = true;
        }
    } catch (...) {
        return SavedSam{};
    }
    return saved;
}

void save_saved_sam(const std::filesystem::path& app_root, const std::string& host,
                    std::uint16_t port) {
    const std::filesystem::path path = router_prefs_file(app_root);
    nlohmann::json raw = nlohmann::json::object();
    {
        std::ifstream stream(path);
        if (stream) {
            try {
                nlohmann::json parsed;
                stream >> parsed;
                if (parsed.is_object()) {
                    raw = std::move(parsed);
                }
            } catch (...) {
                raw = nlohmann::json::object();
            }
        }
    }
    raw["backend"] = "system";
    raw["system_sam_host"] = host;
    raw["system_sam_port"] = port;
    raw["bundled_auto_start"] = false;
    storage::atomic_write_json(path, raw);
}

}  // namespace i2pchat::tui
