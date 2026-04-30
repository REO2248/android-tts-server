package net.re22.androidttsserver

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SimpleHttpServer(
    private val port: Int,
    private val ttsEngineManager: TtsEngineManager,
    private val onPortChangeRequest: ((Int) -> Unit)? = null,
) {
    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverSocket = ServerSocket(port)
        executor.execute {
            while (running.get()) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute { handleConnection(socket) }
                } catch (_: Exception) {
                    if (running.get()) continue
                    break
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        executor.shutdownNow()
    }

    private fun handleConnection(socket: Socket) {
        socket.use { client ->
            try {
                val request = readRequest(client.getInputStream())
                val response = route(request)
                try {
                    writeResponse(client.getOutputStream(), response)
                } catch (_: SocketException) {
                    // Client disconnected before the response was written.
                }
            } catch (error: Exception) {
                try {
                    writeResponse(
                        client.getOutputStream(),
                        HttpResponse(
                            500,
                            "Internal Server Error",
                            "text/plain; charset=utf-8",
                            error.message.orEmpty().toByteArray(StandardCharsets.UTF_8),
                        ),
                    )
                } catch (_: SocketException) {
                    // Client disconnected before the error response was written.
                }
            }
        }
    }

    private fun route(request: HttpRequest): HttpResponse {
        val pathWithoutQuery = request.path.substringBefore('?')

        if (request.method == "GET" && pathWithoutQuery == "/") {
            val voices = ttsEngineManager.availableVoices()
            return HttpResponse(200, "OK", "text/html; charset=utf-8", buildHtmlUI(voices).toByteArray(StandardCharsets.UTF_8))
        }

        if (request.method == "GET" && pathWithoutQuery == "/api/voices") {
            val json = JSONArray().apply {
                ttsEngineManager.availableVoices().forEach { voice ->
                    put(
                        JSONObject().apply {
                            put("id", voice.id)
                            put("name", voice.name)
                            put("quality", voice.quality)
                            put("latency", voice.latency)
                            put("requires_network_connection", voice.requiresNetworkConnection)
                        },
                    )
                }
            }
            return HttpResponse(200, "OK", "application/json; charset=utf-8", json.toString().toByteArray(StandardCharsets.UTF_8))
        }

        if (request.method == "POST" && pathWithoutQuery == "/api/tts") {
            return try {
                val bodyString = String(request.body, StandardCharsets.UTF_8)
                if (bodyString.isEmpty()) {
                    return HttpResponse(400, "Bad Request", "text/plain; charset=utf-8", "Empty body".toByteArray(StandardCharsets.UTF_8))
                }
                val payload = JSONObject(bodyString)
                val text = payload.optString("text").trim()
                if (text.isEmpty()) {
                    return HttpResponse(400, "Bad Request", "text/plain; charset=utf-8", "Missing text".toByteArray(StandardCharsets.UTF_8))
                }
                val ttsRequest = TtsRequest(
                    text = text,
                    voiceId = payload.optString("voice_id").takeIf { it.isNotBlank() },
                    speed = payload.optDouble("speed", 1.0).toFloat(),
                    pitch = payload.optDouble("pitch", 1.0).toFloat(),
                )
                HttpResponse(200, "OK", "audio/wav", ttsEngineManager.synthesize(ttsRequest))
            } catch (jsonError: org.json.JSONException) {
                HttpResponse(400, "Bad Request", "text/plain; charset=utf-8", "Invalid JSON: ${jsonError.message.orEmpty()}".toByteArray(StandardCharsets.UTF_8))
            } catch (error: Exception) {
                HttpResponse(400, "Bad Request", "text/plain; charset=utf-8", error.message.orEmpty().toByteArray(StandardCharsets.UTF_8))
            }
        }

        return HttpResponse(404, "Not Found", "text/plain; charset=utf-8", "Not found".toByteArray(StandardCharsets.UTF_8))
    }

    private fun buildHtmlUI(voices: List<VoiceInfo>): String {
        val voicesJson = JSONArray().apply {
            voices.sortedBy { it.id }.forEach { voice ->
                put(
                    JSONObject().apply {
                        put("id", voice.id)
                        put("name", voice.name)
                        put("quality", voice.quality)
                        put("latency", voice.latency)
                        put("requires_network_connection", voice.requiresNetworkConnection)
                    },
                )
            }
        }.toString()

        return """
            <!DOCTYPE html>
            <html lang=en>
            <head>
                <meta charset=UTF-8>
                <meta name=viewport content="width=device-width,initial-scale=1">
                <title>Android TTS Server</title>
                <style>
                    html, body {
                        width: 100%;
                        margin: 0;
                        padding: 0;
                        border: 0;
                    }
                    .container {
                        max-width: 800px;
                        margin: auto;
                    }
                    #text {
                        width: calc(100% - 10px);
                        height: 10em;
                    }
                    table {
                        width: 100%;
                        border-collapse: collapse;
                        margin: 10px 0;
                    }
                    td {
                        padding: 4px 2px;
                    }
                    input[type=range] {
                        width: 100%;
                    }
                </style>
            </head>
            <body>
                <div class=container>
                    <h1>Android TTS Server</h1>

                    <div id=voice_selection style="margin:5px 0">
                        Voice <select id=voice_selector></select>
                        (<span id=voice_count>0</span> voices available.)
                    </div>

                    <table>
                        <tr>
                            <td><label for=speed>Speed</label></td>
                            <td><input type=range id=speed value=1.0 min=0.5 max=4.0 step=0.01></td>
                            <td><span id=speed_s>1.00</span></td>
                        </tr>
                        <tr>
                            <td><label for=pitch>Pitch</label></td>
                            <td><input type=range id=pitch value=1.0 min=0.5 max=2.0 step=0.01></td>
                            <td><span id=pitch_s>1.00</span></td>
                        </tr>
                        <tr>
                            <td></td>
                            <td><button id=reset>Reset Parameter</button></td>
                        </tr>
                    </table>

                    <textarea id=text placeholder="Text to Synthesis"></textarea>
                    <p>
                        <button id=synthesis_button>Synthesis</button>
                    </p>
                    <audio id=audio controls></audio>
                    <script>
                        let voices;
                        const embedded_voices = $voicesJson;
                        const voice_selector = document.getElementById("voice_selector");
                        const text = document.getElementById("text");
                        const speed = document.getElementById("speed");
                        const pitch = document.getElementById("pitch");

                        const fmt_float = v => {
                            v += "";
                            const parts = v.split(".");
                            if (parts.length == 1) parts.push("");
                            while (parts[1].length < 2) parts[1] += "0";
                            return parts.join(".");
                        };

                        const update_values = () => {
                            document.getElementById("speed_s").textContent = fmt_float(speed.value);
                            document.getElementById("pitch_s").textContent = fmt_float(pitch.value);
                        };

                        update_values();

                        document.getElementById("reset").addEventListener("click", () => {
                            speed.value = 1.0;
                            pitch.value = 1.0;
                            update_values();
                        });

                        speed.addEventListener("input", update_values);
                        pitch.addEventListener("input", update_values);

                        voices = embedded_voices;
                        for (const voice of voices) {
                            const option = document.createElement("option");
                            const quality_label = voice.quality >= 400 ? "high" : voice.quality >= 300 ? "normal" : "low";
                            const latency_label = voice.latency <= 100 ? "very low" : voice.latency <= 200 ? "low" : voice.latency <= 300 ? "normal" : voice.latency <= 400 ? "high" : "very high";
                            option.textContent = `${'$'}{voice.name} (${'$'}{voice.requires_network_connection ? "network" : "local"}, ${'$'}{quality_label}, ${'$'}{latency_label})`;
                            option.value = voice.id;
                            voice_selector.appendChild(option);
                        }
                        document.getElementById("voice_count").textContent = voices.length;
                        if (voice_selector.options.length > 0) {
                            voice_selector.selectedIndex = 0;
                        }

                        document.getElementById("synthesis_button").addEventListener("click", async () => {
                            const request_body = JSON.stringify({
                                voice_id: voice_selector.value,
                                text: text.value,
                                speed: speed.value - 0,
                                pitch: pitch.value - 0,
                            });

                            const response = await fetch("/api/tts", {
                                method: "POST",
                                headers: { "Content-Type": "application/json" },
                                body: request_body,
                            });

                            if (response.status != 200) {
                                alert(response.status + " " + response.statusText + "\n" + await response.text());
                                return;
                            }

                            const audio = document.getElementById("audio");
                            const wav_blob = await response.blob();
                            if (audio.src) {
                                URL.revokeObjectURL(audio.src);
                            }
                            audio.src = URL.createObjectURL(wav_blob);
                            audio.play();
                        });
                    </script>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun readRequest(inputStream: InputStream): HttpRequest {
        val buffered = BufferedInputStream(inputStream)
        val headerBytes = ByteArrayOutputStream()
        var previousBytes = 0
        while (true) {
            val byte = buffered.read()
            if (byte < 0) throw IllegalStateException("Empty request")
            headerBytes.write(byte)
            previousBytes = ((previousBytes shl 8) or byte) and 0xFFFFFFFF.toInt()
            if (previousBytes == 0x0D0A0D0A) break
        }

        val rawHeaders = headerBytes.toString(StandardCharsets.ISO_8859_1.name())
        val headerLines = rawHeaders.split("\r\n")
        val requestLine = headerLines.firstOrNull()?.trim().orEmpty()
        val parts = requestLine.split(" ")
        if (parts.size < 2) throw IllegalStateException("Malformed request line: $requestLine")
        val method = parts[0].uppercase()
        val path = parts[1]

        val headers = mutableMapOf<String, String>()
        for (line in headerLines.drop(1)) {
            if (line.isBlank()) continue
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                headers[line.substring(0, colonIndex).trim().lowercase()] = line.substring(colonIndex + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val count = buffered.read(body, read, contentLength - read)
            if (count < 0) break
            read += count
        }
        return HttpRequest(method = method, path = path, headers = headers, body = if (read == contentLength) body else body.copyOf(read))
    }

    private fun writeResponse(outputStream: OutputStream, response: HttpResponse) {
        outputStream.use {
            val bodyBytes = response.body
            val headerText = buildString {
                append("HTTP/1.1 ")
                append(response.statusCode)
                append(' ')
                append(response.statusText)
                append("\r\n")
                append("Content-Type: ")
                append(response.contentType)
                append("\r\n")
                append("Content-Length: ")
                append(bodyBytes.size)
                append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            it.write(headerText.toByteArray(StandardCharsets.UTF_8))
            it.write(bodyBytes)
            it.flush()
        }
    }
}

data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

data class HttpResponse(
    val statusCode: Int,
    val statusText: String,
    val contentType: String,
    val body: ByteArray,
)
