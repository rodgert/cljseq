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

// SPDX-License-Identifier: LGPL-2.1-or-later
#include "ipc.h"
#include "keyboard_capture.h"
#include "midi_clock.h"
#include "midi_dispatch.h"
#include "midi_monitor.h"

#include <cljseq/link_bridge.h>
#include <cljseq/scheduler.h>

#include <asio.hpp>
#include <array>
#include <cstddef>
#include <memory>
#include <vector>
#include <mutex>
#include <cstdio>
#include <cstring>
#include <string>

#ifdef CLJSEQ_WITH_SQLITE
#  include <sqlite3.h>
#endif

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
    NoteOn              = 0x01,
    NoteOff             = 0x02,
    CC                  = 0x03,
    PitchBend           = 0x04,  // per-note MPE pitch bend
    ChanPressure        = 0x05,  // per-channel pressure (MPE aftertouch)
    SysEx               = 0x06,  // raw SysEx blob (payload = F0 ... F7)
    LinkEnable          = 0x10,
    LinkDisable         = 0x11,
    LinkSetBpm          = 0x12,
    LinkTransportStart  = 0x13,  // JVM→sidecar: request transport start
    LinkTransportStop   = 0x14,  // JVM→sidecar: request transport stop
    MidiIn              = 0x20,  // sidecar→JVM: MIDI message received on input port
    KbdEvent            = 0x21,  // sidecar→JVM: keyboard event (CGEventTap)
    TxLog               = 0x30,  // JVM→sidecar: append transaction to session SQLite
    SessionOpen         = 0x31,  // JVM→sidecar: open session SQLite file (path payload)
    SessionClose        = 0x32,  // JVM→sidecar: flush and close session SQLite
    LinkState           = 0x80,
    Ping                = 0xF0,
    Shutdown            = 0xFF,
};

// ---------------------------------------------------------------------------
// Read helpers for the TxLog payload
// ---------------------------------------------------------------------------

static uint16_t read_le16(const uint8_t* p) noexcept {
    return static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8);
}

// ---------------------------------------------------------------------------
// TxDb — session SQLite writer
//
// One session = one .sqlite file.  Opened on SessionOpen (0x31), closed on
// SessionClose (0x32) or process exit.  All inserts are wrapped in a single
// BEGIN/COMMIT per transaction for atomicity and performance.
//
// Compiled out when CLJSEQ_WITH_SQLITE is not defined.
// ---------------------------------------------------------------------------

#ifdef CLJSEQ_WITH_SQLITE

class TxDb {
public:
    TxDb() = default;
    ~TxDb() { close(); }

    bool open(const std::string& path) {
        close();
        if (sqlite3_open(path.c_str(), &db_) != SQLITE_OK) {
            std::fprintf(stderr, "[txdb] sqlite3_open(%s) failed: %s\n",
                         path.c_str(), sqlite3_errmsg(db_));
            sqlite3_close(db_);
            db_ = nullptr;
            return false;
        }

        // WAL mode: writer does not block readers during long sessions.
        sqlite3_exec(db_, "PRAGMA journal_mode=WAL;", nullptr, nullptr, nullptr);
        sqlite3_exec(db_, "PRAGMA synchronous=NORMAL;", nullptr, nullptr, nullptr);

        const char* schema = R"(
CREATE TABLE IF NOT EXISTS transactions (
  id          BLOB PRIMARY KEY,
  beat        REAL  NOT NULL,
  wall_ns     INTEGER NOT NULL,
  source_kind INTEGER NOT NULL,
  source_id   TEXT,
  parent_id   BLOB
);
CREATE TABLE IF NOT EXISTS changes (
  tx_id       BLOB    NOT NULL REFERENCES transactions(id),
  seq         INTEGER NOT NULL,
  path        TEXT    NOT NULL,
  before      TEXT,
  after       TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tx_beat     ON transactions(beat);
CREATE INDEX IF NOT EXISTS idx_change_path ON changes(path);
CREATE INDEX IF NOT EXISTS idx_change_tx   ON changes(tx_id);
)";
        char* errmsg = nullptr;
        if (sqlite3_exec(db_, schema, nullptr, nullptr, &errmsg) != SQLITE_OK) {
            std::fprintf(stderr, "[txdb] schema error: %s\n", errmsg);
            sqlite3_free(errmsg);
            close();
            return false;
        }

        sqlite3_prepare_v2(db_,
            "INSERT OR IGNORE INTO transactions(id,beat,wall_ns,source_kind,source_id,parent_id)"
            " VALUES (?,?,?,?,?,?)",
            -1, &stmt_tx_, nullptr);
        sqlite3_prepare_v2(db_,
            "INSERT INTO changes(tx_id,seq,path,before,after) VALUES (?,?,?,?,?)",
            -1, &stmt_chg_, nullptr);

        std::fprintf(stderr, "[txdb] opened %s\n", path.c_str());
        return true;
    }

