package com.comfyui.mobile.agent

import com.comfyui.mobile.modelcenter.LocalModel
import com.comfyui.mobile.modelcenter.ModelAnnotation
import com.comfyui.mobile.modelcenter.ModelProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The per-turn 【LoRA 速查】, slot-free set_lora, group switches and the trigger-word safety net. */
class AgentLoraBriefingTest {
    private val watercolor = "style\\watercolor_v2.safetensors"
    private val miku = "chars\\miku.safetensors"
    private val options = listOf(watercolor, miku, "detail.safetensors")

    private fun loader(lora: String) = mutableMapOf<String, Any>("class_type" to "LoraLoader", "inputs" to mutableMapOf<String, Any>(
        "lora_name" to lora, "strength_model" to 1.0, "strength_clip" to 1.0, "model" to listOf("4", 0), "clip" to listOf("4", 1)))

    private fun loaderFields(node: String) = listOf(
        AgentFieldSpec(node, "lora_name", "LoraLoader", AgentFieldKind.TEXT, options = options),
        AgentFieldSpec(node, "strength_model", "LoraLoader", AgentFieldKind.DECIMAL, min = -10.0, max = 10.0),
        AgentFieldSpec(node, "strength_clip", "LoraLoader", AgentFieldKind.DECIMAL, min = -10.0, max = 10.0))

    private fun single(lora: String = "detail.safetensors") = AgentWorkflowSnapshot(mapOf("10" to loader(lora)), loaderFields("10"))

    private val catalog = AgentLoraCatalog.from(listOf(
        LocalModel(category = "LORA", present = true, name = "watercolor_v2", nodeModelName = watercolor, sha256 = "a".repeat(64),
            annotation = ModelAnnotation(alias = "水彩画风", useCases = listOf("水彩", "插画"), preferredStrength = 0.7, notes = "背景很淡")),
        LocalModel(category = "LORA", present = true, name = "miku", nodeModelName = miku,
            provider = ModelProvider(trainedWords = listOf("hatsune miku"), description = "<p>Miku <b>character</b> LoRA</p>"))
    ), mapOf("a".repeat(64) to ModelProvider(trainedWords = listOf("watercolor"), baseModel = "SDXL 1.0")))

    private fun args(json: String): JsonObject = JsonParser.parseString(json).asJsonObject
    private fun data(result: AgentToolResult) = result.data as Map<*, *>
    private fun finalPrompt(result: AgentToolResult) = JsonParser.parseString(result.finalContent).asJsonObject["prompt"].asString

    @Test fun briefingListsTheSlotsAndEveryLoadableLoraWithItsTriggerWords() {
        val text = AgentDrawToolbox(single(), titles = mapOf("10" to "LoRA 加载"), loras = catalog).briefing()
        assertTrue(text, text.startsWith("【LoRA 速查】"))
        val lines = text.lines()
        assertTrue(text, "- 10:lora_name（LoRA 加载）：detail.safetensors，强度 1.0" in lines)
        val loras = lines.filter { it.startsWith("- ") && !it.startsWith("- 10:") }
        assertEquals(listOf(
            "- $watercolor｜水彩画风｜触发词 watercolor｜SDXL 1.0｜用途 水彩、插画｜推荐强度 0.7｜备注 背景很淡",
            "- $miku｜触发词 hatsune miku",
            "- detail.safetensors（没有资料）"), loras)
        assertTrue(AgentDrawToolbox.instruction(8, text).endsWith(text))
        assertEquals(AgentDrawToolbox.instruction(8), AgentDrawToolbox.instruction(8, ""))
    }

    @Test fun noSlotNoBriefing() {
        val plain = AgentWorkflowSnapshot(mapOf("3" to mapOf("class_type" to "KSampler", "inputs" to mapOf("steps" to 20))),
            listOf(AgentFieldSpec("3", "steps", "KSampler", AgentFieldKind.INTEGER)))
        assertEquals("", AgentDrawToolbox(plain, loras = catalog).briefing())
    }

    @Test fun theOnlySlotNeedsNoSlotArgument() = runBlocking {
        val box = AgentDrawToolbox(single(), loras = catalog)
        val result = box.call(AgentDrawToolbox.SET_LORA, args("""{"lora":"miku"}"""))
        assertTrue(result.error, result.ok)
        assertEquals(miku, box.changes.single { it.key == "lora_name" }.value)
        assertEquals("detail.safetensors", data(result)["replaced"])
        assertTrue(data(result)["hint"].toString().contains("把触发词放进正向提示词"))
    }

