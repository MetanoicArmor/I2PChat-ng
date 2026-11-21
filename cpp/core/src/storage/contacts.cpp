#include "i2pchat/storage/contacts.hpp"

#include <algorithm>
#include <cctype>

#include "i2pchat/encoding.hpp"
#include "i2pchat/storage/profile_paths.hpp"
#include "i2pchat/storage/sealed_json.hpp"

namespace i2pchat::storage {
namespace {

constexpr std::string_view kWhitespace = " \t\r\n\f\v";
constexpr std::string_view kB32Suffix = ".b32.i2p";

std::string trim_whitespace(std::string_view text) {
    const auto first = text.find_first_not_of(kWhitespace);
    if (first == std::string_view::npos) {
        return "";
    }
    const auto last = text.find_last_not_of(kWhitespace);
    return std::string(text.substr(first, last - first + 1));
}

std::string to_lower(std::string value) {
    for (char& ch : value) {
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    }
    return value;
}

bool is_base32_host(std::string_view host) {
    if (host.size() < 40 || host.size() > 80) {
        return false;
    }
    return std::all_of(host.begin(), host.end(), [](const char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= '2' && ch <= '7');
    });
}

/// Keep the first `limit` code points. Byte-based truncation would cut a
/// multi-byte character in half and produce invalid UTF-8.
std::string truncate_code_points(const std::string& text, std::size_t limit) {
    const std::optional<std::size_t> offset =
        encoding::utf8_offset_of_code_point(text, limit);
    return offset.has_value() ? text.substr(0, *offset) : text;
}

/// A JSON value coerced to text the way the reference implementation does:
/// strings as they are, null as empty, anything else as its literal form.
std::string as_text(const nlohmann::json& value) {
    if (value.is_string()) {
        return value.get<std::string>();
    }
    if (value.is_null()) {
        return "";
    }
    return value.dump();
}

std::string field_text(const nlohmann::json& object, const char* key) {
    const auto it = object.find(key);
    return it == object.end() ? "" : as_text(*it);
}

std::optional<ContactRecord> record_from_json(const nlohmann::json& entry) {
    if (!entry.is_object()) {
        return std::nullopt;
    }
    const std::string addr = normalize_contact_address(field_text(entry, "addr"));
    if (addr.empty()) {
        return std::nullopt;
    }
    ContactRecord record;
    record.addr = addr;
    record.display_name = trim_whitespace(field_text(entry, "display_name"));
    record.note = trim_whitespace(field_text(entry, "note"));
    record.last_preview =
        truncate_code_points(field_text(entry, "last_preview"), kPreviewMaxLength);
    record.last_activity_ts = field_text(entry, "last_activity_ts");
    const auto days_it = entry.find("history_retention_days");
    if (days_it != entry.end() && days_it->is_number_integer()) {
        const auto days = days_it->get<long long>();
        record.history_retention_days = static_cast<unsigned>(
            std::clamp<long long>(days, 0, kMaxPeerHistoryRetentionDays));
    }
    const auto pinned_it = entry.find("pinned");
    if (pinned_it != entry.end() && pinned_it->is_boolean()) {
        record.pinned = pinned_it->get<bool>();
    }
    const auto order_it = entry.find("pin_order");
    if (order_it != entry.end() && order_it->is_number_integer()) {
        record.pin_order = static_cast<int>(std::max(0LL, order_it->get<long long>()));
    }
    if (!record.pinned) {
        record.pin_order = 0;
    }
    const auto muted_it = entry.find("notifications_muted");
    if (muted_it != entry.end() && muted_it->is_boolean()) {
        record.notifications_muted = muted_it->get<bool>();
    }
    return record;
}

nlohmann::json record_to_json(const ContactRecord& record) {
    nlohmann::json out = nlohmann::json::object();
    out["addr"] = record.addr;
    out["display_name"] = record.display_name;
    out["note"] = record.note;
    out["last_preview"] = record.last_preview;
    out["last_activity_ts"] = record.last_activity_ts;
    if (record.history_retention_days > 0) {
        out["history_retention_days"] = record.history_retention_days;
    }
    if (record.pinned) {
        out["pinned"] = true;
        out["pin_order"] = record.pin_order;
    }
    if (record.notifications_muted) {
        out["notifications_muted"] = true;
    }
    return out;
}

bool group_ui_worth_keeping(const GroupUiMeta& meta) {
    return meta.pinned || !meta.last_activity_ts.empty() || meta.notifications_muted;
}

}  // namespace

