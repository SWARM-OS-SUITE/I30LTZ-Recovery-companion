package com.example.data.ai

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs one of the [OfflineModelCatalog] models on the phone with Google's LiteRT-LM runtime. Nothing here
 * touches the network: once a model file is installed, chat works in airplane mode.
 *
 * Only one model is in memory at a time and only while it is being used. On a 4 GB phone the model is the
 * biggest thing in RAM, so it is released after [IDLE_UNLOAD_MS] of silence and whenever Android reports
 * memory pressure.
 *
 * Honesty rule (kept from the stub this replaces): if nothing is installed or loaded, callers get a plain
 * "unavailable" result. This class never invents an answer.
 */
class OfflineLlm private constructor(
    private val app: Context,
    val store: OfflineModelStore,
) {
    sealed interface State {
        data object Idle : State
        data class Loading(val model: OfflineModel) : State
        data class Ready(val model: OfflineModel) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loadLock = Mutex()
    private val generateLock = Mutex()
    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    private var idleJob: Job? = null

    val loadedModel: OfflineModel? get() = (state.value as? State.Ready)?.model

    /** The phone's total RAM as Android reports it (a "4 GB" phone shows about 3.4 GB). */
    fun totalRamBytes(): Long = memoryInfo().totalMem

    private fun memoryInfo() = ActivityManager.MemoryInfo().also {
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
    }

    /** The best installed model this phone can run: comfortable ones first, then tight ones, biggest first. */
    fun bestInstalled(): OfflineModel? {
        val ram = totalRamBytes()
        val installed = OfflineModelCatalog.all.filter { store.readyFile(it) != null }
        return installed.filter { it.fit(ram) == OfflineModel.Fit.COMFORTABLE }.maxByOrNull { it.sizeBytes }
            ?: installed.filter { it.fit(ram) == OfflineModel.Fit.TIGHT }.maxByOrNull { it.sizeBytes }
    }

    suspend fun load(model: OfflineModel): Result<Unit> = loadLock.withLock {
        if (loadedModel?.id == model.id && engine != null) return@withLock Result.success(Unit)
        val file: File = store.readyFile(model)
            ?: return@withLock fail("${model.displayName} is not installed yet. Download it first.")
        if (model.fit(totalRamBytes()) == OfflineModel.Fit.TOO_BIG) {
            return@withLock fail("${model.displayName} needs a bigger phone (more RAM) than this one.")
        }
        releaseEngine()
        _state.value = State.Loading(model)
        try {
            val created = withContext(Dispatchers.IO) {
                Engine(
                    EngineConfig(
                        modelPath = file.path,
                        backend = Backend.CPU(),
                        maxNumTokens = model.contextTokens,
                        cacheDir = app.cacheDir.path,
                    )
                ).also { it.initialize() }
            }
            engine = created
            _state.value = State.Ready(model)
            scheduleIdleUnload()
            Result.success(Unit)
        } catch (e: CancellationException) {
            _state.value = State.Idle
            throw e
        } catch (e: Throwable) {
            // UnsatisfiedLinkError (no native library for this CPU), the runtime's own exceptions, and OOM.
            Log.w(TAG, "model load failed", e)
            fail("Could not start ${model.displayName}: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun fail(message: String): Result<Unit> {
        _state.value = State.Failed(message)
        return Result.failure(IllegalStateException(message))
    }

    /**
     * Streams the model's reply to [user] as text pieces. [history] is earlier turns (trimmed to fit),
     * [system] is the standing instruction. Cancelling the collector stops generation on the phone.
     */
    fun chat(system: String, history: List<ChatTurn>, user: String): Flow<String> = flow {
        val model = loadedModel ?: throw IllegalStateException(UNAVAILABLE)
        val eng = engine ?: throw IllegalStateException(UNAVAILABLE)
        if (!generateLock.tryLock()) throw IllegalStateException("Boltz is still answering the last message.")
        idleJob?.cancel()
        var conv: Conversation? = null
        try {
            val clippedUser = ChatPrompt.clip(user, model.contextTokens / 2)
            val earlier = ChatPrompt.fitHistory(history, system, clippedUser, model.contextTokens).map {
                if (it.role == ChatTurn.Role.USER) Message.user(it.text) else Message.model(it.text)
            }
            conv = eng.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    initialMessages = earlier,
                    samplerConfig = SamplerConfig(40, 0.95, 0.4, 0),
                )
            )
            conversation = conv
            val prompt = if (model.usesQwenThinkSwitch) "$clippedUser /no_think" else clippedUser
            val filter = ThinkFilter()
            // The library's own Flow overload crashes at runtime (NoSuchMethodError SendChannel.close$default:
            // litertlm 0.16.1 was built against a newer kotlinx-coroutines than the one we ship), so use the
            // callback overload and bridge it to a Channel here, where our own coroutines version is used.
            val pieces = Channel<Result<String>>(Channel.UNLIMITED)
            conv.sendMessageAsync(prompt, object : MessageCallback {
                override fun onMessage(message: Message) { pieces.trySend(Result.success(textOf(message))) }
                override fun onDone() { pieces.close() }
                override fun onError(throwable: Throwable) { pieces.trySend(Result.failure(throwable)); pieces.close() }
            })
            for (piece in pieces) {
                val visible = filter.accept(piece.getOrThrow())
                if (visible.isNotEmpty()) emit(visible)
            }
            val tail = filter.finish()
            if (tail.isNotEmpty()) emit(tail)
        } catch (e: CancellationException) {
            runCatching { conv?.cancelProcess() }
            throw e
        } finally {
            conversation = null
            runCatching { conv?.close() }
            generateLock.unlock()
            scheduleIdleUnload()
        }
    }.flowOn(Dispatchers.Default)

    /** The whole reply as one string, or a plain "unavailable" note when no model is ready. */
    suspend fun answer(system: String, user: String, history: List<ChatTurn> = emptyList()): String {
        if (loadedModel == null) {
            val candidate = bestInstalled() ?: return UNAVAILABLE
            load(candidate).onFailure { return it.message ?: UNAVAILABLE }
        }
        val out = StringBuilder()
        chat(system, history, user).collect { out.append(it) }
        return out.toString().trim().ifEmpty { "The model returned no text. Try rephrasing." }
    }

    /** Stops the current reply, keeping the model loaded. */
    fun stop() {
        runCatching { conversation?.cancelProcess() }
    }

    fun unload() {
        scope.launch { loadLock.withLock { stopAndRelease() } }
    }

    /** Called when Android is short of memory; frees the model unless it is mid-answer. */
    fun onMemoryPressure() {
        if (!generateLock.isLocked) unload()
    }

    private fun stopAndRelease() {
        stop()
        releaseEngine()
        _state.value = State.Idle
    }

    private fun releaseEngine() {
        idleJob?.cancel()
        val old = engine
        engine = null
        if (old != null) runCatching { old.close() }
        if (_state.value is State.Ready || _state.value is State.Loading) _state.value = State.Idle
    }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            if (!generateLock.isLocked) unload()
        }
    }

    private fun textOf(message: Message): String =
        message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    companion object {
        private const val TAG = "OfflineLlm"
        private const val IDLE_UNLOAD_MS = 5 * 60 * 1000L

        const val UNAVAILABLE = "Offline AI is unavailable: no offline model is installed on this phone. " +
            "No command has been run. Download a model under AI Lab, or connect a configured AI provider."

        @Volatile private var instance: OfflineLlm? = null

        /** The one shared engine for the app. */
        fun get(context: Context): OfflineLlm = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                OfflineLlm(app, OfflineModelStore(File(app.filesDir, "models"))).also { instance = it }
            }
        }

        /** The shared engine if the app has started it, else null (plain JVM tests, early startup). */
        fun peek(): OfflineLlm? = instance
    }
}
