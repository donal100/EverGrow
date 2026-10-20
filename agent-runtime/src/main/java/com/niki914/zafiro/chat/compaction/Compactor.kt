package com.niki914.zafiro.chat.compaction

import com.niki914.logging.Logger
import com.niki914.okia.Okia
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.zafiro.api.text.SummaryBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 会话上下文压缩调度器与摘要生成引擎。
 *
 * 核心设计：
 * 1. 触发判断：基于上下文窗口占用阈值（默认 70%）或用户手动 /compact 强制触发；
 * 2. 对话序列化：展平历史为纯文本，剔除思考块（Thinking），截断大工具输出；
 * 3. 摘要生成：调用一过性摘要生成器产出高质量 Markdown 检查点；
 * 4. “无事发生”原则：用户取消或协程终止时，绝对不调用 [Okia.append]，保持会话树 0 污染；
 * 5. 故障降级：网络异常时平滑跳过压缩，不阻断主交互流程。
 */
class Compactor(
    private val summaryGenerator: SummaryGenerator,
    /** 真实会话的 system prompt（用于前缀重放，保证与上一次请求逐字节一致）。 */
    var systemPromptProvider: () -> String = { CompactorPrompts.SUMMARIZATION_SYSTEM_PROMPT },
    var thresholdRatio: Float = DEFAULT_THRESHOLD_RATIO,
    private val keepTurns: Int = DEFAULT_KEEP_TURNS,
) {

    /** 摘要模型调用契约。 */
    /**
     * 生成摘要。参数刻意与 DeepSeek Harness 的 SummarizationInput 对齐：
     * 把「真实的系统提示 + 真实的消息序列」交进来，末尾再追加一条指令，
     * 这样前缀与刚才那次请求逐字节一致，provider 的 KV cache 能直接命中。
     * 旧做法（自带一条 system prompt + 把历史压成一段文本）前缀完全不同，
     * 每次都等于把整段历史重新处理一遍。
     */
    fun interface SummaryGenerator {
        suspend fun generate(systemPrompt: String, messages: List<Message>, instruction: String): String
    }

    companion object {
        private const val LOG_TAG = "niki914_zafiro_Compactor"

        /** 默认自动压缩触发阈值（窗口占用的 70%）。 */
        const val DEFAULT_THRESHOLD_RATIO = 0.70f

        /** 默认保留的最近活跃轮次数。 */
        const val DEFAULT_KEEP_TURNS = 2

        /** 单条工具结果最大保留字符数（对齐 Pi 的 TOOL_RESULT_MAX_CHARS）。 */
        const val TOOL_RESULT_MAX_CHARS = 2000

        /**
         * 首屏压缩（还没有检查点、无法重放）时，喂给摘要模型的文本总长上限。
         * 约 40 万字符 ≈ 12 万 token，离 1M 窗口很远，但足够装下真实进展。
         */
        const val MAX_SUMMARY_INPUT_CHARS = 400_000

        /**
         * 判定是否应当触发压缩。
         */
        fun shouldCompact(
            contextTokens: Long,
            contextWindow: Long,
            thresholdRatio: Float = DEFAULT_THRESHOLD_RATIO,
            reservedOutputTokens: Long = 0L,
        ): Boolean {
            if (thresholdRatio <= 0f || contextWindow <= 0L || contextTokens <= 0L) return false
            // 关键：请求里还给补全留了额度（deepseek-flash 默认 393,216），
            // 那部分必须先从窗口里扣掉，否则「消息占比 62%」看起来没到 70%、
            // 实际发出去却是 62% + 37% > 100%，直接被服务端拒绝。
            val usableInput = (contextWindow - reservedOutputTokens).coerceAtLeast(1L)
            val thresholdTokens = (usableInput * thresholdRatio).toLong()
            return contextTokens >= thresholdTokens
        }

        /**
         * 将平坦消息历史展平为用于生成摘要的纯文本（对齐 Pi 的 serializeConversation）。
         */
        fun serializeConversation(
            messages: List<Message>,
            maxToolResultChars: Int = TOOL_RESULT_MAX_CHARS,
        ): String {
            val parts = mutableListOf<String>()

            for (msg in messages) {
                when (msg) {
                    is Message.User -> {
                        val text = msg.content
                            .filterIsInstance<ContentBlock.Text>()
                            .joinToString("\n") { it.text }
                            .trim()
                        if (text.isNotEmpty()) {
                            val stripped = SummaryBlock.strip(text)
                            if (stripped.payload != null) {
                                if (stripped.text.isNotBlank()) {
                                    parts.add("[User]: ${stripped.text.trim()}")
                                }
                            } else {
                                parts.add("[User]: $text")
                            }
                        }
                    }

                    is Message.Assistant -> {
                        val textBlocks = msg.message.content
                            .filterIsInstance<ContentBlock.Text>()
                            .map { it.text.trim() }
                            .filter { it.isNotEmpty() }
                        if (textBlocks.isNotEmpty()) {
                            parts.add("[Assistant]: ${textBlocks.joinToString("\n")}")
                        }
                        val toolCalls = msg.message.content.filterIsInstance<ContentBlock.ToolCall>()
                        if (toolCalls.isNotEmpty()) {
                            val callsStr = toolCalls.joinToString("; ") { call ->
                                "${call.name}(${call.argumentsJson})"
                            }
                            parts.add("[Assistant tool calls]: $callsStr")
                        }
                        // ContentBlock.Thinking 故意剔除：思考过程冗长且省 token，防格式冲突
                    }

                    is Message.ToolResult -> {
                        val rawContent = when (val outcome = msg.outcome) {
                            is ToolCallOutcome.Success -> outcome.content
                            is ToolCallOutcome.Failure -> outcome.content ?: outcome.message
                            is ToolCallOutcome.Intercepted -> outcome.content ?: outcome.reason
                            is ToolCallOutcome.Interrupted -> outcome.content ?: "Interrupted"
                            is ToolCallOutcome.Unknown -> outcome.content ?: outcome.message
                        }
                        val content = truncateForSummary(rawContent, maxToolResultChars)
                        if (content.isNotBlank()) {
                            parts.add("[Tool result]: $content")
                        }
                    }
                }
            }

            return parts.joinToString("\n\n")
        }

        private fun truncateForSummary(text: String, maxChars: Int): String {
            if (text.length <= maxChars) return text
            val truncatedChars = text.length - maxChars
            return "${text.take(maxChars)}\n\n[... $truncatedChars more characters truncated]"
        }
    }

    /**
     * 执行会话压缩流程。
     *
     * @param session 当前活跃会话实例。
     * @param force 是否强制触发（忽略 Token 阈值）。
     * @param contextTokens 当前已用 Token。
     * @param contextWindow 上下文总窗口大小。
     * @return 若成功压缩并提交注入块则返回 true；未触发或降级跳过返回 false。
     * @throws CancellationException 若被外部取消，绝不捕获，直接抛出以恪守“无事发生”原则。
     */
    suspend fun compact(
        session: Okia,
        force: Boolean = false,
        contextTokens: Long = 0L,
        contextWindow: Long = 0L,
        reservedOutputTokens: Long = 0L,
    ): Boolean {
        val entries = session.conversation.value.history
        if (entries.isEmpty()) return false
        val history = entries.map { it.message }

        val userTurnsCount = history.count { msg ->
            msg is Message.User && SummaryBlock.strip(textOf(msg)).payload == null
        }

        val isThresholdTriggered =
            shouldCompact(contextTokens, contextWindow, thresholdRatio, reservedOutputTokens)

        if (!force && !isThresholdTriggered) {
            return false
        }

        Logger.i(
            LOG_TAG,
            "starting compaction: force=$force threshold=$isThresholdTriggered " +
                    "historySize=${history.size} userTurns=$userTurnsCount"
        )

        // 提取旧摘要（若存在）
        val previousSummary = history.mapNotNull { msg ->
            if (msg is Message.User) SummaryBlock.strip(textOf(msg)).payload?.summary else null
        }.lastOrNull()

        // 重放边界：只重放「真正会被发出去」的那段前缀，也就是裁剪之后的切片。
        // 旧实现把 6553 条完整历史重放一遍、instruction 里再塞一份序列化文本，
        // 同样的内容付了两遍 token（≈76 万），服务端直接拒单（stopReason=Parse），
        // 摘要于是恒为空，只能吃抽取式兜底 —— 压缩了但等于没压缩。
        val pruned = ContextPruner.prune(history)
        val replayMessages = if (pruned.hasCompacted) pruned.messages else emptyList()

        val instruction = if (replayMessages.isNotEmpty()) {
            // 常规路径：真实消息已重放，只追加指令 -> 前缀与上一轮一致，KV cache 命中。
            CompactorPrompts.buildSummarizationInstruction(previousSummary)
        } else {
            // 首次压缩、尚无检查点：历史太长没法整段重放，退回文本摘要路径，
            // 并对工具结果截断 + 总长封顶，避免刚修好的窗口又被撑爆。
            val conversationText = serializeConversation(history)
            if (conversationText.isBlank()) return false
            CompactorPrompts.buildSummarizationUserPrompt(
                conversationText.takeLast(MAX_SUMMARY_INPUT_CHARS),
                previousSummary,
            )
        }
        val systemPrompt = systemPromptProvider()

        val summaryResult: String
        try {
            summaryResult = summaryGenerator.generate(systemPrompt, replayMessages, instruction)
        } catch (e: CancellationException) {
            // 核心不变量：用户取消严禁生吞，不向会话树追加节点（“无事发生”）
            Logger.i(LOG_TAG, "compaction cancelled by user or coroutine, leaving session untouched")
            throw e
        } catch (e: Throwable) {
            Logger.w(LOG_TAG, "compaction failed gracefully due to error, skipping", e)
            return false
        }

        // 模型摘要为空时不能放弃：实测每一轮都是空摘要，于是历史一直涨到 76 万 token。
        // 用一个确定性的抽取式摘要兜底：把旧对话按「谁说了什么」压成条目，宁粗勿丢。
        val effectiveSummary = summaryResult.ifBlank {
            Logger.w(LOG_TAG, "summary generator returned blank, using extractive fallback")
            extractiveSummary(history)
        }
        if (effectiveSummary.isBlank()) {
            Logger.w(LOG_TAG, "compaction produced no usable summary, aborting")
            return false
        }

        // 确保协程仍然存活
        currentCoroutineContext().ensureActive()

        // 组装 SummaryBlock 节点并挂入树
        val payload = SummaryBlock.Payload(keepTurns = keepTurns, summary = effectiveSummary.trim())
        val blockText = SummaryBlock.block(payload)
        val checkpointMessage = Message.User(listOf(ContentBlock.Text(blockText)))

        session.append(checkpointMessage)
        Logger.i(LOG_TAG, "compaction committed successfully with keepTurns=$keepTurns")
        return true
    }

    /**
     * 确定性摘要：不依赖模型。把最近若干轮压成「user: … / assistant: …」条目。
     * 目的不是优雅，而是保证压缩一定发生 —— 空摘要导致历史无限增长是实测过的故障。
     */
    private fun extractiveSummary(history: List<Message>): String {
        val lines = ArrayList<String>()
        for (msg in history.takeLast(80)) {
            when (msg) {
                is Message.User -> {
                    val t = textOf(msg).replace(Regex("\\s+"), " ").trim()
                    if (t.isNotEmpty()) lines.add("- user: " + t.take(160))
                }
                is Message.Assistant -> {
                    val t = msg.message.content.filterIsInstance<ContentBlock.Text>()
                        .joinToString(" ") { it.text }
                        .replace(Regex("\\s+"), " ").trim()
                    if (t.isNotEmpty()) lines.add("- assistant: " + t.take(160))
                }
                else -> Unit
            }
        }
        return lines.joinToString("\n").take(6000)
    }

    private fun textOf(user: Message.User): String =
        user.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
}
