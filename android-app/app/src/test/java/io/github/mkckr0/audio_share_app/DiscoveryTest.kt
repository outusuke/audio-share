/*
 *    Copyright 2022-2024 mkckr0 <https://github.com/mkckr0>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.github.mkckr0.audio_share_app

import io.github.mkckr0.audio_share_app.service.Discovery
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryTest {
    private fun reply(port: Int, name: String, version: Int = 1): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        return byteArrayOf(
            'A'.code.toByte(), 'S'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
            version.toByte(), (port and 0xff).toByte(), (port shr 8).toByte(), nameBytes.size.toByte(),
        ) + nameBytes
    }

    @Test
    fun request_hasMagicAndMinimumSize() {
        val request = Discovery.buildRequest()
        assertEquals(64, request.size)
        assertEquals("ASDQ", String(request, 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun parseReply_valid() {
        val data = reply(65530, "desktop")
        val parsed = Discovery.parseReply(data, data.size)
        assertNotNull(parsed)
        assertEquals(65530, parsed!!.port)
        assertEquals("desktop", parsed.name)
    }

    @Test
    fun parseReply_portIsLittleEndian() {
        val data = reply(0x1234, "x")
        assertEquals(0x1234, Discovery.parseReply(data, data.size)!!.port)
    }

    @Test
    fun parseReply_utf8Name() {
        val data = reply(1000, "書斎のPC")
        assertEquals("書斎のPC", Discovery.parseReply(data, data.size)!!.name)
    }

    @Test
    fun parseReply_ignoresTrailingBytesFromNewerServers() {
        val data = reply(1000, "pc") + byteArrayOf(1, 2, 3)
        assertEquals("pc", Discovery.parseReply(data, data.size)!!.name)
    }

    @Test
    fun parseReply_rejectsBadInput() {
        val good = reply(1000, "pc")
        assertNull(Discovery.parseReply(good, 7))
        assertNull(Discovery.parseReply(good, good.size - 1))
        assertNull(Discovery.parseReply(reply(1000, "pc", version = 0), good.size))
        assertNull(Discovery.parseReply(reply(0, "pc"), good.size))
        assertNull(Discovery.parseReply(good.copyOf().also { it[0] = 'X'.code.toByte() }, good.size))
        assertNull(Discovery.parseReply(ByteArray(0), 0))
        assertNull(Discovery.parseReply(good, good.size + 10))
    }

    @Test
    fun broadcastAddress_commonPrefixes() {
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 255.toByte()), Discovery.broadcastAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 23), 24))
        assertArrayEquals(byteArrayOf(10, 0, 255.toByte(), 255.toByte()), Discovery.broadcastAddress(byteArrayOf(10, 0, 4, 9), 16))
        assertArrayEquals(byteArrayOf(172.toByte(), 16, 15, 255.toByte()), Discovery.broadcastAddress(byteArrayOf(172.toByte(), 16, 3, 7), 20))
    }

    @Test
    fun broadcastAddress_edgePrefixes() {
        assertArrayEquals(byteArrayOf(10, 1, 2, 3), Discovery.broadcastAddress(byteArrayOf(10, 1, 2, 3), 32))
        assertArrayEquals(byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte()), Discovery.broadcastAddress(byteArrayOf(10, 1, 2, 3), 0))
    }
}
