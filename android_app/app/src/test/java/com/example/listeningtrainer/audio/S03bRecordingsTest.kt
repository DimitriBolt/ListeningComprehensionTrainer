package com.example.listeningtrainer.audio

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.TurnEnd
import com.example.listeningtrainer.conversation.VadTurnDetector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * S03c, п. 2: детектор приложения (тот же код — [VadTurnDetector] + [SileroVad]) на 48 записях пользователя
 * из S03b. Записи лежат только в `diagnostics/recordings/` (вне Git, D69); нет папки — тест пропускается.
 *
 * Звук идёт кусками по 80 мс, как в [AudioRecorder]; запись кончается на первом «конце» детектора или на
 * нажатии кнопки (время кнопки — в звук файла, как в пробе: по концу записи). Разметка конца речи — из
 * `docs/research/03b-endpoint.json` (проба S03b). Меры — как в пробе: ложный конец — раньше конца речи
 * больше чем на 0,2 с; задержка — от конца речи до конца записи; нет конца — до кнопки не кончил.
 * Итог по записям (с вероятностями кадров для сверки с Python) — `diagnostics/s03c-kotlin.json`.
 */
class S03bRecordingsTest {
    private val repo = File("../..").canonicalFile
    private val recordings = File(repo, "diagnostics/recordings")
    private val labels = File(repo, "docs/research/03b-endpoint.json")

    private class Outcome(
        val base: String, val condition: String, val kind: String, val speechEndS: Double?,
        val pressMs: Long, val endMs: Long?, val heard: Boolean, val speechMs: Long, val probs: List<Float>,
    )

    @Test
    fun `app detector on the S03b recordings meets the brief targets`() {
        assumeTrue("no diagnostics/recordings — skipped (recordings stay outside Git)", recordings.isDirectory)
        val json = Json { ignoreUnknownKeys = true }
        val doc = json.parseToJsonElement(labels.readText()).jsonObject
        val speechEnd = doc.getValue("recordings").jsonArray.associate {
            val o = it.jsonObject
            o.str("base") to o["last_word_end_s"]?.jsonPrimitive?.doubleOrNull
        }

        // Последний дубль каждой пары (условие, реплика) — как проба.
        val takes = recordings.listFiles { f -> f.name.startsWith("ep_") && f.name.endsWith(".json") }.orEmpty()
            .map { it to json.parseToJsonElement(it.readText()).jsonObject }
            .filter { (f, _) -> File(f.path.removeSuffix(".json") + ".wav").isFile }
            .groupBy { (_, m) -> m.str("condition") to m.getValue("line").jsonPrimitive.int }
            .map { (_, v) -> v.maxBy { (_, m) -> m.getValue("startedAtMs").jsonPrimitive.long * 100 + m.getValue("take").jsonPrimitive.int } }
        assertEquals(48, takes.size)

        val out = takes.map { (file, meta) -> run(file, meta, speechEnd[file.name.removeSuffix(".json")]) }
            .sortedWith(compareBy({ CONDITIONS.indexOf(it.condition) }, { it.base }))

        val speech = out.filter { it.kind != "no_speech" }
        val silent = out.filter { it.kind == "no_speech" }
        assertEquals(40, speech.size)
        val noEnd = speech.filter { it.endMs == null }
        val falseEnd = speech.filter { it.endMs != null && it.endMs / 1000.0 < it.speechEndS!! - 0.2 }
        val latency = (speech - noEnd.toSet() - falseEnd.toSet()).map { it.endMs!! / 1000.0 - it.speechEndS!! }.sorted()
        val falseSpeech = silent.filter { it.heard }
        val latencyMed = median(latency)

        // Было: порог 59 дБ / 3 с на тех же записях (проба S03b, «как есть»).
        val old = doc.getValue("as_recorded").jsonObject.getValue("metrics").jsonObject.getValue("threshold 59 dB / 3000 ms").jsonObject
        val oldFalseEndQuiet = listOf("silence", "steady_noise").sumOf { old.getValue(it).jsonObject.getValue("false_end").jsonPrimitive.int }
        val falseEndQuiet = falseEnd.count { it.condition == "silence" || it.condition == "steady_noise" }

        val report = buildString {
            appendLine("S03c/S03d: Silero p≥${ModelConfig.VAD_SPEECH_PROB} / ${ModelConfig.TURN_END_SILENCE_MS} ms (no speech yet: ${ModelConfig.TURN_START_SILENCE_MS} ms), heard ≥ ${ModelConfig.TURN_MIN_SPEECH_MS} ms")
            appendLine("no end ${noEnd.size} of 40 ${noEnd.map { "${it.base} (button ${"%.2f".format(it.pressMs / 1000.0 - it.speechEndS!!)} s after speech)" }}")
            appendLine("false speech ${falseSpeech.size} of 8 ${falseSpeech.map { it.base }}")
            appendLine("latency median ${"%.2f".format(latencyMed)} s, p90 ${"%.2f".format(pct(latency, 0.9))} s")
            appendLine("false end ${falseEnd.size} of 40, in silence + steady noise $falseEndQuiet (was $oldFalseEndQuiet) ${falseEnd.map { it.base }}")
            for (c in CONDITIONS) {
                val s = speech.filter { it.condition == c }
                val fe = falseEnd.count { it.condition == c }
                val lat = median(s.filter { it.endMs != null && it !in falseEnd }.map { it.endMs!! / 1000.0 - it.speechEndS!! })
                appendLine("  $c: false end $fe, latency median ${"%.2f".format(lat)} s, no end ${s.count { it.endMs == null }}")
            }
            appendLine("speech ms in lines: min ${speech.minOf { it.speechMs }}; without speech: max ${silent.maxOf { it.speechMs }} ${silent.map { it.speechMs }}")
        }
        println(report)
        File(repo, "diagnostics/s03c-kotlin.json").writeText(
            Json { prettyPrint = false }.encodeToString(JsonElement.serializer(), toJson(out, report)),
        )

        // «Нет конца» 0 из 40 недостижимо при тишине 3000 мс: в тихой комнате кнопка нажата через 1,5–2,3 с после
        // речи — раньше, чем кончит любой детектор с тишиной 3 с. Требуем: нет конца только там, где кнопка нажата
        // раньше 3 с тишины (плюс кадр) после последней речи; в воде и на улице — ни одного.
        val tooEarly = noEnd.filter { it.pressMs / 1000.0 - it.speechEndS!! < ModelConfig.TURN_END_SILENCE_MS / 1000.0 + 0.1 }
        assertEquals(report, noEnd.map { it.base }, tooEarly.map { it.base })
        assertTrue(report, noEnd.none { it.condition == "water" || it.condition == "street" })
        assertTrue(report, falseSpeech.size <= 1)
        assertTrue(report, latencyMed <= 3.2)
        assertTrue(report, falseEndQuiet <= oldFalseEndQuiet)
        // heardSpeech: все реплики — «слышал»
        assertTrue(report, speech.all { it.heard })
    }

