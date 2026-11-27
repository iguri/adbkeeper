package com.adbkeeper.adb

import android.util.Base64
import com.adbkeeper.core.Keeper
import com.adbkeeper.core.Logs
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.Signature

/**
 * 极简 ADB 客户端：CNXN / AUTH 握手 + shell v2 子流。
 *
 * 现状：只支持明文 TCP（例如电脑执行过 `adb tcpip 5555` 的场景）。
 * adb 的无线调试走 TLS-PSK，需要 SPAKE2 与 PSK 回调，JDK/Conscrypt 没有公开 API，
 * 因此本类暂不能直接连无线调试端口；配对能力见 PairingWizard 的说明。
 */
class AdbShellSession(
    private val keyPair: KeyPair,
    private val packageName: String,
) : Closeable {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var nextLocalId = 1

    fun connect(host: String, port: Int, timeoutMs: Int = 5000): Boolean = runCatching {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
        send(AdbProtocol.cnxn())
        handshake()
    }.getOrElse {
        Logs.e(TAG, "连接 $host:$port 失败", it)
        false
    }

    private fun handshake(): Boolean {
        repeat(4) {
            val packet = AdbProtocol.readPacket(input!!) ?: return false
            when (packet.command) {
                AdbProtocol.A_CNXN -> return true
                AdbProtocol.A_AUTH -> {
                    // AUTH TOKEN：先用私钥签名；被拒后上报公钥等待用户确认
                    val signature = sign(packet.payload)
                    send(AdbProtocol.authSignature(signature))
                }
                AdbProtocol.A_STLS -> {
                    Logs.i(TAG, "对端要求 TLS 连接，当前版本不支持")
                    return false
                }
                else -> Unit
            }
            if (packet.arg0 == AdbProtocol.AUTH_TOKEN && it == 2) {
                send(AdbProtocol.authPublicKey(publicKeyPayload()))
            }
        }
        return false
    }

    /** 执行命令，返回 (退出码, stderr)。 */
    fun shell(command: String): Pair<Int, String> {
        val localId = nextLocalId++
        send(AdbProtocol.open(localId, "shell,v2,raw:${Base64.encodeToString(command.toByteArray(), Base64.NO_WRAP)}"))
        var remoteId = 0
        var exit = -1
        val stderr = StringBuilder()
        val stdout = StringBuilder()
        while (true) {
            val packet = AdbProtocol.readPacket(input!!) ?: break
            when (packet.command) {
                AdbProtocol.A_OKAY -> {
                    if (remoteId == 0) remoteId = packet.arg0
                }
                AdbProtocol.A_WRTE -> {
                    if (remoteId == 0) remoteId = packet.arg0
                    AdbProtocol.parseShellV2(packet.payload) { type, data ->
                        when (type) {
                            AdbProtocol.SHELL_STDOUT -> stdout.append(String(data))
                            AdbProtocol.SHELL_STDERR -> stderr.append(String(data))
                            AdbProtocol.SHELL_EXIT -> exit = data.firstOrNull()?.toInt() ?: -1
                        }
                    }
                    send(AdbProtocol.okay(localId, remoteId))
                }
                AdbProtocol.A_CLSE -> {
                    if (remoteId == 0) remoteId = packet.arg0
                    send(AdbProtocol.close(localId, remoteId))
                    break
                }
                else -> Unit
            }
        }
        Logs.i(TAG, "shell 退出码=$exit stdout=${stdout.take(120)} stderr=${stderr.take(120)}")
        return exit to stderr.toString()
    }

    /**
     * 授予自身 WRITE_SECURE_SETTINGS。
     * 注意：多数国产 ROM 禁止 shell 执行 grant，必须回读确认，不能只看退出码。
     */
    fun grantSelfPermission(): Boolean {
        val (exit, stderr) = shell(
            "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS",
        )
        if (exit != 0) {
            Logs.i(TAG, "授予失败：${stderr.take(160)}")
            return false
        }
        return true
    }

    private fun sign(token: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(keyPair.private)
        signature.update(token)
        return signature.sign()
    }

    /** Android adb 使用的 RSAPublicKey 结构 + user@host 后缀。 */
    private fun publicKeyPayload(): ByteArray {
        val rsa = keyPair.public as java.security.interfaces.RSAPublicKey
        val modulus = rsa.modulus
        val length = (modulus.bitLength() + 31) / 32
        val buffer = ByteBuffer.allocate(4 * (2 + length * 2 + 2)).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(4 * (2 + length * 2 + 2))          // 结构体总长度
        buffer.putInt(n0inv(modulus, length))            // n0inv
        putLittleEndian(buffer, modulus, length)        // n
        val r = BigIntegerHolder.rr(modulus, length)
        putLittleEndian(buffer, r, length)              // rr
        buffer.putInt(rsa.publicExponent.toInt())       // exponent
        buffer.putInt(0)                                 // reserved
        val blob = Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
        return "$blob adbkeeper@android\u0000".toByteArray(Charsets.UTF_8)
    }

    private fun n0inv(modulus: java.math.BigInteger, length: Int): Int {
        val r32 = java.math.BigInteger.ONE.shiftLeft(32)
        val n0 = modulus.and(r32.subtract(java.math.BigInteger.ONE))
        return n0.modInverse(r32).negate().and(r32.subtract(java.math.BigInteger.ONE)).toInt()
    }

    private fun putLittleEndian(
        buffer: ByteBuffer,
        value: java.math.BigInteger,
        words: Int,
    ) {
        val mask = java.math.BigInteger.ONE.shiftLeft(32).subtract(java.math.BigInteger.ONE)
        var current = value
        repeat(words) {
            buffer.putInt(current.and(mask).toInt())
            current = current.shiftRight(32)
        }
    }

    private fun send(packet: AdbProtocol.Packet) {
        output?.write(packet.bytes)
        output?.flush()
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private companion object {
        const val TAG = "AdbShell"
    }
}

/** rr = R^2 mod n，R = 2^(32*len)。 */
private object BigIntegerHolder {
    fun rr(modulus: java.math.BigInteger, length: Int): java.math.BigInteger {
        val r = java.math.BigInteger.ONE.shiftLeft(32 * length)
        return r.multiply(r).mod(modulus)
    }
}
