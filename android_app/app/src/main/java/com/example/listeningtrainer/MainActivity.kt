package com.example.listeningtrainer

import com.example.listeningtrainer.audio.lastUserSpeechFile
import com.example.listeningtrainer.conversation.ConversationState
import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.PauseScale
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.example.listeningtrainer.eventlog.AppLog
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jeziellago.compose.markdowntext.MarkdownText
import com.example.listeningtrainer.endpoint.EndpointRecorder
import com.example.listeningtrainer.endpoint.EndpointScenario

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.event(
            SOURCE,
            if (savedInstanceState == null) "onCreate" else "onCreate: recreated (e.g. screen rotation)",
        )

        // Запрос прав на уведомления для Android 13+ (необходимо для Foreground Service)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TrainerApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppLog.event(SOURCE, "onStart")
        TrainerService.screenVisible.value = true
    }

    override fun onStop() {
        AppLog.event(SOURCE, "onStop (screen off or app in background)")
        TrainerService.screenVisible.value = false
        super.onStop()
    }

    override fun onDestroy() {
        AppLog.event(SOURCE, "onDestroy (configuration change: $isChangingConfigurations, finishing: $isFinishing)")
        super.onDestroy()
    }

    private companion object {
        const val SOURCE = "Activity"
    }
}

@Composable
fun TrainerApp(viewModel: TrainerViewModel = viewModel()) {
    // rememberSaveable: экран переживает поворот (Activity пересоздаётся, разговор в сервисе идёт дальше).
    // Если разговор уже идёт (открыли из уведомления), сразу показываем его.
    var currentScreen by rememberSaveable { mutableStateOf(if (TrainerService.isRunning) "practice" else "setup") }
    LaunchedEffect(currentScreen) { AppLog.event("UI", "screen: $currentScreen") }
    val endpointUi by TrainerService.endpointUi.collectAsState()
    if (endpointUi.active) {
        EndpointRecordingScreen()
    } else if (currentScreen == "setup") {
        KeySetupScreen(viewModel = viewModel, onStartPractice = { currentScreen = "practice" })
    } else {
        PracticeScreen(viewModel = viewModel, onBack = { currentScreen = "setup" })
    }
}

@Composable
fun KeySetupScreen(viewModel: TrainerViewModel, onStartPractice: () -> Unit) {
    val isValidated = viewModel.isKeyValid
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "OpenAI API Setup", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = viewModel.statusText, style = MaterialTheme.typography.bodyMedium, color = if (isValidated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(
            value = viewModel.apiKey,
            onValueChange = { viewModel.apiKey = it },
            label = { Text("Enter your OpenAI API Key") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            enabled = !viewModel.isProcessing,
            singleLine = false,
            maxLines = 5
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (viewModel.isProcessing) {
            CircularProgressIndicator()
        } else {
            Button(modifier = Modifier.fillMaxWidth().height(56.dp), onClick = { viewModel.validateKey() }) {
                Text(if (isValidated) "Re-validate Key" else "Validate Key")
            }
            if (isValidated) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary), onClick = onStartPractice) {
                    Text("Start Listening Practice 🎙️")
                }
            }
        }
    }
}

