package com.niki914.zafiro.chat.compaction

/**
 * 上下文压缩提示词模板集合。
 *
 * 全面参考并对齐 Pi (`@earendil-works/pi`) 在上下文压缩方面的成熟实现：
 * - 明确助手角色，严禁接话与回答；
 * - 纯 Markdown 结构化输出（Goal, Constraints, Progress, Key Decisions, Next Steps, Critical Context）；
 * - 支持旧摘要滚动增量合并（<previous-summary>）。
 */
object CompactorPrompts {

    /**
     * 摘要角色设定系统提示词（对齐 Pi 的 SUMMARIZATION_SYSTEM_PROMPT）。
     */
    const val SUMMARIZATION_SYSTEM_PROMPT =
        """You are a context summarization assistant. Your task is to read a conversation between a user and an AI assistant, then produce a structured summary following the exact format specified.

Do NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the structured summary."""

    /**
     * 初次生成摘要的结构化提示词（对齐 Pi 的 SUMMARIZATION_PROMPT）。
     */
    const val SUMMARIZATION_PROMPT =
        """The messages above are a conversation to summarize. Create a structured context checkpoint summary that another LLM will use to continue the work.

Use this EXACT format:

## Goal
[What is the user trying to accomplish? Can be multiple items if the session covers different tasks.]

## Constraints & Preferences
- [Any constraints, preferences, or requirements mentioned by user]
- [Or "(none)" if none were mentioned]

## Progress
### Done
- [x] [Completed tasks/changes]

### In Progress
- [ ] [Current work]

### Blocked
- [Issues preventing progress, if any]

## Key Decisions
- **[Decision]**: [Brief rationale]

## Next Steps
1. [Ordered list of what should happen next]

## Critical Context
- [Any data, examples, or references needed to continue]
- [Or "(none)" if not applicable]

Keep each section concise. Preserve exact file paths, function names, and error messages."""

    /**
     * 存在旧摘要时的增量合并指令（对齐 Pi 的 UPDATE_SUMMARIZATION_INSTRUCTIONS）。
     */
    const val UPDATE_SUMMARIZATION_INSTRUCTIONS =
        """Update the existing structured summary with new information. RULES:
- PRESERVE all existing information from the previous summary
- ADD new progress, decisions, and context from the new messages
- UPDATE the Progress section: move items from "In Progress" to "Done" when completed
- UPDATE "Next Steps" based on what was accomplished
- PRESERVE exact file paths, function names, and error messages
- If something is no longer relevant, you may remove it

Use this EXACT format:

## Goal
[Preserve existing goals, add new ones if the task expanded]

## Constraints & Preferences
- [Preserve existing, add new ones discovered]

## Progress
### Done
- [x] [Include previously done items AND newly completed items]

### In Progress
- [ ] [Current work - update based on progress]

### Blocked
- [Current blockers - remove if resolved]

## Key Decisions
- **[Decision]**: [Brief rationale] (preserve all previous, add new)

## Next Steps
1. [Update based on current state]

## Critical Context
- [Preserve important context, add new if needed]

Keep each section concise. Preserve exact file paths, function names, and error messages."""

    /**
     * 仅指令版本：真实消息已经被原样重放，这里只追加「该做什么」。
     *
     * DeepSeek Harness 就是这么做的 —— 前缀与上次请求逐字节一致，KV cache 全命中。
     * 旧做法把序列化文本再塞进 instruction，等于同样的内容付两次 token，
     * 6553 条历史下直接把请求撑爆（服务端拒单 -> 摘要恒为空）。
     */
    fun buildSummarizationInstruction(previousSummary: String?): String {
        return if (previousSummary.isNullOrBlank()) {
            "The messages above are a conversation to summarize.\n\n$SUMMARIZATION_PROMPT"
        } else {
            """<previous-summary>
$previousSummary
</previous-summary>

The messages above are NEW conversation messages to incorporate into the existing summary provided in <previous-summary> tags.

$UPDATE_SUMMARIZATION_INSTRUCTIONS"""
        }
    }

    /**
     * 构建发送给摘要模型的最终用户提示词。
     */
    fun buildSummarizationUserPrompt(conversationText: String, previousSummary: String?): String {
        return if (previousSummary.isNullOrBlank()) {
            "$conversationText\n\n$SUMMARIZATION_PROMPT"
        } else {
            """<previous-summary>
$previousSummary
</previous-summary>

$conversationText

The messages above are NEW conversation messages to incorporate into the existing summary provided in <previous-summary> tags.

$UPDATE_SUMMARIZATION_INSTRUCTIONS"""
        }
    }
}