    @Test fun severalSlotsNeedASlotUnlessOneAlreadyHoldsTheLora() = runBlocking {
        val snapshot = AgentWorkflowSnapshot(mapOf("10" to loader("detail.safetensors"), "11" to loader(miku)),
            loaderFields("10") + loaderFields("11"))
        val box = AgentDrawToolbox(snapshot, loras = catalog)
        val missing = box.call(AgentDrawToolbox.SET_LORA, args("""{"lora":"水彩画风"}"""))
        assertFalse(missing.ok)
        assertTrue(missing.error, missing.error!!.contains("请用 slot 指定") &&
            missing.error!!.contains("10:lora_name（detail.safetensors，强度 1.0）") && missing.error!!.contains("11:lora_name（$miku，强度 1.0，触发词 hatsune miku）"))
        val again = box.call(AgentDrawToolbox.SET_LORA, args("""{"lora":"miku","strength":0.5}"""))
        assertTrue(again.error, again.ok)
        assertEquals(setOf("11:lora_name", "11:strength_model", "11:strength_clip"), box.changes.map { "${it.nodeId}:${it.key}" }.toSet())
    }

    @Test fun submitAddsTheTriggerWordOfALoraLoadedThisTurn() = runBlocking {
        val box = AgentDrawToolbox(single(), loras = catalog)
        assertTrue(box.call(AgentDrawToolbox.SET_LORA, args("""{"lora":"miku"}""")).ok)
        assertTrue(box.call(AgentDrawToolbox.SET_PROMPT, args("""{"prompt":"1girl, smile,"}""")).ok)
        val submitted = box.call(AgentDrawToolbox.SUBMIT, args("""{"reply":"好"}"""))
        assertTrue(submitted.error, submitted.ok)
        assertEquals("1girl, smile, hatsune miku", finalPrompt(submitted))
        assertEquals("1girl, smile, hatsune miku", box.prompt)
        assertTrue(data(submitted)["summary"].toString().contains("已补上 LoRA 触发词：hatsune miku"))

        val written = AgentDrawToolbox(single(), loras = catalog)
        written.call(AgentDrawToolbox.SET_LORA, args("""{"lora":"miku"}"""))
        written.call(AgentDrawToolbox.SET_PROMPT, args("""{"prompt":"hatsune_miku, 1girl"}"""))
        assertEquals("hatsune_miku, 1girl", finalPrompt(written.call(AgentDrawToolbox.SUBMIT, args("""{"reply":"好"}"""))))

        // Already loaded before the turn: the user's prompt is left alone.
        val kept = AgentDrawToolbox(single(miku), loras = catalog)
        kept.call(AgentDrawToolbox.SET_PROMPT, args("""{"prompt":"1girl"}"""))
        assertEquals("1girl", finalPrompt(kept.call(AgentDrawToolbox.SUBMIT, args("""{"reply":"好"}"""))))
    }

    @Test fun replacingALoraReportsItsLeftoverTriggerWordsWithoutRemovingThem() = runBlocking {
        val box = AgentDrawToolbox(single(miku), loras = catalog)
        assertTrue(box.call(AgentDrawToolbox.SET_PROMPT, args("""{"prompt":"hatsune miku, 1girl"}""")).ok)
        val result = box.call(AgentDrawToolbox.SET_LORA, args("""{"lora":"水彩画风"}"""))
        assertTrue(result.error, result.ok)
        assertEquals(miku, data(result)["replaced"])
        val hint = data(result)["hint"].toString()
        assertTrue(hint, hint.contains("当前提示词里还没有这个 LoRA 的触发词"))
        assertTrue(hint, hint.contains("提示词里还留着换下的「miku」的触发词：hatsune miku"))
        assertEquals("hatsune miku, 1girl, watercolor", finalPrompt(box.call(AgentDrawToolbox.SUBMIT, args("""{"reply":"好"}"""))))
    }

    @Test fun hasSlotsDecidesWhetherTheTurnWaitsForTheServerLoraList() {
        // No LoRA slot means no LoRA tool, so the turn must not wait for a possibly stale server list first.
        val plain: Map<String, Any> = mapOf("3" to mapOf("class_type" to "KSampler", "inputs" to mapOf("seed" to 1)))
        assertFalse(AgentLoraCatalog.hasSlots(plain))
        val loaded: Map<String, Any> = mapOf("4" to mapOf("class_type" to "LoraLoader",
            "inputs" to mapOf("lora_name" to "a.safetensors", "strength_model" to 1.0, "strength_clip" to 1.0)))
        assertTrue(AgentLoraCatalog.hasSlots(loaded))
    }

