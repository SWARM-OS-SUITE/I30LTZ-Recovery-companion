package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.phonenode.HttpError
import com.example.data.phonenode.MiniHttp
import com.example.data.service.OpenRouterManager
import com.example.data.service.OpenRouterVoice
import com.example.data.service.TtsVoiceManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The real voice path on a real Android: settings storage, TtsVoiceManager, chunked playback with MediaPlayer
 * (a real mp3), and the fallback to the phone's voice. OpenRouter is a fake server on localhost, so no key, no
 * cost and no text leaves the device. The real ElevenLabs sound can only be judged with a real key (TEST VOICE).
 */
@RunWith(AndroidJUnit4::class)
class VoiceOnDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val mp3: ByteArray by lazy { instr.context.assets.open("voice-sample.mp3").use { it.readBytes() } }

    private lateinit var server: ServerSocket
    private val bodies = CopyOnWriteArrayList<JSONObject>()
    private val auths = CopyOnWriteArrayList<String>()
    @Volatile private var status = 200
    private lateinit var manager: TtsVoiceManager
    private val settings by lazy { OpenRouterManager(app) }

    @Before fun start() {
        server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val c = try { server.accept() } catch (e: Exception) { return@Thread }
                Thread {
                    c.use {
                        try {
                            val req = MiniHttp.readRequest(BufferedInputStream(c.getInputStream()))
                            if (req.method == "POST" && req.path == "/api/v1/audio/speech") {
                                bodies += JSONObject(String(req.body)); auths += req.headers["authorization"].orEmpty()
                                if (status == 200) {
                                    MiniHttp.writeHead(c.getOutputStream(), 200, linkedMapOf("Content-Type" to "audio/mpeg", "Content-Length" to mp3.size.toString()))
                                    c.getOutputStream().write(mp3); c.getOutputStream().flush()
                                } else MiniHttp.writeJson(c.getOutputStream(), status, """{"error":{"message":"insufficient credits"}}""")
                            } else MiniHttp.writeJson(c.getOutputStream(), 404, "{}")
                        } catch (_: HttpError) { } catch (_: java.io.IOException) { }
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }
        settings.saveApiKey("fake-key-not-real")
        settings.saveVoice(true, "bill", "elevenlabs/eleven-turbo-v2.5")
        instr.runOnMainSync { manager = TtsVoiceManager(app, OpenRouterVoice("http://127.0.0.1:${server.localPort}/api/v1")) }
    }

    @After fun stop() {
        instr.runOnMainSync { manager.stop(); manager.shutdown() }
        server.close()
        settings.saveVoice(false, "bill", OpenRouterManager.DEFAULT_VOICE_MODEL)
        settings.clearApiKey()
    }

    private fun speak(text: String, ignoreMute: Boolean = false) = instr.runOnMainSync { manager.speak(text, ignoreMute = ignoreMute) }
    private fun until(ms: Long, what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(50) }
        throw AssertionError("Timed out waiting for $what; status='${manager.voiceStatus.value}' requests=${bodies.size}")
    }

    @Test fun anOlderMaleVoiceIsRequestedAndRealMp3AudioPlaysToTheEnd() {
        speak("Good evening. All systems are in order.")
        until(10000, "the request") { bodies.isNotEmpty() }
        val j = bodies.first()
        assertEquals("bill", j.getString("voice")); assertEquals("elevenlabs/eleven-turbo-v2.5", j.getString("model"))
        assertEquals("mp3", j.getString("response_format")); assertEquals("Good evening. All systems are in order.", j.getString("input"))
        assertEquals("Bearer fake-key-not-real", auths.first())
        until(10000, "it to start speaking") { manager.isSpeaking.value }
        until(15000, "playback to finish") { !manager.isSpeaking.value }
        assertTrue(manager.voiceStatus.value, manager.voiceStatus.value == "Spoke as 'bill'")
        assertEquals("a short reply is one request", 1, bodies.size)
    }

    @Test fun whenOpenRouterRefusesThePhonesOwnVoiceTakesOverAndSaysWhy() {
        status = 402
        speak("This will not be spoken by the cloud voice.")
        until(10000, "the explanation") { manager.voiceStatus.value.startsWith("Realistic voice unavailable") }
        assertTrue(manager.voiceStatus.value, manager.voiceStatus.value.contains("no credit") && manager.voiceStatus.value.contains("phone's voice"))
        assertFalse(manager.voiceStatus.value.contains("not-real"))
        until(5000, "the speaking flag to settle") { !manager.isSpeaking.value }
        assertEquals("one request, no retry loop", 1, bodies.size)
    }

    @Test fun nothingLeavesThePhoneWhenTheVoiceIsOffOrThereIsNoKey() {
        settings.saveVoice(false, "bill", OpenRouterManager.DEFAULT_VOICE_MODEL)
        speak("Voice is off, so nothing may be sent.")
        Thread.sleep(1500)
        assertEquals(0, bodies.size)
        settings.saveVoice(true, "bill", OpenRouterManager.DEFAULT_VOICE_MODEL); settings.clearApiKey()
        speak("There is no key, so nothing may be sent.")
        Thread.sleep(1500)
        assertEquals(0, bodies.size)
    }

    @Test fun muteIsRespectedExceptForTheTestButton() {
        instr.runOnMainSync { manager.toggleMute() }
        speak("Muted.")
        Thread.sleep(1200)
        assertEquals("muted means silent and nothing is sent", 0, bodies.size)
        speak("Test voice.", ignoreMute = true)
        until(10000, "the test voice request") { bodies.size == 1 }
    }

    @Test fun aLongReplyIsSentInSentenceSizedPiecesAndStopCancelsTheRest() {
        val sentence = "The system is running within normal limits and nothing needs your attention right now. "
        speak(sentence.repeat(14))
        until(10000, "the first requests") { bodies.size >= 2 } // piece one plus the prefetch of piece two
        assertTrue(bodies.all { it.getString("input").length <= OpenRouterVoice.CHUNK_CHARS })
        instr.runOnMainSync { manager.stop() }
        until(5000, "speaking to stop") { !manager.isSpeaking.value }
        val after = bodies.size
        Thread.sleep(2500)
        assertEquals("no further requests after stop", after, bodies.size)
    }

    @Test fun voiceSettingsAreValidatedAndSurviveARestart() {
        for (bad in listOf("", "Bill Smith", "../x", "a".repeat(41), "bill;rm")) {
            val err = runCatching { settings.saveVoice(true, bad, OpenRouterManager.DEFAULT_VOICE_MODEL) }.exceptionOrNull()
            assertTrue("'$bad' must be refused: $err", err is IllegalArgumentException)
        }
        assertTrue(runCatching { settings.saveVoice(true, "bill", "evil/model") }.exceptionOrNull() is IllegalArgumentException)
        settings.saveVoice(true, "  George ", "elevenlabs/eleven-v3")
        val fresh = OpenRouterManager(app).readVoice()
        assertEquals(OpenRouterManager.VoiceSettings(true, "george", "elevenlabs/eleven-v3"), fresh)
    }
}
