package com.comfyui.mobile.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class MarketBackNavigationGateTest {
    @Test fun oneBackPopsOneEntryWithoutNavigatingAgain() {
        var pops = 0
        var dashboards = 0
        MarketBackNavigationGate().leave(true, { pops++; true }, { dashboards++ })
        assertEquals(1, pops)
        assertEquals(0, dashboards)
    }

    @Test fun duplicateGestureOrToolbarEventCannotPopTheDashboard() {
        var pops = 0
        val gate = MarketBackNavigationGate()
        repeat(2) { gate.leave(true, { pops++; true }, { fail("Unexpected fallback") }) }
        assertEquals(1, pops)
    }

    @Test fun staleMarketCallbackCannotPopAnotherDestination() {
        MarketBackNavigationGate().leave(false, { fail("Popped another destination"); true },
            { fail("Unexpected navigation") })
    }

    @Test fun missingPreviousEntryOpensDashboardOnceInsteadOfLeavingAnEmptyHost() {
        var pops = 0
        var dashboards = 0
        val gate = MarketBackNavigationGate()
        repeat(2) { gate.leave(true, { pops++; false }, { dashboards++ }) }
        assertEquals(1, pops)
        assertEquals(1, dashboards)
    }

    @Test fun reenteringTheMarketGetsAnIndependentGate() {
        var pops = 0
        repeat(2) { MarketBackNavigationGate().leave(true, { pops++; true }, {}) }
        assertEquals(2, pops)
    }
}
