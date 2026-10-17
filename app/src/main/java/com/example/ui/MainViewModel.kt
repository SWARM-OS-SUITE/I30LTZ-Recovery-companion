package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.config.SwarmConnection
import com.example.data.db.*
import com.example.data.nodes.LinkState
import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.NodeSecurity
import com.example.data.nodes.NodeSecurityController
import com.example.data.nodes.NodeRegistry
import com.example.data.nodes.NodeStream
import com.example.data.repository.AppRepository
import com.example.data.service.CompanionHeartbeat
import com.example.data.service.GeminiAiService
import com.example.data.service.SystemInfoManager
import com.example.data.service.SystemStats
import com.example.data.service.TtsVoiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val systemInfoManager = SystemInfoManager(application)
    private val ttsVoiceManager = TtsVoiceManager(application)
    val openRouterManager = com.example.data.service.OpenRouterManager(application)
    private val geminiAiService = GeminiAiService(openRouterManager)
    private val swarmConnection = SwarmConnection(application)

    val nodeRegistry = NodeRegistry.get(application)
    val repository = AppRepository(db, systemInfoManager, ttsVoiceManager, geminiAiService, swarmConnection, nodeRegistry)
    val heartbeat = CompanionHeartbeat(swarmConnection)

    val openRouterApiKey = openRouterManager.apiKey

    // ── Dr. Boltz's realistic voice (ElevenLabs through OpenRouter) ──
    val voiceEnabled = openRouterManager.voiceEnabled
    val voiceName = openRouterManager.voiceName
    val voiceModel = openRouterManager.voiceModel
    val voiceStatus = ttsVoiceManager.voiceStatus
    private val _voiceCheck = MutableStateFlow("")
    val voiceCheck: StateFlow<String> = _voiceCheck

    /** Returns null on success, otherwise what to tell the person. */
    fun saveVoice(enabled: Boolean, name: String, model: String): String? =
        try { openRouterManager.saveVoice(enabled, name, model); null } catch (e: IllegalArgumentException) { e.message } catch (e: IllegalStateException) { e.message }

    fun testVoice() = ttsVoiceManager.speak(
        "I am Doctor Boltz. Your systems are in order, and I shall keep watching over them.", ignoreMute = true)

    /** Asks OpenRouter (no key, no text sent) whether the voice is still on offer for this model. */
    fun checkVoice(model: String, name: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _voiceCheck.value = "Checking OpenRouter's voice list…"
            val list = com.example.data.service.OpenRouterVoice().supportedVoices(model)
            _voiceCheck.value = when {
                list == null -> "Could not read OpenRouter's voice list right now."
                name.trim().lowercase() in list -> "OpenRouter still lists '${name.trim().lowercase()}' for this model."
                else -> "OpenRouter no longer lists '${name.trim().lowercase()}' for this model. Try one of: ${list.take(12).joinToString()}"
            }
        }
    }
    val selectedOpenRouterModel = openRouterManager.selectedModel

    fun saveOpenRouterApiKey(key: String) {
        openRouterManager.saveApiKey(key)
    }

    fun saveOpenRouterModel(model: String) {
        openRouterManager.saveSelectedModel(model)
    }

    fun clearOpenRouterApiKey() {
        openRouterManager.clearApiKey()
    }

    // Navigation Tab
    private val _currentTab = MutableStateFlow(NavigationTab.DASHBOARD)
    val currentTab: StateFlow<NavigationTab> = _currentTab

    // System Stats
    private val _systemStats = MutableStateFlow(systemInfoManager.getLiveSystemStats())
    val systemStats: StateFlow<SystemStats> = _systemStats

    // NOTE: the init block is at the END of the property declarations (just above onCleared) on purpose.
    // Its coroutines start running immediately on the main thread, and a property declared below an init
    // block is still null at that moment. That crashed the app right after login (see MainViewModelTest).

    // ── Nodes ─────────────────────────────────────────────────────────────────
    val nodes: StateFlow<List<Node>> = nodeRegistry.nodes

    // ── Malware scanning per node (ClamAV through the node agent) ─────────────
    internal val securityController = NodeSecurityController(viewModelScope)
    val security: StateFlow<Map<String, NodeSecurity>> = securityController.state

    private class Running(val node: Node, val stream: NodeStream, val collector: kotlinx.coroutines.Job, val stateJob: kotlinx.coroutines.Job)
    private val running = HashMap<String, Running>()
    private val _linkStates = MutableStateFlow<Map<String, LinkState>>(emptyMap())
    /** Live-stream state per node id (absent = no stream wanted for that node). */
    val linkStates: StateFlow<Map<String, LinkState>> = _linkStates

    @Synchronized
    private fun reconcileStreams(list: List<Node>) {
        val wanted = list.filter { it.wantsLiveStream }.associateBy { it.id }
        for ((id, r) in running.toMap()) {
            val next = wanted[id]
            // Restart when the node was removed, turned off, or its address/credentials changed.
            if (next == null || next.host != r.node.host || next.port != r.node.port || next.token != r.node.token || next.backend != r.node.backend) {
                r.stream.stop(); r.collector.cancel(); r.stateJob.cancel()
                running.remove(id)
                _linkStates.value = _linkStates.value - id
            }
        }
        for ((id, node) in wanted) {
            if (id in running) continue
            val stream = NodeStream(node, viewModelScope)
            val collector = viewModelScope.launch { stream.telemetry.collect { repository.applyLiveTelemetry(node, it) } }
            val stateJob = viewModelScope.launch { stream.state.collect { s -> _linkStates.value = _linkStates.value + (id to s) } }
            running[id] = Running(node, stream, collector, stateJob)
            stream.start()
        }
    }

    fun saveNode(node: Node, makePrimary: Boolean) {
        nodeRegistry.upsert(node)
        if (makePrimary) {
            swarmConnection.host = node.host
            swarmConnection.port = node.port
            swarmConnection.authToken = node.token
            com.example.data.memory.MemorySyncEngine.connectionChanged()
            if (swarmConnection.hitlAlertsEnabled) com.example.data.hitl.HitlApprovalService.restart(getApplication())
        }
    }

    fun deleteNode(node: Node) {
        nodeRegistry.remove(node.id)
        viewModelScope.launch { repository.removeDeviceForNode(node) }
    }

    /** Which node (if any) the terminal, Coder and shared memory currently use. */
    fun isPrimary(node: Node): Boolean =
        swarmConnection.isUsable && swarmConnection.host.equals(node.host, ignoreCase = true) && swarmConnection.port == node.port

    fun optimizeSingleDevice(deviceId: Long) {
        if (deviceId == -1L) { runSuperchargerOptimization(); return }
        viewModelScope.launch {
            val scan = repository.optimizeSingleDevice(deviceId)
            _avatarSpeech.value = scan?.summaryMessage
                ?: "Could not optimize device #$deviceId — it is not reachable."
        }
    }

    /// This phone: a settings audit, labelled as such (it inspects settings, not apps).
    /// Any other device: reports whether that node can scan at all. Today none can,
    /// so the answer is "no verdict". This previously slept 1.8s and then decided the
    /// verdict with `(1..10).random() > 2`: a coin flip that told the user their
    /// device was infected, or clean, at random.
    fun runMalwareScanOnDevice(deviceId: Long) {
        viewModelScope.launch {
            _isOptimizing.value = true
            try {
                if (deviceId == -1L) {
                    _avatarSpeech.value = "⚡ Auditing this phone's security settings..."
                    val scan = repository.runAntivirusScan()
                    // The local audit looks at settings, not apps: say so instead of declaring the phone clean.
                    _avatarSpeech.value = "Settings audit finished. This is not a malware scan, so there is no clean or infected verdict. ${scan.summaryMessage}"
                } else {
                    startRemoteScan(deviceId)
                }
            } catch (e: Exception) {
                _avatarSpeech.value = "Scan did not complete: ${e.message}. No verdict; treat this device as unknown."
            }
            _isOptimizing.value = false
        }
    }

    /** The SCAN button on another device: starts a real quick scan on that node, or says why it cannot. */
    private suspend fun startRemoteScan(deviceId: Long) {
        val device = repository.allDevices.first().firstOrNull { it.id == deviceId }
        val node = device?.let { nodeRegistry.findByHost(it.ipAddress) }
        _avatarSpeech.value = when {
            device == null -> "That device is no longer in the list."
            node == null -> "${device.deviceName}: no node is set up for ${device.ipAddress}. No scan was run and there is no verdict."
            !node.isConfigured -> "${node.name} is not paired yet. Pair it under Nodes first."
            node.backend == NodeBackend.PHONE ->
                "${node.name} is a phone: Android does not let an app scan other apps, so there is no scan and no verdict for it."
            node.backend != NodeBackend.AGENT ->
                "${node.name} runs ${node.backend.label}, which has no malware scanner. Install the I30LTZ node agent on it to scan it. No scan was run and there is no verdict."
            else -> {
                securityController.start(node, "quick")
                _currentTab.value = NavigationTab.ANTIVIRUS
                "Started a quick malware scan on ${node.name}. Progress and results are on the Security tab."
            }
        }
    }

    fun runInfiniteFleetScan() {
        viewModelScope.launch {
            repository.allDevices.firstOrNull()?.forEach { dev ->
                repository.optimizeSingleDevice(dev.id)
            }
        }
    }

    // Database Flows
    val tasks: StateFlow<List<TaskEntity>> = repository.allTasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val devices: StateFlow<List<DeviceSyncEntity>> = repository.allDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentScans: StateFlow<List<SystemScanEntity>> = repository.recentScans
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chatMessages: StateFlow<List<AIChatEntity>> = repository.chatMessages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Voice State
    val isVoiceMuted = repository.isVoiceMuted
    val isVoiceSpeaking = repository.isVoiceSpeaking

    // Active Speech Text for DR. I30LTZ avatar
    private val _avatarSpeech = MutableStateFlow<String?>("⚡ Greetings! I am DR. I30LTZ — your IT Field Agent. Ask me anything to optimize your device & PC!")
    val avatarSpeech: StateFlow<String?> = _avatarSpeech

    // Is Optimizing Flag
    private val _isOptimizing = MutableStateFlow(false)
    val isOptimizing: StateFlow<Boolean> = _isOptimizing

    // OS Deployment / Boot Tools State
    private val _isoDownloadProgress = MutableStateFlow(0f)
    val isoDownloadProgress: StateFlow<Float> = _isoDownloadProgress

    private val _isUsbMounted = MutableStateFlow(false)
    val isUsbMounted: StateFlow<Boolean> = _isUsbMounted

    private val _grubCommandProposal = MutableStateFlow("")
    val grubCommandProposal: StateFlow<String> = _grubCommandProposal

    private val _isOfflineAi = MutableStateFlow(false)
    val isOfflineAi: StateFlow<Boolean> = _isOfflineAi


    fun selectTab(tab: NavigationTab) {
        _currentTab.value = tab
        refreshSystemStats()
    }

    fun refreshSystemStats() {
        _systemStats.value = systemInfoManager.getLiveSystemStats()
    }

    fun runSuperchargerOptimization() {
        viewModelScope.launch {
            _isOptimizing.value = true
            _avatarSpeech.value = "⚡ Initializing DR. I30LTZ Supercharger! Flushed RAM caches and re-indexed ZRAM swap..."
            val scanResult = repository.runFullSystemOptimization()
            _systemStats.value = systemInfoManager.getLiveSystemStats()
            _avatarSpeech.value = "⚡ ${scanResult.summaryMessage}"
            _isOptimizing.value = false
        }
    }

    fun runAntivirusScan() {
        viewModelScope.launch {
            _isOptimizing.value = true
            _avatarSpeech.value = "⚡ Reading device configuration; no malware scan is performed."
            val scanResult = repository.runAntivirusScan()
            _systemStats.value = systemInfoManager.getLiveSystemStats()
            _avatarSpeech.value = "⚡ ${scanResult.summaryMessage}"
            _isOptimizing.value = false
        }
    }

    fun addTask(title: String, description: String, category: String, priority: String) {
        viewModelScope.launch {
            repository.addTask(
                TaskEntity(
                    title = title,
                    description = description,
                    category = category,
                    priority = priority,
                    dueDate = System.currentTimeMillis() + 86400000
                )
            )
        }
    }

    fun toggleTaskCompletion(taskId: Long, currentCompleted: Boolean) {
        viewModelScope.launch {
            repository.updateTaskCompletion(taskId, !currentCompleted)
        }
    }

    fun deleteTask(taskId: Long) {
        viewModelScope.launch {
            repository.deleteTask(taskId)
        }
    }

    fun completeAllTasks() {
        viewModelScope.launch {
            repository.completeAllTasks()
            _avatarSpeech.value = "⚡ All pending system & PC sync tasks marked complete!"
        }
    }

    fun syncAllWithPc() {
        viewModelScope.launch {
            try {
                val count = repository.syncAllTasksWithPc()
                _avatarSpeech.value = "$count completed tasks acknowledged by shared memory."
            } catch (e: Exception) {
                _avatarSpeech.value = "Task sync failed: ${e.message}"
            }
        }
    }

    fun sendAiUserMessage(message: String) {
        if (message.isBlank()) return
        viewModelScope.launch {
            val response = repository.sendMessageToAi(message)
            _avatarSpeech.value = response
        }
    }

    fun speakAvatarGreeting() {
        viewModelScope.launch {
            val greeting = "⚡ DR. I30LTZ⚡ reporting in! Core memory is optimal at ${_systemStats.value.ramPercentage}% load. Ready for your commands!"
            _avatarSpeech.value = greeting
            repository.speakAloud(greeting)
        }
    }

    fun toggleMuteVoice() {
        repository.toggleVoiceMute()
    }

    // --- OS Deployment / Boot Tools Methods ---

    fun toggleOfflineAi() {
        _isOfflineAi.value = !_isOfflineAi.value
    }

    fun downloadIso(isoName: String) {
        _avatarSpeech.value = "SWARM ISO releases are managed on the paired computer at /mnt/ventoy/ISO/SWARM_OS/. No phone download started."
    }

    fun toggleUsbMount() {
        _isUsbMounted.value = false
        _avatarSpeech.value = "USB boot emulation needs a supported rooted kernel and is not provided by this app."
    }

    fun askDrI3oltzGrub(issue: String) {
        if (issue.isBlank()) return
        viewModelScope.launch {
            _avatarSpeech.value = "⚡ Analyzing GRUB boot issue..."
            val prompt = "I am in GRUB. My issue is: $issue. What exact command should I type? Just give the command in a code block or clearly."
            val response = repository.sendMessageToAi(prompt, forceOffline = _isOfflineAi.value)
            
            // Extract code block if any
            val codeRegex = Regex("```(.*?)```", RegexOption.DOT_MATCHES_ALL)
            val match = codeRegex.find(response)
            if (match != null) {
                _grubCommandProposal.value = match.groupValues[1].trim().removePrefix("bash").trim()
            } else {
                // simple fallback if no code block
                if (response.contains("`")) {
                    val fallbackRegex = Regex("`(.*?)`")
                    val fallbackMatch = fallbackRegex.find(response)
                    _grubCommandProposal.value = fallbackMatch?.groupValues?.get(1).orEmpty()
                } else {
                    _grubCommandProposal.value = ""
                }
            }
            _avatarSpeech.value = response
        }
    }

    fun sendHidCommand(command: String) {
        val clipboard = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Recovery command", command))
        _avatarSpeech.value = "Command copied. Nothing has been executed."
    }

    fun sendHidEnter() {
        _avatarSpeech.value = "USB keyboard injection is not supported by this app. Use the connected computer's terminal."
    }

    init {
        heartbeat.start(viewModelScope)
        nodeRegistry.importLegacyConnectionOnce(swarmConnection)
        nodeRegistry.seedKnownMachinesOnce()
        viewModelScope.launch {
            repository.resetDeviceTelemetryOnLaunch()
            // Keep a device row and (for agent / bridge nodes) a live stream per node.
            nodeRegistry.nodes.collect { list ->
                repository.syncDevicesFromNodes(list)
                reconcileStreams(list)
            }
        }
        viewModelScope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                // Poll nodes that have no live stream (LucyServer, or streams that are down).
                val streaming = linkStates.value.filterValues { it == LinkState.Live }.keys
                repository.refreshPolledNodes(nodeRegistry.nodes.value, streaming)
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    override fun onCleared() {
        heartbeat.stop()
        ttsVoiceManager.shutdown()
        super.onCleared()
    }
}
