package com.niki914.zafiro.chat.agentic

/**
 * 屏幕截图已经下线：它依赖无障碍服务，而 Evergrow 决定不再要求那个权限。
 * 保留同名的结果类型，调用方（文字动作触发器等）无需改动即可编译。
 */
object ScreenCaptureIngestor {

    sealed interface CaptureResult {
        data class Ok(val path: String) : CaptureResult
        data class Err(val stage: String = "capture", val message: String) : CaptureResult
    }

    suspend fun captureAndIngest(): CaptureResult =
        CaptureResult.Err("capture", "screenshot unavailable: accessibility removed")
}
