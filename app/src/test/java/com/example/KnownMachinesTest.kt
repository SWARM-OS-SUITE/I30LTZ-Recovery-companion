package com.example

import com.example.data.nodes.KnownMachines
import com.example.data.nodes.Node
import com.example.data.nodes.NodeBackend
import com.example.data.nodes.lucyServerNodeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownMachinesTest {
    @Test fun debianIsKnownByItsTailscaleAddress() {
        val k = KnownMachines.forHost("100.82.1.19")
        assertNotNull(k)
        assertEquals("Debian", k!!.name)
        assertEquals("Debian", k.os)
        assertEquals("Debian (100.82.1.19)", KnownMachines.label("100.82.1.19"))
        assertEquals("Debian (100.82.1.19)", KnownMachines.label(" 100.82.1.19 "))
    }

    @Test fun unknownAddressesStayAsTyped() {
        assertNull(KnownMachines.forHost("100.82.1.17"))
        assertEquals("100.82.1.17", KnownMachines.label("100.82.1.17"))
    }

    @Test fun noKnownMachineCarriesASecret() {
        // CP-05: nothing is configured until the owner pairs it. The seed must have a host but never a token.
        KnownMachines.all.forEach { k ->
            val n = KnownMachines.toNode(k)
            assertTrue(n.host.isNotBlank())
            assertEquals("", n.token)
            assertFalse(n.isConfigured)
            assertFalse(n.wantsLiveStream)
            assertEquals(k.backend.defaultPort, n.port)
        }
    }

    @Test fun seedAddsDebianToAnEmptyList() {
        val seeded = KnownMachines.missingFrom(emptyList())
        assertEquals(1, seeded.size)
        assertEquals("100.82.1.19", seeded[0].host)
        assertEquals(NodeBackend.AGENT, seeded[0].backend)
    }

    @Test fun seedSkipsAMachineTheListAlreadyHas() {
        val mine = Node(name = "dev pc", os = "Debian", backend = NodeBackend.LUCYSERVER, host = "100.82.1.19", port = 5000, token = "t")
        assertTrue(KnownMachines.missingFrom(listOf(mine)).isEmpty())
        assertTrue(KnownMachines.missingFrom(listOf(mine.copy(host = "100.82.1.19 ".trim()))).isEmpty())
    }

    @Test fun nodeNamedOnlyByItsAddressTakesTheKnownName() {
        val byAddress = Node(name = "100.82.1.19", os = "Other", backend = NodeBackend.LUCYSERVER, host = "100.82.1.19", port = 5000)
        assertEquals("Debian", KnownMachines.displayName(byAddress))
        assertEquals("Debian", KnownMachines.displayName(byAddress.copy(name = "")))
        assertEquals("My PC", KnownMachines.displayName(byAddress.copy(name = "My PC")))
        val other = Node(name = "10.0.0.5", os = "Other", backend = NodeBackend.LUCYSERVER, host = "10.0.0.5", port = 5000)
        assertEquals("10.0.0.5", KnownMachines.displayName(other))
    }

    // --- saving a Remote PC connection over a seeded, unpaired Debian node ---------------------------------

    @Test fun savedConnectionConvertsTheUnpairedSeedToLucyServerWithItsToken() {
        val seed = KnownMachines.toNode(KnownMachines.DEBIAN)
        val saved = lucyServerNodeFor(seed, "100.82.1.19", 5000, "tok")
        assertEquals(seed.id, saved.id)
        assertEquals("Debian", saved.name)
        assertEquals(NodeBackend.LUCYSERVER, saved.backend)
        assertEquals(5000, saved.port)
        assertEquals("tok", saved.token)
        assertTrue(saved.isConfigured)
    }

    @Test fun savedConnectionLeavesAConfiguredAgentNodeAlone() {
        val agent = Node(name = "Debian", os = "Debian", backend = NodeBackend.AGENT, host = "100.82.1.19", port = 7437, token = "agent-token")
        assertSame(agent, lucyServerNodeFor(agent, "100.82.1.19", 5000, "other"))
    }

    @Test fun savedConnectionUpdatesAnExistingLucyServerNode() {
        val ls = Node(name = "Debian", os = "Debian", backend = NodeBackend.LUCYSERVER, host = "100.82.1.19", port = 5000, token = "old")
        val saved = lucyServerNodeFor(ls, "100.82.1.19", 5001, "new")
        assertEquals(ls.id, saved.id)
        assertEquals(5001, saved.port)
        assertEquals("new", saved.token)
    }

    @Test fun savedConnectionCreatesANodeWhenThereIsNone() {
        val n = lucyServerNodeFor(null, "100.82.1.19", 5000, "tok")
        assertEquals("100.82.1.19", n.name)
        assertEquals(NodeBackend.LUCYSERVER, n.backend)
        assertEquals("tok", n.token)
    }
}
