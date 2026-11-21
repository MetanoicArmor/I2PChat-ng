#pragma once

/// Placeholder for a future Boost.Asio SAM DATAGRAM channel used by native
/// call media. Android currently opens DATAGRAM from Kotlin
/// (`SamDatagramSession`); desktop uses Python `i2pchat.sam.datagram`.
/// Signaling for calls is already handled via `SignalKind::CallSignal`.

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace i2pchat::runtime {

struct MediaDatagramPacket {
    std::string from_destination;
    std::vector<std::uint8_t> payload;
};

using MediaDatagramHandler = std::function<void(MediaDatagramPacket)>;

}  // namespace i2pchat::runtime