    void close() {
        if (db_) {
            if (stmt_tx_)  { sqlite3_finalize(stmt_tx_);  stmt_tx_  = nullptr; }
            if (stmt_chg_) { sqlite3_finalize(stmt_chg_); stmt_chg_ = nullptr; }
            sqlite3_close(db_);
            db_ = nullptr;
        }
    }

    bool is_open() const { return db_ != nullptr; }

    // Insert one transaction + its changes atomically.
    // payload must be a valid TxLog binary payload.
    void insert_tx(const uint8_t* p, std::size_t len) {
        if (!db_ || len < 35) return;  // minimum viable payload: 16+8+8+1+2+0+1+0+2

        // Decode fixed fields
        // tx_id: [int64 hi][int64 lo] — stored as 16-byte BLOB
        const uint8_t* tx_id_hi_p = p;                      // 8 bytes
        const uint8_t* tx_id_lo_p = p + 8;                  // 8 bytes
        uint8_t tx_id_blob[16];
        std::memcpy(tx_id_blob,     tx_id_hi_p, 8);
        std::memcpy(tx_id_blob + 8, tx_id_lo_p, 8);

        double  beat     = read_le_double(p + 16);
        int64_t wall_ns  = read_le64(p + 24);
        uint8_t src_kind = p[32];

        std::size_t off = 33;
        if (off + 2 > len) return;
        uint16_t src_id_len = read_le16(p + off); off += 2;
        if (off + src_id_len > len) return;
        std::string src_id(reinterpret_cast<const char*>(p + off), src_id_len); off += src_id_len;

        if (off + 1 > len) return;
        uint8_t has_parent = p[off++];
        uint8_t parent_id_blob[16] = {};
        bool    has_par = has_parent == 1;
        if (has_par) {
            if (off + 16 > len) return;
            std::memcpy(parent_id_blob, p + off, 16); off += 16;
        }

        if (off + 2 > len) return;
        uint16_t n_changes = read_le16(p + off); off += 2;

        // Insert transaction
        sqlite3_exec(db_, "BEGIN", nullptr, nullptr, nullptr);

        sqlite3_reset(stmt_tx_);
        sqlite3_bind_blob(stmt_tx_, 1, tx_id_blob, 16, SQLITE_STATIC);
        sqlite3_bind_double(stmt_tx_, 2, beat);
        sqlite3_bind_int64(stmt_tx_, 3, wall_ns);
        sqlite3_bind_int(stmt_tx_, 4, static_cast<int>(src_kind));
        sqlite3_bind_text(stmt_tx_, 5, src_id.c_str(), -1, SQLITE_STATIC);
        if (has_par)
            sqlite3_bind_blob(stmt_tx_, 6, parent_id_blob, 16, SQLITE_STATIC);
        else
            sqlite3_bind_null(stmt_tx_, 6);
        sqlite3_step(stmt_tx_);

        // Insert changes
        for (uint16_t i = 0; i < n_changes && off < len; ++i) {
            if (off + 2 > len) break;
            uint16_t path_len = read_le16(p + off); off += 2;
            if (off + path_len > len) break;
            std::string path_str(reinterpret_cast<const char*>(p + off), path_len); off += path_len;

            if (off + 2 > len) break;
            uint16_t before_len = read_le16(p + off); off += 2;
            std::string before_str;
            if (before_len > 0) {
                if (off + before_len > len) break;
                before_str.assign(reinterpret_cast<const char*>(p + off), before_len);
                off += before_len;
            }

            if (off + 2 > len) break;
            uint16_t after_len = read_le16(p + off); off += 2;
            if (off + after_len > len) break;
            std::string after_str(reinterpret_cast<const char*>(p + off), after_len); off += after_len;

            sqlite3_reset(stmt_chg_);
            sqlite3_bind_blob(stmt_chg_, 1, tx_id_blob, 16, SQLITE_STATIC);
            sqlite3_bind_int(stmt_chg_, 2, static_cast<int>(i));
            sqlite3_bind_text(stmt_chg_, 3, path_str.c_str(), -1, SQLITE_STATIC);
            if (before_len > 0)
                sqlite3_bind_text(stmt_chg_, 4, before_str.c_str(), -1, SQLITE_STATIC);
            else
                sqlite3_bind_null(stmt_chg_, 4);
            sqlite3_bind_text(stmt_chg_, 5, after_str.c_str(), -1, SQLITE_STATIC);
            sqlite3_step(stmt_chg_);
        }

        sqlite3_exec(db_, "COMMIT", nullptr, nullptr, nullptr);
    }

private:
    sqlite3*      db_       = nullptr;
    sqlite3_stmt* stmt_tx_  = nullptr;
    sqlite3_stmt* stmt_chg_ = nullptr;
};

#else  // CLJSEQ_WITH_SQLITE not defined

class TxDb {
public:
    bool open(const std::string& path) {
        std::fprintf(stderr, "[txdb] SQLite3 not compiled in — ignoring session open (%s)\n",
                     path.c_str());
        return false;
    }
    void close() {}
    bool is_open() const { return false; }
    void insert_tx(const uint8_t*, std::size_t) {}
};

