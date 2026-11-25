package com.canon.multicam.protocol.ptpip

import com.canon.multicam.protocol.discovery.CameraNetworkBinder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class PtpIpTransport(
    private val hostIp: String,
    private val port: Int = PtpIpConstants.DEFAULT_PTPIP_PORT,
    private val clientName: String = "CanonMultiCamPro"
) {
    private var commandSocket: Socket? = null
    private var eventSocket: Socket? = null
    private var cmdOut: DataOutputStream? = null
    private var cmdIn: DataInputStream? = null
    private var evtOut: DataOutputStream? = null
    private var evtIn: DataInputStream? = null

    private val transactionCounter = AtomicInteger(1)
    private var connectionNumber: Int = 0

    fun connect(timeoutMs: Int = DEFAULT_TIMEOUT_MS) {
        val guidBytes = createGuidBytes(UUID.randomUUID())

        val cmdSock = createBoundSocket(timeoutMs)
        commandSocket = cmdSock
        cmdOut = DataOutputStream(cmdSock.getOutputStream())
        cmdIn = DataInputStream(cmdSock.getInputStream())

        sendInitCommandRequest(guidBytes)
        readInitCommandAck()

        val evtSock = createBoundSocket(timeoutMs)
        eventSocket = evtSock
        evtOut = DataOutputStream(evtSock.getOutputStream())
        evtIn = DataInputStream(evtSock.getInputStream())

        sendInitEventRequest(connectionNumber)
        readInitEventAck()
    }

    private fun createBoundSocket(timeoutMs: Int): Socket {
        val socket = Socket()
        CameraNetworkBinder.bindSocket(socket)
        socket.soTimeout = timeoutMs
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(hostIp, port), timeoutMs)
        return socket
    }

    private fun sendInitCommandRequest(guidBytes: ByteArray) {
        val out = cmdOut ?: throw IOException("Command stream unavailable")
        val nameUtf16 = encodeUtf16LeNullTerminated(clientName)
        val totalLen = PtpIpConstants.HEADER_BYTES +
            PtpIpConstants.GUID_BYTES +
            nameUtf16.size +
            VERSION_FIELD_BYTES

        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(totalLen)
        buf.putInt(PtpIpConstants.PKT_INIT_COMMAND_REQUEST)
        buf.put(guidBytes)
        buf.put(nameUtf16)
        buf.putInt(PtpIpConstants.PROTOCOL_VERSION)

        out.write(buf.array())
        out.flush()
    }

    private fun readInitCommandAck() {
        val input = cmdIn ?: throw IOException("Command input unavailable")
        val (pktType, payload) = readPacket(input)
        if (pktType != PtpIpConstants.PKT_INIT_COMMAND_ACK) {
            throw IOException("PTP/IP Init Command rejected with type: 0x${pktType.toString(HEX_RADIX)}")
        }
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        connectionNumber = buf.int
    }

    private fun sendInitEventRequest(connId: Int) {
        val out = evtOut ?: throw IOException("Event stream unavailable")
        val totalLen = PtpIpConstants.HEADER_BYTES + INT_BYTES
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(totalLen)
        buf.putInt(PtpIpConstants.PKT_INIT_EVENT_REQUEST)
        buf.putInt(connId)

        out.write(buf.array())
        out.flush()
    }

    private fun readInitEventAck() {
        val input = evtIn ?: throw IOException("Event input unavailable")
        val (pktType, _) = readPacket(input)
        if (pktType != PtpIpConstants.PKT_INIT_EVENT_ACK) {
            throw IOException("PTP/IP Init Event rejected with type: 0x${pktType.toString(HEX_RADIX)}")
        }
    }

    @Synchronized
    fun sendOperation(opCode: Int, params: IntArray = intArrayOf()): Int {
        val txId = transactionCounter.getAndIncrement()
        writeOperationRequest(
            dataPhase = PtpIpConstants.DATA_PHASE_NO_DATA,
            opCode = opCode,
            txId = txId,
            params = params
        )
        return readOperationResponse(txId)
    }

    @Synchronized
    fun sendOperationWithDataOut(
        opCode: Int,
        payloadData: ByteArray,
        params: IntArray = intArrayOf()
    ): Int {
        val out = cmdOut ?: throw IOException("Command output unavailable")
        val txId = transactionCounter.getAndIncrement()

        // 1. Operation Request with DATA_PHASE_SEND_DATA (0x02)
        writeOperationRequest(
            dataPhase = PtpIpConstants.DATA_PHASE_SEND_DATA,
            opCode = opCode,
            txId = txId,
            params = params
        )

        // 2. Start Data Packet (0x09): [Length: 4B][0x09: 4B][TxID: 4B][TotalDataLength: 8B]
        val startLen = PtpIpConstants.HEADER_BYTES + INT_BYTES + LONG_BYTES
        val startBuf = ByteBuffer.allocate(startLen).order(ByteOrder.LITTLE_ENDIAN)
        startBuf.putInt(startLen)
        startBuf.putInt(PtpIpConstants.PKT_START_DATA)
        startBuf.putInt(txId)
        startBuf.putLong(payloadData.size.toLong())
        out.write(startBuf.array())

        // 3. Intermediate Data Packets (0x0A) if payload exceeds chunk size, then End Data (0x0C)
        var offset = 0
        while (payloadData.size - offset > PtpIpConstants.MAX_DATA_CHUNK_BYTES) {
            val chunkLen = PtpIpConstants.MAX_DATA_CHUNK_BYTES
            writeDataChunkPacket(
                out = out,
                pktType = PtpIpConstants.PKT_DATA,
                txId = txId,
                data = payloadData,
                offset = offset,
                length = chunkLen
            )
            offset += chunkLen
        }

        val remaining = payloadData.size - offset
        writeDataChunkPacket(
            out = out,
            pktType = PtpIpConstants.PKT_END_DATA,
            txId = txId,
            data = payloadData,
            offset = offset,
            length = remaining
        )
        out.flush()

        // 4. Operation Response (0x07)
        return readOperationResponse(txId)
    }

    @Synchronized
    fun sendOperationWithDataIn(opCode: Int, params: IntArray = intArrayOf()): Pair<Int, ByteArray> {
        val txId = transactionCounter.getAndIncrement()
        writeOperationRequest(
            dataPhase = PtpIpConstants.DATA_PHASE_GET_DATA,
            opCode = opCode,
            txId = txId,
            params = params
        )
        return readDataAndVerifyResponse(txId)
    }

    private fun writeOperationRequest(
        dataPhase: Int,
        opCode: Int,
        txId: Int,
        params: IntArray
    ) {
        val out = cmdOut ?: throw IOException("Command output unavailable")
        val totalLen = PtpIpConstants.HEADER_BYTES +
            OP_HEADER_FIELDS_BYTES +
            (params.size * INT_BYTES)

        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(totalLen)
        buf.putInt(PtpIpConstants.PKT_OPERATION_REQUEST)
        buf.putInt(dataPhase)
        buf.putShort(opCode.toShort())
        buf.putInt(txId)
        for (p in params) {
            buf.putInt(p)
        }
        out.write(buf.array())
        out.flush()
    }

    private fun writeDataChunkPacket(
        out: DataOutputStream,
        pktType: Int,
        txId: Int,
        data: ByteArray,
        offset: Int,
        length: Int
    ) {
        val packetLen = PtpIpConstants.HEADER_BYTES + INT_BYTES + length
        val header = ByteBuffer.allocate(PtpIpConstants.HEADER_BYTES + INT_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(packetLen)
        header.putInt(pktType)
        header.putInt(txId)
        out.write(header.array())
        if (length > 0) {
            out.write(data, offset, length)
        }
    }

    private fun readOperationResponse(expectedTxId: Int): Int {
        val input = cmdIn ?: throw IOException("Command input unavailable")
        while (true) {
            val (pktType, payload) = readPacket(input)
            if (pktType == PtpIpConstants.PKT_OPERATION_RESPONSE) {
                val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                val respCode = buf.short.toInt() and SHORT_MASK
                val respTxId = buf.int
                if (respTxId != expectedTxId) {
                    throw IOException("PTP/IP TxID mismatch: expected $expectedTxId got $respTxId")
                }
                return respCode
            }
        }
    }

    private fun readDataAndVerifyResponse(expectedTxId: Int): Pair<Int, ByteArray> {
        val input = cmdIn ?: throw IOException("Command input unavailable")
        val dataCollector = ByteArrayOutputStream()

        while (true) {
            val (pktType, payload) = readPacket(input)
            when (pktType) {
                PtpIpConstants.PKT_START_DATA -> Unit
                PtpIpConstants.PKT_DATA, PtpIpConstants.PKT_END_DATA -> {
                    if (payload.size > INT_BYTES) {
                        dataCollector.write(payload, INT_BYTES, payload.size - INT_BYTES)
                    }
                }
                PtpIpConstants.PKT_OPERATION_RESPONSE -> {
                    val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                    val respCode = buf.short.toInt() and SHORT_MASK
                    val respTxId = buf.int
                    if (respTxId != expectedTxId) {
                        throw IOException("PTP/IP TxID mismatch: expected $expectedTxId got $respTxId")
                    }
                    return respCode to dataCollector.toByteArray()
                }
            }
        }
    }

    private fun readPacket(input: DataInputStream): Pair<Int, ByteArray> {
        val headerBytes = ByteArray(PtpIpConstants.HEADER_BYTES)
        input.readFully(headerBytes)
        val headerBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val totalLen = headerBuf.int
        val pktType = headerBuf.int
        val payloadLen = totalLen - PtpIpConstants.HEADER_BYTES
        if (payloadLen < 0 || payloadLen > MAX_ALLOWED_PACKET_BYTES) {
            throw IOException("Invalid PTP/IP packet length: $totalLen")
        }
        val payload = ByteArray(payloadLen)
        if (payloadLen > 0) {
            input.readFully(payload)
        }
        return pktType to payload
    }

    fun close() {
        runCatching { commandSocket?.close() }
        runCatching { eventSocket?.close() }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 5000
        private const val VERSION_FIELD_BYTES = 4
        private const val INT_BYTES = 4
        private const val LONG_BYTES = 8
        private const val OP_HEADER_FIELDS_BYTES = 10
        private const val SHORT_MASK = 0xFFFF
        private const val HEX_RADIX = 16
        private const val MAX_ALLOWED_PACKET_BYTES = 16 * 1024 * 1024

        private fun createGuidBytes(uuid: UUID): ByteArray {
            val buf = ByteBuffer.allocate(PtpIpConstants.GUID_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buf.putLong(uuid.mostSignificantBits)
            buf.putLong(uuid.leastSignificantBits)
            return buf.array()
        }

        private fun encodeUtf16LeNullTerminated(text: String): ByteArray {
            val raw = text.toByteArray(Charsets.UTF_16LE)
            return raw + byteArrayOf(0x00, 0x00)
        }
    }
}
