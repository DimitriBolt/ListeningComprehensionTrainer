package com.example.listeningtrainer.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.listeningtrainer.config.ModelConfig
import com.example.listeningtrainer.conversation.SpeechProbability
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Модель Silero VAD v6 (ONNX, MIT) в onnxruntime (S03c, D66). Сеанс создаётся при первой записи —
 * в её потоке, не в главном ([SileroVad.reset]). Один сеанс на все [SileroVad]: состояние у каждого своё.
 * [load] отдаёт байты модели (в приложении — из assets, в тестах — из файла).
 */
class SileroModel(private val load: () -> ByteArray) : AutoCloseable {
    internal val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var created: OrtSession? = null

    internal val session: OrtSession
        @Synchronized get() = created ?: run {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            env.createSession(load(), opts).also { created = it }
        }

    @Synchronized
    override fun close() {
        created?.close()
        created = null
    }
}

/**
 * Вероятность речи кадра 512 отсчётов (32 мс) при 16 кГц — как обёртка `OnnxWrapper` из silero-vad и проба
 * S03b: на вход модели идут 64 последних отсчёта прошлого кадра и сам кадр (`input` 1×576), состояние
 * `state` 2×1×128 переходит от кадра к кадру. Звук — `отсчёт / 32768`.
 */
class SileroVad(private val model: SileroModel) : SpeechProbability {
    override val frameSamples = ModelConfig.VAD_FRAME_SAMPLES

    private val input = FloatArray(CONTEXT + frameSamples)
    private val state = FloatArray(STATE_SIZE)

    /** Начало реплики (в потоке записи): состояние — с нуля; модель грузится здесь, а не в первом кадре. */
    override fun reset() {
        model.session
        input.fill(0f)
        state.fill(0f)
    }

    override fun probability(frame: ShortArray): Float {
        require(frame.size == frameSamples) { "frame must be $frameSamples samples" }
        val session = model.session
        val env = model.env
        // Контекст: конец прошлого кадра — в начало входа.
        System.arraycopy(input, frameSamples, input, 0, CONTEXT)
        for (i in 0 until frameSamples) input[CONTEXT + i] = frame[i] / 32768f
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), STATE_SHAPE).use { s ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(SAMPLE_RATE), longArrayOf()).use { sr ->
                    session.run(mapOf("input" to x, "state" to s, "sr" to sr)).use { out ->
                        (out.get(1) as OnnxTensor).floatBuffer.get(state)
                        return (out.get(0) as OnnxTensor).floatBuffer.get(0)
                    }
                }
            }
        }
    }

    private companion object {
        const val CONTEXT = 64
        const val STATE_SIZE = 2 * 1 * 128
        val STATE_SHAPE = longArrayOf(2, 1, 128)
        val SAMPLE_RATE = longArrayOf(ModelConfig.RECORD_SAMPLE_RATE.toLong())
    }
}
