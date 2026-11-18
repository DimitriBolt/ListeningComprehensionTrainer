package com.example.listeningtrainer

import com.example.listeningtrainer.audio.AudioRecorder
import com.example.listeningtrainer.conversation.SilenceTurnDetector
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
}

fun exportToPublicMusic(context: Context, inputFile: File) {
    try {
        val fileName = "debug_speech_${System.currentTimeMillis()}.wav"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/ListeningTrainer")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let { targetUri ->
            resolver.openOutputStream(targetUri)?.use { outputStream ->
                inputFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
}

@Composable
fun TrainerApp(viewModel: TrainerViewModel = viewModel()) {
    var currentScreen by remember { mutableStateOf("setup") }
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
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    fun startTrainerService() {
        val intent = Intent(context, TrainerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopTrainerService() {
        val intent = Intent(context, TrainerService::class.java)
        context.stopService(intent)
    }

    var isRecording by remember { mutableStateOf(false) }
    var currentVolume by remember { mutableStateOf(0f) }
    
    // --- НАСТРОЙКИ ПРЕРЫВАНИЯ ЗАПИСИ ---
    // pauseThreshold: Сколько миллисекунд тишины ждать перед остановкой записи
    val pauseThreshold = 3000L
    // silenceThresholdDb: Порог шума в децибелах. 
    // Если Noise ниже этого числа — считается, что пользователь молчит.
    val silenceThresholdDb = 59f

    val musicFolder = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
    val debugAudioFile = remember { File(musicFolder, "last_user_speech.wav") }
    val recorder = remember { AudioRecorder(debugAudioFile) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { if (it) viewModel.statusText = "Permission granted." }

    val startRecordingAction: () -> Unit = {
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            if (!isRecording) {
                isRecording = true
                viewModel.recognizedText = ""
                viewModel.teacherResponse = ""
                scope.launch {
                    val file = recorder.record(SilenceTurnDetector(silenceThresholdDb, pauseThreshold)) { currentVolume = it }.file
                    isRecording = false
                    scope.launch(Dispatchers.IO) { exportToPublicMusic(context, file) }
                    viewModel.processVoiceRecording(file, context.cacheDir)
                }
            } else { 
                recorder.finish()
                isRecording = false 
            }
        } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(viewModel.autoRecordTrigger) {
        if (viewModel.autoRecordTrigger > 0 && viewModel.isAutoMode && !isRecording) {
            startRecordingAction()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { 
                viewModel.stopAll()
                stopTrainerService()
                onBack() 
            }) { Text("← Back") }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-mode", style = MaterialTheme.typography.labelSmall)
                Switch(
                    checked = viewModel.isAutoMode,
                    onCheckedChange = { 
                        viewModel.isAutoMode = it 
                        if (it) startTrainerService() else stopTrainerService()
                    },
                    modifier = Modifier.scale(0.7f)
                )
            }

            if (debugAudioFile.exists() && !isRecording) {
                TextButton(onClick = { viewModel.playDebugAudio(debugAudioFile) }) { Text("🔈 My Voice") }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(text = "Listening Practice", style = MaterialTheme.typography.headlineSmall)
        
        if (viewModel.currentSpokenChunk.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                border = CardDefaults.outlinedCardBorder()
            ) {
                MarkdownText(
                    markdown = viewModel.currentSpokenChunk,
                    modifier = Modifier.padding(24.dp).fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(text = "Status: ${viewModel.statusText}", style = MaterialTheme.typography.bodySmall)
        
        if (isRecording) {
            Spacer(modifier = Modifier.height(8.dp))
            // ИНДИКАТОР УРОВНЯ ШУМА
            Text(
                "Noise: ${currentVolume.toInt()} dB", 
                color = if (currentVolume > silenceThresholdDb) MaterialTheme.colorScheme.primary else Color.Gray,
                fontWeight = FontWeight.Bold
            )
            LinearProgressIndicator(
                progress = (currentVolume / 100f).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().height(8.dp).padding(vertical = 8.dp)
            )
            Text("Listening...", color = Color.Red, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (viewModel.recognizedText.isNotEmpty()) {
            Text(text = "You said:", style = MaterialTheme.typography.labelMedium)
            Card(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(text = viewModel.recognizedText, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }

        if (viewModel.teacherResponse.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Teacher:", style = MaterialTheme.typography.labelMedium)
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                MarkdownText(
                    markdown = viewModel.teacherResponse,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            modifier = Modifier.fillMaxWidth().height(72.dp),
            enabled = !viewModel.isProcessing || isRecording,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary
            ),
            onClick = startRecordingAction
        ) {
            Text(if (isRecording) "Stop Recording" else "Talk to Teacher 🎙️")
        }
        
        if (viewModel.isAutoMode) {
            Text(
                "Auto-mode is ON: I will listen after I finish speaking.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(40.dp))
    }
}
