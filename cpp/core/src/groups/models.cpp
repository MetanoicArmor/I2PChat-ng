#include "i2pchat/groups/models.hpp"

#include <algorithm>
#include <cctype>
#include <set>

#include "i2pchat/storage/contacts.hpp"

namespace i2pchat::groups {
namespace {

std::string trim_lower(std::string_view text) {
    constexpr std::string_view kWhitespace = " \t\r\n\f\v";
    const auto first = text.find_first_not_of(kWhitespace);
    if (first == std::string_view::npos) {
        return "";
    }
    const auto last = text.find_last_not_of(kWhitespace);
    std::string value(text.substr(first, last - first + 1));
    for (char& ch : value) {
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    }
    return value;
}

std::string trim(std::string_view text) {
    constexpr std::string_view kWhitespace = " \t\r\n\f\v";
    const auto first = text.find_first_not_of(kWhitespace);
    if (first == std::string_view::npos) {
        return "";
    }
    const auto last = text.find_last_not_of(kWhitespace);
    return std::string(text.substr(first, last - first + 1));
}

}  // namespace

std::string sanitize_group_title(std::string_view value) {
    // Mirrors the Python model: C0/C1 controls (including the UTF-8 encoded
    // C1 range U+0080..U+009F) become spaces, runs of whitespace collapse,
    // and the result is cut at kMaxGroupTitleLength code points.
    std::string cleaned;
    cleaned.reserve(value.size());
    for (std::size_t i = 0; i < value.size(); ++i) {
        const auto byte = static_cast<unsigned char>(value[i]);
        if (byte < 0x20 || byte == 0x7f) {
            cleaned.push_back(' ');
            continue;
        }
        if (byte == 0xc2 && i + 1 < value.size()) {
            const auto next = static_cast<unsigned char>(value[i + 1]);
            if (next >= 0x80 && next <= 0x9f) {
                cleaned.push_back(' ');
                ++i;
                continue;
            }
        }
        cleaned.push_back(static_cast<char>(byte));
    }

    std::string collapsed;
    collapsed.reserve(cleaned.size());
    bool pending_space = false;
    for (const char ch : cleaned) {
        if (ch == ' ') {
            pending_space = !collapsed.empty();
            continue;
        }
        if (pending_space) {
            collapsed.push_back(' ');
            pending_space = false;
        }
        collapsed.push_back(ch);
    }

    std::size_t code_points = 0;
    std::size_t cut = collapsed.size();
    for (std::size_t i = 0; i < collapsed.size(); ++i) {
        const auto byte = static_cast<unsigned char>(collapsed[i]);
        if ((byte & 0xc0) != 0x80) {
            if (code_points == kMaxGroupTitleLength) {
                cut = i;
                break;
            }
            ++code_points;
        }
    }
    collapsed.resize(cut);
    return collapsed;
}

std::string_view content_type_name(ContentType type) {
    return type == ContentType::GroupText ? "GROUP_TEXT" : "GROUP_CONTROL";
}

std::optional<ContentType> parse_content_type(std::string_view text) {
    if (text == "GROUP_TEXT") {
        return ContentType::GroupText;
    }
    if (text == "GROUP_CONTROL") {
        return ContentType::GroupControl;
    }
    return std::nullopt;
}

std::string_view delivery_status_name(DeliveryStatus status) {
    switch (status) {
        case DeliveryStatus::DeliveredLive:
            return "delivered_live";
        case DeliveryStatus::QueuedOffline:
            return "queued_offline";
        case DeliveryStatus::Failed:
            return "failed";
    }
    return "failed";
}

std::string normalize_member_id(std::string_view value) {
    const std::string lowered = trim_lower(value);
    if (lowered.empty()) {
        return "";
    }
    const std::string canonical = storage::normalize_contact_address(lowered);
    return canonical.empty() ? lowered : canonical;
}

GroupState::GroupState(std::string group_id, std::uint64_t epoch,
                       std::vector<std::string> members, std::string title)
    : group_id_(trim(group_id)), epoch_(epoch), title_(sanitize_group_title(title)) {
    std::set<std::string> seen;
    for (const std::string& raw : members) {
        const std::string member = normalize_member_id(raw);
        if (member.empty() || !seen.insert(member).second) {
            continue;
        }
        members_.push_back(member);
    }
}

bool GroupState::has_member(std::string_view member_id) const {
    const std::string normalized = normalize_member_id(member_id);
    if (normalized.empty()) {
        return false;
    }
    return std::any_of(members_.begin(), members_.end(),
                       [&normalized](const std::string& member) {
                           return storage::same_i2p_destination(normalized, member);
                       });
}

}  // namespace i2pchat::groups
