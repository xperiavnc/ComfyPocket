package com.comfyui.mobile.ui.screens.aiapps

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.model.AiApp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.Column
import com.comfyui.mobile.ui.components.DialogFormColumn

@Composable
fun AiAppEditTitleDialog(
    show: Boolean,
    titleValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
    ) {
    OverlayDialog(
        show = show,
        title = "修改标题",
        onDismissRequest = onDismiss
    ) {
        DialogFormColumn(
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
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
    )
                }
            }
    ) {
            TextField(
                value = titleValue,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = "标题",
                useLabelAsPlaceholder = true
    )
        }
    }
}

@Composable
fun AiAppDeleteConfirmDialog(
    app: AiApp?,
    onDismiss: () -> Unit,
    onConfirm: (AiApp) -> Unit
    ) {
    OverlayDialog(
        show = app != null,
        title = "删除应用",
        onDismissRequest = onDismiss
    ) {
        Text(
            text = if (app != null) "确定删除“${app.title}”吗？" else "",
            style = MiuixTheme.textStyles.body2
    )
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                    )
            Spacer(Modifier.width(12.dp))
            TextButton(
                text = "删除",
                onClick = {
                    if (app != null) {
                        onConfirm(app)
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
    )
        }
    }
}

@Composable
fun AiAppSettingsMenuDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onEditTitle: () -> Unit,
    onEditCover: () -> Unit,
    onDelete: () -> Unit,
    onManageParams: (() -> Unit)? = null
    ) {
    OverlayDialog(
        show = show,
        title = "应用设置",
        onDismissRequest = onDismiss
    ) {
        if (onManageParams != null) {
            TextButton(
                text = "显示参数",
                onClick = onManageParams,
                modifier = Modifier.fillMaxWidth()
    )
            Spacer(modifier = Modifier.height(4.dp))
        }
        TextButton(
            text = "改标题",
            onClick = onEditTitle,
            modifier = Modifier.fillMaxWidth()
    )
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(
            text = "改封面",
            onClick = onEditCover,
            modifier = Modifier.fillMaxWidth()
    )
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(
            text = "删除应用",
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary()
    )
    }
}
