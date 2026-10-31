package com.comfyui.mobile.ui.screens

import com.comfyui.mobile.ui.designsystem.PocketCard
import com.comfyui.mobile.ui.designsystem.PocketSurface

import com.comfyui.mobile.ui.designsystem.PocketSettingsGroup

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.comfyui.mobile.ui.feedback.AppToast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.comfyui.mobile.R
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun AboutScreen(navController: NavController) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val floatingNavClearance = LocalFloatingNavClearance.current
    val scheme = MiuixTheme.colorScheme

    val versionName = remember {
        try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            info.versionName ?: "2.4.5-beta"
        } catch (_: Exception) {
            "2.4.5-beta"
        }
    }

    val githubUrl = "https://github.com/HAIGC/ComfyUI-APP"
    val iconSourceUrl = "https://www.pixiv.net/artworks/135715296"

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            AppToast.show(context, "无法打开浏览器链接")
        }
    }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = "关于应用",
                color = scheme.background,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                }
    )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = 28.dp + floatingNavClearance
    ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 1. 核心品牌卡片 (Hero Card)
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(horizontal = 20.dp, vertical = 26.dp)
    ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
                    // Logo：无多余容器遮罩、无底部投影，纯净无损放大圆角呈现
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = "App Logo",
                        modifier = Modifier
                            .size(124.dp)
                            .clip(RoundedCornerShape(26.dp)),
                        contentScale = ContentScale.Crop
    )

                    // 标题与 Beta 版本标签
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
                        Text(
                            text = "ComfyUI",
                            style = MiuixTheme.textStyles.title2,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
    )

                        // Beta 版本胶囊徽章
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(scheme.primaryContainer)
                                .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
                            Text(
                                text = if (versionName.contains("beta", ignoreCase = true)) "v$versionName" else "v$versionName · Beta",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onPrimaryContainer
    )
                        }
                    }

                    Text(
                        text = "ComfyUI 移动端控制器",
                        style = MiuixTheme.textStyles.body2,
                        color = scheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center
    )
                }
            }

            // 2. 官方开源链接与图标署名
            PocketSettingsGroup("项目与署名") {
                ArrowPreference(
                    title = "开源主页",
                    summary = "GitHub (HAIGC/ComfyUI-APP)",
                    startAction = {
                        Icon(
                            MiuixIcons.Link,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = scheme.onBackground
    )
                    },
                    onClick = { openUrl(githubUrl) }
    )
                ArrowPreference(
                    title = "APP图标",
                    summary = "Pixiv (京科夫猫子)",
                    startAction = {
                        Icon(
                            MiuixIcons.Link,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = scheme.onBackground
    )
                    },
                    onClick = { openUrl(iconSourceUrl) }
    )
            }

            // 3. 核心特性速览卡片
            PocketCard(modifier = Modifier.fillMaxWidth(), surface = PocketSurface.Base) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "技术亮点",
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.SemiBold
    )
                    FeatureItem(
                        icon = "⚡",
                        title = "实时连接",
                        desc = "进度与显存监控"
    )
                    FeatureItem(
                        icon = "🎨",
                        title = "液态玻璃",
                        desc = "HyperOS 风格界面"
    )
                    FeatureItem(
                        icon = "🤖",
                        title = "Agent 助手",
                        desc = "自然语言拆镜"
    )
                    FeatureItem(
                        icon = "☁️",
                        title = "后台保活",
                        desc = "锁屏查看出图进度"
    )
                }
            }

            // 4. 技术栈与组件致谢
            PocketSettingsGroup("致谢") {
                ArrowPreference(
                    title = "Jetpack Compose",
                    summary = "Android 声明式 UI",
                    onClick = { openUrl("https://developer.android.com/jetpack/compose") }
    )
                ArrowPreference(
                    title = "Miuix UI",
                    summary = "HyperOS 组件库",
                    onClick = { openUrl("https://github.com/compose-miuix-ui/miuix") }
    )
                ArrowPreference(
                    title = "Coil",
                    summary = "图片与视频解码",
                    onClick = { openUrl("https://coil-kt.github.io/coil/") }
    )
                ArrowPreference(
                    title = "Retrofit & OkHttp",
                    summary = "网络与 WebSocket",
                    onClick = { openUrl("https://square.github.io/okhttp/") }
    )
            }

            // 5. 底部版权
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
                Text(
                    text = "Made with ❤️ for ComfyUI Creators",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariantSummary.copy(alpha = 0.8f)
    )
                Text(
                    text = "Apache-2.0 Licensed · Open Source Project",
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
    )
            }
        }
    }
}

@Composable
private fun FeatureItem(
    icon: String,
    title: String,
    desc: String
    ) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = icon,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 2.dp)
    )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface
    )
            Text(
                text = desc,
                fontSize = 12.sp,
                color = scheme.onSurfaceVariantSummary,
                lineHeight = 16.sp
    )
        }
    }
}
