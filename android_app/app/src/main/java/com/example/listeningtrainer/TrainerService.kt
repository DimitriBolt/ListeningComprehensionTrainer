package com.example.listeningtrainer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.listeningtrainer.audio.AudioRecorder
import com.example.listeningtrainer.audio.AudioSession
import com.example.listeningtrainer.audio.AndroidTtsSynthesizer
import com.example.listeningtrainer.audio.AudioTrackOutput
import com.example.listeningtrainer.audio.exportToPublicMusic
import com.example.listeningtrainer.audio.lastUserSpeechFile
import com.example.listeningtrainer.audio.lastUserUploadFile
import com.example.listeningtrainer.conversation.AudioInput
import com.example.listeningtrainer.conversation.ConversationController
import com.example.listeningtrainer.conversation.ConversationUi
import com.example.listeningtrainer.conversation.EndReason
import com.example.listeningtrainer.conversation.RecordedAudio
import com.example.listeningtrainer.conversation.SilenceTurnDetector
import com.example.listeningtrainer.conversation.SpeechPlanner
import com.example.listeningtrainer.conversation.TurnDetector
import com.example.listeningtrainer.eventlog.AppLog
import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.openai.RealtimeSynthesizer
import com.example.listeningtrainer.openai.FileTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Здесь живёт разговор (ConversationController), поэтому он идёт и с выключенным экраном.
 * Экран шлёт команды через Intent ([send]) и читает состояние из [ui].
 *
 * Сервис запускается первой командой («Talk» или авто-режим) — уже после разрешения на
 * микрофон: foreground-сервис с типом microphone без него на Android 14 падает.
 * Пока он жив, приложение держит аудиофокус ([AudioSession]).
 * Останавливается кнопкой «Back» ([stop]): круг заканчивается с причиной «Back», звучит «конец».
 */
class TrainerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: ConversationController
    private lateinit var audioSession: AudioSession

    override fun onCreate() {
        super.onCreate()
        AppLog.event(SOURCE, "onCreate")
        createNotificationChannel()
        running = true
        audioSession = AudioSession(this, AppLog).also { it.start() }

        val keyStore = ApiKeyStore(this)
        val apiKey = { keyStore.current() }
        val tutor = (application as TrainerApplication).tutor
        AppLog.event(SOURCE, "tutor history: ${tutor.historySize} messages")
        val recorder = AudioRecorder(lastUserSpeechFile(this), lastUserUploadFile(this), AppLog)
        // Копия каждой записи в общую папку Music — только в debug-сборке, последние 20 файлов.
        val input: AudioInput = if (!BuildConfig.DEBUG) recorder else object : AudioInput by recorder {
            override suspend fun record(turnDetector: TurnDetector, onLevel: (Float) -> Unit): RecordedAudio =
                recorder.record(turnDetector, onLevel).also { audio ->
                    scope.launch(Dispatchers.IO) { exportToPublicMusic(this@TrainerService, audio.file) }
                }
        }

        controller = ConversationController(
            scope = scope,
            input = input,
            turnDetector = SilenceTurnDetector(),
            transcriber = FileTranscriber(apiKey, log = AppLog),
            tutor = tutor,
            planner = SpeechPlanner(),
            synthesizer = RealtimeSynthesizer(apiKey, AppLog),
            fallbackSynthesizer = AndroidTtsSynthesizer(this, AppLog),
            output = AudioTrackOutput(this, AppLog),
            log = AppLog,
            speechLookahead = ModelConfig.SPEECH_LOOKAHEAD,
        )
        // Сессия Realtime и запасной движок — заранее, чтобы подключение не попало во время ответа.
        controller.prepare()
        scope.launch { controller.ui.collect { _ui.value = it } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, createNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        )
        val action = intent?.action
        AppLog.event(SOURCE, "command ${action?.substringAfterLast('.') ?: "none (restart)"}")
        when (action) {
            ACTION_TALK -> controller.startTurn()
            ACTION_FINISH_RECORDING -> controller.finishRecording()
            ACTION_STOP -> controller.stop(EndReason.STOP_BUTTON)
            ACTION_SET_AUTO -> controller.setAutoMode(intent.getBooleanExtra(EXTRA_ENABLED, false))
            ACTION_NEW_CONVERSATION -> controller.newConversation()
            ACTION_EXIT -> {
                controller.stop(EndReason.BACK)
                stopSelf()
            }
        }
        // Без экрана перезапуск сервиса с микрофоном не нужен (и на Android 14 запрещён из фона).
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AppLog.event(SOURCE, "onDestroy")
        controller.release() // если круг ещё шёл — «конец» с причиной «service destroyed»
        audioSession.stop()
        _ui.value = controller.ui.value
        scope.cancel()
        running = false
        super.onDestroy()
    }

    /** Приложение смахнули из списка недавних; сервис при этом может продолжать работать. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.event(SOURCE, "onTaskRemoved (app swiped from recents)")
        super.onTaskRemoved(rootIntent)
    }

    /** Последний записанный уровень onTrimMemory: система шлёт один и тот же уровень сотнями раз. */
    private var lastTrimLevel: Int? = null

    /** Система просит освободить память — подсказка, если процесс потом убьют. Пишется только смена уровня. */
    override fun onTrimMemory(level: Int) {
        if (level != lastTrimLevel) {
            AppLog.event(SOURCE, "onTrimMemory level=$level (was ${lastTrimLevel ?: "-"})")
            lastTrimLevel = level
        }
        super.onTrimMemory(level)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Listening Trainer Active")
            .setContentText("The AI teacher is listening and ready to talk.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Listening Trainer Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    companion object {
        private const val SOURCE = "Service"
        private const val CHANNEL_ID = "TrainerServiceChannel"
        private const val NOTIFICATION_ID = 1
        const val TIMING_TAG = "LCT-Timing"

        private const val ACTION_TALK = "com.example.listeningtrainer.TALK"
        private const val ACTION_FINISH_RECORDING = "com.example.listeningtrainer.FINISH_RECORDING"
        private const val ACTION_STOP = "com.example.listeningtrainer.STOP"
        private const val ACTION_SET_AUTO = "com.example.listeningtrainer.SET_AUTO"
        private const val ACTION_EXIT = "com.example.listeningtrainer.EXIT"
        private const val ACTION_NEW_CONVERSATION = "com.example.listeningtrainer.NEW_CONVERSATION"
        private const val EXTRA_ENABLED = "enabled"

        private val _ui = MutableStateFlow(ConversationUi())
        /** Состояние разговора для экрана. Живёт дольше сервиса: после остановки — последнее значение. */
        val ui: StateFlow<ConversationUi> = _ui.asStateFlow()

        @Volatile
        private var running = false

        /** Идёт ли разговор (сервис жив) — экран тогда открывается сразу на разговоре. */
        val isRunning: Boolean get() = running

        /** «Talk»: начать круг. Микрофон должен быть уже разрешён. */
        fun talk(context: Context) = send(context, ACTION_TALK)

        /** «Stop Recording»: закончить реплику. */
        fun finishRecording(context: Context) { if (running) send(context, ACTION_FINISH_RECORDING) }

        /** «Stop»: остановить всё (авто-режим выключается). */
        fun stopTalking(context: Context) { if (running) send(context, ACTION_STOP) }

        /** Переключатель авто-режима. Включение запускает сервис — микрофон должен быть разрешён. */
        fun setAutoMode(context: Context, enabled: Boolean) {
            if (!enabled && !running) {
                _ui.value = _ui.value.copy(autoMode = false)
                return
            }
            send(context, ACTION_SET_AUTO) { putExtra(EXTRA_ENABLED, enabled) }
        }

        /** «New conversation»: учитель забывает историю, экран очищается. */
        fun newConversation(context: Context) {
            if (running) {
                send(context, ACTION_NEW_CONVERSATION)
                return
            }
            // Сервис не запущен (после «Back»): история живёт в процессе — чистим её здесь.
            (context.applicationContext as TrainerApplication).tutor.newConversation()
            _ui.value = _ui.value.copy(studentText = "", teacherText = "", currentChunk = "", status = "New conversation.")
            AppLog.event(SOURCE, "New conversation (service not running): tutor history cleared")
        }

        /** «Back»: круг останавливается с причиной «Back», звучит «конец», сервис закрывается. */
        fun stop(context: Context) {
            if (running) send(context, ACTION_EXIT)
        }

        private fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, TrainerService::class.java).setAction(action).apply(extras)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
