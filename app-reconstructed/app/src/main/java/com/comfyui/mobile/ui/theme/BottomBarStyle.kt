package com.comfyui.mobile.ui.theme

/** Stable preference IDs; old installs retain their current standard navigation. */
enum class BottomBarStyle(val id: String, val title: String, val summary: String) {
    MATERIAL("material", "标准底栏", "贴底布局，保留当前样式"),
    CLASSIC("classic", "经典悬浮", "老版胶囊、图标与弹性动效");

    companion object {
        const val PREFERENCE_KEY = "bottom_bar_style"
        fun fromId(id: String?): BottomBarStyle = entries.firstOrNull { it.id == id } ?: MATERIAL
    }
}
