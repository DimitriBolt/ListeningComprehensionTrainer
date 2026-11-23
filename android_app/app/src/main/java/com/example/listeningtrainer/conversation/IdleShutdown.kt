package com.example.listeningtrainer.conversation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Сервис без разговора и без видимого экрана закрывается сам (D18): нет идущего круга
 * ([circleRunning] = false) и экран не виден ([screenVisible] = false) дольше [timeoutMs] —
 * вызывается [onIdle]. Любое изменение (начался круг, открыли экран) сбрасывает отсчёт.
 * Чистый Kotlin, время — из корутин.
 */
object IdleShutdown {
    fun watch(
        scope: CoroutineScope,
        circleRunning: Flow<Boolean>,
        screenVisible: Flow<Boolean>,
        timeoutMs: Long,
        onIdle: () -> Unit,
    ): Job = scope.launch {
        combine(circleRunning, screenVisible) { running, visible -> !running && !visible }
            .distinctUntilChanged()
            .collectLatest { idle ->
                if (idle) {
                    delay(timeoutMs)
                    onIdle()
                }
            }
    }

    /** Идёт ли круг: всё, кроме покоя и ошибки. */
    fun isCircleRunning(state: ConversationState) =
        state != ConversationState.Idle && state !is ConversationState.Error
}
