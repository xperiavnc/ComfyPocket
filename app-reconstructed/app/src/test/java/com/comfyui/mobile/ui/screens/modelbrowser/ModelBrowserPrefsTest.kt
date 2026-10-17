package com.comfyui.mobile.ui.screens.modelbrowser

import org.junit.Assert.*
import org.junit.Test

class ModelBrowserPrefsTest {
    @Test fun managerOffersKrea2UnderImageModels() {
        val group = ALL_BASE_MODEL_GROUPS.single { it.categoryName.contains("主流生图") }
        assertTrue("Krea 2" in group.items)
        assertEquals(1, ALL_BASE_MODEL_GROUPS.flatMap { it.items }.count { it == "Krea 2" })
    }

    @Test fun krea2AliasesAcceptSpacedCompactAndMixedCaseNames() {
        for (tag in listOf("Krea 2", "Krea2", " kReA2 ", "krea 2")) {
            val aliases = getBaseModelAliases(tag)
            assertTrue("Krea 2" in aliases)
            assertTrue("Krea2" in aliases)
            assertTrue("krea2" in aliases)
            assertFalse(aliases.any { it.contains("Flux", ignoreCase = true) })
        }
    }
}
