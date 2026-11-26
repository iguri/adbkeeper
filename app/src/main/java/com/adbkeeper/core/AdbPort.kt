package com.adbkeeper.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无线调试实际监听的端口。
 *
 * 该端口由 adbd 在每次开启无线调试时随机分配，通常写在系统属性 service.adb.tls.port 里，
 * 但新系统（如 Android 17）对普通应用隐藏了该属性，getprop 也会被 SELinux 拦。
 * 因此按可靠性依次尝试：
 *  1. 反射 android.os.SystemProperties；
 *  2. 执行 getprop；
 *  3. 扫描 /proc/net/tcp{,6} 里 uid=shell(root) 的监听端口；
 *  4. 用 NsdManager 发现 _adb-tls-connect._tcp 广播（只接受本机地址的服务）。
 * 全部失败返回 null，界面会提示改用「自定义端口」。
 */
object AdbPort {

    private const val TAG = "AdbPort"
    private const val PROP = "service.adb.tls.port"
    private const val SERVICE_TYPE = "_adb-tls-connect._tcp"
    private const val CACHE_MS = 10 * 60_000L
    private const val NEGATIVE_CACHE_MS = 15_000L
    private const val STALE_GRACE_MS = 10 * 60_000L
    private const val MDNS_COOLDOWN_MS = 15_000L

    @Volatile
    private var cachedPort: String? = null

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var lastGoodPort: String? = null

    @Volatile
    private var lastGoodAt = 0L

    @Volatile
    private var lastMdnsAt = 0L

    /** 上次成功的探测方式可靠时，下次优先用它，避免重复走必然失败的步骤。 */
    @Volatile
    private var preferMdns = false

    @Synchronized
    fun read(context: Context?): String? {
        val now = System.currentTimeMillis()
        cachedPort?.let { if (now - cachedAt < CACHE_MS) return it }
        if (cachedPort == null && now - cachedAt < NEGATIVE_CACHE_MS) return null

        var resolved = if (preferMdns && context != null) {
            readMdnsIfDue(context) ?: readProp() ?: readGetProp() ?: readProcNet()
        } else {
            readProp() ?: readGetProp() ?: readProcNet() ?: readMdnsIfDue(context)
        }
        if (resolved == null) {
            // 系统每次开启无线调试才会换端口；短会话内沿用上次成功的值更稳
            val good = lastGoodPort
            if (good != null && now - lastGoodAt < STALE_GRACE_MS) {
                Logs.i(TAG, "本轮探测失败，沿用上次端口：$good")
                resolved = good
            }
        }
        cachedPort = resolved
        cachedAt = now
        if (resolved != null) {
            lastGoodPort = resolved
            lastGoodAt = now
        }
        Logs.i(TAG, if (resolved != null) "端口解析结果：$resolved" else "端口解析：未取到")
        return resolved
    }

    fun invalidate() {
        cachedAt = 0L
        cachedPort = null
        lastGoodPort = null
        lastGoodAt = 0L
        lastMdnsAt = 0L
        preferMdns = false
    }

    private fun readProp(): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, PROP) as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" && it.all { ch -> ch.isDigit() } }
            ?.also { Logs.i(TAG, "SystemProperties 读取成功：$it") }
    } catch (t: Throwable) {
        Logs.i(TAG, "SystemProperties 不可用：${t.javaClass.simpleName}")
        null
    }

    private fun readGetProp(): String? = try {
        val process = ProcessBuilder("sh", "-c", "getprop $PROP")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val code = process.waitFor()
        Logs.i(TAG, "getprop 退出码=$code，输出=「${output.take(40)}」")
        output.takeIf { it.isNotEmpty() && it.all { ch -> ch.isDigit() } }
    } catch (t: Throwable) {
        Logs.i(TAG, "getprop 执行失败：${t.javaClass.simpleName}")
        null
    }

    /** 扫描监听套接字：adbd 的 TLS 服务会出现在这里，uid 为 shell(2000) 或 root(0)。 */
    private fun readProcNet(): String? {
        val entries = mutableListOf<Triple<Int, String, String>>() // 端口, uid, 文件
        for (path in listOf(
            "/proc/self/net/tcp6",
            "/proc/self/net/tcp",
            "/proc/net/tcp6",
            "/proc/net/tcp",
        )) {
            val lines = runCatching { File(path).readLines() }.getOrNull() ?: continue
            for (line in lines.drop(1)) {
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size < 8) continue
                if (cols[3] != "0A") continue // LISTEN
                val port = cols[1].substringAfterLast(':').toIntOrNull(16) ?: continue
                if (port !in 1024..65535 || port == 5037 || port == 5353) continue
                entries += Triple(port, cols[7], path)
            }
        }
        if (entries.isEmpty()) {
            Logs.i(TAG, "/proc/net 未发现监听端口（可能被系统限制）")
            return null
        }
        Logs.i(TAG, "/proc/net 监听候选：" + entries.joinToString { "${it.first}(uid=${it.second})" })
        val shellLike = entries.filter { it.second == "2000" || it.second == "0" }
        val pool = shellLike.ifEmpty { entries }
        val preferred = pool.firstOrNull { it.first >= 30000 } ?: pool.first()
        return preferred.first.toString()
    }

    private fun readMdnsIfDue(context: Context?): String? {
        if (context == null) return null
        val now = System.currentTimeMillis()
        if (now - lastMdnsAt < MDNS_COOLDOWN_MS) return null
        lastMdnsAt = now
        return readMdns(context)
    }

    /** 通过 mDNS 发现本机 _adb-tls-connect._tcp 服务，端口来自广播本身。 */
    private fun readMdns(context: Context): String? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val localAddresses = NetInfo.allLocalAddresses(context)
        val latch = CountDownLatch(1)
        var result: String? = null

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Logs.i(TAG, "mDNS 开始发现 $serviceType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (result != null) return
                runCatching {
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Logs.i(TAG, "mDNS 解析失败：$errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress?.substringBefore('%')
                            Logs.i(TAG, "mDNS 服务：${info.serviceName} host=$host port=${info.port}")
                            if (host != null && localAddresses.isNotEmpty() && host !in localAddresses) {
                                Logs.i(TAG, "忽略非本机地址的 adb 服务：$host")
                                return
                            }
                            if (info.port > 0 && result == null) {
                                result = info.port.toString()
                                latch.countDown()
                            }
                        }
                    })
                }.onFailure { Logs.i(TAG, "mDNS 解析调用失败：${it.javaClass.simpleName}") }
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Logs.i(TAG, "mDNS 启动失败：$errorCode")
                latch.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        val started = runCatching {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.isSuccess
        if (!started) {
            Logs.i(TAG, "mDNS 发现不可用")
            return null
        }
        latch.await(4, TimeUnit.SECONDS)
        runCatching { nsd.stopServiceDiscovery(listener) }
        Logs.i(TAG, "mDNS 结果：${result ?: "未发现"}")
        if (result != null) preferMdns = true
        return result
    }
}
