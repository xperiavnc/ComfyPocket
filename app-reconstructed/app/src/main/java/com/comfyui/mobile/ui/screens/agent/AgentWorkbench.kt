package com.comfyui.mobile.ui.screens.agent

import androidx.compose.animation.AnimatedVisibility
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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Image
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
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scheme = MiuixTheme.colorScheme
    BoxWithConstraints(modifier) {
        val previewHeight = (maxHeight * 0.34f - 64.dp).coerceIn(48.dp, 216.dp)
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = sidePad, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp)).background(scheme.surfaceContainer)
                    .testTag("agent-open-sessions")
                    .clickable(role = Role.Button, onClick = onOpenSessions)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(sessionTitle, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium))
                Text("会话", style = MiuixTheme.textStyles.footnote1, color = scheme.primary)
            }
            AgentWorkbench(
                mediaList = mediaList, previewUrl = previewUrl, status = status, wfLabel = wfLabel,
                cardColors = cardColors, sidePad = sidePad,
                expanded = expanded && !keyboardVisible, previewHeight = previewHeight,
                onExpandedChange = { next ->
                    expanded = next
                    if (next && keyboardVisible) { focus.clearFocus(); keyboard?.hide() }
                }, onImageClick = onImageClick
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
    modifier: Modifier = Modifier
) {
    val scheme = MiuixTheme.colorScheme
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { mediaList.size })
    LaunchedEffect(mediaList.firstOrNull()?.let { "${it.subfolder}|${it.filename}|${it.timestamp}" }) {
        if (mediaList.isNotEmpty() && pagerState.currentPage != 0) pagerState.scrollToPage(0)
    }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "workbenchChevron")
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = sidePad, vertical = 4.dp)
            .clip(RoundedCornerShape(22.dp)).background(scheme.surfaceContainer)
            .testTag("agent-workbench")
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val media = mediaList.firstOrNull()
            val url = media?.let(previewUrl).orEmpty()
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(scheme.surfaceContainerHighest)
                    .then(if (media != null && !status.showOverlay) Modifier.clickable(
                        role = Role.Button, onClickLabel = "全屏查看最新生成"
                    ) { onImageClick(0) } else Modifier)
                    .testTag("agent-workbench-thumbnail"),
                contentAlignment = Alignment.Center
            ) {
                if (url.isNotBlank()) AsyncImage(url, "最新生成缩略图", Modifier.size(40.dp), contentScale = ContentScale.Crop)
                else Icon(MiuixIcons.Image, contentDescription = null, tint = scheme.onSurfaceVariantSummary)
            }
            Row(
                Modifier.weight(1f).height(64.dp)
                    .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                    .testTag("agent-workbench-toggle")
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "收起工作台" else "展开工作台") {
                        onExpandedChange(!expanded)
                    }.padding(horizontal = 12.dp),
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
        if (status.showProgress) AgentStatusProgressBar(status.progress,
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("agent-workbench-progress"))
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(220)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(140))
        ) {
            AgentImagePreviewCard(
                mediaList = mediaList, previewUrl = previewUrl, status = status,
                wfLabel = wfLabel, cardColors = cardColors, sidePad = 8.dp,
                modifier = Modifier.height(previewHeight).testTag("agent-workbench-preview"),
                pagerState = pagerState,
                showControls = false,
                onImageClick = onImageClick,
                onClearMediaListClick = {}, onWorkflowClick = {}, onStyleParamsClick = {}, onPromptClick = {}
            )
        }
    }
}
