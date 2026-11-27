package com.adbkeeper.adb

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB 传输层帧格式（小端）。
 * 结构：command(4) arg0(4) arg1(4) dataLength(4) dataCrc32(4) magic(4)，共 24 字节。
 */
object AdbProtocol {

    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_STLS = 0x534c5453

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val CNXN_VERSION = 0x01000001
    const val MAX_DATA = 256 * 1024

    /** shell v2 子流的数据包类型。 */
    const val SHELL_STDIN = 0
    const val SHELL_STDOUT = 1
    const val SHELL_STDERR = 2
    const val SHELL_EXIT = 3
    const val SHELL_CLOSE_STDIN = 4

    data class Packet(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
    ) {
        val bytes: ByteArray
            get() {
                val buffer = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
                buffer.putInt(command)
                buffer.putInt(arg0)
                buffer.putInt(arg1)
                buffer.putInt(payload.size)
                buffer.putInt(checksum(payload))
                buffer.putInt(command xor -1)
                buffer.put(payload)
                return buffer.array()
            }
    }

    fun checksum(data: ByteArray): Int {
        var sum = 0
        for (byte in data) sum += byte.toInt() and 0xFF
        return sum
    }

    fun cnxn(): Packet = Packet(
        A_CNXN, CNXN_VERSION, MAX_DATA,
        "host::adbkeeper\u0000".toByteArray(Charsets.UTF_8),
    )

    fun authSignature(signature: ByteArray): Packet = Packet(A_AUTH, AUTH_SIGNATURE, 0, signature)

    fun authPublicKey(payload: ByteArray): Packet = Packet(A_AUTH, AUTH_RSAPUBLICKEY, 0, payload)

    fun open(localId: Int, destination: String): Packet =
        Packet(A_OPEN, localId, 0, (destination + "\u0000").toByteArray(Charsets.UTF_8))

    fun okay(localId: Int, remoteId: Int): Packet = Packet(A_OKAY, localId, remoteId, ByteArray(0))

    fun write(localId: Int, remoteId: Int, data: ByteArray): Packet =
        Packet(A_WRTE, localId, remoteId, data)

    fun close(localId: Int, remoteId: Int): Packet =
        Packet(A_CLSE, localId, remoteId, ByteArray(0))

    fun readPacket(input: InputStream): Packet? {
        val header = ByteArray(24)
        var read = 0
        while (read < 24) {
            val count = input.read(header, read, 24 - read)
            if (count < 0) throw EOFException("adb 连接已断开")
            read += count
        }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val length = buffer.int
        buffer.int // 校验和，本地回环可忽略
        buffer.int // magic
        if (length < 0 || length > MAX_DATA) error("非法的帧长度 $length")
        val payload = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val count = input.read(payload, filled, length - filled)
            if (count < 0) throw EOFException("adb 连接已断开")
            filled += count
        }
        return Packet(command, arg0, arg1, payload)
    }

    /** 解析 shell v2 数据包：1 字节类型 + 4 字节长度 + 内容。 */
    inline fun parseShellV2(payload: ByteArray, onPacket: (type: Int, data: ByteArray) -> Unit) {
        var offset = 0
        while (offset + 5 <= payload.size) {
            val type = payload[offset].toInt() and 0xFF
            val length = ByteBuffer.wrap(payload, offset + 1, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            offset += 5
            if (length < 0 || offset + length > payload.size) break
            onPacket(type, payload.copyOfRange(offset, offset + length))
            offset += length
        }
    }
}
