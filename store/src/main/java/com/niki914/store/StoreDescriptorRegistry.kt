package com.niki914.store

object StoreDescriptorRegistry {

    const val WEB_SETTINGS_ID = "web_settings"
    const val LOCAL_SETTINGS_ID = "local_settings"
    const val AGENT_REGISTRY_ID = "agents.registry"
    const val LLM_CONFIGS_ID = "llm.saved_configs"
    const val LLM_MODEL_FACTS_ID = "llm.model_facts"
    const val OPENROUTER_CATALOG_ID = "llm.openrouter_catalog"
    const val AGENT_MAIN_MEMORY_ID = "agent.main.memory"
    const val TOOLS_BUILTIN_ID = "tools.builtin"
    const val TOOLS_PY_ID = "tools.py"
    const val TOOLS_MCP_SERVERS_ID = "tools.mcp.servers"
    const val TOOLS_TEXT_ACTIONS_ID = "tools.text_actions"
    const val TOOLS_MCP_SERVICE_ID = "tools.mcp.service"
    const val RULES_EXECUTION_ID = "rules.execution"
    const val RULES_TAKEOVER_ID = "rules.takeover"
    const val APP_STATE_ID = "app.state"
    const val AGENT_CONFIG_PREFIX = "agent.config."
    const val MAIN_AGENT_ID = "main"

    private val safeAgentIdPattern = Regex("[a-z][a-z0-9_-]{1,31}")

