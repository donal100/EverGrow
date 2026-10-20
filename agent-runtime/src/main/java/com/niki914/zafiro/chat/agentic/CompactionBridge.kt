package com.niki914.zafiro.chat.agentic

import java.util.concurrent.ConcurrentHashMap

/**
 * 压缩触发桥：让工具侧按「当前会话」找到引擎的压缩入口。
 *
 * 为什么需要桥：工具执行链路里只有 [AgentTenantContext]（租户 id + 控制面），
 * 而控制面 [com.niki914.zafiro.api.AgentControl] 是给常驻通知 / 悬浮窗用的窄接口，
 * 压缩属于会话内部维护，不该为了一个工具把它塞进公共接口。
 * 因此引擎在构造时按 tenantId 登记自己，工具按上下文里的 tenantId 取回。
 *
 * 键是 tenantId 而不是全局单例：同一进程里可以并发跑多个 Agent（主对话 + 瞬态任务），
 * 工具必须压自己那个会话的上下文。
 */
object CompactionBridge {

    /** 一次压缩请求的结果。 */
    enum class Outcome {
        /** 已生成检查点并挂进会话树。 */
        COMMITTED,

        /** 引擎在，但压缩没有产出（摘要为空或已取消），会话树未改动。 */
        SKIPPED,

        /** 有回合正在跑：会话树不能在一轮中间被改写，推迟到下一轮开头。 */
        SCHEDULED,
    }

    private val handlers = ConcurrentHashMap<String, suspend () -> Outcome>()

    fun register(tenantId: String, handler: suspend () -> Outcome) {
        if (tenantId.isNotBlank()) handlers[tenantId] = handler
    }

    fun unregister(tenantId: String) {
        handlers.remove(tenantId)
    }

    /** null 表示该租户没有登记（会话已关闭或不是本进程的 Agent）。 */
    suspend fun request(tenantId: String): Outcome? = handlers[tenantId]?.invoke()

    /** 单测复位：对象状态跨用例保留。 */
    fun clearForTest() {
        handlers.clear()
    }
}
