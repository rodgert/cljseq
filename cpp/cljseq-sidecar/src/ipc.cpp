// SPDX-License-Identifier: LGPL-2.1-or-later
//
// IPC — bidirectional TCP localhost frame receiver/sender for cljseq-sidecar
//
// Wire format (little-endian throughout):
//   [uint32 payload_len][uint8 msg_type][uint8 reserved×3][uint8[] payload]
//
// NoteOn / NoteOff / CC payload (12 bytes):
//   [int64 time_ns][uint8 channel][uint8 note_or_cc][uint8 vel_or_val][uint8 reserved]
//
// LinkEnable payload (1 byte):  [uint8 quantum]  (0 → use default 4)
// LinkDisable: no payload.
// LinkSetBpm payload (8 bytes): [double bpm LE]
//
// 0x80 LinkState push payload (37 bytes):
//   [double bpm][double anchor_beat][int64 anchor_us][uint32 peers][uint8 playing][uint8 reserved×3]
//
// Ping has no payload (payload_len == 0).
// Shutdown has no payload; receipt causes ipc_serve() to return.
//
// One connection is accepted; reconnection is not supported in Phase 1.

#include "ipc.h"
#include "midi_clock.h"
#include "midi_dispatch.h"
#include "midi_monitor.h"

#include <cljseq/link_bridge.h>
#include <cljseq/scheduler.h>

#include <asio.hpp>
#include <array>
#include <vector>
#include <memory>
#include <mutex>
#include <cstdio>
#include <cstring>

using asio::ip::tcp;

// ---------------------------------------------------------------------------
// Little-endian helpers
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

double read_le_double(const uint8_t* p) noexcept {
    int64_t bits = read_le64(p);
    double v;
    std::memcpy(&v, &bits, sizeof(v));
    return v;
}

// Write helpers (little-endian)

void write_le32(uint8_t* p, uint32_t v) noexcept {
    p[0] = static_cast<uint8_t>(v);
    p[1] = static_cast<uint8_t>(v >> 8);
    p[2] = static_cast<uint8_t>(v >> 16);
    p[3] = static_cast<uint8_t>(v >> 24);
}

void write_le64(uint8_t* p, int64_t v) noexcept {
    for (int i = 0; i < 8; ++i)
        p[i] = static_cast<uint8_t>(v >> (8 * i));
}

void write_le_double(uint8_t* p, double v) noexcept {
    int64_t bits;
    std::memcpy(&bits, &v, sizeof(bits));
    write_le64(p, bits);
}

// ---------------------------------------------------------------------------
// MsgType enum — must match cljseq.sidecar Clojure constants
// ---------------------------------------------------------------------------

enum class MsgType : uint8_t {
    NoteOn       = 0x01,
    NoteOff      = 0x02,
    CC           = 0x03,
    PitchBend    = 0x04,  // per-note MPE pitch bend
    ChanPressure = 0x05,  // per-channel pressure (MPE aftertouch)
    SysEx        = 0x06,  // raw SysEx blob (payload = F0 ... F7)
    LinkEnable   = 0x10,
    LinkDisable  = 0x11,
    LinkSetBpm   = 0x12,
    MidiIn       = 0x20,  // sidecar→JVM: MIDI message received on input port
    LinkState    = 0x80,
    Ping         = 0xF0,
    Shutdown     = 0xFF,
};

// ---------------------------------------------------------------------------
// IpcSession — owns one socket, drives bidirectional async I/O
// ---------------------------------------------------------------------------

class IpcSession : public std::enable_shared_from_this<IpcSession> {
public:
    IpcSession(tcp::socket socket, asio::io_context& io,
               cljseq::LinkBridge& link, int midi_in_port)
        : socket_(std::move(socket)), io_(io), link_(link),
          midi_in_port_(midi_in_port) {}

    ~IpcSession() {
        if (midi_in_port_ >= 0)
            cljseq::midi_monitor_stop();
    }

