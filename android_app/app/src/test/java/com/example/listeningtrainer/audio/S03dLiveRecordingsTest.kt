package com.example.listeningtrainer.audio

import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.LiveResult
import com.example.listeningtrainer.conversation.RecordedAudio
import com.example.listeningtrainer.conversation.TranscriptionPrompt
import com.example.listeningtrainer.conversation.VadTurnDetector
import com.example.listeningtrainer.openai.FileTranscriber
import com.example.listeningtrainer.openai.OkHttpLiveSocketFactory
import com.example.listeningtrainer.openai.RealtimeTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.Properties

/**
 * S03d, п. 5: потоковое распознавание кодом приложения ([RealtimeTranscriber] — сессия, `prompt`, `commit`,
 * запасной путь; конец записи — [VadTurnDetector] + [SileroVad]) на 48 записях пользователя из S03b, с настоящим
 * ключом. Для сравнения — файл ([FileTranscriber], `gpt-transcribe`) с тем же контекстом.
 *
 * Стоит денег (≈ $0,2), поэтому идёт только по явной просьбе: `S03D_LIVE=1 ./gradlew testDebugUnitTest
 * --tests '*S03dLiveRecordingsTest'`. Без переменной, без `diagnostics/recordings/` или без
 * `credentials.properties` тест пропускается. Ключ нигде не печатается.
 *
 * Как в приложении:
 * - звук кусками по 80 мс **в темпе реального времени** идёт в поток с первого куска и в детектор;
 * - конец — по Silero (3000 мс тишины после речи) или по кнопке (время кнопки — как в S03c);
 * - сессия одна на «разговор»: 4 сессии параллельно, каждая служит 12 записям подряд (`session.update` с
 *   `prompt` перед каждой, `commit` или `clear` в конце);
 * - речь была — `commit` и ждать текст не дольше 2500 мс, иначе файл.
 *
 * Отличия от приложения (записаны в отчёте):
 * - ложный конец на паузе обдумывания (D65, задача S04) не останавливает запись: иначе точность мерилась бы на
 *   половине фразы. Запись кончается на первом конце **после** конца речи по разметке S03b;
 * - файл — WAV, а не m4a (на JVM нет MediaCodec).
 *
 * Итог: `diagnostics/s03d-live.json` (с текстами, вне Git) и `docs/research/03d-live.md` (числа).
 */
class S03dLiveRecordingsTest {
    private val repo = File("../..").canonicalFile
    private val recordings = File(repo, "diagnostics/recordings")
    private val labels = File(repo, "docs/research/03b-endpoint.json")
    private val credentials = File(repo, "credentials.properties")

    private class Line(
        val base: String, val condition: String, val line: Int, val kind: String, val text: String,
        val wav: File, val pressMs: Long, val speechEndS: Double?,
    )

    private class Outcome(
        val line: Line, val endedBy: String, val endS: Double, val falseEnds: Int, val heard: Boolean,
        val path: String, val liveText: String?, val liveFailure: String?, val commitToTextMs: Long?,
        val deltas: Int, val sessionWaitMs: Long, val reused: Boolean, val liveCost: Double,
        val fileText: String?, val fileMs: Long?, val text: String?,
    )

