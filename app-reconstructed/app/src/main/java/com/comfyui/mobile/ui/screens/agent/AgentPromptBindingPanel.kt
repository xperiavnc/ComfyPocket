package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.agent.AgentPromptBinding
import com.comfyui.mobile.agent.PromptBindingMode
import com.comfyui.mobile.model.EditableNode
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentPromptBindingPanel(
    positive: AgentPromptBinding,
    negative: AgentPromptBinding,
    nodes: List<EditableNode.TextInput>,
    onSelect: (Boolean, AgentPromptBinding) -> Unit
) {
    var negativeTab by remember { mutableStateOf(false) }
    val selected = if (negativeTab) negative else positive
    val other = if (negativeTab) positive else negative
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { negativeTab = false }, modifier = Modifier.weight(1f),
                colors = if (!negativeTab) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                Text("正向节点")
            }
            Button(onClick = { negativeTab = true }, modifier = Modifier.weight(1f),
                colors = if (negativeTab) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                Text("反向节点")
            }
        }
        Text("正向：${positive.label}\n反向：${negative.label}", style = MiuixTheme.textStyles.footnote1)
        Text("按具体字段分别绑定。支持同节点的不同文本槽；正反向不可使用同一字段，连线槽不列入。",
            style = MiuixTheme.textStyles.footnote1)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Button(onClick = { onSelect(negativeTab, AgentPromptBinding()) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selected.mode == PromptBindingMode.AUTO) "✓ 自动探测" else "自动探测")
                }
            }
            if (negativeTab) item {
                Button(onClick = { onSelect(true, AgentPromptBinding(PromptBindingMode.KEEP)) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (selected.mode == PromptBindingMode.KEEP) "✓ 保留工作流反向原值" else "保留工作流反向原值")
                }
            }
            if (nodes.isEmpty()) item { Text("当前工作流暂无可绑定文本字段") }
            items(nodes, key = { "${it.nodeId}:${it.key}" }) { node ->
                val occupied = other.mode == PromptBindingMode.EXPLICIT &&
                    other.nodeId == node.nodeId && (other.key.isEmpty() || other.key == node.key)
                val chosen = selected.mode == PromptBindingMode.EXPLICIT &&
                    selected.nodeId == node.nodeId && (selected.key.isEmpty() || selected.key == node.key)
                Button(
                    onClick = { onSelect(negativeTab, AgentPromptBinding(PromptBindingMode.EXPLICIT, node.nodeId, node.key)) },
                    enabled = !occupied, modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${if (chosen) "✓ " else ""}#${node.nodeId}:${node.key} · ${node.title}" +
                        if (occupied) "（另一方向已占用）" else "")
                }
            }
        }
    }
}