std::string normalize_contact_address(std::string_view raw) {
    std::string value = to_lower(trim_whitespace(raw));
    if (value.empty()) {
        return "";
    }
    if (value.size() > kB32Suffix.size() &&
        value.compare(value.size() - kB32Suffix.size(), kB32Suffix.size(), kB32Suffix) ==
            0) {
        value.resize(value.size() - kB32Suffix.size());
    }
    return is_base32_host(value) ? value : std::string();
}

bool same_i2p_destination(std::string_view left, std::string_view right) {
    const std::string normalized_left = normalize_contact_address(left);
    const std::string normalized_right = normalize_contact_address(right);
    if (!normalized_left.empty() && !normalized_right.empty()) {
        return normalized_left == normalized_right;
    }
    return to_lower(trim_whitespace(left)) == to_lower(trim_whitespace(right));
}

std::ptrdiff_t ContactBook::peer_index(std::string_view addr) const {
    const auto it = std::find_if(contacts_.begin(), contacts_.end(),
                                 [addr](const ContactRecord& record) {
                                     return record.addr == addr;
                                 });
    return it == contacts_.end() ? -1 : std::distance(contacts_.begin(), it);
}

const ContactRecord* ContactBook::get(std::string_view addr) const {
    const std::ptrdiff_t index = peer_index(addr);
    return index < 0 ? nullptr : &contacts_[static_cast<std::size_t>(index)];
}

ContactRecord* ContactBook::get(std::string_view addr) {
    const std::ptrdiff_t index = peer_index(addr);
    return index < 0 ? nullptr : &contacts_[static_cast<std::size_t>(index)];
}

bool ContactBook::has_peer(std::string_view addr) const {
    const std::string normalized = normalize_contact_address(addr);
    return !normalized.empty() && peer_index(normalized) >= 0;
}

std::vector<std::string> ContactBook::ordered_peer_addrs() const {
    std::vector<std::string> out;
    out.reserve(contacts_.size());
    for (const ContactRecord& record : contacts_) {
        out.push_back(record.addr);
    }
    return out;
}

bool ContactBook::remember_peer(std::string_view addr) {
    const std::string normalized = normalize_contact_address(addr);
    if (normalized.empty()) {
        return false;
    }
    const std::ptrdiff_t index = peer_index(normalized);
    if (index == 0) {
        return false;
    }
    if (index > 0) {
        ContactRecord record = contacts_[static_cast<std::size_t>(index)];
        contacts_.erase(contacts_.begin() + index);
        contacts_.insert(contacts_.begin(), std::move(record));
        return true;
    }
    contacts_.insert(contacts_.begin(), ContactRecord{normalized, "", "", "", ""});
    trim();
    return true;
}

bool ContactBook::set_last_active_peer(std::optional<std::string_view> addr) {
    if (!addr.has_value() || addr->empty()) {
        if (!last_active_peer_.has_value()) {
            return false;
        }
        last_active_peer_.reset();
        return true;
    }
    const std::string normalized = normalize_contact_address(*addr);
    if (normalized.empty()) {
        return false;
    }
    if (last_active_peer_ == normalized) {
        return false;
    }
    last_active_peer_ = normalized;
    return true;
}

bool ContactBook::set_peer_profile(std::string_view addr, std::string_view display_name,
                                   std::string_view note) {
    const std::string normalized = normalize_contact_address(addr);
    if (normalized.empty()) {
        return false;
    }
    const std::string name = trim_whitespace(display_name);
    const std::string trimmed_note = trim_whitespace(note);

    ContactRecord* record = get(normalized);
    if (record == nullptr) {
        remember_peer(normalized);
        record = get(normalized);
    }
    if (record == nullptr) {
        return false;
    }
    if (record->display_name == name && record->note == trimmed_note) {
        return false;
    }
    record->display_name = name;
    record->note = trimmed_note;
    return true;
}

bool ContactBook::set_peer_history_retention_days(std::string_view addr, unsigned days) {
    const std::string normalized = normalize_contact_address(addr);
    if (normalized.empty()) {
        return false;
    }
    days = std::min(days, kMaxPeerHistoryRetentionDays);
    ContactRecord* record = get(normalized);
    if (record == nullptr) {
        if (days == 0) {
            return false;
        }
        remember_peer(normalized);
        record = get(normalized);
    }
    if (record == nullptr || record->history_retention_days == days) {
        return false;
    }
    record->history_retention_days = days;
    return true;
}

