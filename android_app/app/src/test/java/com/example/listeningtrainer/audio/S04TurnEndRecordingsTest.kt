package com.example.listeningtrainer.audio

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.TurnContext
import com.example.listeningtrainer.conversation.TurnEnd
import com.example.listeningtrainer.conversation.TurnEndRule
import com.example.listeningtrainer.conversation.VadTurnDetector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * S04, п. 3: конец реплики кодом приложения — [VadTurnDetector] + [SileroVad] + [TurnEndRule] (судья J2, кандидат 1000,
 * предел 8000) — на 48 записях S03b. Данные готовит `tools/probe/turn_end_export.py` в `diagnostics/s04/` (вне Git, D69):
 * записи с тем же хвостом шума, что в пробе, и лента частичного текста настоящей сессии `gpt-live-transcribe` (кэш пробы).
 *
 * Как приложение: звук кусками 80 мс; конец — запись кончается, и сразу идёт запись-продолжение (детектор с нуля,
 * `continuation`); учитель звучит через 2,4 с после конца; ученик заговорил раньше (речь ≥ 200 мс) — отмена, реплика идёт
 * дальше; позже — конец окончательный (ложный — «перебил»). Итог — `diagnostics/s04-kotlin.json`, сверка с пробой —
 * `turn_end_export.py --check`. Нет данных — тест пропускается.
 */
class S04TurnEndRecordingsTest {
    private val repo = File("../..").canonicalFile
    private val dir = File(repo, "diagnostics/s04")

    private class Partial(val t: Double, val text: String)

    private class Result(
        val base: String, val condition: String, val kind: String, val lastWordEnd: Double?,
        val endS: Double?, val endedBy: String?, val interrupted: Boolean, val cancels: List<Pair<Double, Double>>,
    )

    @Test
    fun `app turn end with the last-word judge on the S03b recordings`() {
        assumeTrue("no diagnostics/s04 — run tools/probe/turn_end_export.py", File(dir, "index.json").isFile)
        val json = Json { ignoreUnknownKeys = true }
        val index = json.parseToJsonElement(File(dir, "index.json").readText()).jsonObject
        val teacherS = index.getValue("teacher_audible_s").jsonPrimitive.double
        val out = index.getValue("records").jsonArray.map { e ->
            val o = e.jsonObject
            val base = o.getValue("base").jsonPrimitive.content
            val partial = o.getValue("partial").jsonArray.map {
                Partial(it.jsonObject.getValue("t").jsonPrimitive.double, it.jsonObject.getValue("text").jsonPrimitive.content)
            }
            run(
                base, o.getValue("condition").jsonPrimitive.content, o.getValue("kind").jsonPrimitive.content,
                o["last_word_end_s"]?.jsonPrimitive?.doubleOrNull, partial, teacherS,
            )
        }
        assertEquals(48, out.size)

        val speech = out.filter { it.kind != "no_speech" }
        val paused = speech.filter { it.kind == "pause" || it.kind == "long" }
        val interrupted = speech.filter { it.interrupted }
        val latency = speech.filter { !it.interrupted && it.endS != null }.map { it.endS!! - it.lastWordEnd!! }.sorted()
        val noEnd = speech.filter { it.endS == null }
        val falseSpeech = out.filter { it.kind == "no_speech" && it.endedBy != null && it.endedBy != "silence" }
        val med = latency[latency.size / 2]
        val p90 = latency[(latency.size * 9) / 10]

        File(repo, "diagnostics/s04-kotlin.json").writeText(
            buildJsonObject {
                put("candidate_ms", ModelConfig.TURN_CANDIDATE_MS)
                put("limit_ms", ModelConfig.TURN_LIMIT_MS)
                put("rejudge_ms", ModelConfig.TURN_REJUDGE_MS)
                put("records", buildJsonArray {
                    out.forEach { r ->
                        add(buildJsonObject {
                            put("base", r.base)
                            put("end_s", r.endS?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("ended_by", r.endedBy)
                            put("interrupted", r.interrupted)
                            put("cancels", buildJsonArray { r.cancels.forEach { (a, b) -> add(buildJsonArray { add(JsonPrimitive(a)); add(JsonPrimitive(b)) }) } })
                        })
                    }
                })
            }.toString(),
        )
        println(
            "S04: перебил ${interrupted.size} (с паузой ${interrupted.count { it in paused }} из ${paused.size}), " +
                "отменено ${speech.count { it.cancels.isNotEmpty() }}, задержка медиана ${"%.2f".format(med)} с, p90 ${"%.2f".format(p90)} с, " +
                "долгий конец ${latency.count { it > 3.0 } + noEnd.size}, нет конца ${noEnd.size}, ложная речь ${falseSpeech.size}; " +
                "перебил: ${interrupted.map { it.base.substringAfter('_').substringAfter('_') }}",
        )
        // Цели брифа, кроме «перебил»: он — на паузах после законченного предложения (docs/research/04-turn-end.md).
        assertTrue(med <= 1.5)
        assertTrue(p90 <= 2.5)
        assertTrue(latency.count { it > 3.0 } + noEnd.size <= 4)
        assertEquals(0, noEnd.size)
        assertTrue(falseSpeech.size <= 1)
        assertTrue(interrupted.size <= 8)
        assertTrue(interrupted.all { it.kind == "long" })
    }

    private fun run(base: String, condition: String, kind: String, lwe: Double?, partial: List<Partial>, teacherS: Double): Result {
        val pcm = WavPcm.read(File(dir, "$base.wav").readBytes()).pcm
        val d = VadTurnDetector(SileroVad(TestSilero.model), rule = TurnEndRule())
        val rate = ModelConfig.RECORD_SAMPLE_RATE.toDouble()
        var pos = 0
        fun now() = pos / rate
        fun text(): String = partial.lastOrNull { it.t <= now() }?.text.orEmpty()
        val cancels = mutableListOf<Pair<Double, Double>>()
        var heard = false
        var continuation = false
        var resumed = false
        var segStart = 0
        var lastEnd: Double? = null
        var endedBy: String? = null
        fun startSegment() {
            heard = false
            d.setContext(TurnContext(text = ::text, continuation = continuation, onHeardSpeech = { heard = true }))
            d.reset()
            segStart = pos
        }
        fun final() = Result(base, condition, kind, lwe, lastEnd, endedBy, lwe != null && lastEnd!! < lwe - 0.2, cancels)
        startSegment()
        while (pos < pcm.size) {
            val n = minOf(1280, pcm.size - pos)
            val got = d.onAudio(pcm.copyOfRange(pos, pos + n), n)
            pos += n
            if (continuation && !resumed) {
                if (heard) {
                    resumed = true // ученик заговорил раньше учителя: ответ отменён, реплика идёт дальше
                    cancels += lastEnd!! to now()
                } else if (now() >= lastEnd!! + teacherS) {
                    return final() // учитель зазвучал
                }
            }
            if (got == null) continue
            if (got == TurnEnd.SILENCE && !d.heardSpeech) return Result(base, condition, kind, lwe, null, "silence", false, cancels)
            if (got == TurnEnd.LIMIT) return Result(base, condition, kind, lwe, now(), "limit", false, cancels)
            lastEnd = segStart / rate + d.endMs!! / 1000.0
            endedBy = got.label
            continuation = true
            resumed = false
            startSegment()
        }
        return if (continuation && !resumed) final() else Result(base, condition, kind, lwe, null, null, false, cancels)
    }
}