    void start() {
        // Register Link state callback: when Link fires, push 0x80 to JVM.
        link_.set_state_callback([this](const cljseq::LinkState& ls) {
            push_link_state(ls);
        });

        // If a MIDI input port was requested, open it and push every received
        // message back to the JVM as a 0x20 MidiIn frame.
        if (midi_in_port_ >= 0) {
            auto self = shared_from_this();
            bool ok = cljseq::midi_monitor_start(
                static_cast<unsigned int>(midi_in_port_),
                [self](int64_t time_ns, uint8_t status, uint8_t b1, uint8_t b2) {
                    self->push_midi_in(time_ns, status, b1, b2);
                });
            if (!ok)
                std::fprintf(stderr,
                    "[ipc] midi_monitor_start(%d) failed — input monitoring disabled\n",
                    midi_in_port_);
        }

        read_header();
    }

private:
    static constexpr std::size_t kHeaderLen = 8;

    tcp::socket              socket_;
    asio::io_context&        io_;
    cljseq::LinkBridge&      link_;
    int                      midi_in_port_;
    std::array<uint8_t, kHeaderLen> header_buf_;
    std::vector<uint8_t>     payload_buf_;

    // Serialise concurrent writes (Link callback, MIDI-in callback vs. read loop).
    std::mutex               write_mutex_;

    // ---------------------------------------------------------------------------
    // Inbound: read → dispatch
    // ---------------------------------------------------------------------------

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
        auto msg_type = static_cast<MsgType>(msg_type_byte);

        switch (msg_type) {

        case MsgType::NoteOn:
        case MsgType::NoteOff:
        case MsgType::CC:
        case MsgType::PitchBend:
        case MsgType::ChanPressure:
            // All five types share the same 12-byte payload layout:
            //   [int64 time_ns][uint8 channel][uint8 byte9][uint8 byte10][uint8 reserved]
            //   NoteOn/NoteOff: byte9=note,   byte10=velocity/0
            //   CC:             byte9=cc_num, byte10=value
            //   PitchBend:      byte9=lsb,    byte10=msb   (14-bit = msb<<7|lsb)
            //   ChanPressure:   byte9=0,       byte10=pressure
            if (len < 12) {
                std::fprintf(stderr, "[ipc] short payload for type 0x%02x (%zu bytes)\n",
                             msg_type_byte, len);
                break;
            }
            {
                cljseq::ScheduledEvent ev;
                ev.time_ns           = read_le64(payload);
                ev.type              = static_cast<cljseq::MsgType>(msg_type_byte);
                ev.channel           = payload[8];
                ev.note_or_cc        = payload[9];
                ev.velocity_or_value = payload[10];
                std::fprintf(stderr, "[ipc] enqueue type=0x%02x ch=%u b9=%u b10=%u time_ns=%lld\n",
                             msg_type_byte, ev.channel, ev.note_or_cc,
                             ev.velocity_or_value, (long long)ev.time_ns);
                cljseq::scheduler_enqueue(ev);
            }
            break;

        case MsgType::SysEx:
            // Payload is raw SysEx bytes including F0 and F7 delimiters.
            if (len < 2 || payload[0] != 0xF0 || payload[len - 1] != 0xF7) {
                std::fprintf(stderr, "[ipc] SysEx: invalid framing (%zu bytes)\n", len);
                break;
            }
            midi_send_sysex(payload, len);
            break;

        case MsgType::LinkEnable: {
            double quantum = (len >= 1 && payload[0] != 0)
                             ? static_cast<double>(payload[0])
                             : 4.0;
            link_.enable(quantum);
            // Start MIDI clock output — Link is now the tempo source.
            midi_clock_set_playing(true);
            break;
        }

        case MsgType::LinkDisable:
            // Stop MIDI clock before leaving the Link session.
            midi_clock_set_playing(false);
            link_.disable();
            break;

        case MsgType::LinkSetBpm:
            if (len >= 8) {
                double bpm = read_le_double(payload);
                link_.set_bpm(bpm);
            }
            break;

        case MsgType::Ping:
            break;

        case MsgType::Shutdown:
            std::fprintf(stderr, "[ipc] Shutdown received — stopping\n");
            io_.stop();
            return; // do not chain read_header

        default:
            std::fprintf(stderr, "[ipc] unknown message type 0x%02x — ignoring\n",
                         msg_type_byte);
            break;
        }

