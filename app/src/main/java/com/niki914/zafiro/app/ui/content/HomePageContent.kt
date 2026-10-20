package com.niki914.zafiro.app.ui.content

import android.Manifest
import android.content.pm.PackageManager

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.niki914.logging.Logger
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.infra.LiquidDialog
import com.niki914.uikit.infra.LiquidMenuItem
import com.niki914.uikit.infra.LiquidPopover
import com.niki914.uikit.infra.LocalHasActiveDialog
import com.niki914.uikit.infra.LocalScreenBackdrop
import com.niki914.uikit.infra.PopoverAlignment
import com.niki914.uikit.infra.ProvideLiquidScreenContentForPreview
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.niki914.uikit.infra.LiquidGlassPresets
import com.niki914.uikit.infra.ReportTitleBarCollapsed
import com.niki914.uikit.infra.interaction.InteractiveHighlight
import com.niki914.uikit.infra.interaction.LiquidButtonInteractiveStyle
import com.niki914.uikit.infra.interaction.applyLiquidInteractiveTransform
import com.niki914.uikit.infra.component.MaterialTintLiquidButton
import com.niki914.uikit.infra.component.OptionRow
import com.niki914.uikit.infra.component.OptionSheet
import com.niki914.uikit.infra.liquidScreenTopPadding
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.uikit.infra.scrollbar.LiquidOverscrollContainer
import com.niki914.uikit.infra.scrollbar.LiquidOverscrollController
import com.niki914.uikit.infra.scrollbar.rememberLiquidOverscrollController
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.conversation.TurnBranchState
import com.niki914.zafiro.app.notification.TurnNotificationManager
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.PageChromeMenuItem
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.model.ToolPresentation
import com.niki914.zafiro.app.ui.model.home.ActionSource
import com.niki914.zafiro.app.ui.model.home.FileAttachReason
import com.niki914.zafiro.app.ui.model.home.HomeChatBlock
import com.niki914.zafiro.app.ui.model.home.HomeChatEffect
import com.niki914.zafiro.app.ui.model.home.HomeChatFile
import com.niki914.zafiro.app.ui.model.home.HomeChatImage
import com.niki914.zafiro.app.ui.model.home.HomeChatIntent
import com.niki914.zafiro.app.ui.model.home.HomeChatTurn
import com.niki914.zafiro.app.ui.model.home.HomeChatUiState
import com.niki914.zafiro.app.ui.model.home.HomeChatViewModel
import com.niki914.zafiro.app.ui.model.home.HomeToolState
import com.niki914.zafiro.app.ui.model.home.HomeToolStatus
import com.niki914.zafiro.app.audio.CallModeController
import com.niki914.zafiro.app.audio.VoiceReplySpeaker
import com.niki914.zafiro.app.ui.model.home.MessageActionsDisplay
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.repo.UpdateCheckHolder
import com.niki914.zafiro.repo.UpdateCheckResult
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.findService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * 冷启动后仅首次进入 Home 时抢焦点弹键盘；进程内不再重复
 * （从设置页/历史页返回不打断用户）。仿 StartupPageContent.demoHasPlayed 的写法。
 */
private var composerAutoFocusDone = false

/**
 * 已经念过的「回合 + 正文」。放在文件级而不是 remember{} 里：
 * 退出 Home 再进来、或页面重建时，旧回答不该被重新念一遍。
 */
private var voiceLatchTurnId = -1L
private var voiceLatchText = ""

/**
 * 通话模式开关。放文件级 Compose state 而不是 remember{}：页面重建、切去设置再回来
 * 都不该忘记「我正在打电话」。之前用 remember，结果菜单显示 off、麦克风其实还在听。
 * 进程重启会归零 —— 这是有意的，没人希望应用一启动就自己开麦。
 */
private val listenModeState = mutableStateOf(false)

/**
 * 发言器必须是进程级的：之前它跟着 composition 走，页面一重建 onDispose 就把
 * 后台播放线程 cancel 掉 —— 表现是「念了第一句就没了」。
 */
/** 念话的协程作用域：进程级，页面重建不打断。 */
private val speechScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private var sharedSpeaker: com.niki914.zafiro.app.audio.VoiceReplySpeaker? = null

private fun speakerFor(context: android.content.Context) = sharedSpeaker
    ?: com.niki914.zafiro.app.audio.VoiceReplySpeaker(context).also { sharedSpeaker = it }

/** 送合成的最小字符数：太小就会出现句间空隙（合成比播放慢）。 */
private const val MIN_SPEECH_CHUNK = 140

/** 通话模式期间使用的思考强度：语音对话要的是「马上开口」，不是深思熟虑。 */
private const val VOICE_THINKING_LEVEL = "minimal"

/** 进通话模式前用户原来的思考强度，退出时还原。 */
private var savedThinkingLevel: String? = null

/**
 * 流式播报：边生成边念完整的句子，第一句不用等全文。
 * 互斥锁保证「第一句」和「尾巴」不会同时占用播放器（Qwen 合成是并发的）。
 */
@Volatile private var streamingTurnId = -1L

/**
 * 当前正在念这一轮的任务。必须只允许一个：否则上一轮的尾巴和新一轮的开头会同时
 * 往队列里塞句子，播放器把它们交错念出来 —— 听感就是「念的根本不是我写的」。
 */
@Volatile private var streamingJob: kotlinx.coroutines.Job? = null
@Volatile private var speechCancelled = false

/** 上一次完整念完的正文。防止「回头找上一条有正文的消息」时把旧回答再念一遍。 */
@Volatile private var lastFullySpoken: String = ""

/**
 * 缩写词：句点后面不是句子结束。不做这一步的话 "e.g." 与 "Dr." 会把一句话劈成两半，
 * 合成出来就是断在词中间的两段音频。
 */
private val ABBREVIATIONS = setOf(
    "e.g", "i.e", "vs", "dr", "mr", "mrs", "ms", "st", "no", "approx", "etc", "cf", "al",
)

