package com.comfyui.mobile.ui.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comfyui.mobile.agent.AgentQuestionPolicy
import com.comfyui.mobile.model.AgentQuestionAnswer
import com.comfyui.mobile.model.AgentUserQuestion
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Draft selection is local to this message. Nothing is sent until the whole form is submitted. */
@Composable
internal fun AgentQuestionForm(
    messageId: String,
    questions: List<AgentUserQuestion>,
    enabled: Boolean,
    onSubmit: (List<AgentQuestionAnswer>) -> Boolean,
    onCancel: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    var choices by rememberSaveable(messageId) { mutableStateOf(List(questions.size) { emptyList<String>() }) }
    var texts by rememberSaveable(messageId) { mutableStateOf(List(questions.size) { "" }) }
    var submitted by rememberSaveable(messageId) { mutableStateOf(false) }
    val active = enabled && !submitted
    val answers = questions.mapIndexed { index, question ->
        AgentQuestionAnswer(question.id, choices[index], texts[index])
    }
    val normalized = AgentQuestionPolicy.validate(questions, answers)
    val answered = answers.count { !it.optionIds.isNullOrEmpty() || !it.text.isNullOrBlank() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            if (active) "等待你回答 · $answered/${questions.size}" else if (submitted) "答案已发送" else "历史问题",
            style = MiuixTheme.textStyles.footnote1,
            color = scheme.primary,
            modifier = Modifier.testTag("agent-question-status")
        )
        questions.forEachIndexed { index, question ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${index + 1}. ${question.prompt}",
                    style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium)
                )
                if (question.kind != "free_text") {
                    Text(
                        if (question.allowMultiple) "可多选，也可自行填写" else "选择一项，也可自行填写",
                        style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary
                    )
                    question.options.orEmpty().forEach { option ->
                        val checked = option.id in choices[index]
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (checked) scheme.primaryContainer else scheme.surfaceContainerHigh)
                                .clickable(enabled = active,
                                    role = if (question.allowMultiple) Role.Checkbox else Role.RadioButton) {
                                    val next = if (question.allowMultiple) {
                                        if (checked) choices[index] - option.id else choices[index] + option.id
                                    } else if (checked) emptyList() else listOf(option.id)
                                    choices = choices.mapIndexed { i, value -> if (i == index) next else value }
                                }
                                .semantics { selected = checked }
                                .heightIn(min = 44.dp)
                                .padding(horizontal = 10.dp, vertical = 10.dp)
                                .testTag("agent-question-${question.id}-option-${option.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val shape = if (question.allowMultiple) RoundedCornerShape(4.dp) else CircleShape
                            Box(Modifier.size(18.dp).border(1.dp,
                                if (checked) scheme.primary else scheme.outline, shape),
                                contentAlignment = Alignment.Center) {
                                if (checked) Box(Modifier.size(10.dp).clip(shape).background(scheme.primary))
                            }
                            Text(option.label, modifier = Modifier.weight(1f),
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (active) scheme.onSurface else scheme.onSurfaceVariantSummary)
                        }
                    }
                }
                val requester = remember { BringIntoViewRequester() }
                val scope = rememberCoroutineScope()
                BasicTextField(
                    value = texts[index],
                    onValueChange = { value -> texts = texts.mapIndexed { i, old -> if (i == index) value else old } },
                    enabled = active,
                    textStyle = MiuixTheme.textStyles.body2.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.surfaceContainerHigh)
                        .border(1.dp, scheme.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                        .bringIntoViewRequester(requester)
                        .onFocusEvent { if (it.isFocused) scope.launch { requester.bringIntoView() } }
                        .testTag("agent-question-${question.id}-text"),
                    decorationBox = { inner ->
                        Box {
                            if (texts[index].isEmpty()) Text(
                                question.placeholder ?: if (question.kind == "free_text") "填写你的回答" else "其他答案或补充说明",
                                style = MiuixTheme.textStyles.footnote1, color = scheme.onSurfaceVariantSummary
                            )
                            inner()
                        }
                    }
                )
            }
        }
        if (enabled) {
            QuestionFormButton("提交全部回答", active && normalized != null, true, "agent-question-submit") {
                if (normalized != null && onSubmit(normalized)) submitted = true
            }
            QuestionFormButton("取消本次提问", active, false, "agent-question-cancel", onCancel)
        }
    }
}

@Composable
private fun QuestionFormButton(label: String, enabled: Boolean, primary: Boolean, tag: String, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (primary && enabled) scheme.primary else scheme.surfaceContainerHigh)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp).padding(10.dp).testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MiuixTheme.textStyles.footnote1,
            color = if (primary && enabled) scheme.onPrimary else scheme.onSurfaceVariantSummary)
    }
}
