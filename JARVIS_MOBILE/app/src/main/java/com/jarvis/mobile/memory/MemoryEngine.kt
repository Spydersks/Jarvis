package com.jarvis.mobile.memory

import android.content.Context
import android.util.Log
import com.jarvis.mobile.agent.CommandResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object MemoryEngine {
    private const val TAG = "MemoryEngine"
    private lateinit var dir: File

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "jarvis_memory").also { it.mkdirs() }
        Log.i(TAG, "Memory ready: ${dir.absolutePath}")
    }

    private fun f(name: String) = File(dir, name)

    fun getConfig(ctx: Context): JSONObject = try {
        val file = f("config.json")
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) { JSONObject() }

    fun saveConfig(ctx: Context, config: JSONObject) {
        try { f("config.json").writeText(config.toString(2)) }
        catch (e: Exception) { Log.e(TAG, "saveConfig: ${e.message}") }
    }

    fun getContext(): String = try {
        val file = f("context.json")
        if (file.exists()) file.readText().take(800) else ""
    } catch (e: Exception) { "" }

    fun saveNote(ctx: Context, text: String, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val file  = f("notes.json")
            val notes = if (file.exists()) JSONArray(file.readText()) else JSONArray()
            notes.put(JSONObject().apply { put("text", text); put("time", System.currentTimeMillis()) })
            file.writeText(notes.toString())
            onSpeak("Note saved.")
            CommandResult.success("saved")
        } catch (e: Exception) { CommandResult.failure(e.message ?: "error") }
    }

    fun getNotes(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val file = f("notes.json")
            if (!file.exists()) { onSpeak("No notes yet."); return CommandResult.success("none") }
            val arr  = JSONArray(file.readText())
            val text = (0 until minOf(3, arr.length())).joinToString(". ") {
                arr.getJSONObject(it).getString("text")
            }
            onSpeak("Notes: $text")
            CommandResult.success(text)
        } catch (e: Exception) { CommandResult.failure(e.message ?: "error") }
    }

    fun saveLearnedCommand(command: String, code: String) {
        try {
            val file = f("learned.json")
            val data = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            data.put(command.lowercase().trim(), code)
            file.writeText(data.toString())
        } catch (e: Exception) { Log.e(TAG, "saveLearnedCommand: ${e.message}") }
    }

    fun findLearnedCommand(command: String): String? = try {
        val file = f("learned.json")
        if (!file.exists()) null
        else JSONObject(file.readText()).optString(command.lowercase().trim()).ifBlank { null }
    } catch (e: Exception) { null }

    fun logFailure(command: String, error: String) {
        try {
            val file  = f("failures.json")
            val data  = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            val key   = command.lowercase().take(40).replace(" ", "_")
            val entry = data.optJSONObject(key) ?: JSONObject()
            entry.put("count",   entry.optInt("count") + 1)
            entry.put("error",   error.take(200))
            entry.put("command", command)
            data.put(key, entry)
            file.writeText(data.toString())
        } catch (e: Exception) { Log.e(TAG, "logFailure: ${e.message}") }
    }
}
