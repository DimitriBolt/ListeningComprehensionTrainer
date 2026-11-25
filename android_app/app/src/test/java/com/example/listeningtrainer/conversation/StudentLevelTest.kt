package com.example.listeningtrainer.conversation

import com.example.listeningtrainer.config.ModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Уровень ученика (S02h, D56): окно, переходы, границы, хранение, служебная строка. */
class StudentLevelTest {

    private fun turns(level: StudentLevel, understood: Int, notUnderstood: Int) {
        repeat(notUnderstood) { level.onStudentTurn("What?") }
        repeat(understood) { level.onStudentTurn("I like apples.") }
    }

    @Test
    fun `new install starts at level 1 with an empty window`() {
        val level = StudentLevel(MemoryLevelStore())
        assertEquals(StudentLevel.State(1, 0, 0), level.state.value)
    }

    @Test
    fun `numbers come from ModelConfig - window 20, up at 1 or less, down at 4 or more, five levels`() {
        assertEquals(20, ModelConfig.LEVEL_WINDOW)
        assertEquals(1, ModelConfig.LEVEL_UP_MAX_NOT_UNDERSTOOD)
        assertEquals(4, ModelConfig.LEVEL_DOWN_MIN_NOT_UNDERSTOOD)
        assertEquals(5, ModelConfig.LEVEL_TARGETS.size)
        assertEquals(listOf(25, 35, 45, 55, 65), ModelConfig.LEVEL_TARGETS.map { it.minWords })
        assertEquals(listOf(40, 50, 60, 70, 80), ModelConfig.LEVEL_TARGETS.map { it.maxWords })
        assertEquals(listOf(3, 4, 5, 6, 7), ModelConfig.LEVEL_TARGETS.map { it.minSentences })
        assertEquals(listOf(4, 5, 6, 7, 8), ModelConfig.LEVEL_TARGETS.map { it.maxSentences })
    }

    @Test
    fun `window counts turns and not understood, the level waits for 20 turns`() {
        val level = StudentLevel(MemoryLevelStore())
        turns(level, understood = 18, notUnderstood = 1)
        assertEquals(StudentLevel.State(1, 19, 1), level.state.value)
        val step = level.onStudentTurn("Yes, I do.")
        assertEquals(StudentLevel.State(2, 0, 0), step.state)
        assertEquals(1, step.from)
    }

    @Test
    fun `two or three not understood - same level, the window starts again`() {
        for (nu in listOf(2, 3)) {
            val level = StudentLevel(MemoryLevelStore(StudentLevel.State(level = 3)))
            turns(level, understood = 20 - nu, notUnderstood = nu)
            assertEquals(StudentLevel.State(3, 0, 0), level.state.value)
        }
    }

    @Test
    fun `fourth not understood - level down at once, the window starts again (D59)`() {
        val level = StudentLevel(MemoryLevelStore(StudentLevel.State(level = 3)))
        // Реплики 1–4 поняты, 5–7 — «не понял» (3 в окне), 8-я — четвёртое «не понял».
        turns(level, understood = 4, notUnderstood = 0)
        turns(level, understood = 0, notUnderstood = 3)
        assertEquals(StudentLevel.State(3, 7, 3), level.state.value)
        val step = level.onStudentTurn("I don't understand.")
        assertEquals(StudentLevel.State(2, 0, 0), step.state)
        assertEquals(3, step.from)
        // «Не понял» не обязательно подряд: четыре в любом месте окна.
        val mixed = StudentLevel(MemoryLevelStore(StudentLevel.State(level = 5)))
        repeat(3) { mixed.onStudentTurn("What?"); mixed.onStudentTurn("I like apples.") }
        assertEquals(4, mixed.onStudentTurn("Sorry?").state.level)
    }

    @Test
    fun `bounds - not above 5, not below 1, the window still starts again`() {
        val top = StudentLevel(MemoryLevelStore(StudentLevel.State(level = 5)))
        turns(top, understood = 20, notUnderstood = 0)
        assertEquals(StudentLevel.State(5, 0, 0), top.state.value)

        // Уровень 1: четвёртое «не понял» — уровень тот же, окно заново, перехода нет.
        val bottom = StudentLevel(MemoryLevelStore(StudentLevel.State(level = 1)))
        turns(bottom, understood = 2, notUnderstood = 3)
        val step = bottom.onStudentTurn("What?")
        assertEquals(StudentLevel.State(1, 0, 0), step.state)
        assertNull(step.from)
        turns(bottom, understood = 0, notUnderstood = 3)
        assertEquals(StudentLevel.State(1, 3, 3), bottom.state.value)
    }

    @Test
    fun `stored state survives a new instance (new conversation, restart), bad values are clamped`() {
        val store = MemoryLevelStore()
        val first = StudentLevel(store)
        turns(first, understood = 20, notUnderstood = 0)
        turns(first, understood = 5, notUnderstood = 1)
        val again = StudentLevel(store) // перезапуск приложения: новый объект, то же хранилище
        assertEquals(StudentLevel.State(2, 6, 1), again.state.value)

        assertEquals(StudentLevel.State(5, 20, 0), StudentLevel(MemoryLevelStore(StudentLevel.State(9, 99, -3))).state.value)
    }

    @Test
    fun `set by hand - level clamped, window empty, saved`() {
        val store = MemoryLevelStore(StudentLevel.State(2, 7, 2))
        val level = StudentLevel(store)
        level.setLevel(5)
        assertEquals(StudentLevel.State(5, 0, 0), store.load())
        level.setLevel(0)
        assertEquals(1, level.level)
    }

    @Test
    fun `note - target of the level`() {
        val level = StudentLevel(MemoryLevelStore())
        assertEquals("(App note: target length: 3 to 4 sentences, 25 to 40 words.)", level.note())
        assertEquals("(App note: target length: 5 to 6 sentences, 45 to 60 words.)", level.note(3))
        assertEquals("(App note: target length: 7 to 8 sentences, 65 to 80 words.)", level.note(5))
    }
}
