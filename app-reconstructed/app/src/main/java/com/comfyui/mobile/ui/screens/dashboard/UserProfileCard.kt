package com.comfyui.mobile.ui.screens.dashboard

import androidx.compose.foundation.Image
import com.comfyui.mobile.ui.designsystem.PocketTokens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.comfyui.mobile.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import java.util.Calendar
import com.comfyui.mobile.ui.components.DialogFormColumn

private val DashCardRadius = 28.dp

fun getQqAvatarUrl(qq: String): String? {
    val trimmed = qq.trim()
    return if (trimmed.isNotBlank() && trimmed.all { it.isDigit() } && trimmed.length in 5..12) {
        "https://q1.qlogo.cn/g?b=qq&nk=$trimmed&s=640"
    } else {
        null
    }
}

fun formatRhCoins(v: Double?): String {
    if (v == null || !v.isFinite()) return "—"
    return if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)
}

fun formatRhMoney(v: Double?, currency: String?): String {
    if (v == null || !v.isFinite()) return "—"
    val amount = String.format(java.util.Locale.US, "%.2f", v)
    return when (currency?.trim()?.uppercase()) {
        null, "", "CNY", "RMB" -> "¥$amount"
        "USD" -> "\$" + amount
        else -> amount + " " + currency.orEmpty().trim()
    }
}

/**
 * 一体化主页核心看板卡片：
 * 1. 顶部：个人身份区（头像点击弹出编辑且无多余角标；问候语+云端模式/本地模式高亮胶囊；独占大字粗体昵称；个性签名）
 * 2. 中部：服务器节点条（展示当前节点与连接状态，无多余轻触提示文案）
 * 3. 底部：业务指标区（融合在同一卡片内，Local: 剩余队列 + 最新生成；RH: 账号余额 + 云端任务）
 * 4. 卡片整体支持 Tilt 按压倾斜动效，轻触卡片主体直接跳转服务器管理
 */
