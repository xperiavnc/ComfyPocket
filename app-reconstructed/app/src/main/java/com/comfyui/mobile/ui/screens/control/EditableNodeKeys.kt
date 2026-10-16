package com.comfyui.mobile.ui.screens.control

import com.comfyui.mobile.model.EditableNode

/** Stable compound key: `"nodeId:inputKey"`. Shared by Control / AI App / filter dialogs. */
fun compoundKeyOf(node: EditableNode): String {
    val key = when (node) {
        is EditableNode.TextInput -> node.key
        is EditableNode.NumberInput -> node.key
        is EditableNode.BooleanInput -> node.key
        is EditableNode.ImageInput -> node.key
    }
    return "${node.nodeId}:$key"
}

/** The inputs-map key for this editable node. */
fun paramKeyOf(node: EditableNode): String = when (node) {
    is EditableNode.TextInput -> node.key
    is EditableNode.NumberInput -> node.key
    is EditableNode.BooleanInput -> node.key
    is EditableNode.ImageInput -> node.key
}

/** Format numbers for TextField display (trim trailing `.0` for whole floats/doubles). */
fun formatNumberForDisplay(n: Number): String = when (n) {
    is Int, is Long -> n.toString()
    is Float -> {
        val d = n.toDouble()
        if (d.isFinite() && d % 1.0 == 0.0) d.toLong().toString() else n.toString()
    }
    is Double -> {
        if (n.isFinite() && n % 1.0 == 0.0) n.toLong().toString() else n.toString()
    }
    else -> n.toString()
}
