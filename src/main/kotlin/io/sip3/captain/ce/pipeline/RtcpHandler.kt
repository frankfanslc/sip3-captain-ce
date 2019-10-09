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

import io.sip3.captain.ce.Routes
import io.sip3.captain.ce.USE_LOCAL_CODEC
import io.sip3.captain.ce.domain.Packet
import io.sip3.captain.ce.domain.RtcpReportBlock
import io.sip3.captain.ce.domain.RtcpSession
import io.sip3.captain.ce.domain.SenderReport
import io.sip3.commons.domain.SdpSession
import io.sip3.commons.domain.payload.Encodable
import io.sip3.commons.domain.payload.RtpReportPayload
import io.vertx.core.Vertx
import mu.KotlinLogging
import kotlin.experimental.and

/**
 * Handles RTCP packets
 */
class RtcpHandler(vertx: Vertx, bulkOperationsEnabled: Boolean) : Handler(vertx, bulkOperationsEnabled) {

    private val logger = KotlinLogging.logger {}

    companion object {

        const val MAX_VALID_JITTER = 10000

        const val R0 = 93.2F
        const val I_DELAY_DEFAULT = 0.65
        const val MOS_MIN = 1F
        const val MOS_MAX = 4.5F
    }

    private var bulkSize = 1
    private var expirationDelay: Long = 4000
    private var aggregationTimeout: Long = 30000

    private val sessions = mutableMapOf<Long, RtcpSession>()
    private val sdpSessions = mutableMapOf<Long, SdpSession>()
    private val reports = mutableListOf<Packet>()

    init {
        vertx.orCreateContext.config().getJsonObject("rtcp")?.let { config ->
            if (bulkOperationsEnabled) {
                config.getInteger("bulk-size")?.let { bulkSize = it }
            }
            config.getLong("expiration-delay")?.let { expirationDelay = it }
            config.getLong("aggregation-timeout")?.let { aggregationTimeout = it }
        }

        // Consumer for sdpSession info from remote host
        vertx.eventBus().localConsumer<SdpSession>(Routes.sdp) { event ->
            try {
                val sdpSession = event.body()
                onSdpSession(sdpSession)
            } catch (e: Exception) {
                logger.error("RtcpCollector 'onSdpSession()' failed.", e)
            }
        }

        // Periodic task for session expiration
        vertx.setPeriodic(expirationDelay) {
            val now = System.currentTimeMillis()
            // Sessions cleanup
            sessions.filterValues { it.lastPacketTimestamp + aggregationTimeout < now }
                    .forEach { (sessionId, _) ->
                        sessions.remove(sessionId)
                    }

            // SDP sessions cleanup
            sdpSessions.filterValues { it.timestamp + aggregationTimeout < now }
                    .forEach { (key, _) ->
                        sdpSessions.remove(key)
                    }
        }
    }

    private fun onSdpSession(sdpSession: SdpSession) {
        sdpSessions.put(sdpSession.id, sdpSession)
    }

    override fun onPacket(packet: Packet) {
        val buffer = (packet.payload as Encodable).encode()
        val packetType = buffer.getUnsignedByte(1).toInt()

        when (packetType) {
            // SR: Sender Report RTCP Packet
            200 -> {
                val report = SenderReport().apply {
                    // Reception report count
                    reportBlockCount = buffer.readByte().and(31)
                    // Packet Type
                    buffer.skipBytes(1)
                    // Length
                    buffer.skipBytes(2)
                    // Sender SSRC
                    senderSsrc = buffer.readUnsignedInt()
                    // NTP Timestamp: Most and Least significant words
                    ntpTimestampMsw = buffer.readUnsignedInt()
                    ntpTimestampLsw = buffer.readUnsignedInt()
                    // RTP Timestamp
                    buffer.skipBytes(4)
                    // Sender's packet count
                    senderPacketCount = buffer.readUnsignedInt()
                    // Sender's octet count
                    buffer.skipBytes(4)

                    // Reports
                    for (index in 1..reportBlockCount) {
                        reportBlocks.add(RtcpReportBlock().apply {
                            // SSRC of sender
                            ssrc = buffer.readUnsignedInt()
                            // Fraction lost and Cumulative packet lost
                            buffer.readUnsignedInt().let { value ->
                                fractionLost = ((value and 0xF000) shr 24).toShort()
                                cumulativePacketLost = value and 0x0FFF
                            }
                            // Extended sequence number
                            extendedSeqNumber = buffer.readUnsignedInt()
                            // Interarrival Jitter
                            interarrivalJitter = buffer.readUnsignedInt()
                            // Last SR Timestamp
                            lsrTimestamp = buffer.readUnsignedInt()
                            // Delay since last SR
                            buffer.skipBytes(4)
                        })
                    }
                }
                onSenderReport(packet, report)
            }
            else -> {
                // Ignore:
                // 201 RR: Receiver Report RTCP Packet
                // 202 SDES: Source Description RTCP Packet
                // 203 BYE: Goodbye RTCP Packet
                // 204 APP: Application-Defined RTCP Packet
                // Undefined RTCP packet
            }
        }

    }

