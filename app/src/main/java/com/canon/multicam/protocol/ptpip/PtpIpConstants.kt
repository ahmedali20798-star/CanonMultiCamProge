package com.canon.multicam.protocol.ptpip

object PtpIpConstants {
    const val DEFAULT_PTPIP_PORT = 15740
    const val PROTOCOL_VERSION = 0x00010000

    // CIPA DC-X005-2005 PTP/IP Packet Types
    const val PKT_INIT_COMMAND_REQUEST = 0x00000001
    const val PKT_INIT_COMMAND_ACK = 0x00000002
    const val PKT_INIT_EVENT_REQUEST = 0x00000003
    const val PKT_INIT_EVENT_ACK = 0x00000004
    const val PKT_INIT_FAIL = 0x00000005
    const val PKT_OPERATION_REQUEST = 0x00000006
    const val PKT_OPERATION_RESPONSE = 0x00000007
    const val PKT_EVENT = 0x00000008
    const val PKT_START_DATA = 0x00000009
    const val PKT_DATA = 0x0000000A
    const val PKT_CANCEL = 0x0000000B
    const val PKT_END_DATA = 0x0000000C

    // Data Phase Direction Flags
    const val DATA_PHASE_NO_DATA = 0x00000001
    const val DATA_PHASE_SEND_DATA = 0x00000002
    const val DATA_PHASE_GET_DATA = 0x00000003

    // Standard PTP Operation & Response Codes
    const val OP_GET_DEVICE_INFO = 0x1001
    const val OP_OPEN_SESSION = 0x1002
    const val OP_CLOSE_SESSION = 0x1003
    const val RESP_OK = 0x2001
    const val RESP_SESSION_NOT_OPEN = 0x2003
    const val RESP_DEVICE_BUSY = 0x2019

    // Canon EOS Vendor-Specific PTP Operation Codes
    const val OP_CANON_SET_DEVICE_PROP_VALUE_EX = 0x9110
    const val OP_CANON_SET_EVENT_MODE = 0x9115
    const val OP_CANON_SET_REMOTE_MODE = 0x9114
    const val OP_CANON_GET_EVENT = 0x9116
    const val OP_CANON_REMOTE_RELEASE_ON = 0x9128
    const val OP_CANON_REMOTE_RELEASE_OFF = 0x9129
    const val OP_CANON_GET_VIEWFINDER_DATA = 0x9153
    const val OP_CANON_TOUCH_AF_POSITION = 0x915B
    const val OP_CANON_MOVIE_RECORD_START = 0x9133
    const val OP_CANON_MOVIE_RECORD_STOP = 0x9134

    // Canon EOS Property Codes
    const val PROP_CANON_APERTURE = 0xD101
    const val PROP_CANON_SHUTTER_SPEED = 0xD102
    const val PROP_CANON_ISO = 0xD103
    const val PROP_CANON_WHITE_BALANCE = 0xD109
    const val PROP_CANON_CAPTURE_DESTINATION = 0xD11C
    const val PROP_CANON_EVF_OUTPUT_DEVICE = 0xD1B0
    const val PROP_CANON_EVF_MODE = 0xD1B1
    const val PROP_CANON_RECORD_MODE = 0xD1B8

    // Canon EOS Capture Destination Values (libgphoto2 PTP_DPC_CANON_EOS_CaptureDestination)
    // 1 = Host RAM (requires GetObject download; causes Buffer Full if not drained)
    // 2 = Camera SD Card (saves directly to internal card)
    // 3 = Both Host RAM and Camera SD Card
    const val CAPTURE_TARGET_HOST = 1
    const val CAPTURE_TARGET_CARD = 2
    const val CAPTURE_TARGET_BOTH = 3

    // EVF Output Values
    const val EVF_OUTPUT_OFF = 0
    const val EVF_OUTPUT_TFT = 1
    const val EVF_OUTPUT_PC = 2
    const val REMOTE_RELEASE_FULL = 3

    // Transport Framing Constants
    const val HEADER_BYTES = 8
    const val GUID_BYTES = 16
    const val MAX_DATA_CHUNK_BYTES = 65536
}