@Composable
fun PracticeScreen(viewModel: TrainerViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val ui by TrainerService.ui.collectAsState()

    val isListening = ui.state == ConversationState.Listening
    val isBusy = ui.state == ConversationState.Thinking || ui.state == ConversationState.Speaking
    val debugAudioFile = remember { lastUserSpeechFile(context) }
    var permissionStatus by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { if (it) permissionStatus = "Permission granted." }

    // Микрофон разрешён → выполнить команду; нет → спросить разрешение.
    fun withMicPermission(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) action() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                TrainerService.stop(context)
                onBack()
            }) { Text("← Back") }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-mode", style = MaterialTheme.typography.labelSmall)
                Switch(
                    checked = ui.autoMode,
                    onCheckedChange = { enabled ->
                        if (enabled) withMicPermission { TrainerService.setAutoMode(context, true) }
                        else TrainerService.setAutoMode(context, false)
                    },
                    modifier = Modifier.scale(0.7f)
                )
            }

            if (debugAudioFile.exists() && !isListening) {
                TextButton(onClick = { viewModel.playDebugAudio(debugAudioFile) }) { Text("🔈 My Voice") }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(text = "Listening Practice", style = MaterialTheme.typography.headlineSmall)
        StudentLevelLabel()

        if (ui.currentChunk.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                border = CardDefaults.outlinedCardBorder()
            ) {
                MarkdownText(
                    markdown = ui.currentChunk,
                    modifier = Modifier.padding(24.dp).fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(text = "Status: ${permissionStatus.ifEmpty { ui.status }}", style = MaterialTheme.typography.bodySmall)

        if (isListening) {
            Spacer(modifier = Modifier.height(8.dp))
            // ИНДИКАТОР УРОВНЯ ШУМА
            Text(
                "Noise: ${ui.levelDb.toInt()} dB",
                fontWeight = FontWeight.Bold
            )
            LinearProgressIndicator(
                progress = (ui.levelDb / 100f).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().height(8.dp).padding(vertical = 8.dp)
            )
            Text("Listening...", color = Color.Red, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (ui.studentText.isNotEmpty()) {
            Text(text = "You said:", style = MaterialTheme.typography.labelMedium)
            Card(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(text = ui.studentText, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }

        if (ui.teacherText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Teacher:", style = MaterialTheme.typography.labelMedium)
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                MarkdownText(
                    markdown = ui.teacherText,
                    modifier = Modifier.padding(16.dp)
                )
            }
            // Источники поиска (D37): кликабельные ссылки, вслух не читаются.
            if (ui.sources.isNotEmpty()) {
                val uriHandler = LocalUriHandler.current
                Text(text = "Sources:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                ui.sources.forEach { source ->
                    Text(
                        text = source.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { runCatching { uriHandler.openUri(source.url) } }
                            .padding(vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(32.dp))

        // Одна кнопка: Talk → Stop Recording (закончить реплику) → Stop (пока учитель думает или говорит).
        Button(
            modifier = Modifier.fillMaxWidth().height(72.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isListening || isBusy) Color.Red else MaterialTheme.colorScheme.primary
            ),
            onClick = {
                permissionStatus = ""
                when {
                    isListening -> TrainerService.finishRecording(context)
                    isBusy -> TrainerService.stopTalking(context)
                    else -> withMicPermission { TrainerService.talk(context) }
                }
            }
        ) {
            Text(
                when {
                    isListening -> "Stop Recording"
                    isBusy -> "Stop ⏹"
                    else -> "Talk to Teacher 🎙️"
                }
            )
        }

        // Средняя кнопка гарнитуры (D29) делает то же, что большая кнопка: начать запись / закончить реплику.
        Text(
            "Headset middle button: start / finish",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = Modifier.padding(top = 8.dp)
        )

        if (ui.autoMode) {
            Text(
                "Auto-mode is ON: I will listen after I finish speaking.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // История разговора живёт, пока приложение не закрыто совсем (D15); начать с нуля — здесь.
        if (!isListening && !isBusy) {
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = {
                permissionStatus = ""
                TrainerService.newConversation(context)
            }) { Text("New conversation") }
            // Режим записи для пробы конца реплики (S03b) — только в debug-сборке.
            if (EndpointRecorder.available(BuildConfig.DEBUG)) {
                TextButton(onClick = {
                    permissionStatus = ""
                    withMicPermission { TrainerService.endpoint(context, "open") }
                }) { Text("🎙 Endpoint recordings (debug)") }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

/**
 * Уровень ученика мелким текстом (D56). Только в debug-сборке долгое нажатие переключает уровень по кругу
 * 1 → 2 → … → 5 → 1 (ручная проверка: услышать длинные уровни сразу); окно начинается заново.
 * Рядом — личный множитель пауз (S07, D98): «Pauses × 1.15». В debug долгое нажатие — на шаг больше, после верхней
 * границы — нижняя.
 */
@Composable
private fun StudentLevelLabel() {
    val app = LocalContext.current.applicationContext as TrainerApplication
    val studentLevel = app.studentLevel
    val pauseScale = app.pauseScale
    val state by studentLevel.state.collectAsState()
    val scale by pauseScale.state.collectAsState()
    val modifier = if (!BuildConfig.DEBUG) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures(onLongPress = {
            val next = studentLevel.level % ModelConfig.LEVEL_TARGETS.size + 1
            studentLevel.setLevel(next)
            AppLog.event("UI", "level set by hand to $next (debug long press)")
        })
    }
    val scaleModifier = if (!BuildConfig.DEBUG) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures(onLongPress = {
            val up = pauseScale.value * ModelConfig.PAUSE_SCALE_STEP
            val c = pauseScale.set(if (pauseScale.value >= ModelConfig.PAUSE_SCALE_MAX) ModelConfig.PAUSE_SCALE_MIN else up)
            AppLog.event("UI", "pauses × ${PauseScale.format(c.from)} → × ${PauseScale.format(c.to)} set by hand (debug long press)")
        })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Level ${state.level}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = modifier.padding(4.dp),
        )
        Text(
            text = "Pauses × ${PauseScale.format(scale)}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = scaleModifier.padding(4.dp),
        )
    }
}

/**
 * Режим записи для пробы конца реплики (S03b, только debug): выбор условия, реплики сценария по одной.
 * Запись — средняя кнопка гарнитуры (или кнопка здесь); экран только показывает и шлёт команды в сервис.
 */
@Composable
fun EndpointRecordingScreen() {
    val context = LocalContext.current
    val ui by TrainerService.endpointUi.collectAsState()
    val busy = ui.recording || ui.saving
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = !busy, onClick = {
                if (ui.condition == null) TrainerService.endpoint(context, "close") else TrainerService.endpoint(context, "conditions")
            }) { Text(if (ui.condition == null) "← Close" else "← Conditions") }
            Text("Endpoint recordings (debug)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
        Spacer(modifier = Modifier.height(16.dp))
        val condition = ui.condition
        // Без раннего return из лямбды Column: компилятор Compose 1.5 на нём падает при показе экрана.
        if (condition == null) EndpointConditionChooser() else EndpointLinePanel(ui, condition, busy)
    }
}

/** Выбор условия записи. */
@Composable
private fun EndpointConditionChooser() {
    val context = LocalContext.current
    Text("Choose the condition", style = MaterialTheme.typography.headlineSmall)
    Spacer(modifier = Modifier.height(16.dp))
    EndpointScenario.Condition.values().forEachIndexed { i, c ->
        Button(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(56.dp),
            onClick = { TrainerService.endpoint(context, "condition", i) },
        ) { Text(c.label) }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Keep the phone where it is in normal practice (in your pocket or in your hand). Wear the headphones.",
        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
    )
}

/** Реплика сценария: текст, указание, запись, переход между репликами. */
@Composable
private fun EndpointLinePanel(ui: com.example.listeningtrainer.endpoint.EndpointUi, condition: EndpointScenario.Condition, busy: Boolean) {
    val context = LocalContext.current
    val line = ui.line
    Text(condition.label, style = MaterialTheme.typography.titleMedium)
    Text(
        "Line ${line.number} of ${EndpointScenario.lines.size}" + if (ui.takes > 0) " · recorded ${ui.takes}×" else "",
        style = MaterialTheme.typography.labelMedium, color = Color.Gray,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Text(line.shownText, modifier = Modifier.padding(24.dp), fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text(line.instruction, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    Spacer(modifier = Modifier.height(12.dp))
    Text(ui.message, style = MaterialTheme.typography.bodySmall, color = if (ui.recording) Color.Red else Color.Gray)
    if (ui.recording) Text("Level: ${ui.levelDb.toInt()} dB", style = MaterialTheme.typography.bodySmall)
    Spacer(modifier = Modifier.height(20.dp))
    Button(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        enabled = !ui.saving,
        colors = ButtonDefaults.buttonColors(containerColor = if (ui.recording) Color.Red else MaterialTheme.colorScheme.primary),
        onClick = { TrainerService.endpoint(context, if (ui.recording) "finish" else "start") },
    ) { Text(if (ui.recording) "Stop Recording" else "Record line ${line.number} 🎙️") }
    Text(
        "Headset middle button: start / finish",
        style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(top = 8.dp),
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(enabled = !busy && ui.lineIndex > 0, onClick = { TrainerService.endpoint(context, "line", ui.lineIndex - 1) }) { Text("← Previous") }
        TextButton(
            enabled = !busy && ui.lineIndex < EndpointScenario.lines.lastIndex,
            onClick = { TrainerService.endpoint(context, "line", ui.lineIndex + 1) },
        ) { Text("Next →") }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Recorded: " + EndpointScenario.lines.joinToString(" ") { if (it.number in ui.recorded) "✓${it.number}" else "·${it.number}" },
        style = MaterialTheme.typography.labelSmall, color = Color.Gray,
    )
}
