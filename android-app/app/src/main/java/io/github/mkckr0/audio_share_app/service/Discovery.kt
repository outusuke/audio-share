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

package io.github.mkckr0.audio_share_app.service

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

data class DiscoveredServer(val host: String, val port: Int, val name: String)

/** Finds servers started with --discovery. The wire format is described in docs/protocol.md. */
object Discovery {
    const val PORT = 65531
    private const val REQUEST_SIZE = 64
    private const val HEADER_SIZE = 8

    data class Reply(val port: Int, val name: String)

    fun buildRequest(): ByteArray {
        val request = ByteArray(REQUEST_SIZE)
        "ASDQ".toByteArray(Charsets.US_ASCII).copyInto(request)
        return request
    }

    fun parseReply(data: ByteArray, length: Int): Reply? {
        if (length < HEADER_SIZE || length > data.size) {
            return null
        }
        if (String(data, 0, 4, Charsets.US_ASCII) != "ASDR" || data[4].toInt() < 1) {
            return null
        }
        val port = (data[5].toInt() and 0xff) or ((data[6].toInt() and 0xff) shl 8)
        val nameLength = data[7].toInt() and 0xff
        if (port == 0 || length < HEADER_SIZE + nameLength) {
            return null
        }
        return Reply(port, String(data, HEADER_SIZE, nameLength, Charsets.UTF_8))
    }

    fun broadcastAddress(address: ByteArray, prefixLength: Int): ByteArray {
        require(address.size == 4)
        var ip = 0
        for (b in address) {
            ip = (ip shl 8) or (b.toInt() and 0xff)
        }
        val mask = if (prefixLength <= 0) 0 else -1 shl (32 - prefixLength.coerceAtMost(32))
        val broadcast = (ip and mask) or mask.inv()
        return ByteArray(4) { i -> (broadcast ushr (24 - 8 * i)).toByte() }
    }

    private fun broadcastTargets(context: Context): Set<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.getLinkProperties(cm.activeNetwork)?.linkAddresses?.forEach { linkAddress ->
                val address = linkAddress.address
                if (address is Inet4Address) {
                    targets.add(InetAddress.getByAddress(broadcastAddress(address.address, linkAddress.prefixLength)))
                }
            }
        } catch (_: Exception) {
            // the limited broadcast below still works on most networks
        }
        targets.add(InetAddress.getByName("255.255.255.255"))
        return targets
    }

    suspend fun scan(context: Context, listenMillis: Long = 1500): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val targets = broadcastTargets(context)
        val request = buildRequest()
        val found = LinkedHashMap<String, DiscoveredServer>()
        try {
            DatagramSocket(null).use { socket ->
                socket.broadcast = true
                socket.bind(InetSocketAddress(0))
                socket.soTimeout = 100
                val buffer = ByteArray(256)
                val start = SystemClock.elapsedRealtime()
                var requestsSent = 0
                while (isActive && SystemClock.elapsedRealtime() - start < listenMillis) {
                    // asked twice, in case the first datagram is lost on Wi-Fi
                    val due = if (requestsSent == 0) 0L else 500L
                    if (requestsSent < 2 && SystemClock.elapsedRealtime() - start >= due) {
                        targets.forEach {
                            try {
                                socket.send(DatagramPacket(request, request.size, it, PORT))
                            } catch (_: IOException) {
                            }
                        }
                        requestsSent++
                    }
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val reply = parseReply(packet.data, packet.length) ?: continue
                        val host = packet.address.hostAddress ?: continue
                        found["$host:${reply.port}"] = DiscoveredServer(host, reply.port, reply.name)
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } catch (_: IOException) {
            // no network, report whatever was found
        }
        found.values.toList()
    }
}
