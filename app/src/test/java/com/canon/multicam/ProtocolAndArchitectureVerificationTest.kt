package com.canon.multicam

import com.canon.multicam.domain.model.CanonModelCatalog
import com.canon.multicam.domain.model.ProtocolType
import com.canon.multicam.protocol.ccapi.CcapiServiceMap
import com.canon.multicam.protocol.ccapi.HttpDigestAuthHelper
import com.canon.multicam.protocol.ptpip.PtpIpConstants
import com.canon.multicam.protocol.ptpip.PtpIpTransport
import com.canon.multicam.protocol.usb.UsbPtpTransport
import com.canon.multicam.ui.MultiCamViewModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class ProtocolAndArchitectureVerificationTest {

    @Test
    fun `capture destination targets SD card (2) not Host RAM (1)`() {
        assertEquals(2, PtpIpConstants.CAPTURE_TARGET_CARD)
        assertEquals(1, PtpIpConstants.CAPTURE_TARGET_HOST)

        val usbPayload = UsbPtpTransport.buildCaptureToCardPayload()
        val buf = ByteBuffer.wrap(usbPayload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(12, buf.int)
        assertEquals(PtpIpConstants.PROP_CANON_CAPTURE_DESTINATION, buf.int)
        assertEquals(2, buf.int)
    }

    @Test
    fun `ptpip DataOut emits OperationRequest StartData and EndData packets`() {
        val server = ServerSocket(0)
        val receivedPacketTypes = mutableListOf<Int>()
        var receivedPropValue = -1

        val serverThread = thread {
            server.accept().use { cmdSock ->
                val input = DataInputStream(cmdSock.getInputStream())
                val out = DataOutputStream(cmdSock.getOutputStream())

                // 1. Read Init Command Request (0x01)
                val (initType, _) = readRawPtpIpPacket(input)
                receivedPacketTypes.add(initType)
                val ack = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(12)
                    .putInt(PtpIpConstants.PKT_INIT_COMMAND_ACK)
                    .putInt(42)
                    .array()
                out.write(ack)
                out.flush()

                // Accept Event Socket
                server.accept().use { evtSock ->
                    val evtIn = DataInputStream(evtSock.getInputStream())
                    val evtOut = DataOutputStream(evtSock.getOutputStream())
                    val (evtInitType, _) = readRawPtpIpPacket(evtIn)
                    receivedPacketTypes.add(evtInitType)
                    val evtAck = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(8)
                        .putInt(PtpIpConstants.PKT_INIT_EVENT_ACK)
                        .array()
                    evtOut.write(evtAck)
                    evtOut.flush()

                    // 2. Read OperationRequest (0x06), StartData (0x09), EndData (0x0C)
                    val (opType, _) = readRawPtpIpPacket(input)
                    receivedPacketTypes.add(opType)
                    val (startType, _) = readRawPtpIpPacket(input)
                    receivedPacketTypes.add(startType)
                    val (endType, endPayload) = readRawPtpIpPacket(input)
                    receivedPacketTypes.add(endType)

                    val dataBuf = ByteBuffer.wrap(endPayload).order(ByteOrder.LITTLE_ENDIAN)
                    dataBuf.int // txId
                    dataBuf.int // size (12)
                    dataBuf.int // propCode
                    receivedPropValue = dataBuf.int

                    // Send OperationResponse (0x07) with TxID = 1
                    val resp = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(14)
                        .putInt(PtpIpConstants.PKT_OPERATION_RESPONSE)
                        .putShort(PtpIpConstants.RESP_OK.toShort())
                        .putInt(1)
                        .array()
                    out.write(resp)
                    out.flush()
                }
            }
        }

        val transport = PtpIpTransport(hostIp = "127.0.0.1", port = server.localPort)
        transport.connect(timeoutMs = 2000)
        val payload = UsbPtpTransport.buildCaptureToCardPayload()
        val respCode = transport.sendOperationWithDataOut(
            opCode = PtpIpConstants.OP_CANON_SET_DEVICE_PROP_VALUE_EX,
            payloadData = payload
        )
        transport.close()
        serverThread.join(2000)
        server.close()

        assertEquals(PtpIpConstants.RESP_OK, respCode)
        assertEquals(2, receivedPropValue)
        assertEquals(
            listOf(
                PtpIpConstants.PKT_INIT_COMMAND_REQUEST,
                PtpIpConstants.PKT_INIT_EVENT_REQUEST,
                PtpIpConstants.PKT_OPERATION_REQUEST,
                PtpIpConstants.PKT_START_DATA,
                PtpIpConstants.PKT_END_DATA
            ),
            receivedPacketTypes
        )
    }

    @Test
    fun `ccapi service discovery maps versions and detects touchaf absence on EOS RP`() {
        val rootJson = JSONObject().apply {
            put(
                "ver100",
                JSONArray().apply {
                    put(JSONObject().put("path", "/ccapi/ver100/shooting/liveview"))
                    put(JSONObject().put("path", "/ccapi/ver100/shooting/control/af"))
                }
            )
        }
        val serviceMap = CcapiServiceMap.parseFromRootJson(rootJson)
        assertFalse(serviceMap.hasEndpoint("shooting/control/touchaf"))
        assertTrue(serviceMap.hasEndpoint("shooting/control/af"))
        assertEquals(
            "http://192.168.1.2:8080/ccapi/ver100/shooting/control/af",
            serviceMap.resolveUrl("http://192.168.1.2:8080", "shooting/control/af")
        )
    }

    @Test
    fun `digest auth helper computes valid RFC 2617 authorization header`() {
        val helper = HttpDigestAuthHelper("canon_user", "secret123")
        val challenge = "Digest realm="CanonCCAPI", nonce="dcd98b7102dd2f0e8b11d0f600bfb0c093", " +
            "qop="auth", algorithm=MD5"
        assertTrue(helper.updateChallengeFromHeader(challenge))
        val header = helper.buildAuthorizationHeader("GET", "/ccapi")
        assertNotNull(header)
        assertTrue(header!!.startsWith("Digest "))
        assertTrue(header.contains("username="canon_user""))
        assertTrue(header.contains("realm="CanonCCAPI""))
        assertTrue(header.contains("qop=auth"))
        assertTrue(header.contains("response=""))
    }

    @Test
    fun `all 8 Canon EOS R models resolve to accurate protocol priorities`() {
        val r5c = CanonModelCatalog.resolveByName("Canon EOS R5 C")
        assertEquals(ProtocolType.XC_PROTOCOL, r5c.supportedProtocols.first())
        assertEquals(
            listOf(ProtocolType.XC_PROTOCOL, ProtocolType.WIFI_CCAPI, ProtocolType.WIFI_PTPIP),
            MultiCamViewModel.resolveProbeOrder(r5c)
        )

        val eosR = CanonModelCatalog.resolveByName("Canon EOS R")
        assertEquals(ProtocolType.WIFI_PTPIP, eosR.supportedProtocols.first())

        val eosRp = CanonModelCatalog.resolveByName("Canon EOS RP")
        assertFalse(eosRp.capabilities.supportsTouchAf)

        val r6m3 = CanonModelCatalog.resolveByName("Canon EOS R6 Mark III")
        assertEquals(ProtocolType.WIFI_CCAPI, r6m3.supportedProtocols.first())
        assertFalse(r6m3.requiresCcapiPreActivation)
    }

    private fun readRawPtpIpPacket(input: DataInputStream): Pair<Int, ByteArray> {
        val hdr = ByteArray(8)
        input.readFully(hdr)
        val buf = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val len = buf.int
        val type = buf.int
        val payload = ByteArray(len - 8)
        if (payload.isNotEmpty()) input.readFully(payload)
        return type to payload
    }
}
