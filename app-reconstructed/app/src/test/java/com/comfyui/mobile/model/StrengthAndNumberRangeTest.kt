package com.comfyui.mobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class StrengthAndNumberRangeTest {

    @Test
    fun strengthKeysMatchWeightsOnly() {
        assertTrue(isStrengthKey("strength"))
        assertTrue(isStrengthKey("strength_model"))
        assertTrue(isStrengthKey("strength_clip"))
        assertTrue(isStrengthKey("lora_1_strength"))
        assertTrue(isStrengthKey("lora_2_model_strength"))
        assertTrue(isStrengthKey("lora_3_clip_strength"))
        assertFalse(isStrengthKey("num_loras"))
        assertFalse(isStrengthKey("lora_name"))
        assertFalse(isStrengthKey("lora_1"))
        assertFalse(isStrengthKey("steps"))
    }

    @Test
    fun intCountRangeFromObjectInfo() {
        val objInfo = mapOf(
            "easy loraStack" to mapOf(
                "input" to mapOf(
                    "required" to mapOf(
                        "num_loras" to listOf(
                            "INT",
                            mapOf("default" to 1, "min" to 1, "max" to 10)
                        ),
                        "lora_1_strength" to listOf(
                            "FLOAT",
                            mapOf("default" to 1.0, "min" to -10.0, "max" to 10.0, "step" to 0.01)
                        )
                    )
                )
            )
        )
        assertEquals(1.0 to 10.0, WorkflowOptionParser.getNumberRange(objInfo, "easy loraStack", "num_loras"))
        val strengthObj = WorkflowOptionParser.getNumberRange(objInfo, "easy loraStack", "lora_1_strength")
        assertEquals(-10.0 to 10.0, strengthObj)
        assertEquals(STRENGTH_SLIDER_MIN to STRENGTH_SLIDER_MAX, strengthSliderRange(strengthObj))
        assertEquals(1.23, snapStrength(1.234f), 1e-9)
        assertEquals(0.0, snapStrength(-0.5f), 1e-9)
        assertEquals(5.0, snapStrength(9f), 1e-9)
        assertNull(WorkflowOptionParser.getNumberRange(objInfo, "easy loraStack", "missing"))
        assertEquals(1.0 to 10.0, fallbackNumberRange("num_loras"))
        assertNull(fallbackNumberRange("steps"))
    }
}
