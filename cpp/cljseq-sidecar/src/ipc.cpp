// SPDX-License-Identifier: LGPL-2.1-or-later
//
// IPC — TCP localhost frame receiver for cljseq-sidecar
//
// Wire format (little-endian throughout):
//   [uint32 payload_len][uint8 msg_type][uint8 reserved×3][uint8[] payload]
//
// NoteOn / NoteOff / CC payload (12 bytes):
//   [int64 time_ns][uint8 channel][uint8 note_or_cc][uint8 vel_or_val][uint8 reserved]
//
// Ping has no payload (payload_len == 0).
// Shutdown has no payload; receipt causes ipc_serve() to return.
//
// One connection is accepted; reconnection is not supported in Phase 1.

#include "ipc.h"

#include <cljseq/scheduler.h>

#include <asio.hpp>
#include <array>
#include <vector>
#include <memory>
#include <cstdio>
#include <cstring>

using asio::ip::tcp;

// ---------------------------------------------------------------------------
// Little-endian read helpers
// ---------------------------------------------------------------------------

namespace {

uint32_t read_le32(const uint8_t* p) noexcept {
    return static_cast<uint32_t>(p[0])
         | static_cast<uint32_t>(p[1]) << 8
         | static_cast<uint32_t>(p[2]) << 16
         | static_cast<uint32_t>(p[3]) << 24;
}

int64_t read_le64(const uint8_t* p) noexcept {
    return static_cast<int64_t>(p[0])
         | static_cast<int64_t>(p[1]) << 8
         | static_cast<int64_t>(p[2]) << 16
         | static_cast<int64_t>(p[3]) << 24
         | static_cast<int64_t>(p[4]) << 32
         | static_cast<int64_t>(p[5]) << 40
         | static_cast<int64_t>(p[6]) << 48
         | static_cast<int64_t>(p[7]) << 56;
}

// ---------------------------------------------------------------------------
// IpcSession — owns one socket, drives the async read state machine
// ---------------------------------------------------------------------------

class IpcSession : public std::enable_shared_from_this<IpcSession> {
public:
    IpcSession(tcp::socket socket, asio::io_context& io)
        : socket_(std::move(socket)), io_(io) {}

    void start() { read_header(); }

private:
    static constexpr std::size_t kHeaderLen = 8; // uint32 + uint8 + uint8×3

    tcp::socket              socket_;
    asio::io_context&        io_;
    std::array<uint8_t, kHeaderLen> header_buf_;
    std::vector<uint8_t>     payload_buf_;

    void read_header() {
        auto self = shared_from_this();
        asio::async_read(socket_, asio::buffer(header_buf_),
            [this, self](asio::error_code ec, std::size_t) {
                if (ec) {
                    if (ec != asio::error::eof)
                        std::fprintf(stderr, "[ipc] read error: %s\n",
                                     ec.message().c_str());
                    return;
                }

                uint32_t payload_len = read_le32(header_buf_.data());
                uint8_t  msg_type    = header_buf_[4];
                // bytes [5..7] are reserved

                if (payload_len == 0) {
                    handle_message(msg_type, nullptr, 0);
                } else {
                    payload_buf_.resize(payload_len);
                    read_payload(msg_type);
                }
            });
    }

    void read_payload(uint8_t msg_type) {
        auto self = shared_from_this();
        asio::async_read(socket_, asio::buffer(payload_buf_),
            [this, self, msg_type](asio::error_code ec, std::size_t) {
                if (ec) {
                    std::fprintf(stderr, "[ipc] payload read error: %s\n",
                                 ec.message().c_str());
                    return;
                }
                handle_message(msg_type, payload_buf_.data(), payload_buf_.size());
            });
    }

    void handle_message(uint8_t msg_type_byte, const uint8_t* payload, std::size_t len) {
        using MT = cljseq::MsgType;
        auto msg_type = static_cast<MT>(msg_type_byte);

        switch (msg_type) {
        case MT::NoteOn:
        case MT::NoteOff:
        case MT::CC:
            if (len < 12) {
                std::fprintf(stderr, "[ipc] short payload for type 0x%02x (%zu bytes)\n",
                             msg_type_byte, len);
                break;
            }
            {
                cljseq::ScheduledEvent ev;
                ev.time_ns           = read_le64(payload);
                ev.type              = msg_type;
                ev.channel           = payload[8];
                ev.note_or_cc        = payload[9];
                ev.velocity_or_value = payload[10];
                // payload[11] is reserved
                std::fprintf(stderr, "[ipc] enqueue type=0x%02x ch=%u note=%u vel=%u time_ns=%lld\n",
                             msg_type_byte, ev.channel, ev.note_or_cc,
                             ev.velocity_or_value, (long long)ev.time_ns);
                cljseq::scheduler_enqueue(ev);
            }
            break;

        case MT::Ping:
            // No-op keepalive; log at trace level only if needed
            break;

        case MT::Shutdown:
            std::fprintf(stderr, "[ipc] Shutdown received — stopping\n");
            io_.stop();
            return; // do not chain read_header

        default:
            std::fprintf(stderr, "[ipc] unknown message type 0x%02x — ignoring\n",
                         msg_type_byte);
            break;
        }

        // Chain next read
        read_header();
    }
};

} // anonymous namespace

// ---------------------------------------------------------------------------
// ipc_serve — accept one connection and run the event loop
// ---------------------------------------------------------------------------

void ipc_serve(unsigned short port) {
    asio::io_context io;
    tcp::acceptor acceptor{io, tcp::endpoint{tcp::v4(), port}};

    std::fprintf(stderr, "[ipc] listening on port %u\n", port);

    // Accept a single connection (Phase 1: one JVM peer)
    tcp::socket socket{io};
    acceptor.accept(socket);
    std::fprintf(stderr, "[ipc] JVM connected\n");

    // Kick off the async read loop; io.run() drives it until Shutdown or error
    std::make_shared<IpcSession>(std::move(socket), io)->start();
    io.run();

    std::fprintf(stderr, "[ipc] event loop exited\n");
}
