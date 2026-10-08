package com.example.data.repository

import com.example.data.config.SwarmConnection
import com.example.data.db.*
import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.NodeClient
import com.example.data.nodes.NodeRegistry
import com.example.data.service.GeminiAiService
import com.example.data.service.SystemInfoManager
import com.example.data.service.SystemStats
import com.example.data.service.TtsVoiceManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class AppRepository(
    private val database: AppDatabase,
    private val systemInfoManager: SystemInfoManager,
    private val ttsVoiceManager: TtsVoiceManager,
    private val geminiAiService: GeminiAiService,
    private val swarmConnection: SwarmConnection,
    private val nodeRegistry: NodeRegistry
) {
    // Tasks
    val allTasks: Flow<List<TaskEntity>> = database.taskDao().getAllTasks()

    suspend fun addTask(task: TaskEntity): Long {
        val id = database.taskDao().insertTask(task)
        if (task.priority == "HIGH") {
            ttsVoiceManager.speak("High priority task registered: ${task.title}")
        }
        return id
    }

    suspend fun updateTaskCompletion(taskId: Long, isCompleted: Boolean) {
        database.taskDao().updateTaskCompletion(taskId, isCompleted)
    }

    suspend fun completeAllTasks() {
        database.taskDao().markAllTasksCompleted()
        ttsVoiceManager.speak("All tasks marked as completed and ready for PC synchronization.")
    }

    suspend fun deleteTask(taskId: Long) {
        database.taskDao().deleteTaskById(taskId)
    }

    // Device Sync & Tailscale Fleet
    val allDevices: Flow<List<DeviceSyncEntity>> = database.deviceSyncDao().getAllDevices()

    /// Rows are a cache of what each node last reported, so nothing measured in a
    /// previous session may be shown as current. Real numbers arrive when a node
    /// answers (live stream or poll). Devices come from the node list, not from
    /// built-in addresses.
    suspend fun resetDeviceTelemetryOnLaunch() {
        val dao = database.deviceSyncDao()
        for (device in dao.getAllDevices().first()) {
            dao.updateDevice(device.copy(isConnected = false, cpuLoadPercent = -1, ramLoadPercent = -1,
                storageAvailableGb = -1, syncStatus = "Awaiting fresh measurement"))
        }
    }

    /// Makes sure every node has a device row (matched by address) with its current name.
    suspend fun syncDevicesFromNodes(nodes: List<Node>) {
        val dao = database.deviceSyncDao()
        val rows = dao.getAllDevices().first()
        for (node in nodes) {
            val row = rows.firstOrNull { it.ipAddress.equals(node.host, ignoreCase = true) }
            if (row == null) {
                dao.insertDevice(DeviceSyncEntity(deviceName = node.name, deviceType = node.os.ifBlank { "PC" },
                    connectionType = node.backend.label, ipAddress = node.host, isConnected = false,
                    lastSyncTime = 0, syncStatus = "Not tested"))
            } else if (row.deviceName != node.name || row.connectionType != node.backend.label) {
                dao.updateDevice(row.copy(deviceName = node.name, connectionType = node.backend.label))
            }
        }
    }

    suspend fun removeDeviceForNode(node: Node) = database.deviceSyncDao().deleteByIp(node.host)

    /// Phone nodes report their battery; show it in the status line (no database column needed).
    private fun batteryNote(stats: RemoteTelemetry) = if (stats.battery >= 0) " · battery ${stats.battery}%" else ""

    /// Applies one live (SSE) update to the node's row. Storage is kept when the update does not carry it.
    internal suspend fun applyLiveTelemetry(node: Node, stats: RemoteTelemetry) {
        val dao = database.deviceSyncDao()
        val row = dao.getAllDevices().first().firstOrNull { it.ipAddress.equals(node.host, ignoreCase = true) } ?: return
        dao.updateDevice(row.copy(isConnected = true, lastSyncTime = System.currentTimeMillis(),
            syncStatus = "Live (SSE)" + batteryNote(stats), cpuLoadPercent = stats.cpu, ramLoadPercent = stats.ram,
            storageAvailableGb = if (stats.storage >= 0) stats.storage else row.storageAvailableGb))
    }

    /// Polls every configured node that is not already streaming live.
    suspend fun refreshPolledNodes(nodes: List<Node>, streamingNodeIds: Set<String>) {
        val rows = database.deviceSyncDao().getAllDevices().first()
        for (node in nodes) {
            if (!node.isConfigured || node.id in streamingNodeIds) continue
            rows.firstOrNull { it.ipAddress.equals(node.host, ignoreCase = true) }?.let { refreshDeviceTelemetry(it) }
        }
    }

    /// Polls a device's real stats from LucyServer's /api/system/stats and
    /// writes them to the row. Returns true if the device answered.
    suspend fun refreshDeviceTelemetry(device: DeviceSyncEntity): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val node = nodeRegistry.findByHost(device.ipAddress)
                val started = System.nanoTime()
                val stats = if (node != null) {
                    check(node.isConfigured) { "${node.name} has no token yet. Pair it under Nodes." }
                    NodeClient.telemetry(node)
                } else {
                    // A device added before the node list existed: use the single saved connection.
                    check(swarmConnection.isUsable) { "No node is set up for ${device.ipAddress}. Add it under Nodes." }
                    val request = okhttp3.Request.Builder()
                        .url("http://${device.ipAddress}:${swarmConnection.port}/api/system/stats")
                        .header("X-Swarm-Auth", swarmConnection.authToken).build()
                    telemetryClient.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "HTTP ${response.code}" }
                        RemoteTelemetry.parse(org.json.JSONObject(response.body?.string().orEmpty()))
                    }
                }
                database.deviceSyncDao().updateDevice(device.copy(
                    isConnected = true, lastSyncTime = System.currentTimeMillis(),
                    syncStatus = "Telemetry received" + batteryNote(stats), cpuLoadPercent = stats.cpu,
                    ramLoadPercent = stats.ram, storageAvailableGb = stats.storage,
                    pingMs = ((System.nanoTime() - started) / 1_000_000).toInt()))
                true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                database.deviceSyncDao().updateDevice(device.copy(isConnected = false,
                    cpuLoadPercent = -1, ramLoadPercent = -1, storageAvailableGb = -1,
                    syncStatus = "Unavailable (${e.message})"))
                false
            }
        }

    private val telemetryClient = okhttp3.OkHttpClient.Builder()
        .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build()

    suspend fun addOrUpdateDevice(device: DeviceSyncEntity) {
        database.deviceSyncDao().insertDevice(device)
    }

    /// Refreshes a remote device's telemetry and records what actually came
    /// back. Returns null when the device could not be reached.
    ///
    /// This used to insert a fixed scan claiming 340MB RAM freed, 820MB junk
    /// cleaned, a score of 99, and that it had "Optimized ZRAM, flushed caches,
    /// and verified firewall rules" — none of which it did, for a device it
    /// never contacted.
    suspend fun optimizeSingleDevice(deviceId: Long): SystemScanEntity? {
        val device = database.deviceSyncDao().getDeviceById(deviceId) ?: return null

        val reached = refreshDeviceTelemetry(device)
        if (!reached) {
            ttsVoiceManager.speak("Could not reach ${device.deviceName}.")
            return null
        }

        val refreshed = database.deviceSyncDao().getDeviceById(deviceId) ?: device

        val scan = SystemScanEntity(
            scanType = "Device Telemetry Refresh",
            score = (100 - refreshed.cpuLoadPercent).coerceIn(0, 100),
            threatsFoundCount = 0,
            ramFreedMb = 0,
            junkCleanedMb = 0,
            summaryMessage = "${refreshed.deviceName}: CPU ${refreshed.cpuLoadPercent}%, " +
                "RAM ${refreshed.ramLoadPercent}%, " +
                (if (refreshed.storageAvailableGb < 0) "storage unavailable, " else "${refreshed.storageAvailableGb}GB free, ") +
                "${refreshed.pingMs}ms round trip."
        )
        database.systemScanDao().insertScan(scan)
        ttsVoiceManager.speak("Telemetry refreshed for ${refreshed.deviceName}.")
        return scan
    }

    suspend fun syncAllTasksWithPc(): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val tasks = database.taskDao().getAllTasks().first().filter { it.isCompleted && !it.syncedWithPc }
        val engine = com.example.data.memory.MemorySyncEngine
        for (task in tasks) {
            val payload = org.json.JSONObject().put("device", engine.deviceId).put("kind", "task")
                .put("key", "${engine.deviceId}:task:${task.id}")
                .put("content", org.json.JSONObject().put("title", task.title).put("description", task.description)
                    .put("completed", true).put("completedTaskId", task.id).toString())
            val request = okhttp3.Request.Builder().url("${swarmConnection.httpBase()}/api/memory/entries")
                .header("X-Swarm-Auth", swarmConnection.authToken)
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            telemetryClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Shared-memory task sync returned HTTP ${response.code}; unsent tasks remain pending" }
                val entry = com.example.data.memory.MemoryEntryEntity.fromJson(org.json.JSONObject(response.body?.string().orEmpty()))
                check(entry != null && entry.key == payload.getString("key") && entry.content == payload.getString("content")) { "Server did not acknowledge this task" }
                database.memoryDao().insertAll(listOf(entry))
                database.taskDao().updateTask(task.copy(syncedWithPc = true))
            }
        }
        engine.requestSync()
        tasks.size
    }

    // System Scans
    val recentScans: Flow<List<SystemScanEntity>> = database.systemScanDao().getRecentScans()

    suspend fun runFullSystemOptimization(): SystemScanEntity {
        val (freedRam, cleanedJunk) = systemInfoManager.performSuperchargeOptimization()
        val stats = systemInfoManager.getLiveSystemStats()

        val scan = SystemScanEntity(
            scanType = "DR. I30LTZ Supercharger Optimization",
            // Derived from measured free memory rather than a fixed 98, and the
            // summary no longer claims a "ZRAM swap re-index" that never ran.
            score = (100 - stats.ramPercentage).coerceIn(0, 100),
            threatsFoundCount = 0,
            ramFreedMb = freedRam,
            junkCleanedMb = cleanedJunk,
            summaryMessage = "Freed ${freedRam}MB RAM by stopping background apps, " +
                "reclaimed ${cleanedJunk}MB from this app's caches. RAM now at ${stats.ramPercentage}% used."
        )

        database.systemScanDao().insertScan(scan)
        ttsVoiceManager.speak("Optimization complete! Freed $freedRam megabytes of RAM and $cleanedJunk megabytes of cache.")
        return scan
    }

    suspend fun runAntivirusScan(): SystemScanEntity {
        val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { systemInfoManager.securityAudit() }.getOrElse { "Device audit failed: ${it.message}" }
        }
        val scan = SystemScanEntity(scanType = "Device configuration audit (not malware scan)", score = -1,
            threatsFoundCount = 0, ramFreedMb = 0, junkCleanedMb = 0, summaryMessage = report)
        database.systemScanDao().insertScan(scan)
        ttsVoiceManager.speak("Device configuration audit finished. Review the findings on screen. Malware status has not been assessed.")
        return scan
    }

    // AI Chat
    val chatMessages: Flow<List<AIChatEntity>> = database.aiChatDao().getAllMessages()

    suspend fun sendMessageToAi(userText: String, forceOffline: Boolean = false): String {
        // Save user message
        database.aiChatDao().insertMessage(
            AIChatEntity(sender = "USER", message = userText, category = "User Query")
        )

        // Get system stats for context
        val stats = systemInfoManager.getLiveSystemStats()
        val context = "RAM: ${stats.usedRamMb}/${stats.totalRamMb}MB (${stats.ramPercentage}%), Battery: ${stats.batteryLevel}% (${stats.batteryTemperature}°C), Process Count: ${stats.runningProcessCount}"

        // Get AI response
        val aiResponse = geminiAiService.getAiResponse(userText, context, forceOffline)

        // Save AI response
        database.aiChatDao().insertMessage(
            AIChatEntity(sender = "DR_i3OLTZ", message = aiResponse, category = "AI Insight")
        )

        // Speak aloud
        ttsVoiceManager.speak(aiResponse)

        return aiResponse
    }

    suspend fun clearChat() {
        database.aiChatDao().clearHistory()
    }

    fun getLiveSystemStats(): SystemStats = systemInfoManager.getLiveSystemStats()

    fun speakAloud(text: String) = ttsVoiceManager.speak(text)
    fun toggleVoiceMute() = ttsVoiceManager.toggleMute()
    val isVoiceMuted = ttsVoiceManager.isMuted
    val isVoiceSpeaking = ttsVoiceManager.isSpeaking
}
