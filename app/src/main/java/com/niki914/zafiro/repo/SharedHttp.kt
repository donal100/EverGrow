package com.niki914.zafiro.repo

import android.os.Build
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetSocketAddress

/**
 * 网络失败留痕。用户怀疑「切到别的 app 就出网络问题」，但日志里只有 UI 的
 * 重试提示，没有异常类型和主机名，没法证明也没法排除。
 * 这里给共享 client 挂一个 EventListener：每次连接失败/请求失败写一行
 * call_mode.log（[net] 前缀），时间轴和 [activity] 行对齐即可判断。
 *
 * 业务层共享 OkHttp 单例（连接池 / 线程池复用）。
 * base 保持直连；需要代理的调用方经 newBuilder clone 隔离，不污染 base。
 * okia 自有 HttpEngine，不动。
 */
// ponytail: 全局共享 base 复用连接池；函数体内禁止 new OkHttpClient()
internal object SharedHttp {

    @Volatile private var logFile: File? = null

    fun attachLog(filesDir: File) {
        if (logFile == null) logFile = File(filesDir, "call_mode.log")
    }

    private fun log(msg: String) {
        val f = logFile ?: return
        runCatching {
            f.appendText(System.currentTimeMillis().toString() + "  [net] " + msg + "\n")
        }
    }

    private val netLogger = object : EventListener() {
        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: java.net.Proxy,
            protocol: okhttp3.Protocol?,
            ioe: java.io.IOException,
        ) {
            log("connectFailed host=" + inetSocketAddress.hostName + ":" + inetSocketAddress.port +
                " " + ioe.javaClass.simpleName + ": " + (ioe.message ?: ""))
        }

        override fun callFailed(call: Call, ioe: java.io.IOException) {
            log("callFailed host=" + call.request().url.host + " " +
                ioe.javaClass.simpleName + ": " + (ioe.message ?: ""))
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder().eventListener(netLogger).build()
    }
}
