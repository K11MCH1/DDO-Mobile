package com.ddomobile.launcher.core

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Android 12+ kills an app's child processes past 32 ("phantom processes"); Proton alone starts more. Android 14+
 * offers "Disable child process restrictions" in Developer options, which sets this flag to false.
 */
object ChildProcessLimit {
    private const val SETTING = "settings_enable_monitor_phantom_procs"

    /** True when the game's processes may be killed. */
    fun blocks(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val global = runCatching { Settings.Global.getString(context.contentResolver, SETTING) }.getOrNull()
        val value = global?.trim()?.lowercase()?.ifEmpty { null } ?: property("persist.sys.fflag.override.$SETTING")?.trim()?.lowercase()
        return value != "false" && value != "0"
    }

    const val INSTRUCTIONS = "Turn on \"Disable child process restrictions\" in Developer options, or run over adb:\n" +
        "adb shell settings put global settings_enable_monitor_phantom_procs false"

    private fun property(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull()?.ifEmpty { null }
}
