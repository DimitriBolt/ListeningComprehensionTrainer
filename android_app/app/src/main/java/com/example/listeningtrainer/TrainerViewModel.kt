package com.example.listeningtrainer

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.SpeechPlanner
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
    private val speechPlanner = SpeechPlanner()

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE })
        .connectTimeout(ModelConfig.CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(ModelConfig.READ_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(ModelConfig.WRITE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .dispatcher(Dispatcher().apply { maxRequests = ModelConfig.MAX_PARALLEL_REQUESTS; maxRequestsPerHost = ModelConfig.MAX_PARALLEL_REQUESTS })
        .build()

    private val openAiService: OpenAiService by lazy {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        Retrofit.Builder()
            .baseUrl(ModelConfig.OPENAI_BASE_URL)
            .client(httpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenAiService::class.java)
    }

    init {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Сохранённый на устройстве ключ главнее; ключ из BuildConfig есть только в debug-сборке.
        val savedKey = (prefs.getString(KEY_API_KEY, "") ?: "").ifEmpty { BuildConfig.DEBUG_API_KEY }
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
                val response = openAiService.checkKey("Bearer $cleanKey", ChatRequest(model = ModelConfig.TUTOR_MODEL, messages = listOf(Message("user", "Hi"))))
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
                val response = openAiService.transcribeAudio("Bearer $cleanKey", filePart, ModelConfig.TRANSCRIBE_MODEL.toRequestBody(textType), ModelConfig.TRANSCRIBE_LANGUAGE.toRequestBody(textType))
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
                        val response = httpClient.newCall(Request.Builder().url(ModelConfig.WEATHER_URL.replace("{location}", loc)).build()).execute()
                        response.body?.string() ?: "Weather unavailable"
                    } catch (e: Exception) { "Weather failed" }
                }
                "search_live_web" -> {
                    val query = jsonArgs["query"]?.jsonPrimitive?.content ?: ""
                    try {
                        val encodedQuery = URLEncoder.encode(query, "UTF-8")
                        val url = ModelConfig.SEARCH_URL + encodedQuery
                        val request = Request.Builder().url(url).header("User-Agent", ModelConfig.SEARCH_USER_AGENT).build()
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

                var response = openAiService.checkKey("Bearer $cleanKey", ChatRequest(model = ModelConfig.TUTOR_MODEL, messages = chatHistory, tools = getTools()))
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
                    response = openAiService.checkKey("Bearer $cleanKey", ChatRequest(model = ModelConfig.TUTOR_MODEL, messages = chatHistory))
                    currentMessage = response.choices.first().message
                }

                var finalContent = currentMessage.content ?: ""
                if (toolCalls != null) {
                    finalContent = if (toolDataFound) "(Live Web Data): $finalContent" else "(Search attempted, fallback): $finalContent"
                }

                chatHistory.add(currentMessage)
                teacherResponse = finalContent

                if (finalContent.isNotEmpty()) {
                    val chunks = speechPlanner.plan(finalContent)
                    val deferredFiles = chunks.mapIndexed { index, chunk ->
                        async(Dispatchers.IO) {
                            // Удаляем ** из текста только для озвучки.
                            // Добавляем 8 точек (~1.2 сек тишины) для "железобетонного" прогрева Bluetooth.
                            val ttsInput = chunk.text.replace("**", "")
                            val res = openAiService.generateSpeech("Bearer $cleanKey", TtsRequest(input = " . . . . . . . . " + ttsInput, speed = ModelConfig.TTS_SPEED))
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
                        // ОПЕРЕЖАЮЩИЙ ЗАПУСК: в начале каждого файла ~1,2 с тишины (щит Bluetooth),
                        // поэтому следующий файл запускаем на 1000 мс раньше эталонной паузы.
                        if (i < chunks.size - 1 && !shouldStopPlayback) delay(maxOf(10L, chunks[i].pauseMs - 1000L))
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
