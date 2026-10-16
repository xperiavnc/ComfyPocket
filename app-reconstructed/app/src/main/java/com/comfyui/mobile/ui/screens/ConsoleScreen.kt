package com.comfyui.mobile.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.comfyui.mobile.model.ConsoleMessage
import com.comfyui.mobile.model.LogType
import com.comfyui.mobile.ui.LocalFloatingNavClearance
import com.comfyui.mobile.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun ConsoleScreen(navController: NavController? = null, viewModel: MainViewModel) {
    val logs by viewModel.consoleMessages.collectAsState()
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current

    val scrollBehavior = MiuixScrollBehavior()
    val floatingNavClearance = LocalFloatingNavClearance.current

    fun exportLogs() {
        try {
            val file = viewModel.exportConsoleLogFile()
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
    )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "导出运行日志"))
        } catch (e: Exception) {
            Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = "运行日志",
                color = scheme.background,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navController?.popBackStack() }) {
                        Icon(
                            MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = scheme.onBackground
    )
                    }
                },
                actions = {
                    // 顶栏导出 / 分享
                    IconButton(onClick = { exportLogs() }) {
                        Icon(
                            MiuixIcons.Share,
                            contentDescription = "导出日志",
                            tint = scheme.onBackground
    )
                    }
                    // 顶栏清除
                    IconButton(onClick = {
                        viewModel.clearConsoleLog()
                        Toast.makeText(context, "已清空运行日志", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            MiuixIcons.Delete,
                            contentDescription = "清除日志",
                            tint = scheme.error
    )
                    }
                }
    )
        }
    ) { innerPadding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
    ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
                    Icon(
                        imageVector = MiuixIcons.Recent,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
    )
                    Text(
                        text = "暂无运行日志",
                        fontSize = 14.sp,
                        color = scheme.onSurfaceVariantSummary
    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 6.dp,
                    bottom = 24.dp + floatingNavClearance
    ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
                items(
                    logs.asReversed(),
                    key = { "${it.timestamp}|${it.type}|${it.text.hashCode()}" }
    ) { msg ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
                        LogItem(msg)
                    }
                }
            }
        }
    }
}

@Composable
fun LogItem(msg: ConsoleMessage) {
    val scheme = MiuixTheme.colorScheme
    val (tagColor, tagBg, label) = when (msg.type) {
        LogType.INFO -> Triple(scheme.primary, scheme.primaryContainer, "INFO")
        LogType.SUCCESS -> Triple(Color(0xFF00B578), Color(0xFFE6F8F1), "OK")
        LogType.ERROR -> Triple(Color(0xFFFF3B30), Color(0xFFFFECEB), "ERR")
        LogType.WARNING -> Triple(Color(0xFFFF9500), Color(0xFFFFF6E6), "WARN")
    }

    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 时间
        Text(
            text = timeFormat.format(Date(msg.timestamp)),
            color = scheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
    ),
            modifier = Modifier.padding(top = 2.dp)
    )

        // 状态胶囊标签
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(tagBg)
                .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
            Text(
                text = label,
                color = tagColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
    )
        }

        // 日志正文内容
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = msg.text,
                color = scheme.onSurface,
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
    )
    )
        }
    }
}