    private val staticDescriptors = listOf(
        StoreDescriptor(WEB_SETTINGS_ID, "settings/hooks.json"),
        StoreDescriptor(LOCAL_SETTINGS_ID, "local_settings.json"),
        StoreDescriptor(AGENT_REGISTRY_ID, "settings/agents/registry.json", """{"agents":[]}"""),
        StoreDescriptor(LLM_CONFIGS_ID, "settings/llm/saved_configs.json"),
        StoreDescriptor(
            LLM_MODEL_FACTS_ID,
            "settings/llm/model_facts.json",
            """{"facts":{}}"""
        ),
        StoreDescriptor(
            OPENROUTER_CATALOG_ID,
            "settings/llm/openrouter_catalog.json",
            """{"fetched_at":0,"models":{}}"""
        ),
        StoreDescriptor(
            AGENT_MAIN_MEMORY_ID,
            "settings/agents/main/memory.json",
            // 内置记忆：随 APK 发布。文件存在时以文件为准，用户改动不会被覆盖。
            """{"memories":[{"id":"mem_1792471151876_0","content":"Settings root for this Evergrow debug build is /data/user/0/com.niki914.zafiro.evergrow.debug/files/settings/ (upstream Zafiro uses .../com.niki914.zafiro/files/settings/); skills root is that same files/skills/.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_1","content":"Evergrow is the user's personally maintained fork of Zafiro by niki914 (MIT): repo github.com/donal100/Evergrow, package com.niki914.zafiro.evergrow.debug, docs in /storage/emulated/0/Evergrow/docs/.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_2","content":"Evergrow/Zafiro identity rule: applicationId must stay inside the com.niki914.zafiro.* namespace. XRes.kt classifies the package; a foreign id becomes AppType.Unknown → Transport.Unreachable → settings writes fail → crash ~1.2s after launch.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_3","content":"Device shell (Evergrow debug build) runs as its own unprivileged app UID: no su, `input` keyevent/tap blocked (INJECT_EVENTS), settings/wm/dumpsys permission-denied. Read state through the accessibility tree and screenshots; screen is 1200x2397 px.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_4","content":"Both com.niki914.zafiro (upstream) and com.niki914.zafiro.evergrow.debug are installed; only one should hold the accessibility service at a time, or results are confusingly partial.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_5","content":"Secrets live in Evergrow's private files/tool_output/: evergrow/.token (GitHub PAT, account donal100, push+admin on donal100/EverGrow, mode 0600), serpapi/.token, openrouter/.token, qwen/payg.token. Never copy a token into shared storage. The upstream Zafiro copy of evergrow/.token is unreadable (different UID).","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_6","content":"QwenCloud keys: Pay-As-You-Go (sk-ws-) authenticates against https://maas.qwencloudapi.com/compatible-mode/v1 (ASR: model qwen3-asr-flash, audio as base64 data URI). 'Token Plan' keys (sk-sp-….<ECDSA sig>) only work against https://token-plan.maas.qwencloudapi.com/compatible-mode/v1 — the plain gateways answer 401 invalid_api_key for them.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_7","content":"This device has working on-device speech recognition (provider com.google.android.tts/GoogleTTSRecognitionService) and one TTS engine, so speech-to-text can run locally without a cloud ASR API.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_8","content":"Android's on-device SpeechRecognizer can transcribe supplied audio only with all three: a ParcelFileDescriptor pipe fed in real time (a finished file yields nothing), EXTRA_SEGMENTED_SESSION=true, and reading text from onPartialResults. Write-up: repo docs/android-offline-asr-from-audio.md.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_9","content":"Evergrow voice pipeline as built: AudioRecord 16 kHz mono WAV recorder (MediaRecorder's m4a was rejected by the ASR API above ~130 KB), Qwen ASR transcription that auto-sends, and a Qwen-first reply speaker (qwen3-tts-flash, voice Cherry) falling back to on-device TTS. The old composer speak button recorded AAC .m4a; that is superseded.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_10","content":"Evergrow listen mode (build #148+) is stable: recogniser kept warm across pill toggles, 1.8s endpoint silence, archive saved from true VAD onset, rolling 5-minute wav segments in files/voice_ring read by py_voice_archive (≈1.6s per window). Voice transcripts come from one continuous dictation session, so they are normally complete.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_11","content":"py_voice_archive is a diagnostic for suspect ACCURACY (a name, number or translation that looks plausible but might be wrong), not a habit for correct transcripts.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_12","content":"Barge-in (user speech interrupting the spoken reply) is enabled only with headphones connected; on the phone speaker the mic hears the speaker, so it stays off. The listening pill is the manual interrupt.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_13","content":"Evergrow UI: the composer's text field only gets the width left after buttons, so a fourth button in the compact row squeezes it to zero — keep it attach|text|mic|send. Voice mode toggles from the top-bar '···' menu or a Speak pill in the expanded composer row.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_14","content":"Evergrow voice-reply pitfalls: HomeChatUiState is rebuilt from scratch on new/load conversation and silently drops fields like voiceMode (carry them over explicitly); the speak hook must read latestUiState (rememberUpdatedState), not the captured uiState; reply text can trail isGenerating=false so retry briefly. No logcat on this device — write diagnostics to filesDir/voice_debug.log.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_15","content":"Search routing that works from Hong Kong: Google results via SerpApi (files/tool_output/serpapi/.token, tool py_google_search, ~1s, gives organic results, top stories, ai_overview) — used freely, not rationed. Chinese/HK content → DeepSeek /v1/tools/search; news headlines → Google News RSS + SCMP rss/91 (rss/4 is SCMP's China section). gemini/gpt models return 403 not-available-in-region via OpenRouter; deepseek/deepseek-chat:online works.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_16","content":"Evergrow CI builds land on the rolling release asset github.com/donal100/EverGrow/releases/download/debug-latest/evergrow-debug.apk; stage it to /storage/emulated/0/Download/evergrow-debug.apk. Release-asset downloads often truncate at ~10 MB — resume with a Range request, then verify size and zip integrity before staging. Build/release procedure lives in the evergrow-dev skill.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_17","content":"The work tree at files/work/donal100-Evergrow-3184eba is a snapshot of an OLD commit — pushing a whole file from it verbatim reverts newer upstream changes. Always fetch the file's current content from the branch HEAD via the Contents API, apply only the delta, then push.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_18","content":"deepseek-flash has a 1,048,576-token context and a default completion budget of 393,216, so a long chat full of large tool dumps can exceed the limit and fail with 'maximum context length'. Keep tool output trimmed; the model config is files/settings/llm/saved_configs.json (cfg-f602a4d8eaa7, https://api.deepseek.com/responses, openai-responses, thinking_level minimal, max_tokens 65536).","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_19","content":"Chaquopy pitfall: an exception raised inside a Python Java-interface callback (e.g. a dynamic_proxy LocationListener) propagates to the Java main thread and crashes the app with PyException. Wrap every callback body in try/except; Android 12+ also dispatches onLocationChanged(List<Location>) to the same method, so it can receive an ArrayList.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_20","content":"Zafiro custom Python tool edits (py_meta_tools write) are not always live immediately: a stale module keeps running until the app process restarts, so verify a change by re-running it and checking for new output. py_ tools must PRINT their result — return values are not surfaced. A Kotlin foreground service must call startForeground() within 5s of startForegroundService(), even for a STOP action; use Context.stopService() instead.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_21","content":"Compaction recall: py_history_search greps the whole stored conversation archive (AND terms, newest first) and returns entry ids + snippets; py_history_show opens an id in full with neighbours (full=True for untruncated tool output). Both read conversation_history.db read-only, so compaction never destroys evidence — search before concluding something was never discussed. Pattern from hermes-lcm (MIT) lcm_grep/lcm_expand.","created_at":1792471151876,"updated_at":1792471151876},{"id":"mem_1792471151876_22","content":"Evergrow has its own calendar (no Google Calendar OAuth): ScheduleStore JSON at filesDir/schedule.json, AlarmManager alarms, notifications via ScheduleReceiver, boot re-arm; agent tool py_calendar. One-off events also arm a 24h-ahead prep alarm. User expects proactivity: mention upcoming items when a conversation starts or an event nears.","created_at":1792471151876,"updated_at":1792471151876}]}"""
        ),
        StoreDescriptor(
            TOOLS_BUILTIN_ID,
            "settings/tools/builtin_tools.json",
            """{"version":2,"enabled":{}}"""
        ),
        StoreDescriptor(TOOLS_PY_ID, "settings/tools/custom_py_tools.json", """{"tools":[]}"""),
        StoreDescriptor(
            TOOLS_MCP_SERVERS_ID,
            "settings/tools/mcp/servers.json",
            """{"servers":[]}"""
        ),
        StoreDescriptor(
            TOOLS_TEXT_ACTIONS_ID,
            "settings/tools/text_actions.json",
            // 内置两条 action 写在默认文档里：读不到文件时 readJson 直接回落 defaultJson，
            // 用户一改就落盘、删了不会被补回（区别于 seedPyTools 的复活语义）。
            """{"actions":[{"name":"Explain Screen","prompt":"Explain what is currently on the screen based on the screenshot and selected text.","enabled":true,"requires_screenshot":true,"run_in_background":false},{"name":"Translate","prompt":"Translate the following text into English (or Chinese if already English).","enabled":true,"requires_screenshot":false,"run_in_background":false}]}"""
        ),
        StoreDescriptor(
            TOOLS_MCP_SERVICE_ID,
            "settings/tools/mcp/service.json",
            """{"enabled":false,"port":8791,"host":"127.0.0.1","bearerToken":"","exposedTools":["screenshot","launch_app","find_installed_apps","open_uri","load_skill","notify","view_image","memory"],"keepScreenOn":true}"""
        ),
        StoreDescriptor(
            RULES_EXECUTION_ID,
            "settings/rules/execution_rules.json",
            """{"rules":[]}"""
        ),
        StoreDescriptor(
            RULES_TAKEOVER_ID,
            "settings/rules/takeover_rules.json",
            """{"rules":[]}"""
        ),
        StoreDescriptor(APP_STATE_ID, "settings/app_state.json")
    )

    private val staticDescriptorById = staticDescriptors.associateBy(StoreDescriptor::id)

    fun find(storeId: String): StoreDescriptor? {
        return staticDescriptorById[storeId]
    }

    fun require(storeId: String): StoreDescriptor {
        return resolveDynamic(storeId) ?: throw IllegalArgumentException("Unknown storeId=$storeId")
    }

    fun agentConfigStoreId(agentId: String): String? {
        return agentId.trim().lowercase()
            .takeIf { safeAgentIdPattern.matches(it) }
            ?.let { AGENT_CONFIG_PREFIX + it }
    }

    fun resolveDynamic(storeId: String): StoreDescriptor? {
        find(storeId)?.let { return it }

        val agentId = storeId.removePrefix(AGENT_CONFIG_PREFIX)
        if (agentId != storeId && safeAgentIdPattern.matches(agentId)) {
            return StoreDescriptor(
                id = storeId,
                relativePath = "settings/agents/$agentId/config.json"
            )
        }

        return null
    }

    fun allStatic(): List<StoreDescriptor> {
        return staticDescriptors
    }
}