    @Test
    fun `live transcription on the S03b recordings meets the brief targets`() {
        assumeTrue("set S03D_LIVE=1 to run (costs ≈ \$0.2)", System.getenv("S03D_LIVE") == "1")
        assumeTrue("no diagnostics/recordings — skipped", recordings.isDirectory)
        assumeTrue("no credentials.properties — skipped", credentials.isFile)
        val key = Properties().apply { credentials.reader().use(::load) }.getProperty("APIkey", "").trim()
        assumeTrue("no APIkey", key.isNotEmpty())

        val lines = loadLines()
        // Оценка цены сверху: весь звук до кнопки — в поток и в файл.
        val minutes = lines.sumOf { it.pressMs } / 60_000.0
        val estimate = minutes * (ModelConfig.LIVE_TRANSCRIBE_PRICE_PER_MIN + FILE_PRICE_PER_MIN)
        println("S03d live: ${lines.size} recordings, ${"%.1f".format(Locale.US, minutes)} min of audio, estimate ≤ \$${"%.2f".format(Locale.US, estimate)}")
        assertTrue("estimate \$$estimate is over the \$1 budget", estimate <= 1.0)

        val prompt = TranscriptionPrompt.build(null, ModelConfig.TRANSCRIBE_PROMPT_TEACHER_CHARS)
        val file = FileTranscriber({ key })
        val tmp = File(repo, "diagnostics/s03d-tmp").apply { mkdirs() }
        val started = System.currentTimeMillis()
        // 4 «разговора» — по сессии на каждый; записи вперемешку по условиям.
        val groups = lines.withIndex().groupBy({ it.index % SESSIONS }, { it.value })
        val out = runBlocking(Dispatchers.IO) {
            groups.values.map { group ->
                async {
                    val live = RealtimeTranscriber(OkHttpLiveSocketFactory({ key }))
                    live.prepare()
                    try {
                        group.map { run(it, live, file, prompt, tmp) }
                    } finally {
                        live.release()
                    }
                }
            }.awaitAll().flatten()
        }.sortedWith(compareBy({ CONDITIONS.indexOf(it.line.condition) }, { it.line.line }))
        tmp.deleteRecursively()

        val report = report(out, (System.currentTimeMillis() - started) / 1000)
        println(report.md)
        File(repo, "diagnostics/s03d-live.json").writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), toJson(out, report)))
        File(repo, "docs/research/03d-live.md").writeText(report.md)

        // Цели п. 5 брифа.
        assertTrue(report.md, report.totalCost <= 1.0)
        assertTrue(report.md, report.accuracyLive >= 0.90)
        for (c in CONDITIONS) assertTrue("$c: ${report.md}", report.byCondition.getValue(c).first >= report.byCondition.getValue(c).second - 1e-9)
        assertTrue(report.md, report.commitMed <= 600 && report.commitP90 <= 1000)
        assertTrue(report.md, report.fallbacks == 0)
    }

    private fun loadLines(): List<Line> {
        val json = Json { ignoreUnknownKeys = true }
        val doc = json.parseToJsonElement(labels.readText()).jsonObject
        val labelBy = doc.getValue("recordings").jsonArray.associate { it.jsonObject.str("base") to it.jsonObject }
        return recordings.listFiles { f -> f.name.startsWith("ep_") && f.name.endsWith(".json") }.orEmpty()
            .map { it to json.parseToJsonElement(it.readText()).jsonObject }
            .filter { (f, _) -> File(f.path.removeSuffix(".json") + ".wav").isFile }
            .groupBy { (_, m) -> m.str("condition") to m.getValue("line").jsonPrimitive.int }
            .map { (_, v) -> v.maxBy { (_, m) -> m.getValue("startedAtMs").jsonPrimitive.long * 100 + m.getValue("take").jsonPrimitive.int } }
            .map { (f, m) ->
                val base = f.name.removeSuffix(".json")
                val lab = labelBy.getValue(base)
                val durationMs = m.getValue("durationMs").jsonPrimitive.long
                val walls = m["levelWallMs"]?.jsonArray?.map { it.jsonPrimitive.long }.orEmpty()
                val press = m["buttonPressMs"]?.jsonPrimitive?.longOrNull
                val pressMs = if (press == null) durationMs else if (walls.isEmpty()) press else durationMs - (walls.last() - press)
                Line(
                    base, m.str("condition"), m.getValue("line").jsonPrimitive.int, lab.str("kind"), lab.str("text"),
                    File(f.path.removeSuffix(".json") + ".wav"), pressMs, lab["last_word_end_s"]?.jsonPrimitive?.doubleOrNull,
                )
            }
            .also { check(it.size == 48) { "expected 48 recordings, got ${it.size}" } }
    }

    private suspend fun run(l: Line, live: RealtimeTranscriber, file: FileTranscriber, prompt: String, tmp: File): Outcome {
        val pcm = WavPcm.read(l.wav.readBytes()).pcm
        val d = VadTurnDetector(SileroVad(TestSilero.model))
        d.reset()
        live.prepare()
        val turn = live.begin(prompt)
        val piece = ShortArray(SAMPLES_80MS)
        var pos = 0
        var falseEnds = 0
        var inFalseEnd = false
        var endedBy = "button"
        val t0 = System.nanoTime()
        while (pos < pcm.size && pos.toLong() * 1000 / 16_000 < l.pressMs) {
            val n = minOf(piece.size, pcm.size - pos)
            System.arraycopy(pcm, pos, piece, 0, n)
            turn.onPcm(piece, n)
            val end = d.onAudio(piece, n)
            pos += n
            // темп реального времени: кусок 80 мс — не раньше, чем он был бы записан
            val due = t0 + pos * 1_000_000_000L / 16_000
            val wait = (due - System.nanoTime()) / 1_000_000
            if (wait > 0) delay(wait)
            if (end != null) {
                val atS = d.audioMs / 1000.0
                if (l.speechEndS == null || atS >= l.speechEndS - 0.2) {
                    endedBy = end.label
                    break
                }
                if (!inFalseEnd) falseEnds++
                inFalseEnd = true
            } else {
                inFalseEnd = false
            }
        }
        val endS = pos / 16_000.0
        val heard = d.heardSpeech
        turn.onEnd(heard)
        if (!heard) {
            return Outcome(l, endedBy, endS, falseEnds, false, "silence", null, null, null, 0, 0, false, 0.0, null, null, null)
        }
        // Файл — для сравнения (и запасной путь): та же запись до конца, тот же контекст.
        val wav = File(tmp, "${l.base}.wav").apply { writeBytes(wavBytes(pcm, pos)) }
        val (res, fileDone) = kotlinx.coroutines.coroutineScope {
            val fileJob = async {
                val s = System.nanoTime()
                val text = runCatching { file.transcribe(RecordedAudio(wav, durationMs = pos / 16L, heardSpeech = true, peakDb = 0f), prompt) }
                text to (System.nanoTime() - s) / 1_000_000
            }
            turn.result() to fileJob.await()
        }
        val (fileResult, fileMs) = fileDone
        val fileText = fileResult.getOrNull()
        return when (res) {
            is LiveResult.Text -> Outcome(
                l, endedBy, endS, falseEnds, true, "live", res.text, null, res.stats.commitToTextMs, res.stats.deltas,
                res.stats.sessionWaitMs, res.stats.reused, res.stats.costUsd, fileText, fileMs, res.text,
            )
            is LiveResult.Failed -> Outcome(
                l, endedBy, endS, falseEnds, true, "file", null, res.reason, null, res.stats.deltas,
                res.stats.sessionWaitMs, res.stats.reused, 0.0, fileText, fileMs, fileText,
            )
        }
    }

    // ---- меры -------------------------------------------------------------------------------

    private class Report(
        val md: String, val accuracyLive: Double, val byCondition: Map<String, Pair<Double, Double>>,
        val commitMed: Double, val commitP90: Double, val fallbacks: Int, val totalCost: Double,
    )

    private fun report(out: List<Outcome>, elapsedS: Long): Report {
        val speech = out.filter { it.line.kind != "no_speech" }
        val heard = speech.filter { it.heard }
        fun acc(o: Outcome, t: String?) = accuracy(o.line.text, t.orEmpty())
        val accLive = heard.map { acc(it, it.text) }.average()
        val accFile = heard.map { acc(it, it.fileText) }.average()
        val byCond = CONDITIONS.associateWith { c ->
            val h = heard.filter { it.line.condition == c }
            h.map { acc(it, it.text) }.average() to h.map { acc(it, it.fileText) }.average()
        }
        val commit = heard.mapNotNull { it.commitToTextMs?.toDouble() }.sorted()
        val fileMs = heard.mapNotNull { it.fileMs?.toDouble() }.sorted()
        val fallbacks = heard.count { it.path == "file" }
        val liveCost = out.sumOf { it.liveCost }
        val fileCost = heard.sumOf { it.endS } / 60 * FILE_PRICE_PER_MIN
        val audioMin = out.sumOf { it.endS } / 60
        val noSpeechCommitted = out.count { it.line.kind == "no_speech" && it.heard }

        // «Лишнее» — слова текста, которых нет в эталоне (после выравнивания); в хвосте записи — шум 3 с.
        fun extra(o: Outcome, t: String?): List<String> {
            val ref = wordsOf(o.line.text)
            val hyp = wordsOf(t.orEmpty())
            return unmatched(ref, hyp)
        }
        val extraLive = heard.filter { extra(it, it.text).isNotEmpty() }
        val extraFile = heard.filter { extra(it, it.fileText).isNotEmpty() }

        val s03b = Json { ignoreUnknownKeys = true }.parseToJsonElement(labels.readText()).jsonObject
        val probeLive = s03b.getValue("live_stats").jsonObject.getValue("live low").jsonObject
        val probeFile = s03b.getValue("file_stats").jsonObject
        fun pAcc(o: JsonObject, c: String?) =
            (if (c == null) o else o.getValue("by_condition").jsonObject.getValue(c).jsonObject).getValue("accuracy").jsonPrimitive.doubleOrNull ?: Double.NaN

        val md = buildString {
            appendLine("# S03d — потоковое распознавание кодом приложения на записях S03b")
            appendLine()
            appendLine("_Прогон: ${java.time.LocalDate.now()}, ${out.size} записей (последний дубль каждой реплики), ${"%.1f".format(Locale.US, audioMin)} мин звука, ${elapsedS / 60} мин ${elapsedS % 60} с; " +
                "цена ≈ \$${f3(liveCost + fileCost)} (поток \$${f3(liveCost)} по `usage`, файл ≈ \$${f3(fileCost)} по длине)._")
            appendLine()
            appendLine("Код приложения: `RealtimeTranscriber` (`${ModelConfig.LIVE_TRANSCRIBE_MODEL}`, `delay: ${ModelConfig.LIVE_TRANSCRIBE_DELAY}`, `prompt` = `TranscriptionPrompt.CONTEXT`), " +
                "4 сессии по 12 записей подряд, звук в темпе реального времени кусками 80 мс, конец — `VadTurnDetector` + `SileroVad` (3000 мс тишины после речи) или кнопка, " +
                "`commit` при речи, ожидание текста ≤ ${ModelConfig.LIVE_TEXT_TIMEOUT_MS} мс, иначе файл. Файл для сравнения — `FileTranscriber` (`gpt-transcribe`, WAV до того же конца, тот же `prompt`). " +
                "Точность — доля слов сценария, найденных при выравнивании (как `accuracy` пробы S03b, «um» не считается).")
            appendLine()
            appendLine("Ложный конец на паузе обдумывания (D65) запись **не** останавливал: она шла до первого конца после конца речи по разметке S03b. Таких пауз было ${out.sumOf { it.falseEnds }} (в ${out.count { it.falseEnds > 0 }} записях).")
            appendLine()
            appendLine("## Итог")
            appendLine()
            appendLine("| Мера | Цель | Поток (S03d) | Файл (S03d) | Проба S03b: поток / файл |")
            appendLine("|---|---|---|---|---|")
            appendLine("| точность, все 40 реплик | ≥ 90 % и ≥ файла | **${pc(accLive)}** | ${pc(accFile)} | ${pc(pAcc(probeLive, null))} / ${pc(pAcc(probeFile, null))} |")
            for (c in CONDITIONS) {
                val (a, b) = byCond.getValue(c)
                appendLine("| — ${NAMES.getValue(c)} | ≥ файла | ${if (a >= b - 1e-9) "**${pc(a)}**" else "${pc(a)} ✗"} | ${pc(b)} | ${pc(pAcc(probeLive, c))} / ${pc(pAcc(probeFile, c))} |")
            }
            appendLine("| `commit → text`, медиана | ≤ 0,6 с | **${sec(pct(commit, 0.5))} с** | запрос ${sec(pct(fileMs, 0.5))} с | 0,46 с |")
            appendLine("| `commit → text`, p90 | ≤ 1,0 с | **${sec(pct(commit, 0.9))} с** | запрос ${sec(pct(fileMs, 0.9))} с | 0,58 с |")
            appendLine("| `commit → text`, худший | — | ${sec(commit.lastOrNull() ?: Double.NaN)} с | ${sec(fileMs.lastOrNull() ?: Double.NaN)} с | — |")
            appendLine("| запасной путь (файл) | 0 | **$fallbacks из ${heard.size}** | — | — |")
            appendLine("| без речи (8 записей): `commit` | 0 | **$noSpeechCommitted** (`clear`) | — | — |")
            appendLine("| реплик с лишними словами | — | ${extraLive.size} из ${heard.size} | ${extraFile.size} из ${heard.size} | — |")
            appendLine()
            // Ожидание меньше 50 мс — такт часов между началом реплики и проверкой открытой сессии, а не ожидание.
            appendLine("Сессия: звук ждал открытия ${heard.count { it.sessionWaitMs > 50 }} раз из ${heard.size} (первая реплика новой сессии; ${heard.filter { !it.reused }.map { it.sessionWaitMs }} мс — в тесте сессия открывается прямо перед репликой, звук копился и ушёл после открытия), остальные — `reused`, 0–2 мс.")
            appendLine()
            if (fallbacks > 0) {
                appendLine("Сбои потока: ${heard.filter { it.path == "file" }.joinToString { "${it.line.base} (${it.liveFailure})" }}.")
                appendLine()
            }
            appendLine("## Лишнее из шума в хвосте")
            appendLine()
            appendLine("Слова текста, которых нет в сценарии (после выравнивания). В хвосте каждой записи — 3 с тишины или шума до конца.")
            appendLine()
            appendLine("| Запись | Сценарий | Поток: лишнее | Файл: лишнее |")
            appendLine("|---|---|---|---|")
            for (o in heard.filter { extra(it, it.text).isNotEmpty() || extra(it, it.fileText).isNotEmpty() }) {
                appendLine("| ${NAMES.getValue(o.line.condition)} #${o.line.line} | ${o.line.text} | ${extra(o, o.text).joinToString(" ").ifEmpty { "—" }} | ${extra(o, o.fileText).joinToString(" ").ifEmpty { "—" }} |")
            }
            appendLine()
            appendLine("## По записям")
            appendLine()
            appendLine("| Запись | вид | конец | конец, с | после речи, с | путь | commit → text, мс | дельт | точность поток / файл |")
            appendLine("|---|---|---|---|---|---|---|---|---|")
            for (o in out) {
                appendLine("| ${NAMES.getValue(o.line.condition)} #${o.line.line} | ${o.line.kind} | ${o.endedBy} | ${s2(o.endS)} | ${o.line.speechEndS?.let { s2(o.endS - it) } ?: "—"} | ${o.path} | ${o.commitToTextMs ?: "—"} | ${o.deltas} | " +
                    (if (o.heard) "${pc(acc(o, o.text))} / ${pc(acc(o, o.fileText))}" else "—") + " |")
            }
        }
        return Report(md, accLive, byCond, pct(commit, 0.5), pct(commit, 0.9), fallbacks, liveCost + fileCost)
    }

    private fun toJson(out: List<Outcome>, r: Report) = buildJsonObject {
        put("note", "S03d: texts of the user's speech — diagnostics only, never in Git")
        put("recordings", buildJsonArray {
            for (o in out) add(buildJsonObject {
                put("base", o.line.base)
                put("condition", o.line.condition)
                put("line", o.line.line)
                put("script", o.line.text)
                put("ended_by", o.endedBy)
                put("end_s", o.endS)
                put("false_ends", o.falseEnds)
                put("heard", o.heard)
                put("path", o.path)
                put("live", o.liveText?.let(::JsonPrimitive) ?: JsonNull)
                put("live_failure", o.liveFailure?.let(::JsonPrimitive) ?: JsonNull)
                put("file", o.fileText?.let(::JsonPrimitive) ?: JsonNull)
                put("commit_to_text_ms", o.commitToTextMs?.let(::JsonPrimitive) ?: JsonNull)
                put("file_ms", o.fileMs?.let(::JsonPrimitive) ?: JsonNull)
                put("deltas", o.deltas)
                put("session_wait_ms", o.sessionWaitMs)
                put("reused", o.reused)
                put("live_cost", o.liveCost)
            })
        })
        put("total_cost", r.totalCost)
    }

    /** Как `words_of` пробы S03b: нижний регистр, слова `[a-z0-9']+`, без «um». */
    private fun wordsOf(text: String): List<String> =
        Regex("[a-z0-9']+").findAll(text.lowercase().replace('’', '\'').replace("…", " ")).map { it.value }.filter { it !in FILLERS }.toList()

    /** Как `accuracy` пробы S03b: доля слов эталона, совпавших при выравнивании (LCS). */
    private fun accuracy(reference: String, hypothesis: String): Double {
        val ref = wordsOf(reference)
        if (ref.isEmpty()) return Double.NaN
        return com.example.listeningtrainer.conversation.TranscriptMatch.matched(ref, wordsOf(hypothesis)).toDouble() / ref.size
    }

    /** Слова [hyp], не вошедшие в наибольшее общее выравнивание с [ref]. */
    private fun unmatched(ref: List<String>, hyp: List<String>): List<String> {
        val dp = Array(ref.size + 1) { IntArray(hyp.size + 1) }
        for (i in 1..ref.size) for (j in 1..hyp.size) {
            dp[i][j] = if (ref[i - 1] == hyp[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
        val extra = mutableListOf<String>()
        var i = ref.size
        var j = hyp.size
        while (j > 0) {
            if (i > 0 && ref[i - 1] == hyp[j - 1]) { i--; j-- }
            else if (i > 0 && dp[i - 1][j] >= dp[i][j - 1]) i--
            else { extra += hyp[j - 1]; j-- }
        }
        return extra.reversed()
    }

    private fun wavBytes(pcm: ShortArray, samples: Int): ByteArray {
        val b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(samples * 2)
        for (i in 0 until samples) b.putShort(pcm[i])
        return b.array()
    }

    private fun pct(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val k = (sorted.size - 1) * q
        val f = kotlin.math.floor(k).toInt()
        val c = kotlin.math.ceil(k).toInt()
        return if (f == c) sorted[f] else sorted[f] + (sorted[c] - sorted[f]) * (k - f)
    }

    private fun pc(v: Double) = if (v.isNaN()) "—" else "${Math.round(v * 100)} %"
    private fun s2(seconds: Double) = if (seconds.isNaN()) "—" else "%.2f".format(Locale.US, seconds)
    private fun sec(ms: Double) = s2(ms / 1000)
    private fun f3(v: Double) = "%.3f".format(Locale.US, v)
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private companion object {
        val CONDITIONS = listOf("silence", "water", "street", "steady_noise")
        val NAMES = mapOf("silence" to "тишина", "water" to "вода", "street" to "улица", "steady_noise" to "ровный шум")
        val FILLERS = setOf("um", "uh", "uhm", "umm", "erm", "er", "hmm", "mm", "ah")
        const val SAMPLES_80MS = 1280
        const val SESSIONS = 4
        /** `gpt-transcribe`, $ за минуту (страница модели; как в пробе S03b). */
        const val FILE_PRICE_PER_MIN = 0.0045
    }
}
