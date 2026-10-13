package com.nexastream.app.utils

import android.content.Context
import android.widget.Toast

object ReminderManager {
    private const val PREF_NAME = "coming_soon_reminders"

    fun isReminderSet(context: Context, id: String): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(id, false)
    }

    fun toggleReminder(context: Context, id: String, title: String): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val current = prefs.getBoolean(id, false)
        val newState = !current
        prefs.edit().putBoolean(id, newState).apply()

        val msg = if (newState) {
            "🔔 Reminder set for \"$title\"! You'll be notified when released."
        } else {
            "🔕 Reminder removed for \"$title\"."
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        return newState
    }
}
