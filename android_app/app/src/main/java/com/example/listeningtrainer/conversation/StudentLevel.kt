package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.config.LengthTarget
import com.example.listeningtrainer.config.ModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Уровень ученика (S02h, D56): от него зависит длина реплики учителя. Чистый Kotlin (инвариант 3);
 * хранение — через [Store] (на телефоне — SharedPreferences), поэтому уровень и окно переживают
 * «New conversation», «Back» и перезапуск приложения. Новая установка начинается с уровня 1.
 *
 * Окно — реплики ученика, дошедшие до учителя (молчание сюда не попадает). Когда в окне набралось
 * [window] реплик: «не понял» ([NotUnderstood.isMarker]) не больше [upMax] — уровень выше, не меньше
 * [downMin] — ниже, иначе тот же. Окно после этого начинается заново (и на границах 1 и 5 тоже).
 * Уровень от 1 до [targets].size.
 *
 * Модель длину сама не наращивает (S02g), поэтому цель уходит в запрос служебной строкой [note], как
 * счёт «не понял» (D55). В историю учителя строка не попадает.
 */
class StudentLevel(
    private val store: Store,
    private val targets: List<LengthTarget> = ModelConfig.LEVEL_TARGETS,
    private val window: Int = ModelConfig.LEVEL_WINDOW,
    private val upMax: Int = ModelConfig.LEVEL_UP_MAX_NOT_UNDERSTOOD,
    private val downMin: Int = ModelConfig.LEVEL_DOWN_MIN_NOT_UNDERSTOOD,
) {
    /** Что хранится: уровень, сколько реплик в окне и сколько из них «не понял». */
    data class State(val level: Int = 1, val turns: Int = 0, val notUnderstood: Int = 0)

    /** Где хранится состояние. `load` без сохранённого — `null` (новая установка). */
    interface Store {
        fun load(): State?
        fun save(state: State)
    }

    /** Итог одной реплики: состояние после неё и переход, если был (`from` → `state.level`). */
    data class Step(val state: State, val from: Int? = null)

    private val maxLevel = targets.size
    private val _state = MutableStateFlow(sanitize(store.load() ?: State()))

    /** Текущее состояние (экран показывает уровень). */
    val state: StateFlow<State> = _state.asStateFlow()

    val level: Int get() = _state.value.level

    /** Размер окна (для журнала). */
    val windowSize: Int get() = window

    /** Реплика ученика дошла до учителя: учесть её в окне; может сменить уровень. */
    @Synchronized
    fun onStudentTurn(studentText: String): Step {
        val s = _state.value
        val turns = s.turns + 1
        val nu = s.notUnderstood + if (NotUnderstood.isMarker(studentText)) 1 else 0
        if (turns < window) {
            val state = State(s.level, turns, nu)
            set(state)
            return Step(state)
        }
        val next = when {
            nu <= upMax -> s.level + 1
            nu >= downMin -> s.level - 1
            else -> s.level
        }.coerceIn(1, maxLevel)
        val state = State(next)
        set(state)
        return Step(state, from = s.level.takeIf { it != next })
    }

    /** Задать уровень вручную (только debug-сборка, ручная проверка): окно начинается заново. */
    @Synchronized
    fun setLevel(level: Int) = set(State(level.coerceIn(1, maxLevel)))

    /** Цель длины текущего уровня. */
    fun target(level: Int = this.level): LengthTarget = targets[level.coerceIn(1, maxLevel) - 1]

    /** Служебная строка к реплике ученика. Формат описан в промпте учителя. */
    fun note(level: Int = this.level): String = note(target(level))

    private fun set(state: State) {
        _state.value = state
        store.save(state)
    }

    private fun sanitize(s: State) = State(
        s.level.coerceIn(1, maxLevel), s.turns.coerceIn(0, window), s.notUnderstood.coerceIn(0, window),
    )

    companion object {
        /** Копия — `level_note` в `tools/probe/tutor_dialog.py`. */
        fun note(t: LengthTarget): String =
            "(App note: target length: ${t.minSentences} to ${t.maxSentences} sentences, " +
                "${t.minWords} to ${t.maxWords} words.)"
    }
}

/** Хранение в памяти (тесты; и если хранилище телефона недоступно). */
class MemoryLevelStore(private var saved: StudentLevel.State? = null) : StudentLevel.Store {
    override fun load(): StudentLevel.State? = saved
    override fun save(state: StudentLevel.State) { saved = state }
}
