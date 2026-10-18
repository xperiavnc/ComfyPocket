package com.comfyui.mobile.modelcenter

import org.junit.Assert.*
import org.junit.Test

class ModelIndexStatusTest {
    @Test fun completedPartialScanNamesTheProblemRatherThanSuggestingItIsStillRunning() {
        val roots = listOf(ModelRoot(status = "ready"), ModelRoot(status = "unreadable"),
            ModelRoot(status = "unreadable"), ModelRoot(status = "absent"))
        assertEquals("扫描已结束 · 2 个目录待检查",
            modelIndexStatus(ModelPage(lastSync = 100, scanComplete = false),
                ModelScan("completed", 78, 78), roots))
    }

    @Test fun fullScanWithUnusedAbsentRootsIsSynced() {
        assertEquals("已同步", modelIndexStatus(ModelPage(scanComplete = true),
            ModelScan("completed"), listOf(ModelRoot(status = "absent"))))
    }

    @Test fun activePausedFailedNeverScannedAndInterruptedRemainDistinct() {
        val page = ModelPage()
        assertEquals("正在同步 10/78", modelIndexStatus(page, ModelScan("hashing", 78, 10), emptyList()))
        assertEquals("同步已暂停", modelIndexStatus(page, ModelScan("paused"), emptyList()))
        assertEquals("同步失败 · 请重试", modelIndexStatus(page, ModelScan("failed"), emptyList()))
        assertEquals("尚未同步", modelIndexStatus(page, ModelScan(), emptyList()))
        assertEquals("上次同步中断 · 请重新同步",
            modelIndexStatus(page, ModelScan(), listOf(ModelRoot(status = "scanning"))))
    }
}