    private fun run(file: File, meta: JsonObject, speechEndS: Double?): Outcome {
        val audio = WavPcm.read(File(file.path.removeSuffix(".json") + ".wav").readBytes())
        val durationMs = meta.getValue("durationMs").jsonPrimitive.long
        val walls = meta["levelWallMs"]?.jsonArray?.map { it.jsonPrimitive.long }.orEmpty()
        val press = meta["buttonPressMs"]?.jsonPrimitive?.longOrNull
        val pressMs = if (press == null) durationMs else if (walls.isEmpty()) press else durationMs - (walls.last() - press)
        val probs = mutableListOf<Float>()
        val d = VadTurnDetector(SileroVad(TestSilero.model)).apply { onFrame = { _, p -> probs += p } }
        d.reset()
        var end: TurnEnd? = null
        var pos = 0
        val piece = ShortArray(SAMPLES_80MS)
        // как приложение: кусок за куском, пока детектор не кончит или пока не нажата кнопка
        while (end == null && pos < audio.pcm.size && pos.toLong() * 1000 / 16_000 < pressMs) {
            val n = minOf(piece.size, audio.pcm.size - pos)
            System.arraycopy(audio.pcm, pos, piece, 0, n)
            end = d.onAudio(piece, n)
            pos += n
        }
        val endMs = d.endMs?.takeIf { end == TurnEnd.SILENCE && it <= pressMs }
        return Outcome(
            base = file.name.removeSuffix(".json"), condition = meta.str("condition"), kind = meta.str("kind"),
            speechEndS = speechEndS, pressMs = pressMs, endMs = endMs, heard = d.heardSpeech,
            speechMs = d.stats.speechMs, probs = probs,
        )
    }

    private fun toJson(out: List<Outcome>, report: String) = buildJsonObject {
        put("settings", buildJsonObject {
            put("speech_prob", ModelConfig.VAD_SPEECH_PROB.toDouble())
            put("silence_ms", ModelConfig.TURN_END_SILENCE_MS)
            put("start_silence_ms", ModelConfig.TURN_START_SILENCE_MS)
            put("min_speech_ms", ModelConfig.TURN_MIN_SPEECH_MS)
            put("piece_samples", SAMPLES_80MS)
        })
        put("report", report)
        put("recordings", buildJsonArray {
            for (o in out) add(buildJsonObject {
                put("base", o.base)
                put("condition", o.condition)
                put("kind", o.kind)
                put("speech_end_s", o.speechEndS?.let { JsonPrimitive(it) } ?: JsonNull)
                put("press_ms", o.pressMs)
                put("end_ms", o.endMs?.let { JsonPrimitive(it) } ?: JsonNull)
                put("heard", o.heard)
                put("speech_ms", o.speechMs)
                put("probs", JsonArray(o.probs.map { JsonPrimitive(Math.round(it * 1e6) / 1e6) }))
            })
        })
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun median(v: List<Double>) = pct(v.sorted(), 0.5)

    /** Квантиль с линейной интерполяцией — как `pct` в пробе. */
    private fun pct(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val k = (sorted.size - 1) * q
        val f = kotlin.math.floor(k).toInt()
        val c = kotlin.math.ceil(k).toInt()
        return if (f == c) sorted[f] else sorted[f] + (sorted[c] - sorted[f]) * (k - f)
    }

    private companion object {
        val CONDITIONS = listOf("silence", "water", "street", "steady_noise")
        const val SAMPLES_80MS = 1280
    }
}
