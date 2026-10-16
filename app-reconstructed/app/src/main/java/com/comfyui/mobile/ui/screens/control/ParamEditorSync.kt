package com.comfyui.mobile.ui.screens.control

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.comfyui.mobile.model.EditableNode

/**
 * Keep a mutable local list in sync with [source] while not dragging.
 * Used by both Control (vertical list) and AI App detail (2-column grid)
 * so reorder gestures stay snappy without fighting remote state updates.
 */
fun syncLocalEditableNodes(
    local: SnapshotStateList<EditableNode>,
    source: List<EditableNode>
    ) {
    val currentKeys = local.map { compoundKeyOf(it) }
    val newKeys = source.map { compoundKeyOf(it) }
    if (local.isEmpty() || currentKeys.size != newKeys.size || currentKeys.toSet() != newKeys.toSet()) {
        local.clear()
        local.addAll(source)
    } else if (currentKeys != newKeys) {
        for (i in source.indices) {
            if (i < local.size) {
                local[i] = source[i]
            }
        }
    } else {
        val newMap = source.associateBy { compoundKeyOf(it) }
        for (i in local.indices) {
            val key = compoundKeyOf(local[i])
            val updated = newMap[key]
            if (updated != null && updated != local[i]) {
                local[i] = updated
            }
        }
    }
}

/**
 * Local mutable copy of [source], re-synced when source changes and [draggingKey] is null.
 */
@Composable
fun rememberSyncedEditableNodes(
    source: List<EditableNode>,
    draggingKey: String?
    ): SnapshotStateList<EditableNode> {
    val local = remember { mutableStateListOf<EditableNode>() }
    LaunchedEffect(source, draggingKey) {
        if (draggingKey != null) return@LaunchedEffect
        syncLocalEditableNodes(local, source)
    }
    return local
}
