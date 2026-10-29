package org.stellartimelock.stellarsend

import android.content.Context
import org.json.JSONArray
import org.stellartimelock.send.core.TerminalPolicy

object SavedCommands {
    private const val PREF = "flipsend.terminal"
    private const val KEY = "commands"
    private const val MAX = 30

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() && it.length <= TerminalPolicy.MAX_COMMAND_CHARS }
        }.distinct()
    }

    fun save(context: Context, commands: List<String>) {
        val array = JSONArray()
        commands.take(MAX).forEach { array.put(it) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
