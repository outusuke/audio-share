/*
   Copyright 2022-2024 mkckr0 <https://github.com/mkckr0>

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

#ifndef DISCOVERY_SERVER_HPP
#define DISCOVERY_SERVER_HPP

#include "pre_asio.hpp"

#include <asio.hpp>

#include <array>
#include <chrono>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

// LAN discovery, see docs/protocol.md. A client broadcasts a 64-byte request to UDP port
// default_port and gets a small unicast reply with the server's TCP port and name.
namespace discovery {

constexpr uint16_t default_port = 65531;
constexpr size_t request_size = 64; // replies never exceed this, so the server can't amplify spoofed requests
constexpr size_t max_name_size = 48;
constexpr uint8_t reply_version = 1;

inline bool is_request(const uint8_t* data, size_t size)
{
    return size >= request_size && data[0] == 'A' && data[1] == 'S' && data[2] == 'D' && data[3] == 'Q';
}

// "ASDR" | version u8 | tcp port u16 LE | name length u8 | name (UTF-8)
inline std::vector<uint8_t> make_reply(uint16_t tcp_port, const std::string& name)
{
    size_t len = std::min(name.size(), max_name_size);
    while (len > 0 && len < name.size() && (static_cast<uint8_t>(name[len]) & 0xc0) == 0x80) {
        --len; // don't cut a multi-byte character in half
    }

    std::vector<uint8_t> reply { 'A', 'S', 'D', 'R', reply_version, static_cast<uint8_t>(tcp_port & 0xff), static_cast<uint8_t>(tcp_port >> 8), static_cast<uint8_t>(len) };
    reply.insert(reply.end(), name.begin(), name.begin() + len);
    return reply;
}

inline asio::ip::address normalize(const asio::ip::address& a)
{
    if (a.is_v6() && a.to_v6().is_v4_mapped()) {
        return asio::ip::make_address_v4(asio::ip::v4_mapped, a.to_v6());
    }
    return a;
}

// Only answer private and link-local sources, a discovery port open to the internet shouldn't talk back.
inline bool is_local_source(const asio::ip::address& address)
{
    const auto a = normalize(address);
    if (a.is_v4()) {
        const uint32_t v = a.to_v4().to_uint();
        return (v >> 24) == 10 || (v >> 20) == 0xac1 || (v >> 16) == 0xc0a8 || (v >> 16) == 0xa9fe || (v >> 24) == 127;
    }
    const auto b = a.to_v6().to_bytes();
    return a.is_loopback() || (b[0] == 0xfe && (b[1] & 0xc0) == 0x80) || (b[0] & 0xfe) == 0xfc;
}

class server {
public:
    // bound_to: the address the audio server listens on, or nullopt when it listens on every
    // interface. With a specific address, requests are only answered when the reply would leave
    // from that address, so the phone is never told an address the server isn't listening on.
    server(asio::io_context& ioc, bool ipv6, uint16_t port, uint16_t tcp_port, const std::string& name, std::optional<asio::ip::address> bound_to)
        : _socket(ioc, ipv6 ? asio::ip::udp::v6() : asio::ip::udp::v4())
        , _reply(make_reply(tcp_port, name))
    {
        if (bound_to && !bound_to->is_unspecified()) {
            _bound_to = normalize(*bound_to);
        }
        if (ipv6) {
            _socket.set_option(asio::ip::v6_only(false));
        }
        // lets two servers on one PC (different ports) both answer
        _socket.set_option(asio::socket_base::reuse_address(true));
        _socket.bind(asio::ip::udp::endpoint(ipv6 ? asio::ip::udp::v6() : asio::ip::udp::v4(), port));
    }

    uint16_t local_port() const
    {
        return _socket.local_endpoint().port();
    }

    asio::awaitable<void> run()
    {
        std::array<uint8_t, 128> buf;
        std::chrono::steady_clock::time_point last_reply {}; // clock epoch, so the first request is never rate limited
        while (true) {
            asio::ip::udp::endpoint from;
            auto [ec, n] = co_await _socket.async_receive_from(asio::buffer(buf), from, asio::as_tuple(asio::use_awaitable));
            if (ec == asio::error::message_size || ec == asio::error::connection_reset) {
                continue;
            }
            if (ec) {
                co_return;
            }
            if (!is_request(buf.data(), n) || !is_local_source(from.address())) {
                continue;
            }
            const auto now = std::chrono::steady_clock::now();
            if (now - last_reply < std::chrono::milliseconds(10)) {
                continue; // at most ~100 replies per second
            }
            if (_bound_to && !reply_leaves_from_bound_address(from)) {
                continue;
            }
            last_reply = now;
            co_await _socket.async_send_to(asio::buffer(_reply), from, asio::as_tuple(asio::use_awaitable));
        }
    }

private:
    // connecting a UDP socket sends nothing, it just makes the OS pick the source address
    bool reply_leaves_from_bound_address(const asio::ip::udp::endpoint& from)
    {
        const asio::ip::udp::endpoint target(normalize(from.address()), from.port());
        asio::error_code ec;
        asio::ip::udp::socket probe(_socket.get_executor(), target.protocol());
        probe.connect(target, ec);
        if (ec) {
            return false;
        }
        const auto local = probe.local_endpoint(ec);
        return !ec && normalize(local.address()) == *_bound_to;
    }

    asio::ip::udp::socket _socket;
    std::vector<uint8_t> _reply;
    std::optional<asio::ip::address> _bound_to;
};

} // namespace discovery

#endif // !DISCOVERY_SERVER_HPP
