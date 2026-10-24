package com.niki914.zafiro.chat.agentic

import com.niki914.zafiro.chat.ResolvedTools
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import com.niki914.zafiro.settings.model.RuntimeSkillMetadata

data class PromptComposeResult(
    val finalSystemPrompt: String,
)

data class PromptComposerInput(
    val additionalInstructions: String,
    val memoryItems: List<String> = emptyList(),
    val tools: ResolvedTools = ResolvedTools(),
    val enabledSkills: List<RuntimeSkillMetadata> = emptyList(),
    val sandboxPaths: Set<String> = emptySet(),
)

class PromptComposer {

    fun compose(input: PromptComposerInput): PromptComposeResult {
        val finalSystemPrompt = listOfNotNull(
            buildStableTier(input),
            buildVolatileTier(input),
        ).joinToString(separator = "\n\n")

        return PromptComposeResult(finalSystemPrompt = finalSystemPrompt)
    }

    // --- Stable tier: identity, tools, skills, guidance (cacheable across turns) ---

    private fun buildStableTier(input: PromptComposerInput): String {
        val identity = input.additionalInstructions.trim().ifBlank { DEFAULT_AGENT_IDENTITY }
        val envBlock = renderEnvironmentBlock(input.sandboxPaths)
        return listOfNotNull(
            identity,
            envBlock,
            renderToolContext(input.tools),
            renderSkillContext(input.enabledSkills)
                .takeIf { hasBuiltinTool(input, "load_skill") },
            TASK_COMPLETION_GUIDANCE.takeIf { hasAnyTool(input) },
            TOOL_USE_ENFORCEMENT_GUIDANCE.takeIf { hasAnyTool(input) },
            EXECUTION_RULES_GUIDANCE.takeIf { hasAnyTool(input) },
            MEMORY_GUIDANCE.takeIf { hasBuiltinTool(input, "memory") },
            SKILLS_GUIDANCE.takeIf { hasBuiltinTool(input, "load_skill") },
        ).joinToString(separator = "\n\n")
    }

    // --- Volatile tier: memory snapshot (per-session) ---

    private fun buildVolatileTier(input: PromptComposerInput): String? {
        val items = input.memoryItems.map(String::trim).filter(String::isNotBlank)
        if (items.isEmpty()) return null
        val separator = "═".repeat(46)
        val content = items.joinToString("\n§\n")
        return "$separator\nMEMORY\n$separator\n$content"
    }

    // --- Tool context ---

    private fun renderToolContext(
        tools: ResolvedTools,
    ): String? {
        val blocks = listOfNotNull(
            renderNameBlock("builtin_tools", tools.builtinTools.map { it.name }),
            renderNameBlock("custom_py_tools", tools.customPyTools.map { it.name }),
        )
        if (blocks.isEmpty()) return null
        return "## Tool Context\n\n${blocks.joinToString(separator = "\n\n")}"
    }

    private fun renderNameBlock(tag: String, names: List<String>): String? {
        val normalized = names.map(String::trim).filter(String::isNotBlank).distinct().sorted()
        if (normalized.isEmpty()) return null
        return normalized.joinToString(
            separator = "\n",
            prefix = "<$tag>\n",
            postfix = "\n</$tag>",
        ) { "- $it" }
    }

    // --- Skill context ---

    private fun renderSkillContext(skills: List<RuntimeSkillMetadata>): String? {
        val entries = skills
            .mapNotNull { skill ->
                val id = skill.id.trim()
                if (id.isBlank()) return@mapNotNull null
                val name = skill.name.trim().ifBlank { id }
                val desc = skill.description.trim()
                val dir = skill.absoluteDir.trim()
                buildString {
                    appendLine("  <skill>")
                    appendLine("    <id>$id</id>")
                    appendLine("    <name>$name</name>")
                    if (desc.isNotBlank()) appendLine("    <description>$desc</description>")
                    if (dir.isNotBlank()) appendLine("    <dir>$dir</dir>")
                    append("  </skill>")
                }
            }
            .sorted()
        if (entries.isEmpty()) return null

        return buildString {
            appendLine("## Skills (mandatory)")
            appendLine()
            appendLine(
                "Before replying, scan the skills below. If a skill matches or is even partially " +
                        "relevant to your task, you MUST load it with load_skill and follow its " +
                        "instructions. Err on the side of loading — it is always better to have " +
                        "context you don't need than to miss critical steps, pitfalls, or established " +
                        "workflows. Skills contain specialized knowledge and proven approaches that " +
                        "outperform general-purpose methods."
            )
            appendLine()
            appendLine("<available_skills>")
            entries.forEach { appendLine(it) }
            append("</available_skills>")
        }
    }

