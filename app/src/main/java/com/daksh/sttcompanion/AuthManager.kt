package com.daksh.sttcompanion

import android.content.Context
import java.util.UUID

class AuthManager(context: Context) {
    private val prefs = context.getSharedPreferences("stt_companion_prefs", Context.MODE_PRIVATE)
    
    val token: String
        get() {
            var currentToken = prefs.getString("auth_token", null)
            if (currentToken == null) {
                currentToken = UUID.randomUUID().toString().substring(0, 8)
                prefs.edit().putString("auth_token", currentToken).apply()
            }
            return currentToken
        }
}
