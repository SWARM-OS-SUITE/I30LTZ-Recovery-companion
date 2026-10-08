package com.example

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.config.SwarmConnection
import com.example.data.db.AppDatabase
import com.example.data.db.DeviceSyncEntity
import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.NodeRegistry
import com.example.ui.MainViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds the real MainViewModel the way the app does right after login. A property that is read by a
 * background coroutine in `init` before it has been initialised crashes the app at exactly this moment,
 * so this is the test that must exist for every change to that class.
 */
@RunWith(AndroidJUnit4::class)
class MainViewModelTest {
    private val instr get() = InstrumentationRegistry.getInstrumentation()

    @Test fun viewModelStartsItsBackgroundWorkAndFollowsNodesWithoutCrashing() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val registry = NodeRegistry.get(app)
        // A paired agent node that nothing answers: it must get a live-stream state, not crash anything.
        val node = Node(name = "regression-node", os = "Debian", backend = NodeBackend.AGENT, host = "127.0.0.1", port = 1, token = "not-a-real-token")
        registry.upsert(node)
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        try {
            var vm: MainViewModel? = null
            instr.runOnMainSync {
                vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MainViewModel::class.java]
            }
            assertNotNull(vm)
            // Give init's coroutines (device sync, 30-second poll loop, live streams) several turns.
            val deadline = System.currentTimeMillis() + 10000
            while (System.currentTimeMillis() < deadline && vm!!.linkStates.value[node.id] == null) Thread.sleep(100)
            assertTrue("the node should have a live-stream state, got ${vm!!.linkStates.value}", vm!!.linkStates.value[node.id] != null)
            assertTrue(vm!!.nodes.value.any { it.id == node.id })
            assertNotNull(vm!!.security.value)
        } finally {
            instr.runOnMainSync { owner.viewModelStore.clear() }
            registry.remove(node.id)
        }
    }

    /**
     * A phone updated from the old build keeps a saved single connection and old device rows. Start the real
     * ViewModel on that data: the connection must become one node (once), rows must not be duplicated, and
     * telemetry left over from the last session must not be shown as current.
     * The test resets the once-per-install import flag itself, so it does not matter what ran before.
     */
    @Test fun aPhoneUpdatedFromTheOldBuildKeepsItsConnectionAndDevicesWithoutCrashing() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // The import happens once per install; make this test independent of whatever ran before it.
        com.example.data.config.securePreferences(app, "i30ltz_nodes").edit().remove("legacy_connection_imported").commit()
        val conn = SwarmConnection(app)
        conn.host = "100.82.1.19"; conn.port = 5000; conn.authToken = "old-saved-token-for-test"
        val dao = AppDatabase.getInstance(app).deviceSyncDao()
        dao.insertDevice(DeviceSyncEntity(deviceName = "old CachyOS", deviceType = "PC", connectionType = "Tailscale", ipAddress = "100.82.1.19",
            isConnected = true, lastSyncTime = 1, syncStatus = "Telemetry received", cpuLoadPercent = 40, ramLoadPercent = 50))
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val registry = NodeRegistry.get(app)
        try {
            var vm: MainViewModel? = null
            instr.runOnMainSync { vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MainViewModel::class.java] }
            val deadline = System.currentTimeMillis() + 10000
            while (System.currentTimeMillis() < deadline && registry.findByHost("100.82.1.19") == null) Thread.sleep(100)
            val imported = registry.findByHost("100.82.1.19")
            assertNotNull("the saved connection should have become a node", imported)
            assertEquals(NodeBackend.LUCYSERVER, imported!!.backend)
            assertEquals("old-saved-token-for-test", imported.token)
            Thread.sleep(1500)
            val rows = dao.getAllDevices().first().filter { it.ipAddress == "100.82.1.19" }
            assertEquals("no duplicate device row for the imported node", 1, rows.size)
            assertTrue("stale telemetry must be reset, got ${rows[0]}", rows[0].cpuLoadPercent == -1 || rows[0].syncStatus != "Telemetry received")
            assertTrue(vm!!.nodes.value.size == 1)
        } finally {
            instr.runOnMainSync { owner.viewModelStore.clear() }
            registry.nodes.value.forEach { registry.remove(it.id) }
            dao.deleteByIp("100.82.1.19")
            conn.host = ""; conn.authToken = ""
        }
    }
}