bool ContactBook::set_peer_pin(std::string_view addr, bool pinned, int pin_order) {
    const std::string normalized = normalize_contact_address(addr);
    if (normalized.empty()) {
        return false;
    }
    pin_order = std::max(0, pin_order);
    ContactRecord* record = get(normalized);
    if (record == nullptr) {
        if (!pinned) {
            return false;
        }
        remember_peer(normalized);
        record = get(normalized);
    }
    if (record == nullptr) {
        return false;
    }
    if (record->pinned == pinned && (!pinned || record->pin_order == pin_order)) {
        return false;
    }
    record->pinned = pinned;
    record->pin_order = pinned ? pin_order : 0;
    return true;
}

bool ContactBook::set_group_pin(std::string_view group_id, bool pinned, int pin_order) {
    const std::string gid = trim_whitespace(group_id);
    if (gid.empty()) {
        return false;
    }
    pin_order = std::max(0, pin_order);
    GroupUiMeta& meta = group_ui_[gid];
    if (meta.pinned == pinned && (!pinned || meta.pin_order == pin_order)) {
        return false;
    }
    meta.pinned = pinned;
    meta.pin_order = pinned ? pin_order : 0;
    if (!group_ui_worth_keeping(meta)) {
        group_ui_.erase(gid);
    }
    return true;
}

bool ContactBook::set_peer_notifications_muted(std::string_view addr, bool muted) {
    const std::string normalized = normalize_contact_address(addr);
    if (normalized.empty()) {
        return false;
    }
    ContactRecord* record = get(normalized);
    if (record == nullptr) {
        if (!muted) {
            return false;
        }
        remember_peer(normalized);
        record = get(normalized);
    }
    if (record == nullptr) {
        return false;
    }
    if (record->notifications_muted == muted) {
        return false;
    }
    record->notifications_muted = muted;
    return true;
}

bool ContactBook::set_group_notifications_muted(std::string_view group_id, bool muted) {
    const std::string gid = trim_whitespace(group_id);
    if (gid.empty()) {
        return false;
    }
    GroupUiMeta& meta = group_ui_[gid];
    if (meta.notifications_muted == muted) {
        return false;
    }
    meta.notifications_muted = muted;
    if (!group_ui_worth_keeping(meta)) {
        group_ui_.erase(gid);
    }
    return true;
}

bool ContactBook::touch_group_activity(std::string_view group_id, std::string_view ts_iso) {
    const std::string gid = trim_whitespace(group_id);
    const std::string ts = trim_whitespace(ts_iso);
    if (gid.empty() || ts.empty()) {
        return false;
    }
    GroupUiMeta& meta = group_ui_[gid];
    if (meta.last_activity_ts == ts) {
        return false;
    }
    meta.last_activity_ts = ts;
    return true;
}

bool ContactBook::touch_peer_message_meta(std::string_view addr, std::string_view preview,
                                          std::string_view ts_iso) {
    const std::string normalized = normalize_contact_address(addr);
    if (normalized.empty()) {
        return false;
    }

    std::string flattened(preview);
    std::replace(flattened.begin(), flattened.end(), '\n', ' ');
    flattened = trim_whitespace(flattened);
    const std::optional<std::size_t> length = encoding::utf8_length(flattened);
    if (length.has_value() && *length > kPreviewMaxLength) {
        // An ellipsis marks the cut, so the UI does not imply the message ended
        // there. One code point of budget goes to the ellipsis itself.
        flattened = truncate_code_points(flattened, kPreviewMaxLength - 1) + "\xE2\x80\xA6";
    }
    const std::string timestamp = trim_whitespace(ts_iso);

    ContactRecord* record = get(normalized);
    if (record == nullptr) {
        remember_peer(normalized);
        record = get(normalized);
    }
    if (record == nullptr) {
        return false;
    }
    if (record->last_preview == flattened && record->last_activity_ts == timestamp) {
        return false;
    }
    record->last_preview = flattened;
    record->last_activity_ts = timestamp;
    return true;
}

bool ContactBook::remove_peer(std::string_view addr) {
    const std::string normalized = normalize_contact_address(addr);
    if (normalized.empty()) {
        return false;
    }
    const std::ptrdiff_t index = peer_index(normalized);
    if (index < 0) {
        return false;
    }
    contacts_.erase(contacts_.begin() + index);
    if (last_active_peer_ == normalized) {
        last_active_peer_.reset();
    }
    return true;
}

void ContactBook::trim() {
    if (contacts_.size() > kMaxContacts) {
        contacts_.resize(kMaxContacts);
    }
}

