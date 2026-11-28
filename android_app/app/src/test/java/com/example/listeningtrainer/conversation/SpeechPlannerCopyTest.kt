package com.example.listeningtrainer.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Сверка копий (S05c, D63): нарезка и конец реплики (D48) в Kotlin — те же, что в Python-копии пробы. Эталон пишет
 * `python tools/probe/quotes_check.py` в `tools/probe/fixtures/02c_plans.json`: 90 ответов пробы S02c и случаи брифа S05c.
 */
class SpeechPlannerCopyTest {

    private val repo = File("../..").canonicalFile
    private val fixture = File(repo, "tools/probe/fixtures/02c_plans.json")

    private fun check(section: String, expectedCount: Int) {
        val all = Json.parseToJsonElement(fixture.readText()).jsonObject[section]!!.jsonArray
        assertEquals(expectedCount, all.size)
        for ((n, e) in all.withIndex()) {
            val o = e as JsonObject
            val text = o["text"]!!.jsonPrimitive.content
            val groups = o["groups"]!!.jsonArray.map { g -> (g as JsonArray).let { SpeechChunk(it[0].jsonPrimitive.content, it[1].jsonPrimitive.long) } }
            assertEquals("$section ${n + 1}", groups, SpeechPlanner().plan(SpeechText.clean(text)))
            val d48 = o["d48"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.int }
            assertEquals("$section ${n + 1}: D48", d48, ReplyText.firstQuestionEnd("$text ", final = true))
        }
    }

    @Test
    fun `90 replies of the S02c probe - the same groups, pauses and end of the reply as the Python copy`() = check("replies", 90)

    @Test
    fun `cases of the S05c brief - the same as the Python copy`() = check("cases", 10)
}
