package com.example.data.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TreeUriResolverTest {
    private val primary = File("/storage/emulated/0")
    private val sdCard = File("/storage/1A2B-3C4D")
    private val volumes = mapOf("1A2B-3C4D" to sdCard)

    private fun resolve(docId: String) = TreeUriResolver.resolve(docId, primary) { volumes[it] }

    @Test fun primaryStorageFolderResolvesUnderTheSharedRoot() {
        assertEquals(File("/storage/emulated/0/Documents/app"), resolve("primary:Documents/app"))
    }

    @Test fun theRootOfPrimaryStorageIsAllowed() {
        assertEquals(primary, resolve("primary:"))
        assertEquals(primary, resolve("primary:/"))
    }

    @Test fun sdCardFoldersResolveOnTheirOwnVolume() {
        assertEquals(File("/storage/1A2B-3C4D/src/proj"), resolve("1A2B-3C4D:src/proj"))
        assertEquals(sdCard, resolve("1A2B-3C4D:"))
    }

    @Test fun aVolumeThatIsNotMountedIsNotGuessed() {
        // The old code fell back to the phone's own storage for these, opening the wrong folder.
        assertNull(resolve("FFFF-0000:src"))
    }

    @Test fun homeMapsToTheDocumentsFolder() {
        assertEquals(File("/storage/emulated/0/Documents/notes"), resolve("home:notes"))
        assertEquals(File("/storage/emulated/0/Documents"), resolve("home:"))
    }

    @Test fun malformedOrTraversingIdsAreRejected() {
        assertNull(resolve(""))
        assertNull(resolve("no-colon"))
        assertNull(resolve(":x"))
        assertNull(resolve("primary:../../data"))
        assertNull(resolve("primary:a/../../b"))
    }

    @Test fun dotsInsideNamesAreFine() {
        assertEquals(File("/storage/emulated/0/my..proj"), resolve("primary:my..proj"))
    }

    @Test fun sharedStorageAndRemovableDrivesAreRecognisedButAppFoldersAreNot() {
        assertTrue(StorageAccess.isOnSharedStorage(File("/storage/emulated/0/Documents/app")))
        assertTrue(StorageAccess.isOnSharedStorage(File("/storage/1A2B-3C4D/src")))
        assertTrue(StorageAccess.isOnSharedStorage(File("/sdcard/Download")))
        assertFalse(StorageAccess.isOnSharedStorage(File("/data/user/0/com.aistudio.sysopt.app/files/projects/x")))
        assertFalse(StorageAccess.isOnSharedStorage(File("/tmp/project")))
    }
}
