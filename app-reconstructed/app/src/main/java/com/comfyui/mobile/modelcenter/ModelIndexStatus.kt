package com.comfyui.mobile.modelcenter

/** Scan completion and fingerprint counts describe different work, not a single percentage. */
fun modelIndexStatus(page: ModelPage, scan: ModelScan, roots: List<ModelRoot>): String = when {
    scan.active -> "正在同步 ${scan.hashed}/${scan.discovered}"
    scan.status == "failed" -> "同步失败 · 请重试"
    scan.status == "paused" -> "同步已暂停"
    scan.status == "cancelled" -> "同步已取消"
    page.scanComplete -> "已同步"
    roots.any { it.status == "unreadable" } ->
        "扫描已结束 · ${roots.count { it.status == "unreadable" }} 个目录待检查"
    scan.status == "completed" -> "扫描已结束 · 部分目录待确认"
    roots.any { it.status == "scanning" } -> "上次同步中断 · 请重新同步"
    page.lastSync == 0L -> "尚未同步"
    else -> "部分索引 · 请重新同步"
}
