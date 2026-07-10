package com.hermes.mobile.data

import android.content.Context

class ConnectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("hermes_connection", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(value) = prefs.edit().putString("base_url", value).apply()

    var accessToken: String
        get() = prefs.getString("access_token", "") ?: ""
        set(value) = prefs.edit().putString("access_token", value).apply()

    var refreshToken: String
        get() = prefs.getString("refresh_token", "") ?: ""
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    fun clearTokens() {
        prefs.edit().remove("access_token").remove("refresh_token").apply()
    }
}
