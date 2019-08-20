/*
 * Copyright 2018-2019 SIP3.IO, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sip3.captain.ce.pipeline

import io.sip3.captain.ce.domain.Packet
import io.vertx.core.Vertx

/**
 * Handles UDP packets
 */
class UdpHandler(vertx: Vertx, bulkOperationsEnabled: Boolean) : Handler(vertx, bulkOperationsEnabled) {

    private val rtcpHandler = RtcpHandler(vertx, bulkOperationsEnabled)
    private val rtpHandler = RtpHandler(vertx, bulkOperationsEnabled)
    private val sipHandler = SipHandler(vertx, bulkOperationsEnabled)

    override fun onPacket(packet: Packet) {
        val buffer = packet.payload.encode()

        // Source Port
        packet.srcPort = buffer.readUnsignedShort()
        // Destination Port
        packet.dstPort = buffer.readUnsignedShort()
        // Length
        buffer.skipBytes(2)
        // Checksum
        buffer.skipBytes(2)

        val offset = buffer.readerIndex()

        // Filter packets with the size smaller than minimal RTP/RTCP or SIP
        if (buffer.capacity() - offset < 8) {
            return
        }

        if (buffer.getUnsignedByte(offset).toInt().shr(6) == 2) {
            // RTP or RTCP packet
            val packetType = buffer.getUnsignedByte(offset + 1).toInt()
            if (packetType in 200..211) {
                // Skip ICMP(RTCP) case
                if (!packet.rejected) {
                    rtcpHandler.handle(packet)
                }
            } else {
                rtpHandler.handle(packet)
            }
        } else {
            // Skip ICMP(SIP) case
            if (!packet.rejected) {
                // SIP packet (as long as we have SIP, RTP and RTCP packets only)
                sipHandler.handle(packet)
            }
        }
    }
}