        read_header();
    }

    // ---------------------------------------------------------------------------
    // Outbound: push LinkState (0x80) to JVM
    //
    // Wire payload (37 bytes):
    //   [double bpm(8)][double anchor_beat(8)][int64 anchor_us(8)]
    //   [uint32 peers(4)][uint8 playing(1)][uint8 reserved×3(3)]
    // ---------------------------------------------------------------------------

    void push_link_state(const cljseq::LinkState& ls) {
        static constexpr std::size_t kPayloadLen = 37;
        static constexpr std::size_t kFrameLen   = 8 + kPayloadLen; // header + payload

        auto frame = std::make_shared<std::vector<uint8_t>>(kFrameLen, uint8_t{0});
        uint8_t* p = frame->data();

        // Header: [uint32 payload_len][uint8 msg_type][uint8 reserved×3]
        write_le32(p,     static_cast<uint32_t>(kPayloadLen));
        p[4] = static_cast<uint8_t>(MsgType::LinkState);
        // p[5..7] reserved, zeroed

        // Payload
        uint8_t* d = p + 8;
        write_le_double(d,      ls.bpm);          d += 8;
        write_le_double(d,      ls.anchor_beat);  d += 8;
        write_le64(d,           ls.anchor_us);    d += 8;
        write_le32(d,           ls.peers);        d += 4;
        d[0] = ls.playing ? 1u : 0u;
        // d[1..3] reserved, zeroed

        std::fprintf(stderr,
            "[ipc] push LinkState bpm=%.2f anchor_beat=%.3f peers=%u playing=%d\n",
            ls.bpm, ls.anchor_beat, ls.peers, ls.playing ? 1 : 0);

        // Serialise writes: Link callbacks may fire from any thread.
        std::lock_guard<std::mutex> lk(write_mutex_);
        auto self = shared_from_this();
        asio::async_write(socket_, asio::buffer(*frame),
            [self, frame](asio::error_code ec, std::size_t) {
                if (ec)
                    std::fprintf(stderr, "[ipc] push write error: %s\n",
                                 ec.message().c_str());
            });
    }

    // ---------------------------------------------------------------------------
    // Outbound: push MidiIn (0x20) to JVM
    //
    // Wire payload (12 bytes):
    //   [int64 time_ns(8)][uint8 status(1)][uint8 b1(1)][uint8 b2(1)][uint8 reserved(1)]
    //
    // Called from the RtMidi callback thread — must not block.
    // ---------------------------------------------------------------------------

    void push_midi_in(int64_t time_ns, uint8_t status, uint8_t b1, uint8_t b2) {
        static constexpr std::size_t kPayloadLen = 12;
        static constexpr std::size_t kFrameLen   = 8 + kPayloadLen;

        auto frame = std::make_shared<std::vector<uint8_t>>(kFrameLen, uint8_t{0});
        uint8_t* p = frame->data();

        // Header
        write_le32(p, static_cast<uint32_t>(kPayloadLen));
        p[4] = static_cast<uint8_t>(MsgType::MidiIn);
        // p[5..7] reserved, zeroed

        // Payload
        uint8_t* d = p + 8;
        write_le64(d, time_ns); d += 8;
        d[0] = status;
        d[1] = b1;
        d[2] = b2;
        // d[3] reserved, zeroed

        std::fprintf(stderr,
            "[midi_monitor] recv status=0x%02x b1=%u b2=%u time_ns=%lld\n",
            status, b1, b2, (long long)time_ns);

        std::lock_guard<std::mutex> lk(write_mutex_);
        auto self = shared_from_this();
        asio::async_write(socket_, asio::buffer(*frame),
            [self, frame](asio::error_code ec, std::size_t) {
                if (ec)
                    std::fprintf(stderr, "[ipc] midi_in push write error: %s\n",
                                 ec.message().c_str());
            });
    }
};

} // anonymous namespace

// ---------------------------------------------------------------------------
// ipc_serve — entry point
// ---------------------------------------------------------------------------

void ipc_serve(unsigned short port, cljseq::LinkBridge& link, int midi_in_port) {
    asio::io_context io;
    tcp::acceptor acceptor(io, tcp::endpoint(tcp::v4(), port));
    std::fprintf(stderr, "[ipc] listening on port %u\n", port);

    // Accept exactly one connection.
    tcp::socket socket(io);
    acceptor.accept(socket);
    std::fprintf(stderr, "[ipc] JVM connected\n");

    auto session = std::make_shared<IpcSession>(std::move(socket), io, link, midi_in_port);
    session->start();

    io.run();
    std::fprintf(stderr, "[ipc] event loop exited\n");
}
