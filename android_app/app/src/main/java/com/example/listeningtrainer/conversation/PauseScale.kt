package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.config.ModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToLong

/**
 * Личный множитель пауз ученика (S07, D98). Чистый Kotlin (инвариант 3); хранение — через [Store] (на телефоне —
 * SharedPreferences), поэтому множитель переживает «New conversation», «Back» и перезапуск. Новая установка — 1,0.
 *
 * «Slower» — [slower] (× [step]), «Faster» — [faster] (÷ [step]), в границах [min] … [max]. **Сам множитель не меняется
 * никогда** (D96): ни по уровню, ни по времени, ни по успехам — других способов изменить его, кроме этих двух и ручной
 * установки в debug ([set]), нет.
 */
class PauseScale(
    private val store: Store,
    private val step: Double = ModelConfig.PAUSE_SCALE_STEP,
    private val min: Double = ModelConfig.PAUSE_SCALE_MIN,
    private val max: Double = ModelConfig.PAUSE_SCALE_MAX,
) {
    interface Store {
        /** `null` — ничего не сохранено (новая установка). */
        fun load(): Double?
        fun save(scale: Double)
    }

    /** Итог команды: было, стало; [limit] — уже на границе, множитель не изменился. */
    data class Change(val from: Double, val to: Double, val limit: Boolean)

    private val _value = MutableStateFlow(clamp(store.load() ?: 1.0))
    val state: StateFlow<Double> = _value.asStateFlow()
    val value: Double get() = _value.value

    @Synchronized
    fun slower(): Change = change(value * step)

    @Synchronized
    fun faster(): Change = change(value / step)

    /** Задать вручную (только debug-сборка, ручная проверка). */
    @Synchronized
    fun set(scale: Double): Change = change(scale)

    private fun change(target: Double): Change {
        val from = value
        val to = clamp(target)
        if (to == from) return Change(from, from, limit = true)
        _value.value = to
        store.save(to)
        return Change(from, to, limit = false)
    }

    /** В границах, 4 знака после запятой: 1,15 ÷ 1,15 — снова ровно 1,0. */
    private fun clamp(x: Double): Double = (x.coerceIn(min, max) * 10_000).roundToLong() / 10_000.0

    companion object {
        /** Для экрана и журнала: «1.15». */
        fun format(scale: Double): String = String.format(java.util.Locale.US, "%.2f", scale)
    }
}

/** Хранение в памяти (тесты; и если хранилище телефона недоступно). */
class MemoryPauseScaleStore(private var saved: Double? = null) : PauseScale.Store {
    override fun load(): Double? = saved
    override fun save(scale: Double) { saved = scale }
}
