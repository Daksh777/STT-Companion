package com.daksh.sttcompanion

import android.content.Context

class AuthManager(context: Context) {
    private val prefs = context.getSharedPreferences("stt_companion_prefs", Context.MODE_PRIVATE)

    val token: String
        get() {
            var currentToken = prefs.getString("auth_token", null)
            // Migrate old longer tokens to a simple pin.
            if (currentToken == null || currentToken.length != PIN_LENGTH) {
                currentToken = (1000..9999).random().toString()
                prefs.edit().putString("auth_token", currentToken).apply()
            }
            return currentToken
        }

    private companion object {
        const val PIN_LENGTH = 4
    }
}
