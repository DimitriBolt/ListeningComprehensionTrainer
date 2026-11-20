package com.example.listeningtrainer

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.util.Log
import com.example.listeningtrainer.eventlog.AppLog
import com.example.listeningtrainer.eventlog.FileEventLog
import java.io.File
import java.util.Date

/**
 * Процесс приложения. Здесь включается журнал событий (filesDir/logs, см. FileEventLog),
 * перехват необработанных исключений и запись о том, почему завершился прошлый процесс.
 */
class TrainerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.sink = FileEventLog(
            dir = File(filesDir, LOG_DIR),
            mirror = { source, text, isError ->
                val tag = if (source == TIMING_SOURCE) TrainerService.TIMING_TAG else LOGCAT_TAG
                if (isError) Log.e(tag, "[$source] $text") else Log.i(tag, "[$source] $text")
            },
        )
        installCrashHandler()
        AppLog.event(
            "Process",
            "start: app ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}), " +
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
        )
        logPreviousExit()
    }

    /** Необработанное исключение: записать в журнал и передать прежнему обработчику (он завершит процесс). */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                AppLog.error("Crash", "uncaught exception in thread '${thread.name}'", error)
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Почему система завершила прошлый процесс (Android 11+). Если приложение убили,
     * само оно ничего записать не успевает — причина видна только здесь, при следующем запуске.
     */
    private fun logPreviousExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = getSystemService(ActivityManager::class.java) ?: return
            val info = am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull() ?: return
            val time = FileEventLog.timeFormat().format(Date(info.timestamp))
            AppLog.event(
                "Process",
                "previous process ended at $time: reason=${exitReason(info.reason)}, " +
                    "importance=${info.importance}, status=${info.status}, " +
                    "description=${FileEventLog.safeMessage(info.description).ifEmpty { "-" }}",
            )
        } catch (e: Exception) {
            AppLog.error("Process", "cannot read previous exit reason", e)
        }
    }

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($reason)"
    }

    companion object {
        /** Каталог журнала в filesDir; его же читает tools/pull-log.sh. */
        const val LOG_DIR = "logs"
        /** Источник строк замера скорости; в Logcat они идут под тегом LCT-Timing, как в S01. */
        const val TIMING_SOURCE = "Timing"
        private const val LOGCAT_TAG = "LCT"
    }
}
