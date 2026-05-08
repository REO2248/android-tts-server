package net.re22.androidttsserver

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TtsEngineManager(context: Context) {
    private val appContext = context.applicationContext
    private val initLatch = CountDownLatch(1)
    private val synthesisLock = Any()
    private val activeRequests = ConcurrentHashMap<String, SynthesisResultWaiter>()
    private var cachedVoiceInfos: List<VoiceInfo> = emptyList()

    private val textToSpeech = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            refreshVoiceCache()
        }
        initLatch.countDown()
    }

    private val tag = "TtsEngineManager"

    init {
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                activeRequests[utteranceId]?.complete(SynthesisOutcome.Success)
            }

            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String) {
                activeRequests[utteranceId]?.complete(SynthesisOutcome.Failure("General error"))
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                activeRequests[utteranceId]?.complete(SynthesisOutcome.Failure("Error code: $errorCode"))
            }
        })
    }

    fun isReady(): Boolean = initLatch.count == 0L

    fun availableVoices(): List<VoiceInfo> {
        awaitReady()
        return cachedVoiceInfos
    }

    fun synthesize(request: TtsRequest): ByteArray {
        awaitReady()
        
        val utteranceId = UUID.randomUUID().toString()
        val waiter = SynthesisResultWaiter()
        activeRequests[utteranceId] = waiter
        
        val outputFile = File.createTempFile("tts-", ".wav", appContext.cacheDir)
        
        try {
            synchronized(synthesisLock) {
                setupParameters(request)
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }
                Log.i(tag, "Synthesize: $utteranceId")
                val queueResult = textToSpeech.synthesizeToFile(request.text, params, outputFile, utteranceId)
                if (queueResult != TextToSpeech.SUCCESS) {
                    throw IllegalStateException("Failed to queue synthesis: $queueResult")
                }
            }

            val outcome = waiter.await(60, TimeUnit.SECONDS)
                ?: throw IllegalStateException("Timeout for $utteranceId")

            if (outcome is SynthesisOutcome.Failure) {
                throw IllegalStateException(outcome.message)
            }

            ensureFileIsReady(outputFile)
            return outputFile.readBytes()

        } finally {
            activeRequests.remove(utteranceId)
            if (outputFile.exists()) outputFile.delete()
        }
    }

    private fun setupParameters(request: TtsRequest) {
        request.voiceId?.let { id ->
            textToSpeech.voices?.find { it.name == id }?.let { textToSpeech.voice = it }
        }
        textToSpeech.setSpeechRate(request.speed.coerceIn(0.1f, 5.0f))
        textToSpeech.setPitch(request.pitch.coerceIn(0.1f, 5.0f))
    }

    private fun awaitReady() {
        if (!initLatch.await(15, TimeUnit.SECONDS)) {
            throw IllegalStateException("TTS timeout")
        }
    }

    private fun refreshVoiceCache() {
        cachedVoiceInfos = textToSpeech.voices?.map { voice ->
            VoiceInfo(
                id = voice.name,
                name = voice.name,
                quality = voice.quality,
                latency = voice.latency,
                requiresNetworkConnection = voice.isNetworkConnectionRequired,
            )
        } ?: emptyList()
    }

    private fun ensureFileIsReady(file: File) {
        val start = System.currentTimeMillis()
        while (file.length() < 44 && System.currentTimeMillis() - start < 2000) {
            Thread.sleep(10)
        }
        var lastSize = file.length()
        var stableCount = 0
        while (System.currentTimeMillis() - start < 5000) {
            Thread.sleep(20)
            val currentSize = file.length()
            if (currentSize > 44 && currentSize == lastSize) {
                if (++stableCount >= 3) break
            } else {
                stableCount = 0
                lastSize = currentSize
            }
        }
    }

    fun shutdown() {
        textToSpeech.stop()
        textToSpeech.shutdown()
    }

    private class SynthesisResultWaiter {
        private val latch = CountDownLatch(1)
        private val result = AtomicReference<SynthesisOutcome?>(null)
        fun complete(outcome: SynthesisOutcome) {
            result.set(outcome)
            latch.countDown()
        }
        fun await(timeout: Long, unit: TimeUnit): SynthesisOutcome? {
            return if (latch.await(timeout, unit)) result.get() else null
        }
    }

    private sealed class SynthesisOutcome {
        object Success : SynthesisOutcome()
        data class Failure(val message: String) : SynthesisOutcome()
    }
}