    // --- Environment block ---

    /** 私有存储路径提示块：告诉 Agent 这些目录可自由读写，零权限。 */
    private fun renderEnvironmentBlock(paths: Set<String>): String? {
        if (paths.isEmpty()) return null
        val pathList = paths.sorted().joinToString("\n") { "- $it" }
        return buildString {
            appendLine("# Environment")
            appendLine()
            appendLine("Private app storage (read/write freely, no storage permission required):")
            appendLine(pathList)
            appendLine()
            appendLine("Files outside these directories are shared storage and may require")
            appendLine("storage permission that is not currently granted — prefer private")
            appendLine("directories. If a task requires public files, tell the user.")
        }
    }

    // --- Helpers ---

    private fun hasBuiltinTool(input: PromptComposerInput, name: String): Boolean {
        return input.tools.builtinTools.any { it.name == name }
    }

    private fun hasAnyTool(input: PromptComposerInput): Boolean {
        return input.tools.builtinTools.isNotEmpty() ||
                input.tools.customPyTools.isNotEmpty() ||
                input.tools.mcpServers.any { it.enabled }
    }

    // --- Guidance constants ---

    companion object {
        fun buildMemoryItems(config: RuntimeLlmConfig): List<String> {
            val memories = config.memories.map(String::trim).filter(String::isNotBlank)
            if (memories.isNotEmpty()) {
                return memories
            }
            return listOfNotNull(config.memoryPrompt.trim().takeIf { it.isNotBlank() })
        }

        /**
         * 内置身份与默认偏好：随 APK 一起发布。
         *
         * settings 里的 `prompt` 字段为空时用它；非空则整段替换。这样重装或换机后
         * agent 仍然带着「短回复 + 语音副本 + 只在被要求时构建」这些默认行为，
         * 不用靠用户手动配置才能恢复。
         */
        internal val DEFAULT_AGENT_IDENTITY = """You are Zafiro, the agent living inside Evergrow — the user's own Android build of Zafiro, which they maintain themselves on this phone. You run on their device with their tools: screen access, on-device speech, notifications, their own calendar, and a CI pipeline that builds Evergrow's APK.

# Default preferences

- Short by default: one or two sentences, then stop. Go long only when the user asks for detail or the work genuinely requires it.
- Every reply has a spoken counterpart. The user is often away from the screen, so write so it reads aloud cleanly and make the spoken version shorter than the written one — the app speaks it in voice mode, otherwise call py_speak yourself. One-word acknowledgements still deserve a short spoken line.
- Never double the voice: while the app's own reply voice is active (voice mode, listen mode) do not also call the speech tools.
- Truth over fluency: never fabricate tool output, log lines, files or API responses. If a step is blocked, say so plainly and try another route. Never report DENIED_BY_USER unless a consent dialog was actually refused.
- Measure, don't assume: run the thing, time it, quote the real output — including when it fails.
- Build only when asked. Do not run a CI build after each fix; batch the edits and push when the user says build. Once a build is pushed, watch it to completion and report the real outcome rather than predicting success.
- Reply in the user's language: if they write Cantonese or Chinese, answer in that.
- Durable facts and workflows belong in memory and skills, not in repeated explanations.
- Speech: spoken lines default to the py_speak_qwen tool (Qwen streaming omni, voice Serena); fall back to py_speak only when the cloud voice fails. In listen mode the app may already own the reply speech — check before adding another voice, and never speak twice.
- In listen mode keep the user in the loop: before a tool batch say one short line about what is happening, keep each narration to a few words, never read tool output aloud, and do not narrate trivial single calls. The point is to make silence meaningful, not to fill it.
- When a transcript looks clearly wrong — words missing, a substitution that changes meaning, numbers or names that seem off — re-hear the last half minute with py_voice_archive before acting on it. That tool is a diagnostic for suspect accuracy, not a habit for correct transcripts.
- Assume every install already has all the API keys it needs locally, read straight from the app's private tool_output directory. Do not build key-entry onboarding, proxies or licence checks, and do not ask the user for a key.
- Keep spoken replies to one or two short sentences. Playback takes the headset off the call route, so the microphone goes deaf until the reply ends and anything said meanwhile is discarded — a long reply blocks the conversation. If a point needs length, write it in the chat and speak only the headline.
- When a transcript arrives as a fragment or obviously misheard (a single word like "I." or "Um."), do not diagnose it and do not quote logs. Answer in one very short line making the failure obvious and ask them to repeat."""

        internal const val TASK_COMPLETION_GUIDANCE =
            "# Finishing the job\n" +
                    "When the user asks you to build, run, or verify something, the deliverable is " +
                    "a working result backed by real tool output — not a description of one. " +
                    "Do not stop after writing a stub, a plan, or a single command. Keep working " +
                    "until you have actually exercised the code or produced the requested result, " +
                    "then report what real execution returned.\n" +
                    "If a tool, install, or network call fails and blocks the real path, say so " +
                    "directly and try an alternative (different package manager, different " +
                    "approach, ask the user). NEVER substitute plausible-looking fabricated " +
                    "output (made-up data, invented file contents, synthesised API responses) " +
                    "for results you couldn't actually produce. Reporting a blocker honestly " +
                    "is always better than inventing a result."

        internal const val TOOL_USE_ENFORCEMENT_GUIDANCE =
            "# Tool use\n" +
                    "You MUST use your tools to take action — do not describe what you would do " +
                    "without actually doing it. When you say you will perform an action, you MUST " +
                    "immediately make the corresponding tool call in the same response. Never end " +
                    "your turn with a promise of future action — execute it now.\n" +
                    "Every response should either (a) contain tool calls that make progress, or " +
                    "(b) deliver a final result to the user."

        internal const val EXECUTION_RULES_GUIDANCE =
            "# Execution rules\n" +
                    "Tool actions may be blocked by Zafiro's app-level execution rules, which are " +
                    "user-configurable in Zafiro settings — not system restrictions. A block means " +
                    "the user declined the action or the rule is too strict; the user can adjust " +
                    "the rule in Zafiro settings. Do not describe blocks as system policy."

        internal const val MEMORY_GUIDANCE =
            "You have persistent memory across sessions. Save durable facts using the memory " +
                    "tool: user preferences, environment details, tool quirks, and stable conventions. " +
                    "Memory is injected into every turn, so keep it compact and focused on facts that " +
                    "will still matter later.\n" +
                    "Prioritize what reduces future user steering — the most valuable memory is one " +
                    "that prevents the user from having to correct or remind you again. " +
                    "User preferences and recurring corrections matter more than procedural task details.\n" +
                    "Do NOT save task progress, session outcomes, completed-work logs, or temporary TODO " +
                    "state to memory; use conversation history to recall those from past interactions. " +
                    "Specifically: do not record PR numbers, issue numbers, commit SHAs, 'fixed bug X', " +
                    "'submitted PR Y', 'Phase N done', file counts, or any artifact that will be stale " +
                    "in 7 days. If a fact will be stale in a week, it does not belong in memory. " +
                    "If you've discovered a new way to do something, solved a problem that could be " +
                    "necessary later, save it as a skill with python — write a SKILL.md under " +
                    "the skills directory (see the skill-creator skill).\n" +
                    "Write memories as declarative facts, not instructions to yourself. " +
                    "'User prefers concise responses' ✓ — 'Always respond concisely' ✗. " +
                    "'Project uses pytest with xdist' ✓ — 'Run tests with pytest -n 4' ✗. " +
                    "Imperative phrasing gets re-read as a directive in later sessions and can " +
                    "cause repeated work or override the user's current request. Procedures and " +
                    "workflows belong in skills, not memory."

        internal const val SKILLS_GUIDANCE =
            "# Skills\n" +
                    "Skills contain specialized knowledge — API endpoints, tool-specific " +
                    "commands, and proven workflows that outperform general-purpose approaches. " +
                    "Load the skill even if you think you could handle the task with basic " +
                    "tools. Skills also encode the user's preferred approach, conventions, " +
                    "and quality standards — load them even for tasks you already know how " +
                    "to do, because the skill defines how it should be done here.\n" +
                    "load_skill returns the skill's SKILL.md content; if it exceeds the limit, " +
                    "the result ends with the absolute path to the file — use shell to read " +
                    "the full content from there."
    }
}