    @Test fun slotsFollowStackCountsWeightNamesAndSwitches() {
        val graph: Map<String, Any> = mapOf(
            "1" to mapOf("class_type" to "LoRA Stacker", "inputs" to mapOf("input_mode" to "simple", "lora_count" to 2,
                "lora_name_1" to "a", "lora_wt_1" to 0.8, "model_str_1" to 1.0, "clip_str_1" to 1.0,
                "lora_name_2" to "b", "lora_wt_2" to 1.0, "model_str_2" to 1.0, "clip_str_2" to 1.0,
                "lora_name_3" to "c", "lora_wt_3" to 1.0, "model_str_3" to 1.0, "clip_str_3" to 1.0)),
            "2" to mapOf("class_type" to "CR LoRA Stack", "inputs" to mapOf(
                "switch_1" to "On", "lora_name_1" to "a", "model_weight_1" to 1.0, "clip_weight_1" to 1.0,
                "switch_2" to "Off", "lora_name_2" to "b", "model_weight_2" to 0.5, "clip_weight_2" to 0.5)),
            "3" to mapOf("class_type" to "easy loraStack", "inputs" to mapOf("toggle" to false, "mode" to "simple", "num_loras" to 1,
                "lora_1_name" to "a", "lora_1_strength" to 0.9, "lora_1_model_strength" to 1.0, "lora_1_clip_strength" to 1.0,
                "lora_2_name" to "b", "lora_2_strength" to 1.0, "lora_2_model_strength" to 1.0, "lora_2_clip_strength" to 1.0)))
        val slots = AgentLoraCatalog.slots(graph) { _, _ -> emptyList() }.associateBy { it.id }
        // Slots past the count stay listed, marked unused, so the Agent can grow the stack into them.
        assertEquals(setOf("1:lora_name_1", "1:lora_name_2", "1:lora_name_3", "2:lora_name_1", "2:lora_name_2",
            "3:lora_1_name", "3:lora_2_name"), slots.keys)
        assertEquals(setOf("1:lora_name_3", "3:lora_2_name"), slots.values.filterNot { it.active }.map { it.id }.toSet())
        with(slots.getValue("1:lora_name_3")) { assertEquals("lora_count", countKey); assertEquals(3, number) }
        with(slots.getValue("3:lora_2_name")) { assertEquals("num_loras", countKey); assertEquals(2, number) }
        assertNull(slots.getValue("2:lora_name_1").countKey)
        assertEquals(listOf("lora_wt_1", "model_str_1", "clip_str_1"), slots.getValue("1:lora_name_1").strengthKeys)
        assertEquals(listOf("model_weight_2", "clip_weight_2"), slots.getValue("2:lora_name_2").strengthKeys)
        assertTrue(slots.getValue("2:lora_name_1").switchedOn)
        with(slots.getValue("2:lora_name_2")) { assertEquals("switch_2", switchKey); assertEquals("On", switchOn); assertFalse(switchedOn) }
        with(slots.getValue("3:lora_1_name")) {
            assertEquals(listOf("lora_1_strength", "lora_1_model_strength", "lora_1_clip_strength"), strengthKeys)
            assertEquals("toggle", switchKey); assertEquals(true, switchOn); assertFalse(switchedOn)
        }
        assertNull(slots.getValue("1:lora_name_1").switchKey)
    }

    private fun easyStack(withToggle: Boolean): AgentWorkflowSnapshot {
        val stackOptions = listOf("None", "a.safetensors", "b.safetensors")
        val workflow = mapOf("13" to mapOf("class_type" to "easy loraStack", "inputs" to mapOf("toggle" to false, "mode" to "simple",
            "num_loras" to 2, "lora_1_name" to "a.safetensors", "lora_1_strength" to 1.0, "lora_2_name" to "None", "lora_2_strength" to 1.0)))
        return AgentWorkflowSnapshot(workflow, listOfNotNull(
            AgentFieldSpec("13", "lora_1_name", "easy loraStack", AgentFieldKind.TEXT, options = stackOptions),
            AgentFieldSpec("13", "lora_1_strength", "easy loraStack", AgentFieldKind.DECIMAL),
            AgentFieldSpec("13", "lora_2_name", "easy loraStack", AgentFieldKind.TEXT, options = stackOptions),
            AgentFieldSpec("13", "lora_2_strength", "easy loraStack", AgentFieldKind.DECIMAL),
            if (withToggle) AgentFieldSpec("13", "toggle", "easy loraStack", AgentFieldKind.BOOLEAN) else null))
    }

