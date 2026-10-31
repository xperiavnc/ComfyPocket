package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.animation.AnimatedVisibility
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.comfyui.mobile.model.MediaOutput
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One stable column: changing preview height never recreates the conversation content. */
@Composable
fun AgentWorkspaceLayout(
    sessionTitle: String,
    mediaList: List<MediaOutput>,
    previewUrl: (MediaOutput) -> String,
    status: AgentCardStatus,
    wfLabel: String,
    cardColors: CardColors,
    sidePad: Dp,
    onOpenSessions: () -> Unit,
    onImageClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    headerTrailing: (@Composable () -> Unit)? = null,
    /** Slim line between the session header and the preview (model / context / speed). */
    headerBottom: (@Composable () -> Unit)? = null,
    focusedIndex: Int = 0,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scheme = MiuixTheme.colorScheme
    BoxWithConstraints(modifier) {
        // Whole images are fitted now, so give portraits real height; chat keeps the rest.
        val previewHeight = (maxHeight * 0.42f - 64.dp).coerceIn(48.dp, 300.dp)
        Column(Modifier.fillMaxSize()) {
            // Reads as a subtitle of the "Agent" title (breadcrumb), not as a second input field.
            // Session on the left, the current model (switcher) on the right of the same line.
            Row(Modifier.fillMaxWidth().padding(horizontal = sidePad - 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
            Row(
                Modifier.weight(1f, fill = false)
                    .clip(PocketTokens.Shape.Medium)
                    .testTag("agent-open-sessions")
                    .clickable(role = Role.Button, onClickLabel = "切换会话", onClick = onOpenSessions)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(MiuixIcons.Recent, contentDescription = null, modifier = Modifier.size(16.dp),
                    tint = scheme.onSurfaceVariantSummary)
                Text(sessionTitle, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = scheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium))
                Icon(androidx.compose.material.icons.Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = scheme.onSurfaceVariantSummary)
            }
            if (headerTrailing != null) {
                Spacer(Modifier.width(PocketTokens.Space.XS))
                headerTrailing()
            }
            }
            headerBottom?.invoke()
            AgentWorkbench(
                mediaList = mediaList, previewUrl = previewUrl, status = status, wfLabel = wfLabel,
                cardColors = cardColors, sidePad = sidePad,
                expanded = expanded && !keyboardVisible, previewHeight = previewHeight,
                onExpandedChange = { next ->
                    expanded = next
                    if (next && keyboardVisible) { focus.clearFocus(); keyboard?.hide() }
                }, onImageClick = onImageClick, focusedIndex = focusedIndex
            )
            content()
        }
    }
}

/** Only the preview is disposed on collapse; the conversation and its scroll state stay mounted. */
@Composable
fun AgentWorkbench(
    mediaList: List<MediaOutput>,
    previewUrl: (MediaOutput) -> String,
    status: AgentCardStatus,
    wfLabel: String,
    cardColors: CardColors,
    sidePad: Dp,
    expanded: Boolean,
    previewHeight: Dp,
    onExpandedChange: (Boolean) -> Unit,
    onImageClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    focusedIndex: Int = 0
) {
    val scheme = MiuixTheme.colorScheme
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { mediaList.size })
    // Follow the full-screen viewer: returning from it shows the image the user ended on.
    LaunchedEffect(focusedIndex) {
        if (mediaList.isNotEmpty()) {
            val target = focusedIndex.coerceIn(0, mediaList.lastIndex)
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
        }
    }
    LaunchedEffect(mediaList.firstOrNull()?.let { "${it.subfolder}|${it.filename}|${it.timestamp}" }) {
        if (mediaList.isNotEmpty() && pagerState.currentPage != 0) pagerState.scrollToPage(0)
    }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "workbenchChevron")
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = sidePad, vertical = 4.dp)
            .clip(PocketTokens.Shape.XLarge).background(scheme.surfaceContainer)
            .testTag("agent-workbench")
    ) {
        // The whole header toggles (full-width press highlight); the thumbnail keeps its own tap.
        Row(
            Modifier.fillMaxWidth()
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .testTag("agent-workbench-toggle")
                .clickable(role = Role.Button, onClickLabel = if (expanded) "收起工作台" else "展开工作台") {
                    onExpandedChange(!expanded)
                }
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val media = mediaList.firstOrNull()
            val url = media?.let(previewUrl).orEmpty()
            Box(
                Modifier.size(40.dp).clip(PocketTokens.Shape.Medium)
                    .background(scheme.surfaceContainerHighest)
                    .then(if (media != null && !status.showOverlay) Modifier.clickable(
                        role = Role.Button, onClickLabel = "全屏查看最新生成"
                    ) { onImageClick(0) } else Modifier.pointerInput(Unit) {
                        // Inert while generating: swallow taps so they do not toggle the header.
                        detectTapGestures { }
                    })
                    // Own merge boundary so it stays a separate node inside the clickable header.
                    .semantics(mergeDescendants = true) {}
                    .testTag("agent-workbench-thumbnail"),
                contentAlignment = Alignment.Center
            ) {
                // Same retry story as the preview: a failed thumb otherwise stays an empty tile.
                var thumbAttempt by androidx.compose.runtime.remember(url) { mutableStateOf(0) }
                var thumbFailed by androidx.compose.runtime.remember(url) { mutableStateOf(false) }
                LaunchedEffect(url, thumbFailed) {
                    if (thumbFailed && thumbAttempt < 3) {
                        kotlinx.coroutines.delay(1500L shl thumbAttempt)
                        thumbFailed = false; thumbAttempt++
                    }
                }
                if (url.isNotBlank()) androidx.compose.runtime.key(thumbAttempt) {
                    AsyncImage(url, "最新生成缩略图", Modifier.size(40.dp), contentScale = ContentScale.Crop,
                        onError = { thumbFailed = true })
                }
                else Icon(MiuixIcons.Image, contentDescription = null, tint = scheme.onSurfaceVariantSummary)
            }
            Row(
                Modifier.weight(1f).height(64.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (mediaList.isEmpty()) "绘图工作台" else "最新生成 · ${mediaList.size} 项",
                        style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val summary = status.title ?: status.errorBadge ?: wfLabel
                    Text(listOfNotNull(summary, status.subtitle).joinToString(" · "),
                        color = if (status.errorBadge != null && !status.showOverlay) scheme.error else scheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("agent-workbench-status"))
                }
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = if (expanded) "收起工作台" else "展开工作台",
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
                    tint = scheme.onSurfaceVariantSummary)
            }
        }
        // Collapsed: the header carries a slim progress bar (the expanded preview has its own overlay).
        AnimatedVisibility(visible = status.showProgress && !expanded,
            enter = expandVertically(tween(180)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(140))) {
            AgentStatusProgressBar(status.progress,
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                    .testTag("agent-workbench-progress"),
                trackColor = scheme.surfaceContainerHighest)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(220)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(140))
        ) {
            AgentImagePreviewCard(
                mediaList = mediaList, previewUrl = previewUrl, status = status,
                wfLabel = wfLabel,
                cardColors = CardDefaults.defaultColors(color = scheme.surfaceContainerHighest),
                sidePad = 8.dp,
                modifier = Modifier.height(previewHeight).padding(bottom = 2.dp).testTag("agent-workbench-preview"),
                pagerState = pagerState,
                showControls = false,
                onImageClick = onImageClick,
                onClearMediaListClick = {}, onWorkflowClick = {}, onStyleParamsClick = {}, onPromptClick = {}
            )
        }
    }
}
