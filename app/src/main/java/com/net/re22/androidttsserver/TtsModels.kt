package net.re22.androidttsserver

data class VoiceInfo(
    val id: String,
    val name: String,
    val quality: Int,
    val latency: Int,
    val requiresNetworkConnection: Boolean,
)

data class TtsRequest(
    val text: String,
    val voiceId: String?,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
)
