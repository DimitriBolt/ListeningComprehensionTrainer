package com.example.listeningtrainer

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import kotlin.coroutines.resume

/**
 * Данные для одного фрагмента озвучки (чанка)
 */
data class TtsChunk(val text: String, val pauseMs: Long)

class TrainerViewModel(application: Application) : AndroidViewModel(application) {

    private val PREFS_NAME = "trainer_prefs"
    private val KEY_API_KEY = "openai_api_key"

    var statusText by mutableStateOf("Ready. Please enter API key.")
    var isProcessing by mutableStateOf(false)
    var apiKey by mutableStateOf("")
    var isKeyValid by mutableStateOf(false)
    var teacherResponse by mutableStateOf("")
    var recognizedText by mutableStateOf("")
    var currentSpokenChunk by mutableStateOf("")
    var isAutoMode by mutableStateOf(false)
    var autoRecordTrigger by mutableStateOf(0)
    private var silenceCount = 0 // Счетчик последовательного молчания

    private var mediaPlayer: MediaPlayer? = null
    private var shouldStopPlayback = false
    private val chatHistory = mutableListOf<Message>()

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE })
        .dispatcher(Dispatcher().apply { maxRequests = 50; maxRequestsPerHost = 50 })
        .build()

    private val openAiService: OpenAiService by lazy {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(httpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenAiService::class.java)
    }

    init {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY_API_KEY, "") ?: ""
        if (savedKey.isNotEmpty()) {
            apiKey = savedKey
            validateKey()
        }
    }

    fun stopAll() {
        isAutoMode = false
        shouldStopPlayback = true
        isProcessing = false
        currentSpokenChunk = ""
        statusText = "Stopped."
        try { mediaPlayer?.stop(); mediaPlayer?.reset() } catch (e: Exception) {}
    }

    fun validateKey() {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return
        viewModelScope.launch {
            try {
                isProcessing = true
                statusText = "Validating key..."
                val response = openAiService.checkKey("Bearer $cleanKey", ChatRequest(model = "gpt-4o-mini", messages = listOf(Message("user", "Hi"))))
                if (response.choices.isNotEmpty()) {
                    isKeyValid = true; statusText = "Key valid ✅"
                    getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_API_KEY, cleanKey).apply()
                }
            } catch (e: Exception) { statusText = "Key error: ${e.localizedMessage}" }
            finally { isProcessing = false }
        }
    }

    fun processVoiceRecording(audioFile: File, cacheDir: File) {
        val cleanKey = apiKey.trim()
        val textType = "text/plain".toMediaType()
        viewModelScope.launch {
            try {
                isProcessing = true
                shouldStopPlayback = false
                statusText = "Transcribing..."
                val filePart = MultipartBody.Part.createFormData("file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaType()))
                val response = openAiService.transcribeAudio("Bearer $cleanKey", filePart, "whisper-1".toRequestBody(textType), "en".toRequestBody(textType))
                recognizedText = response.text
                if (response.text.isBlank() || response.text.length < 2) {
                    silenceCount++
                    if (isAutoMode && silenceCount >= 3) {
                        statusText = "Auto-mode: Inactivity timeout."
                        getTeacherReply("SYSTEM_INACTIVITY_TIMEOUT", cacheDir)
                    } else {
                        isAutoMode = false; isProcessing = false; statusText = "Nothing heard."
                    }
                } else {
                    silenceCount = 0 // Сбрасываем при успешном распознавании
                    getTeacherReply(response.text, cacheDir)
                }
            } catch (e: Exception) { statusText = "Whisper error: ${e.localizedMessage}"; isProcessing = false }
        }
    }

    private fun getTools(): List<Tool> {
        return listOf(
            Tool(function = FunctionDef(
                name = "get_weather",
                description = "Get REAL-TIME weather for a city.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("location", buildJsonObject { put("type", "string"); put("description", "City (e.g. Tucson)") })
                    })
                    put("required", buildJsonArray { add("location") })
                }
            )),
            Tool(function = FunctionDef(
                name = "search_live_web",
                description = "Search internet for CURRENT 2026 data, prices, or news. Provide keywords ONLY.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject { put("type", "string"); put("description", "Keywords (e.g. 'gas price Tucson', 'US President')") })
                    })
                    put("required", buildJsonArray { add("query") })
                }
            ))
        )
    }

    private suspend fun callTool(name: String, args: String): String {
        return withContext(Dispatchers.IO) {
            val jsonArgs = Json.parseToJsonElement(args).jsonObject
            when (name) {
                "get_weather" -> {
                    val loc = jsonArgs["location"]?.jsonPrimitive?.content ?: "London"
                    try {
                        val response = httpClient.newCall(Request.Builder().url("https://wttr.in/$loc?format=%l:+%C+%t").build()).execute()
                        response.body?.string() ?: "Weather unavailable"
                    } catch (e: Exception) { "Weather failed" }
                }
                "search_live_web" -> {
                    val query = jsonArgs["query"]?.jsonPrimitive?.content ?: ""
                    try {
                        val encodedQuery = URLEncoder.encode(query, "UTF-8")
                        val url = "https://duckduckgo.com/lite/?q=$encodedQuery"
                        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36").build()
                        val html = httpClient.newCall(request).execute().body?.string() ?: ""
                        
                        // Собираем ВСЕ текстовые блоки из результатов для GPT
                        val snippets = mutableListOf<String>()
                        val matcher = Pattern.compile("<td class=\"result-snippet\">(.*?)</td>", Pattern.DOTALL).matcher(html)
                        while (matcher.find()) {
                            snippets.add(matcher.group(1)?.replace(Regex("<.*?>"), "")?.trim() ?: "")
                        }
                        
                        if (snippets.isNotEmpty()) {
                            snippets.take(6).joinToString("\n---\n")
                        } else {
                            // Фолбек: если сниппеты не найдены, отдаем первые 2000 знаков текста страницы
                            val cleanText = html.replace(Regex("<.*?>"), " ").replace(Regex("\\s+"), " ").take(2000)
                            "Raw results for '$query': $cleanText"
                        }
                    } catch (e: Exception) { "Search failed: ${e.localizedMessage}" }
                }
                else -> "Unknown tool"
            }
        }
    }

    private fun playProcessingBeep() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 70) 
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150) 
        } catch (e: Exception) {}
    }

    private fun playInvitationBeep() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 50) // Чуть тише
            toneGen.startTone(ToneGenerator.TONE_SUP_PIP, 100) // Высокий короткий сигнал
        } catch (e: Exception) {}
    }

    fun getTeacherReply(studentText: String, cacheDir: File) {
        val cleanKey = apiKey.trim()
        val currentDate = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.US).format(Date())

        viewModelScope.launch {
            try {
                isProcessing = true
                teacherResponse = ""
                statusText = "Teacher is thinking... 🧠"
                playProcessingBeep() // <--- ПОДАЕМ СИГНАЛ
                
                if (chatHistory.isEmpty()) {
                    val systemPrompt = """
                        You are a patient English teacher. Today is $currentDate.
                        Your memory is limited to 2023.
                        
                        CRITICAL INSTRUCTIONS:
                        1. For ANY info about prices, leaders, or news in $currentDate, YOU MUST CALL 'search_live_web'.
                        2. Read the search results carefully. If they contain data (like Donald Trump or a price), USE IT.
                        3. If search_live_web returns data, NEVER say "I only know up to 2023".
                        4. If results are truly empty, ADMIT IT and ask for a simpler query.
                        5. Use short sentences, check understanding, and end with a question.
                        6. If the student makes a clear grammatical error or if the transcription suggests a phonetic mistake, briefly and kindly correct it first (e.g., "I think you meant '...', right?"). Then continue the conversation.
                    """.trimIndent()
                    chatHistory.add(Message("system", systemPrompt))
                }
                chatHistory.add(Message("user", studentText))

                // Обработка системного события "Тайм-аут бездействия"
                val actualPrompt = if (studentText == "SYSTEM_INACTIVITY_TIMEOUT") {
                    isAutoMode = false // Выключаем авто-режим после этого ответа
                    "The student has been silent for several minutes. Gently say that you'll take a break and they can click the button to continue later. Keep it very short."
                } else {
                    studentText
                }
                
                // Если это был тайм-аут, мы подменяем последнее сообщение пользователя на инструкцию для учителя
                if (studentText == "SYSTEM_INACTIVITY_TIMEOUT") {
                    chatHistory.removeAt(chatHistory.size - 1)
                    chatHistory.add(Message("user", actualPrompt))
                }

                var response = openAiService.checkKey("Bearer $cleanKey", ChatRequest(model = "gpt-4o-mini", messages = chatHistory, tools = getTools()))
                var currentMessage = response.choices.first().message
                val toolCalls = currentMessage.tool_calls
                var toolDataFound = false

                if (toolCalls != null) {
                    chatHistory.add(currentMessage)
                    for (toolCall in toolCalls) {
                        statusText = "Accessing internet... 🌐"
                        val result = callTool(toolCall.function.name, toolCall.function.arguments)
                        if (result.length > 50 && !result.contains("failed", true)) toolDataFound = true
                        
                        statusText = "Received ${result.length} chars from web."
                        delay(800)
                        chatHistory.add(Message(role = "tool", tool_call_id = toolCall.id, content = result, name = toolCall.function.name))
                    }
                    response = openAiService.checkKey("Bearer $cleanKey", ChatRequest(model = "gpt-4o-mini", messages = chatHistory))
                    currentMessage = response.choices.first().message
                }

                var finalContent = currentMessage.content ?: ""
                if (toolCalls != null) {
                    finalContent = if (toolDataFound) "(Live Web Data): $finalContent" else "(Search attempted, fallback): $finalContent"
                }

                chatHistory.add(currentMessage)
                teacherResponse = finalContent

                if (finalContent.isNotEmpty()) {
                    val chunks = splitIntoChunks(finalContent)
                    val deferredFiles = chunks.mapIndexed { index, chunk ->
                        async(Dispatchers.IO) {
                            // Удаляем ** из текста только для озвучки.
                            // Добавляем 8 точек (~1.2 сек тишины) для "железобетонного" прогрева Bluetooth.
                            val ttsInput = chunk.text.replace("**", "")
                            val res = openAiService.generateSpeech("Bearer $cleanKey", TtsRequest(input = " . . . . . . . . " + ttsInput, speed = 0.88f))
                            val file = File(cacheDir, "chunk_$index.mp3")
                            res.byteStream()?.use { i -> FileOutputStream(file).use { o -> i.copyTo(o) } }
                            file
                        }
                    }
                    for (i in chunks.indices) {
                        if (shouldStopPlayback) break
                        val audioFile = deferredFiles[i].await()
                        currentSpokenChunk = chunks[i].text
                        statusText = "Speaking ${i+1}/${chunks.size} 🔊"
                        playAudioAndWait(audioFile)
                        if (i < chunks.size - 1 && !shouldStopPlayback) delay(chunks[i].pauseMs)
                    }
                    currentSpokenChunk = ""
                    if (!shouldStopPlayback) {
                        statusText = "Ready."
                        
                        // --- [3] ПАУЗА ПЕРЕД АВТО-ОТВЕТОМ (Auto-mode) ---
                        // Время (в миллисекундах) между концом речи учителя и включением микрофона.
                        // Увеличьте это значение, чтобы дать себе больше времени на подготовку ответа.
                        if (isAutoMode) { 
                            delay(700) 
                            playInvitationBeep() // <--- СИГНАЛ "ВАША ОЧЕРЕДЬ"
                            autoRecordTrigger++ 
                        }
                    }
                }
            } catch (e: Exception) { statusText = "Error: ${e.localizedMessage}" }
            finally { isProcessing = false }
        }
    }

    /**
     * Разрезает текст на части для комфортного прослушивания.
     * Здесь настраиваются паузы между фразами и логика дробления.
     */
    private fun splitIntoChunks(text: String): List<TtsChunk> {
        val preparedText = text.replace(Regex("(?<!\\d\\.)\\s*(\\*\\*.*?\\*\\*)"), "\n$1\n")
        val paragraphs = preparedText.split(Regex("\\n+")).filter { it.trim().isNotEmpty() }
        val allChunks = mutableListOf<TtsChunk>()

        // [1] Базовый split: знаки препинания и союзы
        val splitRegex = Regex("(?<=[,!?;:])\\s+|(?<=[.])(?<!\\b\\d\\.)\\s+|\\s+(?=(?i)and|but|because|if|when|then|after|before|that|which|who|so|or|although|while|since|as)\\b")

        paragraphs.forEachIndexed { pIndex, pText ->
            val pChunksRaw = pText.split(splitRegex).filter { it.trim().isNotEmpty() }
            
            // [2] ВТОРИЧНОЕ ДРОБЛЕНИЕ: Если фраза > 6 слов, режем её пополам для педагогических целей.
            val pedagogicalChunks = mutableListOf<String>()
            for (rawChunk in pChunksRaw) {
                val words = rawChunk.split(" ")
                if (words.size > 6) {
                    val mid = words.size / 2
                    pedagogicalChunks.add(words.subList(0, mid).joinToString(" "))
                    pedagogicalChunks.add(words.subList(mid, words.size).joinToString(" "))
                } else {
                    pedagogicalChunks.add(rawChunk)
                }
            }

            val pChunks = pedagogicalChunks.mapIndexed { cIndex, t ->
                val trimmed = t.trim()
                val p = when {
                    cIndex == pedagogicalChunks.size - 1 && pIndex < paragraphs.size - 1 -> 1600L
                    trimmed.startsWith("**") && trimmed.endsWith("**") -> 1100L
                    trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?") -> 1000L
                    trimmed.endsWith(",") || trimmed.endsWith(":") || trimmed.endsWith(";") -> 600L
                    else -> 250L
                }
                // [3] ОПЕРЕЖАЮЩИЙ ЗАПУСК: Вычитаем 1000мс, так как в файле теперь ~1.2 сек тишины.
                // Это заставит телефон начать "трансляцию" тишины в наушники почти мгновенно.
                TtsChunk(trimmed, maxOf(10L, p - 1000L))
            }
            allChunks.addAll(pChunks)
        }
        return allChunks
    }

    /**
     * Проигрывает аудио и ждет окончания.
     */
    private suspend fun playAudioAndWait(file: File) {
        val player = mediaPlayer ?: MediaPlayer().also { mediaPlayer = it }
        try {
            player.reset(); player.setDataSource(file.absolutePath); player.prepare()
            
            // Минимальный delay перед стартом, основная тишина уже в файле
            delay(50)

            suspendCancellableCoroutine { cont ->
                player.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                player.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
                if (!shouldStopPlayback) player.start() else if (cont.isActive) cont.resume(Unit)
            }
        } catch (e: Exception) {}
    }

    fun playDebugAudio(file: File) { viewModelScope.launch { playAudioAndWait(file) } }

    override fun onCleared() { super.onCleared(); mediaPlayer?.release() }
}
