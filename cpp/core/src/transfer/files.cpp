#include "i2pchat/transfer/files.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <system_error>

namespace i2pchat::transfer {
namespace {

/// The reference's unsafe set: shell and Windows path metacharacters plus every
/// control byte. Bytes above 0x7F are left alone so UTF-8 names survive.
bool is_unsafe(unsigned char byte) {
    switch (byte) {
        case '<':
        case '>':
        case ':':
        case '"':
        case '/':
        case '\\':
        case '|':
        case '?':
        case '*':
            return true;
        default:
            return byte <= 0x1F;
    }
}

std::string trim(std::string_view text) {
    const auto begin = text.find_first_not_of(" \t\r\n");
    if (begin == std::string_view::npos) {
        return {};
    }
    const auto end = text.find_last_not_of(" \t\r\n");
    return std::string(text.substr(begin, end - begin + 1));
}

std::int64_t now_seconds() {
    return std::chrono::duration_cast<std::chrono::seconds>(
               std::chrono::system_clock::now().time_since_epoch())
        .count();
}

/// The name without its directory part, for both separators: a peer on Windows
/// may send a backslash path and a peer on Unix a forward-slash one.
std::string basename_of(std::string_view name) {
    const auto position = name.find_last_of("/\\");
    if (position == std::string_view::npos) {
        return std::string(name);
    }
    return std::string(name.substr(position + 1));
}

/// Splits at the last dot, matching `os.path.splitext`: a leading dot is part
/// of the stem, so ".bashrc" has no extension.
std::pair<std::string, std::string> split_extension(const std::string& name) {
    const auto dot = name.find_last_of('.');
    if (dot == std::string::npos || dot == 0) {
        return {name, {}};
    }
    return {name.substr(0, dot), name.substr(dot)};
}

}  // namespace

std::string sanitize_filename(std::string_view name,
                              std::optional<std::int64_t> timestamp) {
    const std::int64_t stamp = timestamp.value_or(now_seconds());
    std::string cleaned = trim(basename_of(name));
    for (char& character : cleaned) {
        if (is_unsafe(static_cast<unsigned char>(character))) {
            character = '_';
        }
    }

    // A hidden name is replaced rather than unhidden: silently creating
    // `.profile` in the downloads directory is worse than an opaque name.
    if (cleaned.empty() || cleaned.front() == '.') {
        return "file_" + std::to_string(stamp);
    }
    if (cleaned.size() > 200) {
        std::string extension = split_extension(cleaned).second;
        if (extension.size() > 10) {
            extension.resize(10);
        }
        return "file_" + std::to_string(stamp) + extension;
    }
    return cleaned;
}

std::filesystem::path allocate_unique_filename(const std::filesystem::path& directory,
                                               std::string_view filename,
                                               int max_attempts) {
    const std::string safe_name = sanitize_filename(filename);
    const std::filesystem::path first = directory / safe_name;
    if (!std::filesystem::exists(first)) {
        return first;
    }

    const auto [stem, extension] = split_extension(safe_name);
    for (int index = 1; index <= max_attempts; ++index) {
        const std::filesystem::path candidate =
            directory / (stem + " (" + std::to_string(index) + ")" + extension);
        if (!std::filesystem::exists(candidate)) {
            return candidate;
        }
    }
    throw std::filesystem::filesystem_error(
        "Cannot allocate a unique filename for " + safe_name, first,
        std::make_error_code(std::errc::file_exists));
}

std::size_t max_base64_chars_for_bytes(std::uint64_t byte_count) {
    if (byte_count == 0) {
        return 0;
    }
    return static_cast<std::size_t>(((byte_count + 2) / 3) * 4);
}

std::optional<std::string> detect_inline_image_format(ByteView header) {
    static constexpr std::array<Byte, 8> kPng{0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    if (header.size() >= kPng.size() &&
        std::equal(kPng.begin(), kPng.end(), header.begin())) {
        return "png";
    }
    if (header.size() >= 3 && header[0] == 0xFF && header[1] == 0xD8 && header[2] == 0xFF) {
        return "jpeg";
    }
    // RIFF, then four bytes of chunk size, then WEBP.
    if (header.size() >= 12 && to_string(header.subspan(0, 4)) == "RIFF" &&
        to_string(header.subspan(8, 4)) == "WEBP") {
        return "webp";
    }
    return std::nullopt;
}

namespace {

constexpr std::array<Byte, 8> kPngSignature{0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

bool starts_with(ByteView data, ByteView prefix) {
    return data.size() >= prefix.size() &&
           std::equal(prefix.begin(), prefix.end(), data.begin());
}

Bytes copy_bytes(ByteView data) { return Bytes(data.begin(), data.end()); }

void append_bytes(Bytes& target, ByteView data) {
    target.insert(target.end(), data.begin(), data.end());
}

Bytes strip_png_metadata(ByteView data) {
    static constexpr std::array<std::string_view, 5> kDrop{"tEXt", "iTXt", "zTXt", "eXIf",
                                                           "tIME"};
    Bytes out(data.begin(), data.begin() + 8);
    std::size_t index = 8;
    while (index + 12 <= data.size()) {
        const std::uint32_t length = (std::uint32_t(data[index]) << 24) |
                                     (std::uint32_t(data[index + 1]) << 16) |
                                     (std::uint32_t(data[index + 2]) << 8) |
                                     std::uint32_t(data[index + 3]);
        const std::string_view type(reinterpret_cast<const char*>(data.data() + index + 4), 4);
        const std::size_t end = index + 12 + static_cast<std::size_t>(length);
        if (end > data.size()) {
            return copy_bytes(data);
        }
        const bool drop = std::find(kDrop.begin(), kDrop.end(), type) != kDrop.end();
        if (!drop) {
            append_bytes(out, data.subspan(index, end - index));
        }
        index = end;
        if (type == "IEND") {
            return out;
        }
    }
    return copy_bytes(data);
}

Bytes strip_jpeg_metadata(ByteView data) {
    Bytes out{0xFF, 0xD8};
    std::size_t index = 2;
    while (index < data.size()) {
        if (data[index] != 0xFF) {
            return copy_bytes(data);
        }
        while (index < data.size() && data[index] == 0xFF) {
            ++index;
        }
        if (index >= data.size()) {
            return copy_bytes(data);
        }
        const Byte marker = data[index++];
        if (marker == 0xD9) {
            out.push_back(0xFF);
            out.push_back(0xD9);
            return out;
        }
        if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
            out.push_back(0xFF);
            out.push_back(marker);
            continue;
        }
        if (index + 2 > data.size()) {
            return copy_bytes(data);
        }
        const std::uint32_t segment_length =
            (std::uint32_t(data[index]) << 8) | std::uint32_t(data[index + 1]);
        if (segment_length < 2 || index + segment_length > data.size()) {
            return copy_bytes(data);
        }
        if (marker == 0xDA) {
            out.push_back(0xFF);
            out.push_back(0xDA);
            append_bytes(out, data.subspan(index));
            return out;
        }
        // APP1 is EXIF and XMP, APP13 is IPTC, COM is a text comment.
        if (marker != 0xE1 && marker != 0xED && marker != 0xFE) {
            out.push_back(0xFF);
            out.push_back(marker);
            append_bytes(out, data.subspan(index, segment_length));
        }
        index += segment_length;
    }
    return out;
}

Bytes strip_webp_metadata(ByteView data) {
    Bytes body;
    std::size_t index = 12;
    while (index + 8 <= data.size()) {
        const std::string_view tag(reinterpret_cast<const char*>(data.data() + index), 4);
        const std::uint32_t payload_size = std::uint32_t(data[index + 4]) |
                                           (std::uint32_t(data[index + 5]) << 8) |
                                           (std::uint32_t(data[index + 6]) << 16) |
                                           (std::uint32_t(data[index + 7]) << 24);
        index += 8;
        if (index + payload_size > data.size()) {
            return copy_bytes(data);
        }
        const ByteView payload = data.subspan(index, payload_size);
        index += payload_size;
        if (payload_size % 2 == 1 && index < data.size()) {
            ++index;
        }
        if (tag == "EXIF" || tag == "XMP ") {
            continue;
        }
        append_bytes(body, as_bytes(tag));
        body.push_back(static_cast<Byte>(payload_size & 0xFF));
        body.push_back(static_cast<Byte>((payload_size >> 8) & 0xFF));
        body.push_back(static_cast<Byte>((payload_size >> 16) & 0xFF));
        body.push_back(static_cast<Byte>((payload_size >> 24) & 0xFF));
        append_bytes(body, payload);
        if (payload_size % 2 == 1) {
            body.push_back(0);
        }
    }
    if (body.empty()) {
        return copy_bytes(data);
    }
    const std::uint32_t riff_size = static_cast<std::uint32_t>(4 + body.size());
    Bytes out{'R', 'I', 'F', 'F'};
    out.push_back(static_cast<Byte>(riff_size & 0xFF));
    out.push_back(static_cast<Byte>((riff_size >> 8) & 0xFF));
    out.push_back(static_cast<Byte>((riff_size >> 16) & 0xFF));
    out.push_back(static_cast<Byte>((riff_size >> 24) & 0xFF));
    append_bytes(out, as_bytes("WEBP"));
    append_bytes(out, ByteView(body));
    return out;
}

}  // namespace

Bytes strip_image_metadata(ByteView data) {
    Bytes stripped = copy_bytes(data);
    if (starts_with(data, ByteView(kPngSignature))) {
        stripped = strip_png_metadata(data);
    } else if (data.size() >= 3 && data[0] == 0xFF && data[1] == 0xD8) {
        stripped = strip_jpeg_metadata(data);
    } else if (data.size() >= 12 && to_string(data.subspan(0, 4)) == "RIFF" &&
               to_string(data.subspan(8, 4)) == "WEBP") {
        stripped = strip_webp_metadata(data);
    }
    if (!detect_inline_image_format(ByteView(stripped))) {
        return copy_bytes(data);
    }
    return stripped;
}

}  // namespace i2pchat::transfer
