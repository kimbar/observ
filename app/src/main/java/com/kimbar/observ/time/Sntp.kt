package com.kimbar.observ.time

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Minimal SNTP client (RFC 4330). Blocking: call off the main thread. */
object Sntp {

    data class Result(
        /** elapsedRealtimeNanos at which the server time below applies. */
        val elapsedNanos: Long,
        val serverEpochNanos: Long,
        val roundTripNanos: Long,
    )

    private const val NTP_TO_UNIX_SECONDS = 2_208_988_800L

    /** Queries [samples] times and keeps the reply with the shortest round trip. */
    fun query(host: String, samples: Int = 5, timeoutMs: Int = 3000): Result {
        val address = InetAddress.getByName(host)
        var best: Result? = null
        var lastError: Exception? = null
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            repeat(samples) {
                try {
                    val r = once(socket, address)
                    if (best == null || r.roundTripNanos < best!!.roundTripNanos) best = r
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }
        return best ?: throw lastError ?: IllegalStateException("No SNTP reply")
    }

    private fun once(socket: DatagramSocket, address: InetAddress): Result {
        val buf = ByteArray(48)
        buf[0] = 0x23 // LI=0, VN=4, Mode=3 (client)
        val request = DatagramPacket(buf, buf.size, address, 123)
        val t1 = SystemClock.elapsedRealtimeNanos()
        socket.send(request)
        val response = DatagramPacket(ByteArray(48), 48)
        socket.receive(response)
        val t4 = SystemClock.elapsedRealtimeNanos()
        val d = response.data
        val mode = d[0].toInt() and 0x7
        val stratum = d[1].toInt() and 0xff
        require(mode == 4 || mode == 5) { "Bad SNTP mode $mode" }
        require(stratum in 1..15) { "Bad SNTP stratum $stratum" }
        val serverReceive = readTimestamp(d, 32)
        val serverTransmit = readTimestamp(d, 40)
        val serverProcessing = serverTransmit - serverReceive
        val rtt = (t4 - t1) - serverProcessing
        // Assume symmetric paths: server transmit happened rtt/2 before t4.
        return Result(t4, serverTransmit + rtt / 2, rtt)
    }

    private fun readTimestamp(b: ByteArray, off: Int): Long {
        var seconds = 0L
        var fraction = 0L
        for (i in 0 until 4) seconds = (seconds shl 8) or (b[off + i].toLong() and 0xff)
        for (i in 4 until 8) fraction = (fraction shl 8) or (b[off + i].toLong() and 0xff)
        return (seconds - NTP_TO_UNIX_SECONDS) * 1_000_000_000L + (fraction * 1_000_000_000L ushr 32)
    }
}
