package com.comfyui.mobile.ui.screens.aiapps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.model.EditableNode
import com.comfyui.mobile.ui.components.NodeIdChip
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private fun nodeKey(node: EditableNode): String = when (node) {
    is EditableNode.TextInput -> node.key
    is EditableNode.NumberInput -> node.key
    is EditableNode.BooleanInput -> node.key
    is EditableNode.ImageInput -> node.key
}

@Composable
fun AiAppParamVisibilityDialog(
    show: Boolean,
    editableNodes: List<EditableNode>,
    onDismiss: () -> Unit,
    onRenameClick: (nodeId: String, key: String, currentTitle: String) -> Unit,
    onToggleVisibility: (nodeId: String, key: String, visible: Boolean) -> Unit
    ) {
    var paramQuery by rememberSaveable { mutableStateOf("") }

    OverlayDialog(
        show = show,
        title = "显示参数",
        onDismissRequest = onDismiss
    ) {
        val q = paramQuery.trim()
        val filtered = editableNodes.filter { node ->
            if (q.isBlank()) return@filter true
            node.title.contains(q, ignoreCase = true) ||
                node.nodeId.contains(q, ignoreCase = true) ||
                nodeKey(node).contains(q, ignoreCase = true)
        }

        val ordered = filtered.sortedWith(
            compareByDescending<EditableNode> { it.isVisible }
                .thenBy { it.title.lowercase() }
                .thenBy { it.nodeId }
                .thenBy { nodeKey(it) }
        )

        val listState = rememberLazyListState()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
        ) {
            TextField(
                value = paramQuery,
                onValueChange = { paramQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = "搜索参数",
                useLabelAsPlaceholder = true
    )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
                state = listState
            ) {
                lazyItems(
                    items = ordered,
                    key = { node -> "${node.nodeId}:${nodeKey(node)}" }
                ) { node ->
                    val k = nodeKey(node)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(node.title, maxLines = 2)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
                                if (node is EditableNode.TextInput || node is EditableNode.NumberInput) {
                                    NodeIdChip(nodeId = node.nodeId)
                                }
                                Text(
                                    text = "Key: $k",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                onRenameClick(node.nodeId, k, node.title)
                            }
                        ) {
                            Icon(MiuixIcons.Edit, contentDescription = null)
                        }
                        Switch(
                            checked = node.isVisible,
                            onCheckedChange = { v ->
                                onToggleVisibility(node.nodeId, k, v)
                            }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(
            text = "完成",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary()
    )
    }
}
