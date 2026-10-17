package com.example.data.storage

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/**
 * Whether the IDE may read and write ordinary folders on shared storage, and how to get that.
 *
 * Android 11+ hides everything outside the app's own folders from `java.io.File` unless the user flips
 * "All files access" for the app; before Android 11 it is the normal storage permission. Without it a
 * folder picked in the IDE "opens" (the path exists) but lists as empty and refuses saves, so the IDE
 * checks this first and sends the user to the right Settings screen instead of failing silently.
 */
object StorageAccess {

    /** True when plain file paths on shared storage are readable and writable by this app. */
    /** Tests set this to fake the permission; production code never touches it. */
    @androidx.annotation.VisibleForTesting
    @Volatile var accessOverrideForTests: Boolean? = null

    fun hasAccess(context: Context): Boolean =
        accessOverrideForTests ?:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** True for the phone's shared storage and for SD cards or USB drives: the places Android filters without the permission. */
    fun isOnSharedStorage(folder: File): Boolean {
        val path = try { folder.canonicalPath } catch (_: java.io.IOException) { folder.absolutePath }
        // Falls back to the usual path where Android's Environment is unavailable (plain JVM tests).
        val shared = try { Environment.getExternalStorageDirectory().canonicalPath } catch (_: Exception) { "/storage/emulated/0" }
        return path == shared || path.startsWith("$shared/") || path.startsWith("/storage/") || path.startsWith("/sdcard")
    }

    /** On Android 10 and older the permission is an ordinary runtime prompt; on 11+ it is a Settings toggle. */
    val needsRuntimePrompt: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    /** Runtime permissions to request on Android 10 and older (empty on 11+, use [openSettings] there). */
    val runtimePermissions: Array<String> = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    /** Opens the Settings screen where the user allows file access for this app. Returns false if none opened. */
    fun openSettings(context: Context): Boolean {
        val pkg = Uri.parse("package:${context.packageName}")
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
                add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // try the next, more generic screen
            }
        }
        return false
    }

    /**
     * Turns a folder the user picked in the system picker into a real path, or null when it cannot be one
     * (cloud providers, Downloads' virtual root, etc.). The picker hands back a tree URI whose document
     * id is "<volume>:<relative path>": `primary` is the phone's own storage, a UUID is an SD card or USB drive.
     */
    fun resolveTreeUri(uri: Uri): File? {
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return TreeUriResolver.resolve(
            docId = docId,
            primaryRoot = Environment.getExternalStorageDirectory(),
            volumeRoot = { uuid -> File("/storage/$uuid").takeIf { it.isDirectory } },
        )
    }
}

/**
 * Pure path logic behind [StorageAccess.resolveTreeUri], kept free of Android types so the JVM tests can
 * cover every shape of document id the system picker produces.
 */
object TreeUriResolver {

    fun resolve(docId: String, primaryRoot: File, volumeRoot: (String) -> File?): File? {
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val relative = docId.substring(colon + 1).trim('/')
        if (relative.split('/').any { it == ".." }) return null
        val base = when (volume) {
            "primary" -> primaryRoot
            // The Documents provider's "Home" is the Documents folder.
            "home" -> File(primaryRoot, "Documents")
            // Anything else must be a real mounted volume (SD card, USB stick); never guess a path for it.
            else -> volumeRoot(volume)
        } ?: return null
        return if (relative.isEmpty()) base else File(base, relative)
    }
}
