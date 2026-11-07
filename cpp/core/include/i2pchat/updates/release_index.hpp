#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <tuple>
#include <vector>

/// Parse a releases listing and compare versions with the local build.
///
/// Source of truth: i2pchat/updates/release_index.py. Fetching the listing is a
/// GUI concern; this module only interprets the page or GitHub releases JSON.
namespace i2pchat::updates {

inline constexpr std::string_view kDefaultReleasesPageUrl =
    "https://api.github.com/repos/MetanoicArmor/I2PChat-ng/releases?per_page=30";
inline constexpr std::string_view kDefaultDownloadsPageUrl =
    "https://github.com/MetanoicArmor/I2PChat-ng/releases";

struct UpdateCheckResult {
    bool ok = false;
    std::string kind;
    std::string message;
    std::optional<std::string> remote_version;
    std::optional<std::string> remote_filename;
};

[[nodiscard]] std::string releases_page_url();
[[nodiscard]] std::string downloads_page_url();
[[nodiscard]] std::optional<std::string> expected_artifact_prefix();
[[nodiscard]] int compare_version_strings(std::string_view a, std::string_view b);
[[nodiscard]] std::optional<std::pair<std::string, std::string>> find_latest_for_prefix(
    std::string_view html, std::string_view artifact_prefix);
[[nodiscard]] UpdateCheckResult check_for_updates_from_html(std::string_view current_version,
                                                            std::string_view html);

}  // namespace i2pchat::updates