/** 找一段文本里下一个句末位置（从 from 起算，太短的片段先攒着）。 */
private fun sentenceEnd(text: String, from: Int): Int {
    var i = from
    while (i < text.length) {
        val c = text[i]
        if (c == '\n' && i - from >= 12) {
            // 列表项（"1. " / "- "）后面换行不算句末，否则会念成 "one" 就停
            return i + 1
        }
        if ((c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？') &&
            i - from >= 12 &&
            (i + 1 >= text.length || text[i + 1].isWhitespace())
        ) {
            if (c == '.') {
                val wordStart = text.lastIndexOf(' ', i - 1).let { if (it < from) from else it + 1 }
                val word = text.substring(wordStart, i).trim().trimStart('(', '"', '\'').lowercase()
                if (word in ABBREVIATIONS) {
                    i++
                    continue
                }
                // 单字母缩写（"J. Smith"）也跳过
                if (word.length == 1) {
                    i++
                    continue
                }
            }
            return i + 1
        }
        i++
    }
    return from
}
private const val AUTO_FOCUS_MAX_ATTEMPTS = 20
private const val AUTO_FOCUS_RETRY_INTERVAL_MILLIS = 150L

/** 附件入口的日志 TAG（只在相机失败这类异常路径上用）。 */
private const val ATTACH_LOG_TAG = "niki914_zafiro_Attach"

/** 图片多选的张数上限。远低于系统的 getPickImagesMaxLimit()，取一个够用且不炸上下文的数。 */
private const val PHOTO_PICK_MAX_ITEMS = 10

@Composable
fun HomePageContent(
    isCurrentPage: Boolean = true,
    selectedConversationId: String?,
    onConversationSelectionConsumed: (String) -> Unit,
    onActiveConversationChanged: (String?) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = pageViewModel<HomeChatViewModel>()
    val newConversationMenuLabel = stringResource(R.string.ui_home_menu_new_conversation)
    val settingsMenuLabel = stringResource(R.string.ui_settings_menu_entry)
    val latestViewModel by rememberUpdatedState(viewModel)
    val latestOnOpenHistory by rememberUpdatedState(onOpenHistory)
    val latestOnOpenSettings by rememberUpdatedState(onOpenSettings)
    val latestOnConversationSelectionConsumed by rememberUpdatedState(
        onConversationSelectionConsumed
    )
    val latestOnActiveConversationChanged by rememberUpdatedState(onActiveConversationChanged)
    val uiState by viewModel.uiStateFlow.collectAsState()
    val latestUiState by rememberUpdatedState(uiState)

    // 附件失败提示：一次性 effect（不占状态）。
    // 三条文案按原因分（D22）：解析不出路径 / 没拿到全局文件访问权 / 读不到文件。
    val attachToastContext = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is HomeChatEffect.FileAttachFailed -> Toast.makeText(
                    attachToastContext,
                    when (effect.reason) {
                        FileAttachReason.Unresolvable -> R.string.ui_home_attach_unresolvable
                        FileAttachReason.NoPermission -> R.string.ui_home_attach_no_permission
                        FileAttachReason.Unreadable -> R.string.ui_home_attach_unreadable
                    },
                    Toast.LENGTH_SHORT,
                ).show()

                HomeChatEffect.EmptyInputAttempted -> Toast.makeText(
                    attachToastContext,
                    R.string.ui_toast_empty_input,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val alwaysShowActions by XRepo.alwaysShowMessageActionsSetting.collectAsState()
    val actionsDisplay = if (alwaysShowActions) {
        MessageActionsDisplay.Always
    } else {
        MessageActionsDisplay.OnTap
    }
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val overscrollController = rememberLiquidOverscrollController()

    // Home 必须显式接管顶栏折叠信号：列表位置会由程序化驱动（自动贴底），且工具结果框
    // 自带滚动子树——两者都是壳层累积量看不见的位移；当向上超滚时，内容推入顶栏区，顶栏需变实体遮挡。
    ReportTitleBarCollapsed {
        listState.firstVisibleItemIndex > 0 ||
            listState.firstVisibleItemScrollOffset > 0 ||
            overscrollController.overscrollOffset.value < 0f
    }

    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    var isComposerFocused by remember { mutableStateOf(false) }
    val effectiveImeBottom = if (isComposerFocused) imeBottom else 0.dp
    // 统一视觉底间距：键盘关闭时与 composer 底距一致，不随 ime 放大——键盘打开时
    // 额外空间全部由 composerBottomPadding 提供，箭头/消息的呼吸空间保持固定
    val composerGap = navigationBottom + 20.dp
    val composerBottomPadding = (effectiveImeBottom + 12.dp).coerceAtLeast(composerGap)
    // composer 实测高度（默认 68dp = LiquidChatComposer 的 minHeight），首帧布局后回填
    val composerHeight = remember { mutableStateOf(68.dp) }
    val bottomThresholdPx = with(density) { 24.dp.roundToPx() }
    val lastTurn = uiState.turns.lastOrNull()
    // 贴底由「滚动位置 + contentPadding」共同决定：composer 几何（ime 动画、多行输入
    // 长高）变化时 padding 跟着变，也必须重新贴底，否则最后一条消息被 composer 遮住
    val bottomContentVersion = remember(
        uiState.turns.size,
        uiState.conversationVersion,
        lastTurn?.id,
        lastTurn?.blocks?.size,
        composerBottomPadding,
        composerHeight.value,
    ) {
        listOf(
            uiState.turns.size,
            uiState.conversationVersion,
            lastTurn?.id,
            lastTurn?.blocks?.size,
            composerBottomPadding,
            composerHeight.value,
        )
    }
    val isAtBottom by remember(listState, bottomThresholdPx) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem =
                layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            val viewportEnd = layoutInfo.viewportEndOffset
            lastVisibleItem.index == layoutInfo.totalItemsCount - 1 &&
                    lastVisibleItem.offset + lastVisibleItem.size <= viewportEnd + bottomThresholdPx
        }
    }
    val shouldFollowBottomState = rememberScrollFollowState(
        interactionSource = listState.interactionSource,
        isScrollInProgress = { listState.isScrollInProgress },
        isAtEnd = { isAtBottom },
    )
    var shouldFollowBottom by shouldFollowBottomState
    val dismissInputFocus = remember(focusManager, keyboardController) {
        {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }

    // 只在内容事件（bottomContentVersion）变化时跳转：恢复跟随本身不触发滚动，
    // 避免「小幅上滚停在阈值内 → 恢复跟随 → 无内容也被拉回底部」
    LaunchedEffect(bottomContentVersion) {
        if (shouldFollowBottom) {
            listState.scrollToItem(uiState.turns.size)
        }
    }
    LaunchedEffect(selectedConversationId) {
        val id = selectedConversationId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        latestViewModel.sendIntent(HomeChatIntent.LoadConversation(id))
        latestOnConversationSelectionConsumed(id)
        TurnNotificationManager.cancelNotification(id)
    }
    LaunchedEffect(uiState.currentConversationId) {
        latestOnActiveConversationChanged(uiState.currentConversationId)
    }
    DisposableEffect(isCurrentPage, uiState.currentConversationId) {
        findService<ApplicationService>()?.setHomeChatActive(isCurrentPage)
        TurnNotificationManager.visibleConversationId =
            if (isCurrentPage) uiState.currentConversationId else null
        onDispose {
            findService<ApplicationService>()?.setHomeChatActive(false)
            if (TurnNotificationManager.visibleConversationId == uiState.currentConversationId) {
                TurnNotificationManager.visibleConversationId = null
            }
        }
    }
    LaunchedEffect(isCurrentPage, uiState.currentConversationId, uiState.isGenerating) {
        if (isCurrentPage && !uiState.isGenerating) {
            val id = uiState.currentConversationId
            if (!id.isNullOrBlank()) {
                latestViewModel.sendIntent(HomeChatIntent.ClearUnread(id))
            }
        }
    }

    // 语音播报交给 agent 自己决定：它有两个工具（py_speak 用本机引擎，py_speak_qwen 用 Qwen 云端音色），
    // 由模型判断「这条回答要不要念、用哪个嗓子」。这里不再无条件朗读——否则会出现
    // 「自动朗读一次 + 工具再念一次」的双重声音。

    // —— 语音模式：回合结束后把最终回答念出来 ——
    // 只有真的观察到「生成中→结束」才念，避免打开历史会话时朗读旧消息；
    // 播的是 final 文本（流式中间态会被反复修正，边生成边念会不停改口）。
    // LocalContext.current 是 @Composable 调用，不能写在 remember{} 的 lambda 里（这条我犯过两次）
    val voiceContext = LocalContext.current.applicationContext
    val voiceSpeaker = remember { speakerFor(voiceContext) }
    // 播报不能挂在 composition 的 LaunchedEffect 上：效果随 key 重启，会把正在进行的
    // 云端合成一起取消（日志里的 "The coroutine scope left the composition" 就是这个），
    // 于是每次都被兜底的本机 TTS 抢着念 —— 听感上就是「一直重复」。给播报一条自己的命。
    val voiceScope = speechScope
    // —— 通话模式（Listen mode）：像打电话一样一直听着 ——
    // 用户说完一句 -> 走正常发消息链路 -> agent 回复 -> 念出来 -> 继续听。
    var listenMode by listenModeState
    // 翻译有两种用法：两个人对话（外放），或单向中译英（耳机，看片/听讲座用）
    // rememberSaveable：菜单开合或配置变化都可能重走 composition，普通 remember 会被重置成 null，
    // 那一下 LaunchedEffect 就走 else 分支，把刚开起来的口译立刻掐掉
    // （实测症状：toast 弹出，然后什么都没发生，socket 已经是 closed 1000）。
    var translateMode by rememberSaveable { mutableStateOf<String?>(null) }
    // 单人模式只出文字：这里轮询最近一条译文当字幕，省得为一个字符串再牵一条 Flow。
    var translateCaption by remember { mutableStateOf("") }
    var pendingUtterance by remember { mutableStateOf<String?>(null) }
    val latestListenMode by rememberUpdatedState(listenMode)
    val callModeController = remember {
        CallModeController(voiceContext) { text ->
            latestViewModel.sendIntent(HomeChatIntent.InputChanged(text))
            pendingUtterance = text
        }
    }
    // 页面销毁什么都不做：发言器和它的作用域都是进程级的。
    // （之前在这里 stop()/cancel()，每回合都丢掉排队中的句子。）
    // 是否正在播报：药丸按钮据此决定「停止说话」还是「关闭通话模式」
    var agentSpeaking by remember { mutableStateOf(false) }

    // —— 语音播报 ——
    // 已经整体搬到 VoiceOutbox：HomeChatViewModel 把最终文本直接推给它，
    // 这里只负责「挂上发言人」和「跟随通话模式开关」。不再从 UI 列表里推断文本。

    // 定位权限：py_gps 工具要靠它读原始 GPS。系统权限弹窗只能从 Activity 拉起，
    // Python 侧拿不到 Activity，所以在顶栏菜单里留一个入口。
    val locationContext = LocalContext.current
    var hasLocationAccess by remember {
        mutableStateOf(
            locationContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasLocationAccess = granted
        Toast.makeText(
            locationContext,
            if (granted) "Location access granted" else "Location access denied",
            Toast.LENGTH_SHORT,
        ).show()
    }

    // 转写文本要等它真的落进输入框状态再 Send，否则发出去的是上一轮草稿
    LaunchedEffect(pendingUtterance, uiState.input, uiState.isGenerating) {
        val utterance = pendingUtterance ?: return@LaunchedEffect
        if (uiState.isGenerating) return@LaunchedEffect
        if (uiState.input.trim() == utterance.trim()) {
            pendingUtterance = null
            latestViewModel.sendIntent(HomeChatIntent.Send)
        }
    }
    LaunchedEffect(listenMode) {
        com.niki914.zafiro.app.audio.VoiceOutbox.attach(voiceContext)
        com.niki914.zafiro.app.audio.VoiceOutbox.enabled = listenMode
        com.niki914.zafiro.app.audio.VoiceOutbox.beginTurn()
    }

    // 回合结束 -> 把麦克风交还通话模式。
    // 每识别完一句话控制器就 pause()，而 resume() 以前只有 25 秒的看门狗在调，
    // 听感上就是「回答完这句它就不理我了」。这里补回那一环：
    // 没在念 -> 直接恢复；正在念 -> 等队列念完（onSpeechIdle）再恢复。
    var wasGenerating by remember { mutableStateOf(uiState.isGenerating) }
    LaunchedEffect(uiState.isGenerating) {
        val outbox = com.niki914.zafiro.app.audio.VoiceOutbox
        if (!latestListenMode) {
            outbox.onSpeechIdle = null
            wasGenerating = uiState.isGenerating
            return@LaunchedEffect
        }
        outbox.onSpeechIdle = { callModeController.micOn(); callModeController.resume() }
        // 只有「真的开始念」才静音：生成思考的那几秒仍然在听，
        // 于是用户接着说的下一句（比如「顺便八点提醒我跑步」）不会掉进空窗。
        outbox.onSpeechStart = { callModeController.micOff() }
        // 麦克风一直在听：把即将念出的文字交给控制器，用来丢掉「听见自己」的转写
        outbox.onSpoken = { spoken -> callModeController.noteSpoken(spoken) }
        // 只在「真的从生成中走到结束」时才恢复收音。
        // 之前写成「只要当前不在生成就 resume」，于是打开通话模式的那一瞬间就调了一次，
        // 把正在工作的识别器状态抹成「没在听」，tick 立刻再开一个 -> error=5 风暴，
        // 识别器被打坏，之后就再也听不见人说话（日志里连续 3 秒每 250ms 一次 error=5）。
        val justFinished = wasGenerating && !uiState.isGenerating
        wasGenerating = uiState.isGenerating
        if (justFinished) {
            // 回合结束了：把还没念完的尾巴发出去，再等它念完才恢复收音
            outbox.flush()
            kotlinx.coroutines.delay(400)
            if (!outbox.isBusy()) callModeController.resume()
        }
    }

    // 打断只在戴耳机时启用：外放会让麦克风听见 agent 自己，产生自我打断。
    // 有耳机（有线/蓝牙/USB）时，用户说话就是真的在说话。
    LaunchedEffect(listenMode) {
        // 真正读「当前声音从哪儿出去」：配了耳机 ≠ 声音走耳机。
        // 外放时开打断，麦克风会听见 agent 自己，把每条回答都掐掉。
        fun hasEarphones(): Boolean = runCatching {
            val fmt = android.media.AudioFormat.Builder()
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16_000)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val track = android.media.AudioTrack.Builder()
                .setAudioFormat(fmt)
                .setAudioAttributes(attrs)
                .setBufferSizeInBytes(4096)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()
            val routed = runCatching { track.routedDevice?.type }.getOrNull()
            runCatching { track.release() }
            routed == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                routed == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                routed == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                routed == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                routed == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }.getOrDefault(false)

        // 打断暂时关闭：即使戴耳机，麦克风仍会收到足够强的信号（耳机漏音、
        // 呼吸、环境），把正在念的回答掐掉。等有真正的回声消除再打开。
        // 想打断时点药丸即可（只停播，不关通话模式）。
        callModeController.bargeIn = false
        callModeController.onBargeIn = {
            // 只有真的在放声音才算打断。否则这句话直接交给识别器，不能被吞掉。
            if (!voiceSpeaker.isBusy()) {
                false
            } else {
                speechCancelled = true
                voiceSpeaker.stop()
                callModeController.setSpeaking(false)
                callModeController.listenNow()
                true
            }
        }
        voiceSpeaker.note("barge-in=" + callModeController.bargeIn)
    }

    // —— 实时翻译 ——
    // 菜单负责开，药丸负责关：口译开着的时候药丸显示 Translate，点一下关掉。
    LaunchedEffect(translateMode) {
        val tr = com.niki914.zafiro.app.translate.Translator
        val mode = translateMode
        Logger.i("niki914_zafiro_TranslateUi", "translateMode effect fired: mode=" + mode)
        if (mode != null) {
            callModeController.stop()
            listenMode = false
            tr.startRealtime(voiceContext, mode)
        } else {
            tr.stop()
        }
    }

    // 字幕只在单人模式跑：双向模式仍然出声，不需要屏幕上的文字。
    LaunchedEffect(translateMode) {
        val mode = translateMode
        if (mode == null) {
            translateCaption = ""
            return@LaunchedEffect
        }
        while (true) {
            val text = com.niki914.zafiro.app.translate.Translator.lastTranslated.trim()
            if (text.isNotEmpty() && text != translateCaption) translateCaption = text
            delay(400)
        }
    }

    LaunchedEffect(listenMode) {
        if (listenMode) callModeController.start() else callModeController.stop()

        // 思考强度跟着模式走：通话模式降到 minimal，退出时还原用户原来的值。
        // 这样打字时仍然是 high，戴上耳机说话时才变快，不用改配置也不用重启。
        runCatching {
            val cfg = XRepo.llmConfigs.active() ?: return@runCatching
            if (listenMode) {
                if (savedThinkingLevel == null) savedThinkingLevel = cfg.thinkingLevel
                if (cfg.thinkingLevel != VOICE_THINKING_LEVEL) {
                    XRepo.llmConfigs.upsert(cfg.copy(thinkingLevel = VOICE_THINKING_LEVEL))
                }
            } else {
                val restore = savedThinkingLevel
                savedThinkingLevel = null
                // 只在「当前值还是我们改的那档」时还原：用户若在通话模式期间手动改过，
                // 那是他的新选择，别用旧值覆盖掉。
                if (restore != null &&
                    cfg.thinkingLevel == VOICE_THINKING_LEVEL &&
                    cfg.thinkingLevel != restore
                ) {
                    XRepo.llmConfigs.upsert(cfg.copy(thinkingLevel = restore))
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { callModeController.release() }
    }
    // 生成期间闭麦：省电，也避免把自己的念话/环境声当输入
    LaunchedEffect(uiState.isGenerating) {
        if (uiState.isGenerating) callModeController.pause()
    }

    LaunchedEffect(Unit) {
        latestViewModel.sendIntent(HomeChatIntent.RefreshConfigs)
    }

    DisposableEffect(Unit) {
        onDispose {
            if (latestUiState.isConfigMenuVisible) {
                latestViewModel.sendIntent(HomeChatIntent.DismissConfigMenu)
            }
        }
    }

    val rectSaver = remember {
        Saver<Rect?, List<Float>>(
            save = { rect -> rect?.let { listOf(it.left, it.top, it.right, it.bottom) } },
            restore = { list -> Rect(list[0], list[1], list[2], list[3]) },
        )
    }
    var titleAnchorBounds by rememberSaveable(stateSaver = rectSaver) {
        mutableStateOf<Rect?>(null)
    }

    val titleContent: @Composable () -> Unit = remember {
        {
            HomeTitleConfigChip(
                uiStateProvider = { latestUiState },
                onChipClick = {
                    latestViewModel.sendIntent(HomeChatIntent.OpenConfigMenu)
                },
                onAnchorBoundsChanged = { bounds ->
                    titleAnchorBounds = bounds
                },
            )
        }
    }

    val pageChromeContribution = remember(
        newConversationMenuLabel,
        settingsMenuLabel,
        titleContent,
        uiState.isConfigMenuVisible,
        uiState.voiceMode,
    ) {
        PageChromeContribution(
            titleContent = titleContent,
            isTitleOccluded = uiState.isConfigMenuVisible,
            actionsVisible = !uiState.isConfigMenuVisible,
            leftAction = TopBarActionSpec(
                icon = Icons.Default.History,
                onClick = {
                    if (latestUiState.isConfigMenuVisible) {
                        latestViewModel.sendIntent(HomeChatIntent.DismissConfigMenu)
                    }
                    latestOnOpenHistory()
                },
                contentDescription = "History Button",
            ),
            menuItems = listOf(
                PageChromeMenuItem(
                    key = "new_conversation",
                    title = newConversationMenuLabel,
                    onClick = {
                        if (latestUiState.isConfigMenuVisible) {
                            latestViewModel.sendIntent(HomeChatIntent.DismissConfigMenu)
                        }
                        latestViewModel.sendIntent(HomeChatIntent.NewConversation)
                    },
                ),
                PageChromeMenuItem(
                    key = "voice_mode",
                    title = if (uiState.voiceMode) "Voice replies: on" else "Voice replies: off",
                    onClick = {
                        if (latestUiState.isConfigMenuVisible) {
                            latestViewModel.sendIntent(HomeChatIntent.DismissConfigMenu)
                        }
                        latestViewModel.sendIntent(HomeChatIntent.ToggleVoiceMode)
                    },
                ),
                PageChromeMenuItem(
                    key = "translate_dialogue",
                    title = "Two people: English ⇄ Mandarin (subtitles)",
                    onClick = {
                        if (latestUiState.isConfigMenuVisible) {
                            latestViewModel.sendIntent(HomeChatIntent.DismissConfigMenu)
                        }
                        val granted = locationContext.checkSelfPermission(
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            Toast.makeText(locationContext, "Microphone permission needed", Toast.LENGTH_SHORT).show()
                        } else {
                            Logger.i("niki914_zafiro_TranslateUi", "menu pick: two-way")
                            translateMode = "two-way"
                            listenMode = false
                            voiceSpeaker.stop()
                            Toast.makeText(locationContext, "Two people: English ⇄ Mandarin", Toast.LENGTH_SHORT).show()
                        }
                    },
                ),
                PageChromeMenuItem(
                    key = "translate_dialogue_yue",
                    title = "Two people: English ⇄ Cantonese (subtitles)",
                    onClick = {
                        if (latestUiState.isConfigMenuVisible) {
                            latestViewModel.sendIntent(HomeChatIntent.DismissConfigMenu)
                        }
                        val granted = locationContext.checkSelfPermission(
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            Toast.makeText(locationContext, "Microphone permission needed", Toast.LENGTH_SHORT).show()
                        } else {
                            Logger.i("niki914_zafiro_TranslateUi", "menu pick: two-way-yue")
                            translateMode = "two-way-yue"
                            listenMode = false
                            voiceSpeaker.stop()
                            Toast.makeText(locationContext, "Two people: English ⇄ Cantonese", Toast.LENGTH_SHORT).show()
                        }
                    },
                ),
                PageChromeMenuItem(
                    key = "translate_cantonese",
                    title = "Translate Cantonese → English (subtitles)",
                    onClick = {
                        if (latestUiState.isConfigMenuVisible) {
                            latestViewModel.sendIntent(HomeChatIntent.DismissConfigMenu)
                        }
                        val granted = locationContext.checkSelfPermission(
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            Toast.makeText(locationContext, "Microphone permission needed", Toast.LENGTH_SHORT).show()
                        } else {
                            Logger.i("niki914_zafiro_TranslateUi", "menu pick: yue-en")
                            translateMode = "yue-en"
                            listenMode = false
                            voiceSpeaker.stop()
                            Toast.makeText(locationContext, "Cantonese → English subtitles", Toast.LENGTH_SHORT).show()
                        }
                    },
                ),
                PageChromeMenuItem(
                    key = "translate_earpiece",
                    title = "Translate Mandarin → English (subtitles)",
                    onClick = {
                        if (latestUiState.isConfigMenuVisible) {
                            latestViewModel.sendIntent(HomeChatIntent.DismissConfigMenu)
                        }
                        val granted = locationContext.checkSelfPermission(
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            Toast.makeText(locationContext, "Microphone permission needed", Toast.LENGTH_SHORT).show()
                        } else {
                            Logger.i("niki914_zafiro_TranslateUi", "menu pick: to-english")
                            translateMode = "to-english"
                            listenMode = false
                            voiceSpeaker.stop()
                            Toast.makeText(locationContext, "Mandarin → English subtitles", Toast.LENGTH_SHORT).show()
                        }
                    },
                ),
                PageChromeMenuItem(
                    key = "listen_mode",
                    // 不带 on/off：这个弹层打开后不会重绘，写状态就是在撒谎。
                    // 真实状态看 composer 里的药丸（红色 Listening）。
                    title = "Listen mode",
                    onClick = {
                        if (latestUiState.isConfigMenuVisible) {
                            latestViewModel.sendIntent(HomeChatIntent.DismissConfigMenu)
                        }
                        val next = !listenMode
                        val granted = locationContext.checkSelfPermission(
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (next && !granted) {
                            Toast.makeText(
                                locationContext,
                                "Microphone permission needed",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            // 真正的 start/stop 由 LaunchedEffect(listenMode) 负责，这里只翻开关
                            listenMode = next
                            if (!next) {
                                // 关掉通话模式要立刻静音，不然它还在念上一条
                                voiceSpeaker.stop()
                            }
                            Toast.makeText(
                                locationContext,
                                if (next) "Listening - ask away" else "Listen mode off",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ),
                PageChromeMenuItem(
                    key = "location_access",
                    title = if (hasLocationAccess) "Location access: on" else "Location access: off",
                    onClick = {
                        if (latestUiState.isConfigMenuVisible) {
                            latestViewModel.sendIntent(HomeChatIntent.DismissConfigMenu)
                        }
                        if (hasLocationAccess) {
                            Toast.makeText(locationContext, "Location already granted", Toast.LENGTH_SHORT).show()
                        } else {
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    },
                ),
                PageChromeMenuItem(
                    key = "settings",
                    title = settingsMenuLabel,
                    onClick = {
                        if (latestUiState.isConfigMenuVisible) {
                            latestViewModel.sendIntent(HomeChatIntent.DismissConfigMenu)
                        }
                        latestOnOpenSettings()
                    },
                ),
            ),
        )
    }
    val composerFocusRequester = remember { FocusRequester() }

    RegisterPageChrome(pageChromeContribution)

    val configs = remember(uiState.savedConfigs, uiState.activeConfigId) {
        val activeId = uiState.activeConfigId
        if (activeId.isNullOrBlank()) {
            uiState.savedConfigs
        } else {
            val (active, rest) = uiState.savedConfigs.partition { it.id == activeId }
            active + rest
        }
    }

    LiquidPopover(
        expanded = uiState.isConfigMenuVisible,
        onDismissRequest = {
            viewModel.sendIntent(HomeChatIntent.DismissConfigMenu)
        },
        anchorBounds = titleAnchorBounds,
        alignment = PopoverAlignment.BottomCenter,
        scrollable = configs.size > 5,
    ) {
        configs.forEach { config ->
            LiquidMenuItem(
                title = config.name,
                trailingText = config.modelId.takeIf { it != config.name },
                checked = config.id == uiState.activeConfigId,
                onClick = {
                    viewModel.sendIntent(HomeChatIntent.SelectConfig(config.id))
                },
            )
        }
    }

    val hasActiveDialog = LocalHasActiveDialog.current
    // 冷启动键盘焦点：仅进程内首次进入 Home、无草稿输入、不在加载中且当前无弹窗时抢焦点。
    // 标志在首次 effect 执行后即置位：无论聚焦成功、attempts 耗尽还是条件不满足
    // （有草稿/正在生成），都不再重试 —— 否则回答完成时 isGenerating 翻转会重启
    // 本 effect，导致"回答完成后自动弹键盘"（冷启动时未成功聚焦过的场景）。
    if (!composerAutoFocusDone && !uiState.isLoadingConversation && !hasActiveDialog) {
        LaunchedEffect(uiState.input.isBlank(), uiState.isGenerating) {
            if (uiState.input.isBlank() && !uiState.isGenerating) {
                repeat(AUTO_FOCUS_MAX_ATTEMPTS) {
                    delay(AUTO_FOCUS_RETRY_INTERVAL_MILLIS)
                    val focused = runCatching { composerFocusRequester.requestFocus() }.isSuccess
                    if (focused) {
                        keyboardController?.show()
                        composerAutoFocusDone = true
                        return@LaunchedEffect
                    }
                }
            }
            composerAutoFocusDone = true
        }
    }

    HomePageContentBody(
        uiState = uiState,
        actionsDisplay = actionsDisplay,
        listState = listState,
        overscrollController = overscrollController,
        composerBottomPadding = composerBottomPadding,
        composerGap = composerGap,
        composerHeight = composerHeight,
        composerFocusRequester = composerFocusRequester,
        followBottom = shouldFollowBottomState,
        isAtBottom = isAtBottom,
        onContentTap = dismissInputFocus,
        onInputChange = { value ->
            viewModel.sendIntent(HomeChatIntent.InputChanged(value))
        },
        onToggleVoiceMode = { viewModel.sendIntent(HomeChatIntent.ToggleVoiceMode) },
        onInterruptSpeech = {
            speechCancelled = true
            voiceSpeaker.stop()
            if (listenMode) callModeController.resume()
        },
        listenMode = listenMode,
        translateMode = translateMode != null,
        translateCaption = translateCaption,
        agentSpeaking = agentSpeaking,
        // 药丸就是一个开关：点一下关掉（并立刻静音），再点一下打开。
        // 不做「先停播再关麦」的花样 —— 用户要的是确定的行为。
        onPillTap = {
            com.niki914.zafiro.app.audio.VoiceOutbox.stop()
            when {
                translateMode != null -> translateMode = null   // 翻译：点药丸即关
                else -> listenMode = !listenMode
            }
        },
        onToggleListenMode = {
            val next = !listenMode
            if (!next) voiceSpeaker.stop()
            listenMode = next
        },
        onSendClick = {
            com.niki914.zafiro.app.audio.VoiceOutbox.markSend()
            val query = uiState.input.trim()
            val hasAttachments =
                uiState.pendingImages.isNotEmpty() || uiState.pendingFiles.isNotEmpty()
            if (query.isNotEmpty() || hasAttachments) {
                dismissInputFocus()
                shouldFollowBottom = true
            }
            viewModel.sendIntent(HomeChatIntent.Send)
        },
        onStopClick = {
            viewModel.sendIntent(HomeChatIntent.StopGenerating)
        },
        pendingImages = uiState.pendingImages,
        pendingFiles = uiState.pendingFiles,
        onImageAttached = { uri ->
            viewModel.sendIntent(HomeChatIntent.ImageAttached(uri))
        },
        onRemoveImage = { id ->
            viewModel.sendIntent(HomeChatIntent.ImageRemoved(id))
        },
        onCameraCaptured = { uri, path ->
            viewModel.sendIntent(HomeChatIntent.CameraCaptured(uri, path))
        },
        onFileAttached = { uri ->
            viewModel.sendIntent(HomeChatIntent.FileAttached(uri))
        },
        onRemoveFile = { id ->
            viewModel.sendIntent(HomeChatIntent.FileRemoved(id))
        },
        onComposerFocusChanged = { focused ->
            isComposerFocused = focused
        },
        onReGenerate = { id ->
            viewModel.sendIntent(HomeChatIntent.ReGenerateAt(id))
        },
        onFork = { id ->
            viewModel.sendIntent(HomeChatIntent.ForkAt(id))
        },
        onRewind = { id ->
            viewModel.sendIntent(HomeChatIntent.RewindAt(id))
        },
        expandedToolRuns = uiState.expandedToolRuns,
        expandedToolResults = uiState.expandedToolResults,
        expandedThinking = uiState.expandedThinking,
        onToggleToolRun = { turnId, runStartIndex ->
            viewModel.sendIntent(HomeChatIntent.ToggleToolRun(turnId, runStartIndex))
        },
        onToggleToolResult = { turnId, runStartIndex, toolIndex ->
            viewModel.sendIntent(HomeChatIntent.ToggleToolResult(turnId, runStartIndex, toolIndex))
        },
        onToggleThinking = { turnId, blockIndex ->
            viewModel.sendIntent(HomeChatIntent.ToggleThinking(turnId, blockIndex))
        },
        expandedActionTurnId = uiState.expandedActionTurnId,
        expandedActionSource = uiState.expandedActionSource,
        activeThinkingKey = uiState.activeThinkingKey,
        onToggleActionRow = { turnId, source ->
            viewModel.sendIntent(
                HomeChatIntent.ToggleActionRow(turnId, source)
            )
        },
        turnBranches = uiState.turnBranches,
        onSwitchTurnBranch = { turnId, delta ->
            viewModel.sendIntent(HomeChatIntent.SwitchTurnBranch(turnId, delta))
        },
    )

    val updateCheckResult by UpdateCheckHolder.result.collectAsState()
    val dismissUpdateScope = rememberCoroutineScope()
    UpdateAvailableDialog(
        update = updateCheckResult,
        onDismiss = { dismissUpdateScope.launch { UpdateCheckHolder.dismiss() } },
    )

    when (val request = uiState.pendingApproval) {
        is ApprovalRequest.ToolExecution -> {
            ToolPermissionDialog(
                request = request,
                onAllow = { viewModel.sendIntent(HomeChatIntent.ResolveApproval(ApprovalDecision.Allow)) },
                onDeny = { viewModel.sendIntent(HomeChatIntent.ResolveApproval(ApprovalDecision.Deny)) },
            )
        }

        is ApprovalRequest.ScreenControlConsent -> {
            ScreenControlConsentDialog(
                onAgree = { viewModel.sendIntent(HomeChatIntent.ResolveApproval(ApprovalDecision.Allow)) },
                onDeny = { viewModel.sendIntent(HomeChatIntent.ResolveApproval(ApprovalDecision.Deny)) },
            )
        }

        null -> {}
    }
}

/**
 * 更新弹窗：标题 + 版本提示 + GitHub release 原文（markdown，可滚动）。
 * 只负责展示；"同一版本只弹一次" 的记账在 [UpdateCheckHolder.dismiss]。
 */
@Composable
private fun UpdateAvailableDialog(
    update: UpdateCheckResult?,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    LiquidDialog(
        visible = update?.hasUpdate == true,
        onDismissRequest = onDismiss,
        dismissOnBackgroundTap = false,
        title = {
            Text(
                text = stringResource(R.string.update_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Text(
                text = stringResource(
                    R.string.update_dialog_text,
                    update?.remoteVersion.orEmpty(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        content = {
            val notes = update?.releaseNotes.orEmpty()
            if (notes.isNotBlank()) {
                // 说明单独限高滚动，标题与按钮常驻
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    AssistantOutputText(notes)
                }
            }
        },
        actions = {
            MaterialTintLiquidButton(
                text = stringResource(R.string.update_dialog_confirm),
                onClick = {
                    update?.releaseUrl?.takeIf { it.isNotBlank() }
                        ?.let(uriHandler::openUri)
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
            MaterialTintLiquidButton(
                text = stringResource(R.string.update_dialog_cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}

/**
 * 屏幕控制知情同意对话框（无障碍 + 悬浮窗）。
 */
@Composable
private fun ScreenControlConsentDialog(
    onAgree: () -> Unit,
    onDeny: () -> Unit,
) {
    LiquidDialog(
        visible = true,
        onDismissRequest = onDeny,
        dismissOnBackgroundTap = false,
        title = {
            Text(
                text = stringResource(R.string.screen_control_consent_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Text(
                text = stringResource(R.string.screen_control_consent_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            MaterialTintLiquidButton(
                text = stringResource(R.string.screen_control_consent_deny),
                onClick = onDeny,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            MaterialTintLiquidButton(
                text = stringResource(R.string.screen_control_consent_agree),
                onClick = onAgree,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    )
}

/**
 * CONFIRM 型执行规则的用户确认对话框（永不超时，PRD §3）。
 */
@Composable
private fun ToolPermissionDialog(
    request: ApprovalRequest.ToolExecution,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    LiquidDialog(
        visible = true,
        onDismissRequest = onDeny,
        dismissOnBackgroundTap = false,
        title = {
            Text(
                text = stringResource(R.string.tool_permission_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.tool_permission_request_intro, request.toolName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = request.command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(10.dp),
                )
                Text(
                    text = stringResource(
                        R.string.tool_permission_matched_rule,
                        request.ruleName,
                        request.matchedPattern,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        actions = {
            MaterialTintLiquidButton(
                text = stringResource(R.string.tool_permission_deny),
                onClick = onDeny,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            MaterialTintLiquidButton(
                text = stringResource(R.string.tool_permission_allow),
                onClick = onAllow,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomePageContentBody(
    uiState: HomeChatUiState,
    actionsDisplay: MessageActionsDisplay,
    listState: LazyListState,
    overscrollController: LiquidOverscrollController = rememberLiquidOverscrollController(),
    composerBottomPadding: Dp,
    composerGap: Dp,
    composerHeight: MutableState<Dp>,
    composerFocusRequester: FocusRequester,
    followBottom: MutableState<Boolean>,
    isAtBottom: Boolean,
    onContentTap: () -> Unit,
    onInputChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    pendingImages: List<HomeChatImage>,
    pendingFiles: List<HomeChatFile>,
    onImageAttached: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onCameraCaptured: (String, String) -> Unit,
    onFileAttached: (String) -> Unit,
    onRemoveFile: (String) -> Unit,
    onComposerFocusChanged: (Boolean) -> Unit,
    onReGenerate: (Long) -> Unit,
    onFork: (Long) -> Unit,
    onRewind: (Long) -> Unit,
    expandedToolRuns: Set<String>,
    expandedToolResults: Set<String>,
    expandedThinking: Set<String>,
    onToggleToolRun: (Long, Int) -> Unit,
    onToggleToolResult: (Long, Int, Int) -> Unit,
    onToggleThinking: (Long, Int) -> Unit,
    expandedActionTurnId: Long?,
    expandedActionSource: ActionSource?,
    activeThinkingKey: String? = null,
    onToggleActionRow: (Long, ActionSource) -> Unit,
    turnBranches: Map<Long, TurnBranchState> = emptyMap(),
    onSwitchTurnBranch: (Long, Int) -> Unit = { _, _ -> },
    onToggleVoiceMode: () -> Unit = {},
    onInterruptSpeech: () -> Unit = {},
    listenMode: Boolean = false,
    translateMode: Boolean = false,
    translateCaption: String = "",
    onToggleListenMode: () -> Unit = {},
    agentSpeaking: Boolean = false,
    onPillTap: () -> Unit = {},
) {

    // 底部避让总高：composer 底距 + 实测高度 + 统一视觉间距。列表贴底留白与箭头
    // 位置同源；键盘关闭时（composerBottomPadding == composerGap）即为
    // composerBottomPadding*2 + composerHeight，composer 顶上方留一个视觉间距
    val bottomClearance = composerBottomPadding + composerHeight.value + composerGap
    val density = LocalDensity.current
    val chatBackdrop = rememberLayerBackdrop()

    // 附件入口：加号 → 选项单（Photos / Camera / File / Folder）。
    val context = LocalContext.current
    var attachSheetVisible by remember { mutableStateOf(false) }
    var pendingCapture by remember { mutableStateOf<CameraTarget?>(null) }

    // 系统图片选择器（photo picker，无权限）：可多选，选完逐张 ingest 落盘 → pendingImages
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(PHOTO_PICK_MAX_ITEMS),
    ) { uris ->
        uris.forEach { onImageAttached(it.toString()) }
    }

    // SAF 文档 / 目录选择器：可多选；把 content uri 原样交给 VM，
    // 由 business:files 逐个解析路径 + 要全局文件访问权，这里不做判断
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.forEach { uri ->
            // 图片走图片管线（不需要路径、不需要存储权限），其余仍走文件引用
            val type = context.contentResolver.getType(uri)
            val asImage = isImportableImage(context, uri, type)
            Logger.i(ATTACH_LOG_TAG, "file picked uri=$uri type=$type asImage=$asImage")
            if (asImage) onImageAttached(uri.toString()) else onFileAttached(uri.toString())
        }
    }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let { onFileAttached(it.toString()) }
    }

    // 系统相机：输出目标是我们自己 cache 里的临时文件（FileProvider uri）。
    // 删那个临时文件由 VM 在 ingest 完成之后做，这里不能当场删（原文件还没被拷走）。
    //
    // 为什么不用 MediaStore：官方 TakePicture contract 只塞 EXTRA_OUTPUT，本就不带
    // URI 授权；补上 clipData + grant 之后依然不行——我们刚 insert 的记录是
    // IS_PENDING=1，pending 项对**非所有者**是 owner-only（MediaProvider 自己拦，
    // URI 授权也压不过），相机是另一个 uid，写不进去。真机实测：相机直接回 NOT_OK。
    // FileProvider 的 uri 落在我们自己的目录上，授权就是唯一的门槛，也是官方文档的配方。
    val takePicture = rememberLauncherForActivityResult(
        contract = GrantingTakePicture(),
    ) { result ->
        val target = pendingCapture
        pendingCapture = null
        if (result.resultCode == Activity.RESULT_OK && target != null) {
            onCameraCaptured(target.uri.toString(), target.file.path)
        } else {
            // 相机没写成（用户取消 / 相机拒绝）：清掉临时文件
            Logger.w(ATTACH_LOG_TAG, "camera result not ok resultCode=${result.resultCode}")
            target?.file?.let { runCatching { it.delete() } }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        LiquidOverscrollContainer(
            overscrollController = overscrollController,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(chatBackdrop)
                    .background(MaterialTheme.colorScheme.background)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onContentTap,
                    ),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    top = liquidScreenTopPadding(24.dp),
                    end = 20.dp,
                    bottom = bottomClearance,
                ),
            ) {
                itemsIndexed(
                    items = uiState.turns,
                    key = { _, turn -> turn.id },
                ) { index, turn ->
                    // User 气泡组内位置（渲染层：两个相邻 UserBubble 之间无任何内容即同组，见 userBubblePosition）
                    val position = userBubblePosition(uiState.turns, index)
                    // 组归一化：点组内任意一条都弹/聚合到组尾（操作行在组尾 turn 下渲染）
                    val tailIndex = userGroupTailIndex(uiState.turns, index)
                    val tailTurn = uiState.turns[tailIndex]
                    // 顶距：组中/组末用组内间隙与上一气泡连体；组首/单条维持 turn 分隔；分割线紧凑居中
                    val turnTopPad = when {
                        index == 0 -> Modifier
                        turn.isCompactedDivider -> Modifier.padding(top = 8.dp, bottom = 8.dp)
                        position == UserBubblePosition.GroupMid || position == UserBubblePosition.GroupLast ->
                            Modifier.padding(top = UserBubbleGap)

                        else -> Modifier.padding(top = TurnSeparator)
                    }
                    HomeChatTurnItem(
                        turn = turn,
                        userBubblePosition = position,
                        isLastTurn = index == uiState.turns.lastIndex,
                        actionsDisplay = actionsDisplay,
                        userGroupTailTurnId = tailTurn.id,
                        userGroupTappable = tailTurn.blocks.isNotEmpty() || tailIndex == uiState.turns.lastIndex,
                        userGroupText = userGroupText(uiState.turns, index),
                        onContentTap = onContentTap,
                        onReGenerate = onReGenerate,
                        onFork = onFork,
                        onRewind = onRewind,
                        expandedToolRuns = expandedToolRuns,
                        expandedToolResults = expandedToolResults,
                        expandedThinking = expandedThinking,
                        onToggleToolRun = onToggleToolRun,
                        onToggleToolResult = onToggleToolResult,
                        onToggleThinking = onToggleThinking,
                        expandedActionTurnId = expandedActionTurnId,
                        expandedActionSource = expandedActionSource,
                        activeThinkingKey = activeThinkingKey,
                        onToggleActionRow = onToggleActionRow,
                        isGenerating = uiState.isGenerating,
                        branchIndex = turnBranches[turn.id]?.current ?: 1,
                        branchCount = turnBranches[turn.id]?.total ?: 1,
                        onSwitchBranch = { delta -> onSwitchTurnBranch(turn.id, delta) },
                        modifier = turnTopPad.fillMaxWidth(),
                    )
                }
                item(key = "bottom_anchor") {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp),
                    )
                }
            }
        }

        // 待发附件条：图片与文件**同一行**（两种卡同尺寸同形状），宽 = composer 本体，
        // 位于 composer 上方 8dp；声明在 composer 之前，万一重合 composer 层级更高盖住它们。
        // composer 拉长/被 IME 顶起时随 composerHeight/composerBottomPadding 精确跟随。
        // 不可点击（TODO: 后续接入点开大图 / 打开文件）
        if (pendingImages.isNotEmpty() || pendingFiles.isNotEmpty()) {
            HomeChatAttachmentRow(
                images = pendingImages,
                files = pendingFiles,
                cardSize = 60.dp,
                cornerRadius = 18.dp,
                onRemoveImage = onRemoveImage,
                onRemoveFile = onRemoveFile,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 20.dp, end = 20.dp)
                    .fillMaxWidth()
                    .padding(bottom = composerBottomPadding + composerHeight.value + 8.dp),
            )
        }

        // 单人模式的字幕条：贴在 composer 上方，读讲座/看片时眼睛有的看。
        if (translateCaption.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 20.dp, end = 20.dp)
                    .padding(bottom = composerBottomPadding + composerHeight.value + 12.dp),
            ) {
                Text(
                    text = translateCaption,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        CompositionLocalProvider(
            LocalScreenBackdrop provides chatBackdrop,
        ) {
            LiquidChatComposer(
                value = uiState.input,
                onValueChange = onInputChange,
                onSendClick = onSendClick,
                onStopClick = onStopClick,
                isGenerating = uiState.isGenerating,
                contextProgress = uiState.contextProgress,
                pendingImages = pendingImages,
                pendingFiles = pendingFiles,
                onAttachImageClick = { attachSheetVisible = true },
                voiceMode = uiState.voiceMode,
                onToggleVoiceMode = onToggleVoiceMode,
                onInterruptSpeech = onInterruptSpeech,
                listenMode = listenMode,
                translateMode = translateMode,
                onToggleListenMode = onToggleListenMode,
                agentSpeaking = agentSpeaking,
                onPillTap = onPillTap,
                backdrop = chatBackdrop,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onFocusChanged { focusState ->
                        onComposerFocusChanged(focusState.hasFocus)
                    }
                    .focusRequester(composerFocusRequester)
                    .padding(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = composerBottomPadding,
                    )
                    // 放在 padding 之后：只测 composer 本体高度，不含底边距
                    .onSizeChanged { size ->
                        composerHeight.value = with(density) { size.height.toDp() }
                    },
            )
        }

        // 解除贴底锚定且不在底部时出现：点击恢复跟随并平滑滚回底部
        val scrollToBottomScope = rememberCoroutineScope()
        val scrollToBottomInteractiveHighlight = remember(scrollToBottomScope) {
            InteractiveHighlight(animationScope = scrollToBottomScope)
        }
        val isDarkTheme = LocalAppDarkTheme.current
        val scrollToBottomShape = CircleShape
        val scrollToBottomSurfaceColor = LiquidGlassPresets.ScrollToBottom.surfaceColor(isDarkTheme, MaterialTheme.colorScheme)

        AnimatedVisibility(
            visible = !followBottom.value && !isAtBottom,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomClearance),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .drawBackdrop(
                        backdrop = chatBackdrop,
                        shape = { scrollToBottomShape },
                        highlight = { LiquidGlassPresets.ScrollToBottom.highlight(isDarkTheme) },
                        shadow = { LiquidGlassPresets.ScrollToBottom.shadow(isDarkTheme) },
                        effects = {
                            LiquidGlassPresets.ScrollToBottom.applyEffects(this, isDarkTheme)
                        },
                        layerBlock = {
                            applyLiquidInteractiveTransform(
                                style = LiquidButtonInteractiveStyle,
                                pressProgress = scrollToBottomInteractiveHighlight.pressProgress,
                                offset = scrollToBottomInteractiveHighlight.offset,
                                size = size,
                            )
                        },
                        onDrawSurface = {
                            drawRect(scrollToBottomSurfaceColor)
                        },
                    )
                    .clip(scrollToBottomShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                    ) {
                        followBottom.value = true
                        scrollToBottomScope.launch {
                            listState.animateScrollToItem(uiState.turns.size)
                        }
                    }
                    .then(scrollToBottomInteractiveHighlight.gestureModifier)
                    .then(
                        if (LiquidButtonInteractiveStyle.highlightEnabled) {
                            scrollToBottomInteractiveHighlight.modifier
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_down),
                    contentDescription = "Scroll To Bottom Icon",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (uiState.isLoadingConversation) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        OptionSheet(
            visible = attachSheetVisible,
            onDismissRequest = { attachSheetVisible = false },
            title = stringResource(R.string.ui_home_attach_sheet_title),
        ) { dismissThen ->
            OptionRow(
                title = stringResource(R.string.ui_home_attach_photos),
                leadingContent = { Icon(Icons.Default.Image, contentDescription = null) },
                onClick = {
                    dismissThen {
                        photoPicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                },
            )
            // 相机项：不再有版本门。旧门存在的理由是「MediaStore 的 RELATIVE_PATH/IS_PENDING
            // 要 29+」，改用 FileProvider 之后这个前提没了，26+ 都能跑。
            OptionRow(
                title = stringResource(R.string.ui_home_attach_camera),
                leadingContent = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                onClick = {
                    dismissThen {
                        val target = createCameraTarget(context)
                        if (target == null) {
                            Logger.w(ATTACH_LOG_TAG, "camera: output target unavailable")
                        } else {
                            pendingCapture = target
                            takePicture.launch(target.uri)
                        }
                    }
                },
            )
            // File / Folder 全版本放出：30+ 走 all-files，<30 走运行时权限（机制与版本分叉见 PermissionSpec）
            OptionRow(
                title = stringResource(R.string.ui_home_attach_file),
                leadingContent = { Icon(Icons.Default.Description, contentDescription = null) },
                onClick = { dismissThen { filePicker.launch(arrayOf("*/*")) } },
            )
            OptionRow(
                title = stringResource(R.string.ui_home_attach_folder),
                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                onClick = { dismissThen { folderPicker.launch(null) } },
            )
        }
    }
}

/**
 * 相机输出目标：FileProvider 的 uri（给相机写）+ 它背后的临时文件（ingest 完成后删）。
 * 文件落在 `cacheDir/capture/`（见 `res/xml/file_paths.xml`）。
 */
private data class CameraTarget(val uri: Uri, val file: File)

/** cache 下専给相机输出的子目录（与 `file_paths.xml` 的 `path` 一致）。 */
private const val CAMERA_CAPTURE_DIR = "capture"

/**
 * 文件入口分流：这个 uri 能不能走图片管线。
 *
 * 判据优先看后缀（docId 通常就带着文件名），没有后缀才回落到 provider 报的 MIME——
 * 「下载」抽屉里在 DownloadManager 数据库中的文件就是裸数字 docId，只能靠 MIME。
 */
private fun isImportableImage(context: Context, uri: Uri, declaredType: String?): Boolean {
    val extension = uri.lastPathSegment.orEmpty()
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
    val isImage = if (extension.isNotEmpty()) {
        extension in IMPORTABLE_IMAGE_EXTENSIONS
    } else {
        declaredType?.startsWith("image/") == true
    }
    if (!isImage) return false
    // 超过图片管线上限的图会被 ingest 拒，而拒了之后草稿项是**静默消失**的，
    // 比「当一个文件附件」更糟。体积未知（-1）时放行，交给一致的上限去判。
    val size = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull()
    return size == null || size <= IMAGE_MAX_BYTES
}

/**
 * 分流时认作图片的后缀。
 *
 * 不含 HEIC / HEIF：26/27 的 `BitmapFactory` 解不了，进来会在图片管线里静默失败，
 * 所以让它们继续走文件路径（= 今天的形态）。
 */
private val IMPORTABLE_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

/** 与图片管线的 `ImageFormat.MAX_IMAGE_BYTES` 同口径（那个常数在 agent-runtime 内部）。 */
private const val IMAGE_MAX_BYTES = 12 * 1024 * 1024L

/**
 * 造一个相机可以写的输出目标。路径与 provider 都对不上时返回 null（理论上不会）。
 */
private fun createCameraTarget(context: Context): CameraTarget? = runCatching {
    val dir = File(context.cacheDir, CAMERA_CAPTURE_DIR).apply { mkdirs() }
    val file = File(dir, "zafiro_capture_${System.currentTimeMillis()}.jpg")
    CameraTarget(
        uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
        file = file,
    )
}.onFailure {
    Logger.w(ATTACH_LOG_TAG, "camera target create failed error=${it.message}")
}.getOrNull()

/**
 * 带写授权的 [ActivityResultContract]。
 *
 * 官方 `TakePicture` 只塞 `EXTRA_OUTPUT`，不带 clipData、不带 grant 标志；FileProvider 的
 * uri 必须显式授权给相机（另一个 uid），否则相机打开它会被拒。所以这里照官方「Take photos」
 * 文档补上 clipData + `FLAG_GRANT_READ/WRITE_URI_PERMISSION`。
 *
 * 回传原始 [resultCode] 而不是 Boolean：排障时必须区分 CANCELED 与其它码，
 * 官方 contract 把它丢了。
 */
private class GrantingTakePicture : ActivityResultContract<Uri, ActivityResult>() {

    override fun createIntent(context: Context, input: Uri): Intent {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, input)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // setClipData 返回 void，不能链式；grant 标志要配 clipData 才会落到被授的 uri 上
        intent.clipData = ClipData.newUri(context.contentResolver, "camera_output", input)
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult =
        ActivityResult(resultCode, intent)
}

/**
 * 渲染层连续 User 气泡分组：两个相邻 UserBubble 之间有无内容，取决于上一 turn 的 blocks
 * 是否为空（内容夹在上一气泡与下一气泡之间）。规则：
 * - 对下一条黏（自身成为组首/组中）：自身 blocks 为空——自身内容会显示在自身气泡与下一条之间；
 * - 对上一条黏（自身成为组末/组中）：上一 turn blocks 为空。
 * 因此流式内容到达只会改变"自身→下一条"一侧，组首一侧的黏性由上一 turn 决定，组不随内容跳变。
 */
private fun userBubblePosition(turns: List<HomeChatTurn>, index: Int): UserBubblePosition {
    val current = turns.getOrNull(index)
    if (current == null || current.isCompactedDivider) return UserBubblePosition.Single
    val stickyUp = index > 0 && turns[index - 1].blocks.isEmpty() && !turns[index - 1].isCompactedDivider
    val stickyDown = index < turns.lastIndex && turns[index].blocks.isEmpty() && !turns[index + 1].isCompactedDivider
    return when {
        stickyUp && stickyDown -> UserBubblePosition.GroupMid
        stickyUp -> UserBubblePosition.GroupLast
        stickyDown -> UserBubblePosition.GroupFirst
        else -> UserBubblePosition.Single
    }
}

/**
 * 渲染层连续 User 气泡组（见 [userBubblePosition]）：index 所在组的组尾下标。
 * 组向下延伸的判定与 userBubblePosition 一致：自身 blocks 为空则下一气泡与本气泡相邻。
 */
internal fun userGroupTailIndex(turns: List<HomeChatTurn>, index: Int): Int {
    var tail = index
    while (tail < turns.lastIndex && turns[tail].blocks.isEmpty() && !turns[tail].isCompactedDivider && !turns[tail + 1].isCompactedDivider) tail++
    return tail
}

/** index 所在 User 组的组内全部用户文本：按序、双换行分隔、跳过空白（纯图片 turn）。 */
internal fun userGroupText(turns: List<HomeChatTurn>, index: Int): String {
    var head = index
    while (head > 0 && turns[head - 1].blocks.isEmpty() && !turns[head - 1].isCompactedDivider) head--
    val tail = userGroupTailIndex(turns, index)
    return turns.subList(head, tail + 1)
        .filterNot { it.isCompactedDivider }
        .map { it.userText }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
}

@Composable
private fun HomeChatTurnItem(
    turn: HomeChatTurn,
    userBubblePosition: UserBubblePosition,
    isLastTurn: Boolean,
    actionsDisplay: MessageActionsDisplay,
    /** 点组内任意一条时操作行归一到组尾 turn；非组尾成员仅作点击归一目标。 */
    userGroupTailTurnId: Long,
    /** 组尾 turn 的操作行资格（blocks 非空或为最后一条）。 */
    userGroupTappable: Boolean,
    /** 组内全部用户文本（双换行分隔），供组尾操作行复制。 */
    userGroupText: String,
    onContentTap: () -> Unit,
    onReGenerate: (Long) -> Unit,
    onFork: (Long) -> Unit,
    onRewind: (Long) -> Unit,
    expandedToolRuns: Set<String>,
    expandedToolResults: Set<String>,
    expandedThinking: Set<String>,
    onToggleToolRun: (Long, Int) -> Unit,
    onToggleToolResult: (Long, Int, Int) -> Unit,
    onToggleThinking: (Long, Int) -> Unit,
    expandedActionTurnId: Long?,
    expandedActionSource: ActionSource?,
    activeThinkingKey: String? = null,
    onToggleActionRow: (Long, ActionSource) -> Unit,
    isGenerating: Boolean,
    branchIndex: Int = 1,
    branchCount: Int = 1,
    onSwitchBranch: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (turn.isCompactedDivider) {
        CompactedDividerItem(
            isCompacting = turn.isCompacting,
            modifier = modifier,
        )
        return
    }

    val alwaysVisible = actionsDisplay == MessageActionsDisplay.Always
    // Agent 操作行：仅正生成中的末轮隐藏；已结束的旧 turn 常显（复制无害，
    // 重新生成/分叉/回退在生成中点按弹 toast，见 guarded 回调）
    val canToggleAction = turn.blocks.isNotEmpty() && (!isGenerating || !isLastTurn)
    // User 操作行资格：生成中同样放开（历史遗留修复：复制无害，重新生成/回退由 Controller 护栏拦截）；
    // 最后一条 turn 即使无内容也放开，使本条 query 仍可复制
    val userRowEligible = turn.blocks.isNotEmpty() || isLastTurn
    val isUserGroupTail = turn.id == userGroupTailTurnId

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun copyText(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
        }
        Toast.makeText(context, R.string.ui_toast_copied, Toast.LENGTH_SHORT).show()
    }

    fun toastGenerating() {
        Toast.makeText(context, R.string.ui_toast_generating, Toast.LENGTH_SHORT).show()
    }
    // 生成中：危险动作（重新生成/分叉/回退）弹 toast 提示，不透传给 Controller（其护栏会静默吞掉）
    val guardedReGenerate: (Long) -> Unit = { id ->
        if (isGenerating) toastGenerating() else onReGenerate(id)
    }
    val guardedFork: (Long) -> Unit = { id ->
        if (isGenerating) toastGenerating() else onFork(id)
    }
    val guardedRewind: (Long) -> Unit = { id ->
        if (isGenerating) toastGenerating() else onRewind(id)
    }

    val isActionExpanded = expandedActionTurnId == turn.id
    val actionSource = expandedActionSource
    var showActionRow by remember { mutableStateOf(false) }
    LaunchedEffect(isActionExpanded) {
        showActionRow = isActionExpanded
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(BlockSpacing),
    ) {
        // 附件行：图片与文件同一行（同尺寸同形状），镜像 UserMessageBubble 的
        // 对齐方式——BoxWithConstraints 右对齐，Row 贴内容宽、max 同 bubble（0.82f）；
        // 外层与卡片同圆角 clip——边缘卡被裁时仍呈圆角。多附件时初始 scroll=0 优先展示左边的，整行靠右。
        // 不可点击（TODO: 点开大图 / 打开文件）
        if (turn.images.isNotEmpty() || turn.files.isNotEmpty()) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                HomeChatAttachmentRow(
                    images = turn.images,
                    files = turn.files,
                    cardSize = 120.dp,
                    cornerRadius = UserBubbleCornerRadius,
                    modifier = Modifier.widthIn(max = maxWidth * 0.82f),
                )
            }
        }

        // 纯图片 turn：文本为空（isEmpty 而非 isBlank——历史里用户发过的空白文本
        // turn 仍显示气泡）；连 clickable 一起隐藏，空 bubble 点击弹操作行无意义
        if (turn.userText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            onContentTap()
                            // Always 模式永不收回；OnTap 模式点组内任意一条都归一到组尾
                            if (!alwaysVisible && userGroupTappable) {
                                onToggleActionRow(userGroupTailTurnId, ActionSource.User)
                            }
                        },
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                UserMessageBubble(text = turn.userText, position = userBubblePosition)
            }
        }

        AnimatedVisibility(
            visible = isUserGroupTail && userRowEligible &&
                    (alwaysVisible || (showActionRow && actionSource == ActionSource.User)),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            TurnActionRow(
                source = ActionSource.User,
                display = actionsDisplay,
                onCopy = {
                    copyText(userGroupText)
                },
                onReGenerate = { guardedReGenerate(turn.id) },
                onFork = { guardedFork(turn.id) },
                onRewind = { guardedRewind(turn.id) },
            )
        }

        // 内容区：turn 分隔补差（TurnSeparator - BlockSpacing）放在内容区顶部而非气泡底部，
        // 使气泡→用户操作行（复制按钮）的间距仅剩 BlockSpacing 12dp，与 markdown→操作行一致；
        // 组内成员（非 Single）不设补差，与下一气泡保持组内紧凑
        if (turn.blocks.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(
                    top = if (userBubblePosition == UserBubblePosition.Single) {
                        TurnSeparator - BlockSpacing
                    } else {
                        0.dp
                    }
                ),
                verticalArrangement = Arrangement.spacedBy(BlockSpacing),
            ) {
                var blockIndex = 0
                while (blockIndex < turn.blocks.size) {
                    // Collect consecutive Tool blocks into a run
                    val runStart = blockIndex
                    var runEnd = runStart
                    while (runEnd < turn.blocks.size && turn.blocks[runEnd] is HomeChatBlock.Tool) {
                        runEnd++
                    }
                    val runSize = runEnd - runStart
                    if (runSize >= 1) {
                        val statuses = turn.blocks.subList(runStart, runEnd)
                            .map { (it as HomeChatBlock.Tool).status }
                        val runKey = "${turn.id}_${runStart}"
                        val runResults = expandedToolResults
                            .filter { it.startsWith("${runKey}_") }
                            .mapNotNull { it.removePrefix("${runKey}_").toIntOrNull() }
                            .toSet()
                        ToolChain(
                            tools = statuses,
                            isExpanded = runKey in expandedToolRuns,
                            expandedResults = runResults,
                            onToggleRun = { onToggleToolRun(turn.id, runStart) },
                            onToggleResult = { ti ->
                                onToggleToolResult(turn.id, runStart, ti)
                            },
                            onContentClick = {
                                onContentTap()
                                if (!alwaysVisible && canToggleAction) {
                                    onToggleActionRow(turn.id, ActionSource.Agent)
                                }
                            },
                        )
                        blockIndex = runEnd
                    } else {
                        when (val block = turn.blocks[blockIndex]) {
                            is HomeChatBlock.Text -> {
                                if (block.text.isNotBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = {
                                                    onContentTap()
                                                    if (!alwaysVisible && canToggleAction) {
                                                        onToggleActionRow(
                                                            turn.id,
                                                            ActionSource.Agent
                                                        )
                                                    }
                                                },
                                            ),
                                    ) {
                                        AssistantOutputText(
                                            text = block.text,
                                        )
                                    }
                                }
                            }

                            is HomeChatBlock.Error -> {
                                val errorUi = toAssistantErrorUi(
                                    message = block.message,
                                    code = block.code,
                                    attempts = block.attempts,
                                )
                                CollapsibleBlock(
                                    icon = Icons.Filled.ErrorOutline,
                                    title = stringResource(errorUi.titleRes),
                                    isExpanded = true,
                                    onToggle = {},
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    nonCollapsible = true,
                                ) {
                                    val body = errorUi.bodyRes?.let { stringResource(it) }
                                        ?: errorUi.body.orEmpty()
                                    if (body.isNotEmpty()) {
                                        ToolResultText(
                                            text = body,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }

                            is HomeChatBlock.Retrying -> {
                                // 常展开、不可折叠（与 Error 块不同：retry 是进行中的转态，
                                // 收起无意义）；重试成功即整个块消失
                                CollapsibleBlock(
                                    icon = Icons.Filled.Refresh,
                                    title = stringResource(R.string.ui_home_retrying_title),
                                    isExpanded = true,
                                    onToggle = {},
                                    isRunning = true,
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    nonCollapsible = true,
                                ) {
                                    val count = stringResource(
                                        R.string.ui_home_retrying_attempt,
                                        block.attempt,
                                        block.maxAttempts,
                                    )
                                    ToolResultText(
                                        text = if (block.reason.isBlank()) count
                                        else "$count: ${block.reason.trim()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            is HomeChatBlock.Thinking -> {
                                // blockIndex 是 var，lambda 捕获按引用；先快照成 val 再进 lambda
                                val blockIndexNow = blockIndex
                                val thinkingKey = "${turn.id}_$blockIndexNow"
                                val isThinkingExpanded = thinkingKey in expandedThinking
                                val thinkingTitle =
                                    stringResource(R.string.ui_home_thinking_title)
                                CollapsibleBlock(
                                    icon = ToolPresentation.Thinking,
                                    title = thinkingTitle + ToolPresentation
                                        .previewOf(block.text)
                                        ?.let { " · $it" }
                                        .orEmpty(),
                                    isExpanded = isThinkingExpanded,
                                    onToggle = { onToggleThinking(turn.id, blockIndexNow) },
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = {
                                                    onContentTap()
                                                    if (!alwaysVisible && canToggleAction) {
                                                        onToggleActionRow(
                                                            turn.id,
                                                            ActionSource.Agent
                                                        )
                                                    }
                                                },
                                            ),
                                    ) {
                                        ToolResultText(
                                            text = block.text,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            // active 思考块展开时滚到底跟随；用户可手动滚动不锁
                                            autoScrollToEnd = isThinkingExpanded && thinkingKey == activeThinkingKey,
                                        )
                                    }
                                }
                            }

                            is HomeChatBlock.Tool -> {} // handled above
                        }
                        blockIndex++
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = (alwaysVisible && canToggleAction) ||
                    (showActionRow && actionSource == ActionSource.Agent),
            enter = expandVertically() + fadeIn(),
        ) {
            TurnActionRow(
                source = ActionSource.Agent,
                display = actionsDisplay,
                onCopy = {
                    val text = turn.blocks
                        .filterIsInstance<HomeChatBlock.Text>()
                        .joinToString("\n\n") { it.text }
                    copyText(text)
                },
                onReGenerate = { guardedReGenerate(turn.id) },
                onFork = { guardedFork(turn.id) },
                // 翻页器由该轮自身分支数驱动，各轮次独立显示
                branchIndex = branchIndex,
                branchCount = branchCount,
                onSwitchBranch = onSwitchBranch,
            )
        }
    }
}

@Composable
private fun CompactedDividerItem(
    isCompacting: Boolean,
    modifier: Modifier = Modifier,
) {
    val dots = if (isCompacting) {
        val dotCount by produceState(initialValue = 1) {
            while (true) {
                delay(400)
                value = (value % 3) + 1
            }
        }
        ".".repeat(dotCount)
    } else {
        ""
    }

    Row(
        modifier = modifier
            .padding(vertical = 6.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        )
        Text(
            text = if (isCompacting) {
                "${stringResource(R.string.ui_home_conversation_compacting)}$dots"
            } else {
                stringResource(R.string.ui_home_conversation_compacted)
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        )
    }
}

/**
 * 手势信任的贴底跟随状态机（外层聊天列表与内层 thinking/工具结果文本共用）。
 * 用户开始拖拽立即暂停跟随——流式新内容不再抢占滚动；
 * 滚动完全停止后按 [isAtEnd] 判定是否恢复跟随。
 * 返回 MutableState：发送消息等场景可主动置回 true 恢复跟随。
 */
@Composable
internal fun rememberScrollFollowState(
    interactionSource: InteractionSource,
    isScrollInProgress: () -> Boolean,
    isAtEnd: () -> Boolean,
): MutableState<Boolean> {
    val follow = remember { mutableStateOf(true) }
    var hasPendingUserScrollDecision by remember { mutableStateOf(false) }
    val isUserDragging by interactionSource.collectIsDraggedAsState()

    LaunchedEffect(isUserDragging) {
        if (isUserDragging) {
            follow.value = false
            hasPendingUserScrollDecision = true
        }
    }
    LaunchedEffect(interactionSource) {
        snapshotFlow { isScrollInProgress() }
            .collectLatest { isScrollInProgress ->
                if (!isScrollInProgress && hasPendingUserScrollDecision) {
                    follow.value = isAtEnd()
                    hasPendingUserScrollDecision = false
                }
            }
    }
    return follow
}

@Preview(
    name = "Home Page Preview",
    showBackground = true,
    widthDp = 420,
    heightDp = 900,
)
@Composable
private fun HomePageContentPreview() {
    BaseTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            HomePageContentBody(
                actionsDisplay = MessageActionsDisplay.OnTap,
                composerFocusRequester = remember { FocusRequester() },
                uiState = HomeChatUiState(
                    input = "继续分析",
                    turns = listOf(
                        HomeChatTurn(
                            id = 0L,
                            userText = "帮我检查一下当前工具状态。",
                            blocks = listOf(
                                HomeChatBlock.Text("I'll call the available tools first."),
                                HomeChatBlock.Tool(
                                    HomeToolStatus(
                                        callId = "tool-1",
                                        name = "read_session",
                                        state = HomeToolState.Succeeded,
                                    )
                                ),
                                HomeChatBlock.Tool(
                                    HomeToolStatus(
                                        callId = "tool-2",
                                        name = "update_config",
                                        state = HomeToolState.Running,
                                    )
                                ),
                                HomeChatBlock.Tool(
                                    HomeToolStatus(
                                        callId = "tool-3",
                                        name = "sync_mcp",
                                        state = HomeToolState.Failed,
                                    )
                                ),
                                HomeChatBlock.Error("MCP 工具调用失败，请检查服务配置。"),
                                HomeChatBlock.Text("I've done the check and summarized the result."),
                            ),
                        ),
                        // 连续用户消息组：失败回合（错误卡已随新回合清除）→ 再发一条，两条纯 User 连成一组
                        HomeChatTurn(id = 1L, userText = "继续分析一下 MCP 的配置差异。"),
                        HomeChatTurn(id = 2L, userText = "先不用管 MCP 了，讲讲会话树。"),
                    ),
                ),
                listState = rememberLazyListState(),
                composerBottomPadding = 20.dp,
                composerGap = 20.dp,
                composerHeight = remember { mutableStateOf(68.dp) },
                followBottom = remember { mutableStateOf(true) },
                isAtBottom = true,
                onContentTap = {},
                onInputChange = {},
                onSendClick = {},
                onStopClick = {},
                pendingImages = emptyList(),
                pendingFiles = emptyList(),
                onImageAttached = {},
                onRemoveImage = {},
                onCameraCaptured = { _, _ -> },
                onFileAttached = {},
                onRemoveFile = {},
                onComposerFocusChanged = {},
                onReGenerate = { },
                onFork = { },
                onRewind = { },
                expandedToolRuns = emptySet(),
                expandedToolResults = emptySet(),
                expandedThinking = emptySet(),
                onToggleToolRun = { _, _ -> },
                onToggleToolResult = { _, _, _ -> },
                onToggleThinking = { _, _ -> },
                expandedActionTurnId = null,
                expandedActionSource = null,
                onToggleActionRow = { _, _ -> },
            )
        }
    }
}

@Composable
private fun HomeTitleConfigChip(
    uiStateProvider: () -> HomeChatUiState,
    onChipClick: () -> Unit,
    onAnchorBoundsChanged: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState = uiStateProvider()
    val isDarkTheme = LocalAppDarkTheme.current
    val activeConfig = uiState.displayConfig
    val titleText = activeConfig?.name ?: stringResource(R.string.app_name)
    val hasConfigs = uiState.savedConfigs.isNotEmpty()

    val latestOnAnchorBoundsChanged by rememberUpdatedState(onAnchorBoundsChanged)
    val latestOnChipClick by rememberUpdatedState(onChipClick)
    var chipCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Row(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                chipCoordinates = coordinates
                if (coordinates.isAttached) {
                    latestOnAnchorBoundsChanged(coordinates.boundsInRoot())
                }
            }
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                enabled = hasConfigs,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    chipCoordinates?.takeIf { it.isAttached }?.boundsInRoot()?.let { bounds ->
                        latestOnAnchorBoundsChanged(bounds)
                    }
                    latestOnChipClick()
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = titleText,
            style = TextStyle(
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isDarkTheme) Color.White else Color.Black,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasConfigs) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isDarkTheme) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f),
            )
        }
    }
}

@Preview(
    name = "Compacted Divider Alignment Preview",
    showBackground = true,
    widthDp = 400,
    heightDp = 500,
)
@Composable
private fun CompactedDividerAlignmentPreview() {
    BaseTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AssistantOutputText(
                text = "这是一段用于测试两端对齐的 Markdown 文本。请观察下方分割线两端与本段文本起止边缘是否在同一条垂线上（start-to-start, end-to-end）。"
            )

            CompactedDividerItem(isCompacting = true)

            AssistantOutputText(
                text = "这是位于两条分割线中间的普通模型回复段落。"
            )

            CompactedDividerItem(isCompacting = false)

            AssistantOutputText(
                text = "这是压缩之后的新一轮回答。上方分割线展示的是「对话已压缩」完成态。"
            )
        }
    }
}


