package com.hank.ascentswitch

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("unused")
object AppLogger {
    private const val TAG = "AscentSwitchLog"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, "debug.log")
    }

    @Suppress("unused")
    fun d(tag: String, message: String) {
        val formatted = "[${dateFormat.format(Date())}] [DEBUG] [$tag] $message"
        Log.d(tag, message)
        writeToLogFile(formatted)
    }

    @Suppress("unused")
    fun w(tag: String, message: String) {
        val formatted = "[${dateFormat.format(Date())}] [WARN] [$tag] $message"
        Log.w(tag, message)
        writeToLogFile(formatted)
    }

    @Suppress("unused")
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val msgWithThrowable = if (throwable != null) "$message\n${Log.getStackTraceString(throwable)}" else message
        val formatted = "[${dateFormat.format(Date())}] [ERROR] [$tag] $msgWithThrowable"
        Log.e(tag, message, throwable)
        writeToLogFile(formatted)
    }

    private fun writeToLogFile(line: String) {
        val file = logFile ?: return
        try {
            val parent = file.parentFile
            if ((parent != null) && (!parent.exists())) {
                parent.mkdirs()
            }
            FileWriter(file, true).use { writer ->
                writer.appendLine(line)
            }
        } catch (_: Exception) {
        }
    }
}
