package com.kupstudio.ridiglass.common

import java.util.UUID

/**
 * BLE communication protocol between phone and glasses.
 * Phone acts as GATT Server, Glasses act as GATT Client.
 *
 * Text chunking protocol:
 * - Each chunk has a 2-byte header: [chunkIndex, totalChunks]
 * - Receiver assembles chunks and displays full text when all received
 */
object BleProtocol {

    // BLE Service & Characteristic UUIDs
    val SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    val CHAR_TEXT_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567891")
    val CHAR_COMMAND_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567892")
    val CHAR_CONTROL_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567893")
    val DESC_CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Commands (phone → glasses via CHAR_COMMAND)
    const val CMD_CLEAR = 0x01.toByte()
    const val CMD_FONT_SIZE_UP = 0x02.toByte()
    const val CMD_FONT_SIZE_DOWN = 0x03.toByte()
    const val CMD_SET_FONT_SIZE = 0x04.toByte()
    const val CMD_VOLUME_LEVEL = 0x05.toByte()
    const val CMD_BG_TOGGLE = 0x06.toByte()

    // Control commands (glasses → phone via CHAR_CONTROL)
    const val CTL_VOLUME_UP = 0x10.toByte()
    const val CTL_VOLUME_DOWN = 0x11.toByte()
    const val CTL_VOLUME_SET = 0x12.toByte()

    // Chunk header: 2 bytes [index, total]
    private const val HEADER_SIZE = 2
    private const val MAX_BLE_PAYLOAD = 509 // MTU 512 - 3 ATT overhead
    private const val MAX_CHUNK_DATA = MAX_BLE_PAYLOAD - HEADER_SIZE

    /**
     * Encode text into BLE chunks with headers for reassembly.
     * Each chunk: [chunkIndex (1 byte), totalChunks (1 byte), ...data...]
     */
    fun encodeText(text: String): List<ByteArray> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val chunks = bytes.toList().chunked(MAX_CHUNK_DATA)
        val total = chunks.size.coerceAtMost(255)

        return chunks.mapIndexed { index, chunkData ->
            val chunk = ByteArray(chunkData.size + HEADER_SIZE)
            chunk[0] = index.coerceAtMost(255).toByte()
            chunk[1] = total.toByte()
            chunkData.forEachIndexed { i, b -> chunk[i + HEADER_SIZE] = b }
            chunk
        }
    }

    /**
     * Decode a single chunk. Returns pair of (header info, data).
     * Header: chunkIndex, totalChunks
     */
    fun decodeChunk(data: ByteArray): Triple<Int, Int, ByteArray> {
        if (data.size < HEADER_SIZE) return Triple(0, 1, data)
        val index = data[0].toInt() and 0xFF
        val total = data[1].toInt() and 0xFF
        val payload = data.copyOfRange(HEADER_SIZE, data.size)
        return Triple(index, total, payload)
    }

    fun decodeText(data: ByteArray): String {
        return String(data, Charsets.UTF_8)
    }
}