    private fun onSenderReport(packet: Packet, senderReport: SenderReport) {
        senderReport.reportBlocks.forEach { report ->
            val srcPort = packet.srcPort.toLong()
            val dstPort = packet.dstPort.toLong()
            val ssrc = senderReport.senderSsrc.toInt().toLong()
            val sessionId = (srcPort shl 48) or (dstPort shl 32) or ssrc
            var isNewSession = false

            val session = sessions.computeIfAbsent(sessionId) {
                isNewSession = true
                RtcpSession()
            }

            if (isNewSession) {
                session.apply {
                    createdAt = packet.timestamp
                    dstAddr = packet.dstAddr
                    this.dstPort = packet.dstPort
                    srcAddr = packet.srcAddr
                    this.srcPort = packet.srcPort
                    this.lastNtpTimestamp = senderReport.ntpTimestamp

                    sdpSession = sdpSessions[dstSdpSessionId] ?: sdpSessions[srcSdpSessionId]
                }
            }

            // If interarrival jitter is greater than maximum, current jitter is bad
            if (report.interarrivalJitter < MAX_VALID_JITTER) {
                session.lastJitter = report.interarrivalJitter.toFloat()
            }

            val rtpReport = Packet().apply {
                timestamp = session.createdAt
                dstAddr = session.dstAddr
                this.dstPort = session.dstPort
                srcAddr = session.srcAddr
                this.srcPort = session.srcPort
                protocolCode = Packet.TYPE_RTPR
            }

            rtpReport.payload = RtpReportPayload().apply {
                source = RtpReportPayload.SOURCE_RTCP
                this.ssrc = report.ssrc

                lastJitter = session.lastJitter

                if(isNewSession) {
                    receivedPacketCount = senderReport.senderPacketCount.toInt()
                    lostPacketCount = report.cumulativePacketLost.toInt()
                    expectedPacketCount = receivedPacketCount + lostPacketCount
                    fractionLost = lostPacketCount / expectedPacketCount.toFloat()
                } else {
                    expectedPacketCount = (report.extendedSeqNumber - session.previousReport.extendedSeqNumber).toInt()
                    lostPacketCount = (report.cumulativePacketLost - session.previousReport.cumulativePacketLost).toInt()
                    receivedPacketCount = expectedPacketCount - lostPacketCount
                    fractionLost = lostPacketCount / expectedPacketCount.toFloat()
                    duration = (senderReport.ntpTimestamp - session.lastNtpTimestamp).toInt()
                }


                // Perform calculations only if codec information persists
                session.sdpSession?.let { sdpSession ->
                    callId = sdpSession.callId
                    payloadType = sdpSession.payloadType

                    // Raw rFactor value
                    val ppl = fractionLost * 100
                    val ieEff = sdpSession.codecIe + (95 - sdpSession.codecIe) * ppl / (ppl + sdpSession.codecBpl)
                    var iDelay = I_DELAY_DEFAULT
                    if (lastJitter - 177.3F >= 0) {
                        iDelay += (lastJitter - 15.93)
                    }
                    rFactor = (R0 - ieEff - iDelay).toFloat()

                    // MoS
                    mos = when {
                        rFactor < 0 -> MOS_MIN
                        rFactor > 100F -> MOS_MAX
                        else -> (1 + rFactor * 0.035 + rFactor * (100 - rFactor) * (rFactor - 60) * 0.000007).toFloat()
                    }
                }
            }

            session.previousReport = report
            session.lastNtpTimestamp = senderReport.ntpTimestamp
            session.lastPacketTimestamp = packet.timestamp.time

            reports.add(rtpReport)
            if (reports.size >= bulkSize) {
                vertx.eventBus().send(Routes.encoder, reports.toList(), USE_LOCAL_CODEC)
                reports.clear()
            }
        }
    }
}