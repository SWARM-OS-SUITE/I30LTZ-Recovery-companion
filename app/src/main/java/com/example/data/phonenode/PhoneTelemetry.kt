package com.example.data.phonenode

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings

/** One reading of this phone. Unmeasurable values are -1 / null, never invented. */
data class PhoneSample(
    val hostname: String,
    val os: String,
    val ramTotalMb: Long,
    val ramUsedMb: Long,
    val storageTotalGb: Double,
    val storageFreeGb: Double,
    val uptimeSeconds: Long,
    val batteryPercent: Int,
    val charging: Boolean,
    val tempC: Double?,
)

fun interface PhoneTelemetry {
    fun sample(): PhoneSample
}

/** What an Android app is actually allowed to read about its own phone. */
class AndroidPhoneTelemetry(private val context: Context) : PhoneTelemetry {
    override fun sample(): PhoneSample {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val stat = StatFs(Environment.getDataDirectory().path)
        val gb = 1024.0 * 1024 * 1024
        val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val name = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        return PhoneSample(
            hostname = name?.takeIf { it.isNotBlank() } ?: Build.MODEL,
            os = "Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})",
            ramTotalMb = mem.totalMem / (1024 * 1024),
            ramUsedMb = (mem.totalMem - mem.availMem) / (1024 * 1024),
            storageTotalGb = stat.totalBytes / gb,
            storageFreeGb = stat.availableBytes / gb,
            uptimeSeconds = SystemClock.elapsedRealtime() / 1000,
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            tempC = if (tempTenths != Int.MIN_VALUE) tempTenths / 10.0 else null,
        )
    }
}

/** Addresses this phone should listen on: the Tailscale (CGNAT 100.64.0.0/10) IPv4 addresses it currently has. */
object LocalAddresses {
    fun isTailnet(ip: String): Boolean {
        val p = ip.split(".")
        if (p.size != 4) return false
        val a = p[0].toIntOrNull() ?: return false
        val b = p[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127
    }

    fun tailnet(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter(::isTailnet)
            .distinct()
    }.getOrDefault(emptyList())
}