#endif  // CLJSEQ_WITH_SQLITE

// ---------------------------------------------------------------------------
// IpcSession — owns one socket, drives bidirectional async I/O
// ---------------------------------------------------------------------------

class IpcSession : public std::enable_shared_from_this<IpcSession> {
public:
    IpcSession(tcp::socket socket, asio::io_context& io,
               cljseq::LinkBridge& link, int midi_in_port, bool enable_kbd)
        : socket_(std::move(socket)), io_(io), link_(link),
          midi_in_port_(midi_in_port), enable_kbd_(enable_kbd) {}

    ~IpcSession() {
        if (midi_in_port_ >= 0)
            cljseq::midi_monitor_stop();
        if (enable_kbd_)
            cljseq::kbd_capture_stop();
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

        // Optionally start keyboard capture (macOS CGEventTap).
        if (enable_kbd_) {
            auto self = shared_from_this();
            bool ok = cljseq::kbd_capture_start(
                {}, // empty = capture all keys; ivk filter is on the Clojure side
                [self](uint8_t keycode, uint8_t mods, uint8_t evt_type) {
                    self->push_kbd_event(keycode, mods, evt_type);
                });
            if (!ok)
                std::fprintf(stderr,
                    "[ipc] kbd_capture_start failed — keyboard input disabled\n");
        }

        read_header();
    }

private:
    static constexpr std::size_t kHeaderLen = 8;

    tcp::socket              socket_;
    asio::io_context&        io_;
    cljseq::LinkBridge&      link_;
    int                      midi_in_port_;
    bool                     enable_kbd_;
    std::array<uint8_t, kHeaderLen> header_buf_;
    std::vector<uint8_t>     payload_buf_;

    // Serialise concurrent writes (Link callback, MIDI-in callback, kbd callback vs. read loop).
    std::mutex               write_mutex_;

    // Session SQLite writer — opened on SessionOpen (0x31), closed on SessionClose (0x32).
    TxDb                     tx_db_;

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

        case MsgType::LinkTransportStart:
            link_.start_playing();
            break;

        case MsgType::LinkTransportStop:
            link_.stop_playing();
            break;

        case MsgType::SessionOpen:
            // Payload: UTF-8 file path.
            if (len > 0) {
                std::string path(reinterpret_cast<const char*>(payload), len);
                tx_db_.open(path);
            }
            break;

        case MsgType::SessionClose:
            tx_db_.close();
            std::fprintf(stderr, "[txdb] session closed\n");
            break;

        case MsgType::TxLog:
            tx_db_.insert_tx(payload, len);
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

    // ---------------------------------------------------------------------------
    // Outbound: push KbdEvent (0x21) to JVM
    //
    // Wire payload (3 bytes):
    //   [uint8 keycode(1)][uint8 modifiers(1)][uint8 event_type(1)]
    //
    // Called from the CGEventTap callback thread — must not block.
    // ---------------------------------------------------------------------------

    void push_kbd_event(uint8_t keycode, uint8_t mods, uint8_t evt_type) {
        static constexpr std::size_t kPayloadLen = 3;
        static constexpr std::size_t kFrameLen   = 8 + kPayloadLen;

        auto frame = std::make_shared<std::vector<uint8_t>>(kFrameLen, uint8_t{0});
        uint8_t* p = frame->data();

        // Header
        write_le32(p, static_cast<uint32_t>(kPayloadLen));
        p[4] = static_cast<uint8_t>(MsgType::KbdEvent);
        // p[5..7] reserved, zeroed

        // Payload
        p[8]  = keycode;
        p[9]  = mods;
        p[10] = evt_type;

        std::lock_guard<std::mutex> lk(write_mutex_);
        auto self = shared_from_this();
        asio::async_write(socket_, asio::buffer(*frame),
            [self, frame](asio::error_code ec, std::size_t) {
                if (ec)
                    std::fprintf(stderr, "[ipc] kbd push write error: %s\n",
                                 ec.message().c_str());
            });
    }
};

} // anonymous namespace

// ---------------------------------------------------------------------------
// ipc_serve — entry point
// ---------------------------------------------------------------------------

void ipc_serve(unsigned short port, cljseq::LinkBridge& link,
               int midi_in_port, bool enable_kbd) {
    asio::io_context io;
    tcp::acceptor acceptor(io, tcp::endpoint(tcp::v4(), port));
    std::fprintf(stderr, "[ipc] listening on port %u\n", port);

    // Accept exactly one connection.
    tcp::socket socket(io);
    acceptor.accept(socket);
    std::fprintf(stderr, "[ipc] JVM connected\n");

    auto session = std::make_shared<IpcSession>(
        std::move(socket), io, link, midi_in_port, enable_kbd);
    session->start();

    io.run();
    std::fprintf(stderr, "[ipc] event loop exited\n");
}
