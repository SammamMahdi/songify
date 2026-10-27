package com.songify.app

import android.content.Context
import android.os.Build
import java.io.File

/** Saves the last crash so the user can share it on the next launch. */
object CrashReport {
    private lateinit var file: File

    fun install(context: Context) {
        file = File(context.filesDir, "last_crash.txt")
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText("Songify $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n\n${error.stackTraceToString()}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report from the last crash, if any; returned once. */
    fun take(): String? = file.takeIf { it.exists() }?.let { f -> f.readText().also { f.delete() } }
}
