package com.example.listeningtrainer.endpoint

/**
 * Сценарий записей для пробы конца реплики (S03b): 12 реплик на каждое из 4 условий.
 * Текст на экране — дословно этот же; его же пишет JSON записи, и по нему проба считает точность текста.
 * Чистый Kotlin.
 */
object EndpointScenario {

    /** Условие записи. [id] — в имени файла и в JSON. */
    enum class Condition(val id: String, val label: String) {
        SILENCE("silence", "Silence (quiet room)"),
        WATER("water", "Water (kitchen tap nearby)"),
        STREET("street", "Street (cars)"),
        STEADY_NOISE("steady_noise", "Steady noise (fan or TV)"),
    }

    /** Вид реплики. [id] — в JSON. */
    enum class Kind(val id: String) {
        SHORT("short"),
        /** Нарочная пауза ≈ 2 с на месте «…». */
        PAUSE("pause"),
        /** 2–3 фразы с естественным «um…». */
        LONG("long"),
        /** Без речи: начать и молчать. */
        NO_SPEECH("no_speech"),
    }

    /** Реплика сценария. [number] — с 1. [text] — пустой для записи без речи. */
    data class Line(val number: Int, val kind: Kind, val text: String) {
        val shownText: String get() = if (kind == Kind.NO_SPEECH) "(say nothing)" else text
        val instruction: String get() = when (kind) {
            Kind.SHORT, Kind.LONG -> "Say it. Then wait for two pulses and press the button."
            Kind.PAUSE -> "At \"…\" stop for about 2 seconds, then go on. After the end, wait for two pulses and press the button."
            Kind.NO_SPEECH -> "Say nothing. Wait for four pulses, then press the button."
        }
    }

    val lines: List<Line> = listOf(
        Line(1, Kind.SHORT, "At noon."),
        Line(2, Kind.SHORT, "Yes, I do."),
        Line(3, Kind.SHORT, "My dog is five."),
        Line(4, Kind.SHORT, "I like to read books in the evening."),
        Line(5, Kind.PAUSE, "I wanted to tell you about … my trip to the mountains."),
        Line(6, Kind.PAUSE, "Yesterday I … went to the shop."),
        Line(7, Kind.PAUSE, "My favorite food is … pizza."),
        Line(8, Kind.PAUSE, "I think that … it is a good idea."),
        Line(9, Kind.LONG, "Yesterday I got up early. Um… I made coffee and walked with my dog in the park."),
        Line(10, Kind.LONG, "Last weekend I visited my friend. We cooked dinner, um… and then we watched a movie."),
        Line(11, Kind.NO_SPEECH, ""),
        Line(12, Kind.NO_SPEECH, ""),
    )
}
