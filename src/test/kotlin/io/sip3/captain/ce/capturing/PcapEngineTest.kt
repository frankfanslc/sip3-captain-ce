/*
 * Copyright 2018-2021 SIP3.IO, Corp.
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

package io.sip3.captain.ce.capturing

import io.mockk.*
import io.mockk.junit5.MockKExtension
import io.sip3.captain.ce.domain.Packet
import io.sip3.captain.ce.pipeline.EthernetHandler
import io.sip3.commons.domain.payload.Encodable
import io.sip3.commons.vertx.test.VertxTest
import io.sip3.commons.vertx.util.setPeriodic
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.pcap4j.core.Pcaps
import java.io.File
import java.net.InetAddress
import java.nio.file.Files.createTempDirectory

@ExtendWith(MockKExtension::class)
class PcapEngineTest : VertxTest() {

    companion object {
        val PCAP_FILE = File("src/test/resources/pcap/PcapEngineTest.pcap")

        const val MESSAGE = "Hello, World!"
    }

    private val loopback = InetAddress.getLoopbackAddress()
    private var port = -1

    @BeforeEach
    fun init() {
        port = findRandomPort()
    }

    @Test
    fun `Capture some packets in offline mode`() {
        val tempDir = createTempDirectory("sip3-captain").toFile()
        val file = tempDir.resolve(PCAP_FILE.name)
        PCAP_FILE.copyTo(file)
        val packetSlot = slot<Packet>()
        mockkConstructor(EthernetHandler::class) {
            every {
                anyConstructed<EthernetHandler>().handle(capture(packetSlot))
            } just Runs

            runTest(
                deploy = {
                    vertx.deployTestVerticle(PcapEngine::class, JsonObject().apply {
                        put("pcap", JsonObject().apply {
                            put("dir", tempDir.absolutePath)
                        })
                    })
                },
                execute = {
                    vertx.setPeriodic(1000) {
                        file.setLastModified(System.currentTimeMillis())
                    }
                },
                assert = {
                    vertx.setPeriodic(300L, 500L) {
                        if (!packetSlot.isCaptured) return@setPeriodic

                        context.verify {
                            verify(timeout = 10000) { anyConstructed<EthernetHandler>().handle(any()) }
                            val buffer = (packetSlot.captured.payload as Encodable).encode()
                            val received = Buffer.buffer(buffer).toString()
                            assertTrue(received.endsWith(MESSAGE))
                        }
                        context.completeNow()
                    }
                },
                cleanup = {
                    tempDir.deleteRecursively()
                }
            )
        }
    }

    @Test
    fun `Capture some 'EN10MB' packets in online mode`() {
        val packetSlot = slot<Packet>()
        mockkConstructor(EthernetHandler::class) {
            every {
                anyConstructed<EthernetHandler>().handle(capture(packetSlot))
            } just Runs

            runTest(
                deploy = {
                    vertx.deployTestVerticle(PcapEngine::class, JsonObject().apply {
                        put("pcap", JsonObject().apply {
                            val dev = Pcaps.getDevByAddress(loopback).name
                            put("dev", dev)
                            put("bpf-filter", "udp and port $port")
                            put("timeout-millis", 10)
                        })
                    })
                },
                execute = {
                    vertx.setPeriodic(200) {
                        vertx.createDatagramSocket().send(MESSAGE, port, loopback.hostAddress) {}
                    }
                },
                assert = {
                    vertx.setPeriodic(300L, 500L) {
                        if (!packetSlot.isCaptured) return@setPeriodic

                        context.verify {
                            verify(timeout = 10000) { anyConstructed<EthernetHandler>().handle(any()) }
                            val buffer = (packetSlot.captured.payload as Encodable).encode()
                            val received = Buffer.buffer(buffer).toString()
                            assertTrue(received.endsWith(MESSAGE))
                        }
                        context.completeNow()
                    }
                }
            )
        }
    }

    @AfterEach
    fun `Unmock all`() {
        unmockkAll()
    }
}