@Composable
fun UserProfileCard(
    // 个人信息
    nickname: String,
    bio: String,
    qq: String,
    onAvatarClick: () -> Unit,
    // 模式与连接
    isRh: Boolean,
    rhUserName: String?,
    statusText: String,
    statusOk: Boolean,
    serverDisplayName: String,
    onServerClick: () -> Unit,
    // Local ComfyUI 业务指标
    queueSize: Int = 0,
    isBusy: Boolean = false,
    latestCount: Int = 0,
    onLatestClick: (() -> Unit)? = null,
    totalModels: Int? = null,
    totalPlugins: Int? = null,
    onAssetsClick: (() -> Unit)? = null,
    onLocalModelsClick: (() -> Unit)? = null,
    onPluginsClick: (() -> Unit)? = null,
    // RunningHub 业务指标
    authorized: Boolean = false,
    rhCoins: Double? = null,
    rhMoney: Double? = null,
    rhCurrency: String? = null,
    rhRunning: Int? = null,
    rhQueued: Int? = null,
    rhRefreshing: Boolean = false,
    onRhTasksClick: () -> Unit = {},
    // 样式
    cardColors: CardColors,
    onCardColor: Color,
    modifier: Modifier = Modifier
    ) {
    val scheme = MiuixTheme.colorScheme

    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..10 -> "早上好"
            in 11..13 -> "中午好"
            in 14..18 -> "下午好"
            else -> "晚上好"
        }
    }

    val displayName = when {
        nickname.isNotBlank() -> nickname
        isRh && !rhUserName.isNullOrBlank() -> rhUserName
        else -> "创作者"
    }

    val displayBio = bio.ifBlank { "探索 AI 的无限可能" }
    val qqAvatarUrl = remember(qq) { getQqAvatarUrl(qq) }

    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = DashCardRadius,
        colors = cardColors,
        pressFeedbackType = PressFeedbackType.None
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
    ) {
            // 1. 顶部：个人身份区
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
    ) {
                // 头像：点击弹出资料编辑，干净圆形无笔形角标
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(scheme.surfaceContainer)
                        .border(1.5.dp, scheme.primary.copy(alpha = 0.25f), CircleShape)
                        .clickable(onClick = onAvatarClick),
                    contentAlignment = Alignment.Center
    ) {
                    if (qqAvatarUrl != null) {
                        AsyncImage(
                            model = qqAvatarUrl,
                            contentDescription = "用户头像",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            placeholder = painterResource(id = R.drawable.app_logo),
                            error = painterResource(id = R.drawable.app_logo)
    )
                    } else {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "默认头像",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
    )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // 文字信息：独立分行排版
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
                    // 第一行：时段问候 + 模式胶囊（统一采用清晰醒目的 primary 强调色）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
    ) {
                        Text(
                            text = greeting,
                            style = MiuixTheme.textStyles.body2,
                            color = onCardColor.copy(alpha = 0.65f),
                            maxLines = 1
    )

                        val badgeBg = scheme.primary.copy(alpha = 0.14f)
                        val badgeColor = scheme.primary
                        val badgeText = if (isRh) "云端模式" else "本地模式"
                        Box(
                            modifier = Modifier
                                .clip(PocketTokens.Shape.Small)
                                .background(badgeBg)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
                            Text(
                                text = badgeText,
                                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                color = badgeColor,
                                maxLines = 1
    )
                        }
                    }

                    // 第二行：昵称（整行独立加粗大字，不拥挤）
                    Text(
                        text = displayName,
                        style = MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.Bold),
                        color = onCardColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
    )

                    // 第三行：个性签名
                    Text(
                        text = displayBio,
                        style = MiuixTheme.textStyles.footnote1,
                        color = onCardColor.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. 中部：服务器连接节点条（点击切换服务器，无轻触切换提示，仅展示状态与名称）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PocketTokens.Shape.Large)
                    .background(scheme.surfaceContainer.copy(alpha = 0.55f))
                    .clickable(onClick = onServerClick)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
    ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false)
    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (statusOk) PocketTokens.Success else scheme.error)
    )
                        Text(
                            text = statusText,
                            style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold),
                            color = if (statusOk) onCardColor else scheme.error,
                            maxLines = 1
    )
                        if (serverDisplayName.isNotBlank()) {
                            Text(
                                text = serverDisplayName,
                                style = MiuixTheme.textStyles.body2,
                                color = onCardColor.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
    )
                        }
                    }

                    Icon(
                        imageVector = if (statusOk) MiuixIcons.CloudFill else MiuixIcons.Report,
                        contentDescription = null,
                        tint = if (statusOk) scheme.primary else scheme.error,
                        modifier = Modifier.size(18.dp)
    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. 底部：业务指标区（融合在同一卡片内，充实饱满舒展）
            if (isRh) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
                    // 账号余额（RH币 & 钱包）
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(PocketTokens.Shape.Large)
                            .background(scheme.surfaceContainer.copy(alpha = 0.45f))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "账号余额",
                                style = MiuixTheme.textStyles.footnote1,
                                color = onCardColor.copy(alpha = 0.65f)
    )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
    ) {
                                Text(
                                    text = "RH币",
                                    style = MiuixTheme.textStyles.body2,
                                    color = onCardColor.copy(alpha = 0.68f)
    )
                                Text(
                                    text = if (rhRefreshing && rhCoins == null) "…" else if (authorized) formatRhCoins(rhCoins) else "—",
                                    style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                                    color = onCardColor
    )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
    ) {
                                Text(
                                    text = "钱包",
                                    style = MiuixTheme.textStyles.body2,
                                    color = onCardColor.copy(alpha = 0.68f)
    )
                                Text(
                                    text = if (rhRefreshing && rhMoney == null) "…" else if (authorized) formatRhMoney(rhMoney, rhCurrency) else "—",
                                    style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                                    color = onCardColor
    )
                            }
                        }
                    }

                    // 云端任务（大小与账号余额卡片完全一致，两行展示且不带冒号）
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(PocketTokens.Shape.Large)
                            .background(scheme.surfaceContainer.copy(alpha = 0.45f))
                            .clickable(onClick = onRhTasksClick)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "云端任务",
                                style = MiuixTheme.textStyles.footnote1,
                                color = onCardColor.copy(alpha = 0.65f)
    )
                            val runningVal = if (authorized) rhRunning?.toString() ?: "—" else "—"
                            val queuedVal = if (authorized) rhQueued?.toString() ?: "—" else "—"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
    ) {
                                Text(
                                    text = "运行",
                                    style = MiuixTheme.textStyles.body2,
                                    color = onCardColor.copy(alpha = 0.68f)
    )
                                Text(
                                    text = runningVal,
                                    style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (authorized && (rhRunning ?: 0) > 0) scheme.primary else onCardColor
    )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
    ) {
                                Text(
                                    text = "排队",
                                    style = MiuixTheme.textStyles.body2,
                                    color = onCardColor.copy(alpha = 0.68f)
    )
                                Text(
                                    text = queuedVal,
                                    style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                                    color = onCardColor
    )
                            }
                        }
                    }
                }
            } else {
                // Local ComfyUI 底部指标：方案 C 双列数值分栏（简洁美观，简短文本）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
                    // 1. 任务监控卡片 (排队 | 生成)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(PocketTokens.Shape.Large)
                            .background(scheme.surfaceContainer.copy(alpha = 0.45f))
                            .then(if (onLatestClick != null) Modifier.clickable(onClick = onLatestClick) else Modifier)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 顶部：简写标题 + 状态圆点
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
    ) {
                                Text(
                                    text = "任务",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = onCardColor.copy(alpha = 0.65f)
    )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
                                    val dotColor = if (!statusOk) scheme.error else if (isBusy) scheme.primary else PocketTokens.Success
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(dotColor)
                                    )
                                    Text(
                                        text = if (!statusOk) "未连接" else if (isBusy) "运行中" else "空闲",
                                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                        color = if (!statusOk) scheme.error else if (isBusy) scheme.primary else onCardColor.copy(alpha = 0.60f)
    )
                                }
                            }

                            // 底部双列指标：排队 | 生成
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
    ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
                                    Text(
                                        text = "排队",
                                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                                        color = onCardColor.copy(alpha = 0.60f)
    )
                                    Text(
                                        text = "$queueSize",
                                        style = MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.Bold),
                                        color = if (queueSize > 0) scheme.primary else onCardColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
    )
                                }

                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(26.dp)
                                        .background(onCardColor.copy(alpha = 0.10f))
                                )

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
                                    Text(
                                        text = "生成",
                                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                                        color = onCardColor.copy(alpha = 0.60f)
    )
                                    Text(
                                        text = if (latestCount > 0) "$latestCount ↗" else "0",
                                        style = MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.Bold),
                                        color = if (latestCount > 0) scheme.primary else onCardColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
    )
                                }
                            }
                        }
                    }

                    // 2. 资产卡片 (模型 | 插件，右上角跳转“集市 ↗”)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(PocketTokens.Shape.Large)
                            .background(scheme.surfaceContainer.copy(alpha = 0.45f))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 顶部：简写标题 + 集市跳转
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
    ) {
                                Text(
                                    text = "资产",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = onCardColor.copy(alpha = 0.65f)
    )
                                Text(
                                    text = "集市 ↗",
                                    modifier = Modifier.then(if (onAssetsClick != null)
                                        Modifier.clickable(onClick = onAssetsClick) else Modifier),
                                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                    color = scheme.primary
    )
                            }

                            // 底部双列指标：模型 | 插件
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
    ) {
                                Column(
                                    modifier = Modifier.weight(1f).then(if (onLocalModelsClick != null)
                                        Modifier.clickable(onClick = onLocalModelsClick) else Modifier),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
                                    Text(
                                        text = "本地模型",
                                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                                        color = onCardColor.copy(alpha = 0.60f)
    )
                                    Text(
                                        text = totalModels?.toString() ?: "—",
                                        style = MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.Bold),
                                        color = onCardColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
    )
                                }

                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(26.dp)
                                        .background(onCardColor.copy(alpha = 0.10f))
                                )

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                         .then(if (onPluginsClick != null) Modifier.clickable(onClick = onPluginsClick) else Modifier)
                                        .padding(start = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
                                    Text(
                                        text = "插件",
                                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                                        color = onCardColor.copy(alpha = 0.60f)
    )
                                    Text(
                                        text = totalPlugins?.toString() ?: "—",
                                        style = MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.Bold),
                                        color = onCardColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditUserProfileDialog(
    show: Boolean,
    currentNickname: String,
    currentBio: String,
    currentQq: String,
    onDismiss: () -> Unit,
    onSave: (nickname: String, bio: String, qq: String) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme

    var editNickname by remember { mutableStateOf(currentNickname) }
    var editBio by remember { mutableStateOf(currentBio) }
    var editQq by remember { mutableStateOf(currentQq) }

    LaunchedEffect(show, currentNickname, currentBio, currentQq) {
        if (show) {
            editNickname = currentNickname
            editBio = currentBio
            editQq = currentQq
        }
    }

    val previewAvatarUrl = remember(editQq) { getQqAvatarUrl(editQq) }

    OverlayDialog(
        show = show,
        title = "编辑个人信息",
        onDismissRequest = onDismiss,
    ) {
        DialogFormColumn(
            modifier = Modifier.padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            footer = {
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = "保存",
                        onClick = {
                            onSave(editNickname, editBio, editQq)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            },
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainer)
                    .border(2.dp, scheme.primary.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (previewAvatarUrl != null) {
                    AsyncImage(
                        model = previewAvatarUrl,
                        contentDescription = "QQ头像预览",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = painterResource(id = R.drawable.app_logo),
                        error = painterResource(id = R.drawable.app_logo),
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = "默认头像",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (previewAvatarUrl != null) "已获取 QQ 头像" else "输入 QQ 号即可获取对应头像",
                style = MiuixTheme.textStyles.footnote1,
                color = if (previewAvatarUrl != null) scheme.primary else scheme.onSurface.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(14.dp))

            TextField(
                value = editQq,
                onValueChange = { editQq = it.filter { ch -> ch.isDigit() } },
                label = "QQ 号",
                singleLine = true,
                useLabelAsPlaceholder = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            TextField(
                value = editNickname,
                onValueChange = { editNickname = it },
                label = "昵称",
                singleLine = true,
                useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            TextField(
                value = editBio,
                onValueChange = { editBio = it },
                label = "个性签名",
                singleLine = true,
                useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

