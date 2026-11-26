package com.adbkeeper.core

import android.content.Context
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 自定义端口的实际实现：本机 TCP 中继。
 *
 * 无线调试端口由 adbd 随机分配，WRITE_SECURE_SETTINGS 只能写 Settings 表，无法改动它。
 * 这里在会话期间监听用户指定端口，把连接透明转发到 127.0.0.1:<系统实际端口>，
 * adb 的 TLS 仍然是端到端的，所以 adb connect <手机IP>:<自定义端口> 等价于直连。
 * 会话结束或无线调试关闭时立即停止，不常驻。
 */
object PortRelay {

    private const val TAG = "PortRelay"

    private val lock = Any()
    private val liveSockets = ConcurrentHashMap.newKeySet<Socket>()
    private var pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null

    @Volatile
    var boundPort: Int? = null
        private set

    val running: Boolean get() = server != null

    /** 根据当前会话状态同步中继；desiredPort 为 null 时停止。 */
    fun sync(context: Context, desiredPort: Int?) {
        synchronized(lock) {
            if (desiredPort == null) {
                stopLocked()
                return
            }
            if (server != null && boundPort == desiredPort) return
            stopLocked()
            startLocked(context.applicationContext, desiredPort)
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun startLocked(context: Context, port: Int) {
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            server = socket
            boundPort = port
            pool.execute { acceptLoop(context, socket) }
            Logs.i(TAG, "自定义端口 $port 已监听，将中继到系统无线调试端口")
        } catch (t: Throwable) {
            Logs.e(TAG, "端口 $port 监听失败：${t.message}")
        }
    }

    private fun acceptLoop(context: Context, socket: ServerSocket) {
        while (server === socket) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                break
            }
            pool.execute { handle(context, client) }
        }
    }

    private fun handle(context: Context, client: Socket) {
        liveSockets += client
        var upstream: Socket? = null
        try {
            val target = AdbPort.read(context)
            if (target == null) {
                Logs.i(TAG, "中继失败：还没取到系统无线调试端口")
                return
            }
            val remote = Socket()
            upstream = remote
            remote.connect(InetSocketAddress("127.0.0.1", target.toInt()), 3000)
            pool.execute { pump(remote, client) }
            pump(client, remote)
        } catch (t: Throwable) {
            Logs.i(TAG, "中继连接失败：${t.message}")
        } finally {
            liveSockets -= client
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (_: Throwable) {
            // 任意一端断开就结束本次转发
        } finally {
            runCatching { to.close() }
            runCatching { from.close() }
        }
    }

    private fun stopLocked() {
        runCatching { server?.close() }
        server = null
        boundPort = null
        liveSockets.forEach { runCatching { it.close() } }
        liveSockets.clear()
    }
}
