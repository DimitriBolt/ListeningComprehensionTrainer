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
import com.example.listeningtrainer.audio.HeadsetButtons
import com.example.listeningtrainer.audio.PulseMarks
import com.example.listeningtrainer.audio.SileroVad
import com.example.listeningtrainer.audio.exportToPublicMusic
import com.example.listeningtrainer.audio.lastUserSpeechFile
import com.example.listeningtrainer.audio.lastUserUploadFile
import com.example.listeningtrainer.conversation.AudioInput
import com.example.listeningtrainer.conversation.ConversationController
import com.example.listeningtrainer.conversation.ConversationUi
import com.example.listeningtrainer.conversation.EndReason
import com.example.listeningtrainer.conversation.IdleShutdown
import com.example.listeningtrainer.conversation.RecordedAudio
import com.example.listeningtrainer.conversation.SpeechPlanner
import com.example.listeningtrainer.conversation.TurnDetector
import com.example.listeningtrainer.conversation.VadTurnDetector
import com.example.listeningtrainer.eventlog.AppLog
import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.openai.RealtimeSynthesizer
import com.example.listeningtrainer.openai.FileTranscriber
import com.example.listeningtrainer.openai.ResponsesCorrector
import com.example.listeningtrainer.endpoint.EndpointRecorder
import com.example.listeningtrainer.endpoint.EndpointScenario
import com.example.listeningtrainer.endpoint.EndpointUi
import com.example.listeningtrainer.endpoint.RecordingStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Здесь живёт разговор (ConversationController), поэтому он идёт и с выключенным экраном.
 * Экран шлёт команды через Intent ([send]) и читает состояние из [ui].
 *
 * Сервис запускается первой командой («Talk» или авто-режим) — уже после разрешения на
 * микрофон: foreground-сервис с типом microphone без него на Android 14 падает.
 * Пока он жив, приложение держит аудиофокус ([AudioSession]) и средняя кнопка гарнитуры работает как
 * «Talk» / «Stop Recording» ([HeadsetButtons], D29).
 * Останавливается кнопкой «Back» ([stop]) или смахиванием из недавних: круг заканчивается, звучит
 * «конец». Без идущего круга и без видимого экрана закрывается сам через 10 мин (D18).
 * Системный жест «назад» сервис не трогает: разговор продолжается в фоне, как после «Домой».
 */
class TrainerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: ConversationController
    private lateinit var audioSession: AudioSession
    private lateinit var headsetButtons: HeadsetButtons
    /** Режим записи для пробы S03b — только в debug-сборке. */
    private var endpoint: EndpointRecorder? = null

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
        // «Пульс» слушания (D28): проигрыватель отмечает его начало (режиму записи — для JSON). Маски нет (D68).
        val pulseMarks = PulseMarks()
        // Конец реплики — Silero VAD (S03c, D64): модель грузится при первой записи, в её потоке.
        val vadModel = (application as TrainerApplication).vadModel
        val recorder = AudioRecorder(lastUserSpeechFile(this), lastUserUploadFile(this), AppLog)
        // Копия каждой записи в общую папку Music — только в debug-сборке, последние 20 файлов.
        val input: AudioInput = if (!BuildConfig.DEBUG) recorder else object : AudioInput by recorder {
            override suspend fun record(turnDetector: TurnDetector, onLevel: (Float) -> Unit): RecordedAudio =
                recorder.record(turnDetector, onLevel).also { audio ->
                    scope.launch(Dispatchers.IO) { exportToPublicMusic(this@TrainerService, audio.file) }
                }
        }

        val output = AudioTrackOutput(this, AppLog, pulseMarks)
        controller = ConversationController(
            scope = scope,
            input = input,
            turnDetector = VadTurnDetector(SileroVad(vadModel)),
            transcriber = FileTranscriber(apiKey, log = AppLog),
            tutor = tutor,
            planner = SpeechPlanner(),
            synthesizer = RealtimeSynthesizer(apiKey, AppLog),
            fallbackSynthesizer = AndroidTtsSynthesizer(this, AppLog),
            output = output,
            log = AppLog,
            speechLookahead = ModelConfig.SPEECH_LOOKAHEAD,
            corrector = ResponsesCorrector(apiKey),
            studentLevel = (application as TrainerApplication).studentLevel,
        )
        // Режим записи для пробы S03b (только debug): свой микрофон без сжатия; моменты пульсов — от проигрывателя
        // сигналов; эмуляция Silero — со своим состоянием модели.
        if (EndpointRecorder.available(BuildConfig.DEBUG)) {
            val tmp = File(filesDir, "recordings_tmp").apply { mkdirs() }
            val rawRecorder = AudioRecorder(File(tmp, "current.wav"), File(tmp, "current.m4a"), AppLog, compress = false)
            endpoint = EndpointRecorder(
                scope = scope,
                input = rawRecorder,
                output = output,
                store = RecordingStore(File(filesDir, RECORDINGS_DIR)),
                vad = SileroVad(vadModel),
                log = AppLog,
                device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
            ).also { rec ->
                pulseMarks.onPulse = rec::onPulseStarted
                scope.launch { rec.ui.collect { _endpointUi.value = it } }
            }
        }
        // Средняя кнопка гарнитуры (D29): медиа-сеанс активен всё время жизни сервиса, как и аудиофокус.
        // Пока открыт режим записи для пробы — кнопка его.
        headsetButtons = HeadsetButtons(this, AppLog) { keyCode, pressMs ->
            val rec = endpoint
            if (rec != null && rec.active) rec.headsetButton(keyCode, pressMs) else controller.headsetButton(keyCode, pressMs)
        }.also { it.start() }
        // Сессия Realtime и запасной движок — заранее, чтобы подключение не попало во время ответа.
        controller.prepare()
        scope.launch { controller.ui.collect { _ui.value = it } }
        // D18: без идущего круга и без видимого экрана — закрыться через 10 мин.
        IdleShutdown.watch(
            scope,
            controller.ui.map { IdleShutdown.isCircleRunning(it.state) },
            screenVisible,
            ModelConfig.SERVICE_IDLE_TIMEOUT_MS,
        ) {
            AppLog.event(SOURCE, "idle ${ModelConfig.SERVICE_IDLE_TIMEOUT_MS / 60_000} min: no conversation and no screen, closing")
            stopSelf()
        }
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
            ACTION_ENDPOINT -> endpointCommand(intent)
            ACTION_EXIT -> {
                controller.stop(EndReason.BACK)
                stopSelf()
            }
        }
        // Без экрана перезапуск сервиса с микрофоном не нужен (и на Android 14 запрещён из фона).
        return START_NOT_STICKY
    }

    /** Команда экрана режима записи (S03b). В release режима нет — команда пропускается. */
    private fun endpointCommand(intent: Intent) {
        val rec = endpoint ?: return
        when (intent.getStringExtra(EXTRA_CMD)) {
            "open" -> {
                val state = controller.ui.value.state
                if (IdleShutdown.isCircleRunning(state)) {
                    AppLog.event(SOURCE, "endpoint recording mode not opened: conversation is running")
                } else {
                    rec.open()
                }
            }
            "condition" -> EndpointScenario.Condition.values().getOrNull(intent.getIntExtra(EXTRA_VALUE, -1))?.let(rec::chooseCondition)
            "line" -> rec.selectLine(intent.getIntExtra(EXTRA_VALUE, -1))
            "start" -> rec.start("screen")
            "finish" -> rec.finish("screen")
            "conditions" -> rec.backToConditions()
            "close" -> rec.close()
        }
    }

    override fun onDestroy() {
        AppLog.event(SOURCE, "onDestroy")
        endpoint?.close()
        _endpointUi.value = EndpointUi()
        controller.release() // если круг ещё шёл — «конец» с причиной «service destroyed»
        headsetButtons.release()
        audioSession.stop()
        _ui.value = controller.ui.value
        scope.cancel()
        running = false
        super.onDestroy()
    }

    /** Приложение смахнули из списка недавних — это «← Back» (D18): круг останавливается, «конец», сервис закрывается. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.event(SOURCE, "onTaskRemoved (app swiped from recents): same as Back")
        controller.stop(EndReason.SWIPED)
        stopSelf()
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
        private const val ACTION_ENDPOINT = "com.example.listeningtrainer.ENDPOINT"
        private const val EXTRA_CMD = "cmd"
        private const val EXTRA_VALUE = "value"
        /** Каталог записей для пробы в `filesDir` — его скачивает `tools/pull-recordings.sh`. */
        const val RECORDINGS_DIR = "recordings"

        private val _endpointUi = MutableStateFlow(EndpointUi())
        /** Экран режима записи для пробы (S03b, только debug). */
        val endpointUi: StateFlow<EndpointUi> = _endpointUi.asStateFlow()

        /** Команда режима записи: open, condition (номер), line (индекс), start, finish, conditions, close. */
        fun endpoint(context: Context, cmd: String, value: Int = -1) {
            if (cmd != "open" && !running) return
            send(context, ACTION_ENDPOINT) { putExtra(EXTRA_CMD, cmd); putExtra(EXTRA_VALUE, value) }
        }

        /** Виден ли экран приложения (MainActivity onStart/onStop) — для закрытия по простою. */
        val screenVisible = MutableStateFlow(false)

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
