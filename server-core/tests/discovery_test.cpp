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

#include "discovery_server.hpp"

#include <cstdio>
#include <cstdlib>
#include <memory>
#include <thread>

static int g_failures = 0;
#define CHECK(cond, ...)                                     \
    do {                                                     \
        if (!(cond)) {                                       \
            ++g_failures;                                    \
            std::printf("FAIL %s:%d: ", __FILE__, __LINE__); \
            std::printf(__VA_ARGS__);                        \
            std::printf("\n");                               \
        }                                                    \
    } while (0)

using asio::ip::address;
using asio::ip::make_address;

static void test_reply_encoding()
{
    auto r = discovery::make_reply(0x1234, "desk");
    CHECK(r.size() == 8 + 4, "size %zu", r.size());
    CHECK(r[0] == 'A' && r[1] == 'S' && r[2] == 'D' && r[3] == 'R', "magic");
    CHECK(r[4] == 1, "version");
    CHECK(r[5] == 0x34 && r[6] == 0x12, "port is little-endian");
    CHECK(r[7] == 4 && r[8] == 'd', "name");

    CHECK(discovery::make_reply(1, std::string(500, 'x')).size() == 8 + discovery::max_name_size, "name is capped");
    CHECK(discovery::make_reply(1, "").size() == 8, "empty name");
    // 16 x 3-byte characters = 48 bytes fits, a 17th must be dropped whole instead of split
    std::string cjk;
    for (int i = 0; i < 17; ++i) cjk += "\xe6\x9c\xac";
    auto c = discovery::make_reply(1, cjk);
    CHECK(c[7] == 48 && c.size() == 8 + 48, "multi-byte name cut on a boundary, len=%d", c[7]);
    CHECK(discovery::make_reply(1, std::string(48, 'x') + "\xe6\x9c\xac")[7] == 48, "exactly full name");
    CHECK(discovery::make_reply(1, std::string(47, 'x') + "\xe6\x9c\xac")[7] == 47, "split character removed");
    CHECK(discovery::make_reply(1, std::string(500, 'x')).size() <= discovery::request_size, "reply never larger than request");
}

static void test_request_check()
{
    std::vector<uint8_t> req(discovery::request_size, 0);
    req[0] = 'A'; req[1] = 'S'; req[2] = 'D'; req[3] = 'Q';
    CHECK(discovery::is_request(req.data(), req.size()), "valid");
    CHECK(!discovery::is_request(req.data(), req.size() - 1), "too short");
    req[3] = 'X';
    CHECK(!discovery::is_request(req.data(), req.size()), "bad magic");
}

static void test_local_source()
{
    for (auto a : { "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.9", "169.254.3.4", "127.0.0.1", "fe80::1", "fd12:3456::1", "fc00::5", "::1", "::ffff:192.168.1.9" }) {
        CHECK(discovery::is_local_source(make_address(a)), "%s should be local", a);
    }
    for (auto a : { "8.8.8.8", "172.15.0.1", "172.32.0.1", "11.0.0.1", "192.169.0.1", "100.64.0.1", "2001:db8::1", "::ffff:8.8.8.8" }) {
        CHECK(!discovery::is_local_source(make_address(a)), "%s should not be local", a);
    }
}

// Sends a datagram to the server and returns the reply, or an empty vector after timeout_ms.
static std::vector<uint8_t> ask(uint16_t port, const std::vector<uint8_t>& payload, int timeout_ms = 300)
{
    asio::io_context ioc;
    asio::ip::udp::socket s(ioc, asio::ip::udp::v4());
    s.send_to(asio::buffer(payload), { make_address("127.0.0.1"), port });
    std::vector<uint8_t> out(256);
    bool got = false;
    size_t n = 0;
    s.async_receive(asio::buffer(out), [&](const asio::error_code& ec, size_t len) { got = !ec; n = len; });
    asio::steady_timer t(ioc, std::chrono::milliseconds(timeout_ms));
    t.async_wait([&](const asio::error_code&) { s.close(); });
    ioc.run();
    out.resize(got ? n : 0);
    return out;
}

static std::vector<uint8_t> good_request()
{
    std::vector<uint8_t> req(discovery::request_size, 0);
    req[0] = 'A'; req[1] = 'S'; req[2] = 'D'; req[3] = 'Q';
    return req;
}

static void run_server_case(bool ipv6, std::optional<address> bound_to, bool expect_reply, const char* label)
{
    asio::io_context ioc;
    std::unique_ptr<discovery::server> srv;
    try {
        srv = std::make_unique<discovery::server>(ioc, ipv6, 0, 4242, "testbox", bound_to);
    } catch (const std::system_error& e) {
        std::printf("SKIP %s: %s\n", label, e.what());
        return;
    }
    const uint16_t port = srv->local_port();
    asio::co_spawn(ioc, srv->run(), asio::detached);
    std::thread t([&] { ioc.run(); });

    auto reply = ask(port, good_request());
    if (expect_reply) {
        CHECK(reply.size() == 8 + 7, "%s: reply size %zu", label, reply.size());
        if (reply.size() == 15) {
            CHECK(reply[5] == (4242 & 0xff) && reply[6] == (4242 >> 8), "%s: port bytes", label);
            CHECK(std::string(reply.begin() + 8, reply.end()) == "testbox", "%s: name", label);
        }
        auto bad = good_request();
        bad[0] = 'X';
        CHECK(ask(port, bad).empty(), "%s: bad magic must be ignored", label);
        auto shorty = good_request();
        shorty.resize(10);
        CHECK(ask(port, shorty).empty(), "%s: short request must be ignored", label);
        CHECK(!ask(port, good_request()).empty(), "%s: still answering after bad packets", label);
    } else {
        CHECK(reply.empty(), "%s: must not answer", label);
    }

    ioc.stop();
    t.join();
}

int main()
{
    test_reply_encoding();
    test_request_check();
    test_local_source();
    run_server_case(false, std::nullopt, true, "wildcard v4");
    run_server_case(false, make_address("127.0.0.1"), true, "bound to the address the reply leaves from");
    run_server_case(false, make_address("192.0.2.7"), false, "bound to an address the reply doesn't leave from");
    run_server_case(true, std::nullopt, true, "dual-stack wildcard");
    std::printf(g_failures ? "%d FAILED\n" : "all passed\n", g_failures);
    return g_failures ? 1 : 0;
}
