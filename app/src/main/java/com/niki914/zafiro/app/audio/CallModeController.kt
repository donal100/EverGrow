package com.niki914.zafiro.app.audio

import android.content.Context

/**
 * 通话模式的外壳：把 UI 需要的接口转给 ListenEngine。
 *
 * 历史包袱留在这里，逻辑一行都不放：内部的识别器状态机已经全部删掉了，
 * 现在只有「麦克风开着 → 识别器一直在转写 → 每句结束给一段文本」。
 */
class CallModeController(
    private val context: Context,
    private val onUtterance: (String) -> Unit,
) {

    private val engine = ListenEngine(context, onUtterance)

    var bargeIn = false
    var onBargeIn: (() -> Boolean)? = null

    fun start() = engine.start()
    fun stop() = engine.stop()
    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun listenNow() = engine.resume()
    fun setSpeaking(speaking: Boolean) = Unit
    fun noteSpoken(text: String) = Unit
    fun release() = engine.stop()
}
