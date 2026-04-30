package net.re22.androidttsserver

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TtsEngineManager(context: Context) {
    private val appContext = context.applicationContext
    private val initLatch = CountDownLatch(1)
    private val requestLock = Any()
    private val readyVoiceNames = mutableMapOf<String, Voice>()
    @Volatile
    private var cachedVoiceInfos: List<VoiceInfo> = emptyList()
    private val textToSpeech = TextToSpeech(appContext) { status ->
        synchronized(requestLock) {
            if (status == TextToSpeech.SUCCESS) {
                refreshVoiceCache()
            }
            initLatch.countDown()
        }
    }

    private val pendingUtteranceId = AtomicReference<String?>(null)
    private val pendingResult = AtomicReference<SynthesisOutcome?>(null)
    private val tag = "TtsEngineManager"

    init {
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                if (pendingUtteranceId.compareAndSet(utteranceId, null)) {
                    pendingResult.set(SynthesisOutcome.Success)
                }
            }

            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String) {
                if (pendingUtteranceId.compareAndSet(utteranceId, null)) {
                    pendingResult.set(SynthesisOutcome.Failure("TTS engine returned an error for $utteranceId"))
                }
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                if (pendingUtteranceId.compareAndSet(utteranceId, null)) {
                    pendingResult.set(SynthesisOutcome.Failure("TTS engine returned error code $errorCode"))
                }
            }
        })
    }

    fun isReady(): Boolean = initLatch.count == 0L && textToSpeech.voices != null

    fun availableVoices(): List<VoiceInfo> {
        awaitReady()
        return cachedVoiceInfos
    }

    fun synthesize(request: TtsRequest): ByteArray {
        awaitReady()
        val voice = request.voiceId?.let { id ->
            readyVoiceNames[id]
        }
        Log.i(tag, "synthesize: textLength=${request.text.length}, voiceId=${request.voiceId}, voice=${voice?.name ?: "<default>"}, locale=${voice?.locale}, speed=${request.speed}, pitch=${request.pitch}")
        if (voice != null) {
            textToSpeech.voice = voice
            Log.i(tag, "selected voice now=${textToSpeech.voice?.name}, locale=${textToSpeech.voice?.locale}")
        }
        textToSpeech.setSpeechRate(request.speed.coerceIn(0.1f, 5.0f))
        textToSpeech.setPitch(request.pitch.coerceIn(0.1f, 5.0f))

        val outputFile = File.createTempFile("tts-", ".wav", appContext.cacheDir)
        val utteranceId = UUID.randomUUID().toString()
        pendingUtteranceId.set(utteranceId)
        pendingResult.set(null)

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        synchronized(requestLock) {
            val queueResult = textToSpeech.synthesizeToFile(request.text, params, outputFile, utteranceId)
            if (queueResult != TextToSpeech.SUCCESS) {
                pendingUtteranceId.set(null)
                outputFile.delete()
                throw IllegalStateException("Failed to queue synthesis: code=$queueResult")
            }
        }

        waitForCompletion(utteranceId, outputFile)

        val audioBytes = outputFile.readBytes()
        outputFile.delete()
        Log.i(tag, "synthesize completed: bytes=${audioBytes.size}")
        return audioBytes
    }

    fun shutdown() {
        textToSpeech.stop()
        textToSpeech.shutdown()
    }

    private fun awaitReady() {
        if (!initLatch.await(15, TimeUnit.SECONDS)) {
            throw IllegalStateException("TTS engine initialization timed out")
        }
    }

    private fun refreshVoiceCache() {
        readyVoiceNames.clear()
        cachedVoiceInfos = textToSpeech.voices
            ?.map { voice ->
                VoiceInfo(
                    id = voice.name,
                    name = voice.name,
                    quality = voice.quality,
                    latency = voice.latency,
                    requiresNetworkConnection = voice.isNetworkConnectionRequired,
                )
            }
            ?: emptyList()
        textToSpeech.voices?.forEach { voice ->
            readyVoiceNames[voice.name] = voice
            readyVoiceNames[voice.locale.toLanguageTag()] = voice
        }
    }

    private fun waitForCompletion(utteranceId: String, outputFile: File) {
        val startedAt = System.currentTimeMillis()
        var lastFileSize = 0L
        var unchangedCount = 0
        
        while (System.currentTimeMillis() - startedAt < 45000) {
            val currentSize = outputFile.length()
            
            if (currentSize >= 44) {
                if (currentSize == lastFileSize) {
                    unchangedCount++
                    if (unchangedCount > 10) {
                        pendingUtteranceId.set(null)
                        return
                    }
                } else {
                    unchangedCount = 0
                    lastFileSize = currentSize
                }
            }
            
            val currentResult = pendingResult.get()
            if (currentResult != null) {
                when (currentResult) {
                    is SynthesisOutcome.Success -> {
                        if (outputFile.exists() && outputFile.length() > 44) {
                            pendingUtteranceId.set(null)
                            return
                        }
                    }
                    is SynthesisOutcome.Failure -> {
                        throw IllegalStateException(currentResult.message)
                    }
                }
                pendingResult.set(null)
            }
            
            Thread.sleep(100)
        }
        
        pendingUtteranceId.set(null)
        if (!outputFile.exists() || outputFile.length() == 0L) {
            throw IllegalStateException("TTS synthesis failed: output file empty or missing after ${System.currentTimeMillis() - startedAt}ms")
        }
    }
}

private sealed class SynthesisOutcome {
    data object Success : SynthesisOutcome()
    data class Failure(val message: String) : SynthesisOutcome()
}

