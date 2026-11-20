package com.example.listeningtrainer.eventlog

/**
 * Журнал событий для разбора сбоев (инвариант 8: каждый выход из круга — с причиной).
 * Чистый Kotlin. Пишется только служебное: состояния, причины, длительности, коды, длины текстов.
 * Ключ и тексты реплик ученика и учителя сюда не попадают — передавай длины.
 */
interface EventLog {
    /** Обычное событие. [source] — кто пишет: Controller, Service, Network… */
    fun event(source: String, message: String)

    /** Ошибка: [message], тип и сообщение исключения, стек. */
    fun error(source: String, message: String, error: Throwable? = null)
}

/** Журнал, который ничего не пишет. */
object NoEventLog : EventLog {
    override fun event(source: String, message: String) {}
    override fun error(source: String, message: String, error: Throwable?) {}
}

/**
 * Журнал процесса. Настоящий файл подставляет TrainerApplication.onCreate;
 * до этого и в модульных тестах — [NoEventLog].
 */
object AppLog : EventLog {
    @Volatile
    var sink: EventLog = NoEventLog

    override fun event(source: String, message: String) = sink.event(source, message)
    override fun error(source: String, message: String, error: Throwable?) = sink.error(source, message, error)
}