ContactBook parse_contact_book(const nlohmann::json& data) {
    ContactBook book;
    if (!data.is_object()) {
        return book;
    }
    const auto raw_contacts = data.find("contacts");
    if (raw_contacts == data.end() || !raw_contacts->is_array()) {
        return book;
    }

    const std::uint32_t version = data.value("version", 1U);
    const bool all_strings =
        std::all_of(raw_contacts->begin(), raw_contacts->end(),
                    [](const nlohmann::json& item) { return item.is_string(); });

    std::vector<ContactRecord>& records = book.contacts();
    for (const nlohmann::json& item : *raw_contacts) {
        std::optional<ContactRecord> record;
        if (version == 1 || all_strings) {
            if (!item.is_string()) {
                continue;
            }
            const std::string addr =
                normalize_contact_address(item.get<std::string>());
            if (!addr.empty()) {
                record = ContactRecord{addr, "", "", "", ""};
            }
        } else {
            record = record_from_json(item);
        }
        if (!record.has_value()) {
            continue;
        }
        const bool duplicate =
            std::any_of(records.begin(), records.end(),
                        [&record](const ContactRecord& existing) {
                            return existing.addr == record->addr;
                        });
        if (!duplicate) {
            records.push_back(std::move(*record));
        }
    }

    // A last-active peer that is not in the list would point at nothing, so it is
    // dropped rather than carried forward.
    const auto last_active = data.find("last_active_peer");
    if (last_active != data.end() && last_active->is_string()) {
        book.set_last_active_peer(last_active->get<std::string>());
        if (book.last_active_peer().has_value() &&
            book.peer_index(*book.last_active_peer()) < 0) {
            book.set_last_active_peer(std::nullopt);
        }
    }
    const auto group_ui = data.find("group_ui");
    if (group_ui != data.end() && group_ui->is_object()) {
        for (auto it = group_ui->begin(); it != group_ui->end(); ++it) {
            if (!it.value().is_object()) {
                continue;
            }
            GroupUiMeta meta;
            const auto& obj = it.value();
            if (obj.contains("pinned") && obj["pinned"].is_boolean()) {
                meta.pinned = obj["pinned"].get<bool>();
            }
            if (obj.contains("pin_order") && obj["pin_order"].is_number_integer()) {
                meta.pin_order =
                    static_cast<int>(std::max(0LL, obj["pin_order"].get<long long>()));
            }
            if (obj.contains("last_activity_ts") && obj["last_activity_ts"].is_string()) {
                meta.last_activity_ts = obj["last_activity_ts"].get<std::string>();
            }
            if (obj.contains("notifications_muted") && obj["notifications_muted"].is_boolean()) {
                meta.notifications_muted = obj["notifications_muted"].get<bool>();
            }
            if (!meta.pinned) {
                meta.pin_order = 0;
            }
            if (group_ui_worth_keeping(meta)) {
                book.group_ui()[it.key()] = std::move(meta);
            }
        }
    }
    return book;
}

nlohmann::json contact_book_to_json(const ContactBook& book) {
    nlohmann::json contacts = nlohmann::json::array();
    const std::size_t count = std::min(book.contacts().size(), kMaxContacts);
    for (std::size_t i = 0; i < count; ++i) {
        contacts.push_back(record_to_json(book.contacts()[i]));
    }

    nlohmann::json out = nlohmann::json::object();
    out["version"] = kContactBookVersion;
    out["last_active_peer"] = nlohmann::json();
    if (book.last_active_peer().has_value()) {
        const std::string normalized =
            normalize_contact_address(*book.last_active_peer());
        if (!normalized.empty()) {
            out["last_active_peer"] = normalized;
        }
    }
    out["contacts"] = std::move(contacts);
    nlohmann::json group_ui = nlohmann::json::object();
    for (const auto& [gid, meta] : book.group_ui()) {
        if (!group_ui_worth_keeping(meta)) {
            continue;
        }
        nlohmann::json entry = nlohmann::json::object();
        if (meta.pinned) {
            entry["pinned"] = true;
            entry["pin_order"] = meta.pin_order;
        }
        if (!meta.last_activity_ts.empty()) {
            entry["last_activity_ts"] = meta.last_activity_ts;
        }
        if (meta.notifications_muted) {
            entry["notifications_muted"] = true;
        }
        group_ui[gid] = std::move(entry);
    }
    if (!group_ui.empty()) {
        out["group_ui"] = std::move(group_ui);
    }
    return out;
}

ContactBook load_contact_book(const std::filesystem::path& path,
                              std::optional<ByteView> identity_key) {
    try {
        return parse_contact_book(read_sealed_json(path, identity_key, kContactsFormat));
    } catch (const std::exception&) {
        return ContactBook();
    }
}

void save_contact_book(const std::filesystem::path& path, const ContactBook& book,
                       std::optional<ByteView> identity_key) {
    write_sealed_json(path, contact_book_to_json(book), identity_key, kContactsFormat);
}

}  // namespace i2pchat::storage
