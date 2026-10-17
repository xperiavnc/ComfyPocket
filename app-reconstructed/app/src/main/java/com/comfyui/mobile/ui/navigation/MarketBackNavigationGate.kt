package com.comfyui.mobile.ui.navigation

/** Shared by the toolbar and system back: leaving a market entry can pop only once. */
internal class MarketBackNavigationGate {
    private var leaving = false

    fun leave(isCurrentMarket: Boolean, pop: () -> Boolean, openDashboard: () -> Unit) {
        if (!isCurrentMarket || leaving) return
        leaving = true
        // Never leave an empty NavHost when a restored entry has no previous destination.
        if (!pop()) openDashboard()
    }
}
