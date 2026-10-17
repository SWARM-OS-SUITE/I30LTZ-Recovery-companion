package com.example.data.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Plays a reply chunk by chunk: while one chunk is playing the next is already being fetched, so a long
 * answer starts after the first sentence and has no gaps. Audio files live in the cache folder only
 * while they play.
 */
class CloudVoicePlayer(private val context: Context, private val voice: OpenRouterVoice) {
    /** [onFirstAudio] runs once the first chunk is ready and about to play. Throws [VoiceException] on failure. */
    suspend fun play(apiKey: String, model: String, voiceName: String, chunks: List<String>, onFirstAudio: () -> Unit) {
        if (chunks.isEmpty()) return
        coroutineScope {
            fun fetch(i: Int) = async(Dispatchers.IO) { voice.synthesize(apiKey, model, voiceName, chunks[i]) }
            var next = fetch(0)
            for (i in chunks.indices) {
                val audio = next.await()
                if (i + 1 < chunks.size) next = fetch(i + 1)
                if (i == 0) onFirstAudio()
                playBytes(audio)
            }
        }
    }

    private suspend fun playBytes(bytes: ByteArray) = withContext(Dispatchers.Main) {
        val file = File.createTempFile("boltz-voice", ".mp3", context.cacheDir)
        val player = MediaPlayer()
        try {
            file.writeBytes(bytes)
            suspendCancellableCoroutine<Unit> { cont ->
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                player.setDataSource(file.path)
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                player.setOnErrorListener { _, what, extra ->
                    if (cont.isActive) cont.resumeWithException(VoiceException("Android could not play the voice audio ($what/$extra)")); true
                }
                cont.invokeOnCancellation { runCatching { player.stop() } }
                player.prepareAsync()
            }
        } finally {
            runCatching { player.release() }
            file.delete()
        }
    }
}
