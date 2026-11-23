package com.example.listeningtrainer

import com.example.listeningtrainer.audio.lastUserSpeechFile
import com.example.listeningtrainer.conversation.ConversationState
import com.example.listeningtrainer.conversation.SilenceTurnDetector
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
    if (currentScreen == "setup") {
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
                color = if (ui.levelDb > SilenceTurnDetector.DEFAULT_THRESHOLD_DB) MaterialTheme.colorScheme.primary else Color.Gray,
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
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}
