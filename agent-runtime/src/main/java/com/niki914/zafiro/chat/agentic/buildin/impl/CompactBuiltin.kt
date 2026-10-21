package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.CompactionBridge
import com.niki914.zafiro.chat.agentic.currentAgentTenantId
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.buildin.RawJsonBuiltinTool
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 让 Agent 自己触发上下文压缩。
 *
 * 为什么需要：压缩只在「上下文占用越过阈值」时自动触发，Agent 发觉自己快要被挤掉
 * 早期细节（比如正在做的长任务中途换了话题）时，没有任何手段主动整理一次。
 * 这个工具把 `AgentSessionEngine.compact(force = true)` 暴露出来。
 *
 * 时机约束：工具是在回合中途被调用的，而压缩要往会话树里追加一条新的 user 检查点，
 * 插在悬挂的工具调用之间会破坏工具链闭合。所以有回合在跑时只记旗标、下一轮开头做，
 * 工具结果里如实写明「已排期」；没有回合在跑才当场压缩。
 */
class CompactBuiltin : BuiltinTool(), RawJsonBuiltinTool {

    override val name: String = "compact"

    override val description: String =
        "Fold the older part of this conversation into a summary checkpoint, freeing context " +
                "without losing the thread. Use it when a long session has drifted or is running " +
                "out of context. Returns whether it compacted now, was skipped, or was scheduled " +
                "for the start of the next round (a round is running, and the session tree cannot " +
                "be rewritten mid-round)."

    override val defaultEnabled: Boolean = true

    override val inputSchemaJson: String? get() = COMPACT_SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        return BuiltinToolResult.failure(
            code = "RAW_JSON_ONLY",
            message = "$name must be executed through invokeRawJson().",
            hint = """Example: {} or {"reason":"long session, freeing context"}""",
        )
    }

    override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
        val reason = try {
            parseReason(request.argumentsJson)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return BuiltinToolResult.failure(
                code = "INVALID_ARGUMENTS",
                message = "compact arguments must be a JSON object; the only field is optional.",
                hint = """Example: {"reason":"long session, freeing context"}""",
            ).toJsonString()
        }

        val tenantId = currentAgentTenantId() ?: return BuiltinToolResult.failure(
            code = "NO_AGENT_CONTEXT",
            message = "compact must run inside an agent turn; no tenant context was found.",
            hint = "Call compact from the conversation that you want to compact.",
        ).toJsonString()

        val outcome = CompactionBridge.request(tenantId) ?: return BuiltinToolResult.failure(
            code = "SESSION_NOT_AVAILABLE",
            message = "No live session is registered for this conversation.",
            hint = "The session may have been closed or rebuilt; try again after your next message.",
        ).toJsonString()

        val message = when (outcome) {
            CompactionBridge.Outcome.COMMITTED ->
                "Compacted: a summary checkpoint was written and older messages were folded into it."

            CompactionBridge.Outcome.SKIPPED ->
                "Compaction ran but produced nothing, so the conversation is unchanged."

            CompactionBridge.Outcome.SCHEDULED ->
                "Compaction scheduled: it runs at the start of the next round, because the session " +
                        "tree cannot be rewritten while this round is still executing."
        }
        return BuiltinToolResult.success(
            message = message,
            data = JsonObject(
                mapOf(
                    "outcome" to JsonPrimitive(outcome.name.lowercase()),
                    "reason" to JsonPrimitive(reason.orEmpty()),
                )
            ),
        ).toJsonString()
    }

    private fun parseReason(argumentsJson: String): String? {
        if (argumentsJson.isBlank()) return null
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return null
        }
        val obj = element as? JsonObject ?: return null
        // reason 只是备注，读不出来就当没填：不能让一个可选字段把压缩请求打成失败
        return try {
            obj["reason"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            null
        }
    }

    private companion object {
        const val COMPACT_SCHEMA = """{
            "type": "object",
            "properties": {
                "reason": {
                    "type": "string",
                    "description": "Optional short note on why compaction is needed."
                }
            },
            "required": []
        }"""
    }
}