    @Test fun loadingALoraTurnsItsGroupSwitchOnOrWarnsWhenItCannot() = runBlocking {
        val box = AgentDrawToolbox(easyStack(withToggle = true))
        assertTrue(box.briefing(), box.briefing().contains("13:lora_2_name（easy loraStack）：空，开关关着（装 LoRA 时会自动打开）"))
        val result = box.call(AgentDrawToolbox.SET_LORA, args("""{"slot":"13:lora_2_name","lora":"b.safetensors"}"""))
        assertTrue(result.error, result.ok)
        assertTrue(data(result)["summary"].toString().contains("并打开了 toggle"))
        assertEquals(mapOf("13:lora_2_name" to "b.safetensors", "13:lora_2_strength" to 1.0, "13:toggle" to true),
            box.changes.associate { "${it.nodeId}:${it.key}" to it.value })
        assertNull(data(result)["warning"])

        val locked = AgentDrawToolbox(easyStack(withToggle = false))
        val staged = locked.call(AgentDrawToolbox.SET_LORA, args("""{"slot":"13:lora_2_name","lora":"b.safetensors"}"""))
        assertTrue(staged.error, staged.ok)
        assertTrue(data(staged)["warning"].toString().contains("开关是关的"))
        assertFalse(locked.changes.any { it.key == "toggle" })
        val off = locked.call(AgentDrawToolbox.SET_LORA, args("""{"slot":"13:lora_1_name","lora":""}"""))
        assertTrue(off.error, off.ok)
        assertEquals("None", locked.changes.single { it.key == "lora_1_name" }.value)
    }

    @Test fun manyWordsThatNeverMatchTogetherFallBackToAnyWord() = runBlocking {
        val box = AgentDrawToolbox(single(), loras = catalog)
        val result = box.call(AgentDrawToolbox.LIST_LORAS, args("""{"query":"miku watercolor"}"""))
        assertTrue(result.error, result.ok)
        assertTrue(data(result)["summary"].toString().contains("没有同时命中全部检索词"))
        assertEquals(listOf(watercolor, miku), (data(result)["loras"] as List<*>).map { (it as Map<*, *>)["file"] })
        assertEquals(listOf("kawaii", "style", "xl"), AgentLoraCatalog.terms("Kawaii_Style-XL"))
    }

    /** easy loraStack using one of three slots; slot 2 still names a file from an earlier setup. */
    private fun growingStack(max: Double?, openCount: Boolean = true): AgentWorkflowSnapshot {
        val stackOptions = listOf("None", "a.safetensors", "b.safetensors")
        val workflow = mapOf("13" to mapOf("class_type" to "easy loraStack", "inputs" to mapOf("toggle" to true, "mode" to "simple",
            "num_loras" to 1, "lora_1_name" to "a.safetensors", "lora_1_strength" to 1.0,
            "lora_2_name" to "b.safetensors", "lora_2_strength" to 1.0, "lora_3_name" to "None", "lora_3_strength" to 1.0)))
        val fields = (1..3).flatMap { n -> listOf(
            AgentFieldSpec("13", "lora_${n}_name", "easy loraStack", AgentFieldKind.TEXT, options = stackOptions),
            AgentFieldSpec("13", "lora_${n}_strength", "easy loraStack", AgentFieldKind.DECIMAL, min = -10.0, max = 10.0)) } +
            listOfNotNull(if (openCount) AgentFieldSpec("13", "num_loras", "easy loraStack", AgentFieldKind.INTEGER, min = 1.0, max = max) else null)
        return AgentWorkflowSnapshot(workflow, fields)
    }

    private val stackCatalog = AgentLoraCatalog.from(listOf(
        LocalModel(category = "LORA", present = true, name = "b", nodeModelName = "b.safetensors",
            provider = ModelProvider(trainedWords = listOf("bstyle")))), emptyMap())

    private fun fieldNames(result: AgentToolResult) = (data(result)["fields"] as List<*>).map { (it as Map<*, *>)["field"] }

