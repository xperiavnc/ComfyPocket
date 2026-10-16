package com.comfyui.mobile.network

import com.comfyui.mobile.model.WorkflowOptionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkflowComboOptionsTest {

    @Test
    fun testStandardNestedListCombo() {
        // Standard ComfyUI format: [ [ "opt1", "opt2" ], { "default": "opt1" } ]
        val objInfo = mapOf(
            "CR Aspect Ratio" to mapOf(
                "input" to mapOf(
                    "required" to mapOf(
                        "aspect_ratio" to listOf(
                            listOf("1:1 square 1024x1024", "16:9 widescreen 1344x768", "9:16 vertical 768x1344"),
                            mapOf("default" to "1:1 square 1024x1024")
                        )
                    )
                )
            )
        )
        val opts = WorkflowOptionParser.getComboOptions(objInfo, "CR Aspect Ratio", "aspect_ratio")
        assertEquals(
            listOf("1:1 square 1024x1024", "16:9 widescreen 1344x768", "9:16 vertical 768x1344"),
            opts
        )
    }

    @Test
    fun testFlatListCombo() {
        // Flat list format used by many resolution / custom nodes: [ "1024x1024", "1280x720", ... ]
        val objInfo = mapOf(
            "FluxResolutionNode" to mapOf(
                "input" to mapOf(
                    "required" to mapOf(
                        "resolution" to listOf("1024x1024", "1280x720", "720x1280", "1920x1080")
                    )
                )
            )
        )
        val opts = WorkflowOptionParser.getComboOptions(objInfo, "FluxResolutionNode", "resolution")
        assertEquals(
            listOf("1024x1024", "1280x720", "720x1280", "1920x1080"),
            opts
        )
    }

    @Test
    fun testDictConfigCombo() {
        // Format: [ "COMBO", { "values": [ ... ] } ]
        val objInfo = mapOf(
            "CustomNode" to mapOf(
                "input" to mapOf(
                    "required" to mapOf(
                        "ratio" to listOf(
                            "COMBO",
                            mapOf("values" to listOf("1:1", "4:3", "16:9"))
                        )
                    )
                )
            )
        )
        val opts = WorkflowOptionParser.getComboOptions(objInfo, "CustomNode", "ratio")
        assertEquals(listOf("1:1", "4:3", "16:9"), opts)
    }

    @Test
    fun testScalarTypesIgnored() {
        // Standard primitives must not be treated as combo options
        val objInfo = mapOf(
            "EmptyLatentImage" to mapOf(
                "input" to mapOf(
                    "required" to mapOf(
                        "width" to listOf("INT", mapOf("default" to 512, "min" to 64, "max" to 4096)),
                        "height" to listOf("INT", mapOf("default" to 512))
                    )
                )
            ),
            "CLIPTextEncode" to mapOf(
                "input" to mapOf(
                    "required" to mapOf(
                        "text" to listOf("STRING", mapOf("multiline" to true))
                    )
                )
            )
        )
        assertNull(WorkflowOptionParser.getComboOptions(objInfo, "EmptyLatentImage", "width"))
        assertNull(WorkflowOptionParser.getComboOptions(objInfo, "EmptyLatentImage", "height"))
        assertNull(WorkflowOptionParser.getComboOptions(objInfo, "CLIPTextEncode", "text"))
    }

    @Test
    fun testNonExistentNodeOrKeyReturnsNull() {
        val objInfo = mapOf<String, Any>()
        assertNull(WorkflowOptionParser.getComboOptions(objInfo, "NonExistent", "key"))
    }
}