    @Test fun addingALoraFillsTheStacksNextSlotAndRaisesItsCount() = runBlocking {
        val box = AgentDrawToolbox(growingStack(max = 10.0), loras = stackCatalog)
        val brief = box.briefing()
        assertTrue(brief, brief.contains("- 13:lora_1_name（easy loraStack）：a.safetensors，强度 1.0"))
        assertTrue(brief, brief.contains("- 13:lora_2_name（easy loraStack）：空（新增槽位：装上 LoRA 时会把 num_loras 调到 2）"))
        assertFalse(brief, brief.contains("13:lora_3_name"))
        // The stack ignores slots past the count, so inspect_workflow leaves them out.
        val before = fieldNames(box.call(AgentDrawToolbox.INSPECT, JsonObject()))
        assertFalse(before.toString(), before.contains("13:lora_2_name"))
        assertTrue(before.toString(), before.contains("13:num_loras"))
        assertFalse(box.call(AgentDrawToolbox.SET_LORA, args("""{"slot":"13:lora_2_name","lora":""}""")).ok)

        val added = box.call(AgentDrawToolbox.SET_LORA, args("""{"slot":"13:lora_2_name","lora":"b.safetensors","strength":0.8}"""))
        assertTrue(added.error, added.ok)
        assertTrue(data(added)["summary"].toString(), data(added)["summary"].toString().contains("并把 num_loras 调到 2"))
        assertEquals(mapOf("13:lora_2_name" to "b.safetensors", "13:lora_2_strength" to 0.8, "13:num_loras" to 2),
            box.changes.associate { "${it.nodeId}:${it.key}" to it.value })
        assertTrue(fieldNames(box.call(AgentDrawToolbox.INSPECT, JsonObject())).contains("13:lora_2_name"))
        // Adding one more uses slot 3.
        val third = box.call(AgentDrawToolbox.SET_LORA, args("""{"slot":"13:lora_3_name","lora":"a.safetensors"}"""))
        assertTrue(third.error, third.ok)
        assertEquals(3, box.changes.single { it.key == "num_loras" }.value)

        // Slot 2 kept its old file name while unused; the LoRA is new to this picture, so submit adds its trigger word.
        box.call(AgentDrawToolbox.SET_PROMPT, args("""{"prompt":"1girl, smile"}"""))
        assertEquals("1girl, smile, bstyle", finalPrompt(box.call(AgentDrawToolbox.SUBMIT, args("""{"reply":"好"}"""))))
    }

    @Test fun theStackOnlyGrowsWhenItsCountIsOpenAndBelowItsMaximum() = runBlocking {
        val capped = AgentDrawToolbox(growingStack(max = 2.0), loras = stackCatalog)
        assertTrue(capped.call(AgentDrawToolbox.SET_LORA, args("""{"slot":"13:lora_2_name","lora":"b.safetensors"}""")).ok)
        val full = capped.call(AgentDrawToolbox.SET_LORA, args("""{"slot":"13:lora_3_name","lora":"a.safetensors"}"""))
        assertFalse(full.ok)
        assertTrue(full.error, full.error.orEmpty().contains("没有槽位「13:lora_3_name」"))

        val closed = AgentDrawToolbox(growingStack(max = 10.0, openCount = false), loras = stackCatalog)
        assertFalse(closed.briefing(), closed.briefing().contains("新增槽位"))
        assertFalse(closed.call(AgentDrawToolbox.SET_LORA, args("""{"slot":"13:lora_2_name","lora":"b.safetensors"}""")).ok)

        // Without a slot, "换成 b" replaces the LoRA in use instead of adding one.
        val swapped = AgentDrawToolbox(growingStack(max = 10.0), loras = stackCatalog)
        assertTrue(swapped.call(AgentDrawToolbox.SET_LORA, args("""{"lora":"b.safetensors"}""")).ok)
        assertEquals(mapOf("13:lora_1_name" to "b.safetensors", "13:lora_1_strength" to 1.0),
            swapped.changes.associate { "${it.nodeId}:${it.key}" to it.value })
    }

    @Test fun expandedStacksRaisesOnlyCountsBelowTheHighestSlot() {
        val single = loader("detail.safetensors")
        val graph: Map<String, Any> = mapOf("10" to single,
            "13" to mapOf("class_type" to "easy loraStack", "inputs" to mapOf("num_loras" to 1.0,
                "lora_1_name" to "a", "lora_1_strength" to 1.0, "lora_2_name" to "None", "lora_2_strength" to 1.0, "lora_3_name" to "None")))
        val expanded = AgentLoraCatalog.expandedStacks(graph)!!
        assertEquals(3.0, ((expanded["13"] as Map<*, *>)["inputs"] as Map<*, *>)["num_loras"])
        assertSame(single, expanded["10"])
        assertEquals(1.0, ((graph["13"] as Map<*, *>)["inputs"] as Map<*, *>)["num_loras"])
        assertNull(AgentLoraCatalog.expandedStacks(mapOf("10" to single)))
    }
}
