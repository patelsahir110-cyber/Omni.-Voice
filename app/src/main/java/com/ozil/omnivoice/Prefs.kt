package com.ozil.omnivoice

import android.content.Context

object Prefs {
    val LANGS = listOf(
        "en-IN" to "English (India)",
        "hi-IN" to "हिन्दी",
        "mr-IN" to "मराठी",
        "en-US" to "English (US)",
        "en-GB" to "English (UK)",
        "es-ES" to "Español",
        "fr-FR" to "Français",
        "de-DE" to "Deutsch"
    )

    // 0 = Hybrid (default), 1 = Local offline preferred, 2 = Internet
    val ENGINES = listOf("Hybrid", "Offline", "Internet")

    private fun p(c: Context) = c.getSharedPreferences("omnivoice", Context.MODE_PRIVATE)

    fun dark(c: Context) = p(c).getBoolean("dark", false)
    fun setDark(c: Context, v: Boolean) = p(c).edit().putBoolean("dark", v).apply()

    fun lang(c: Context) = p(c).getInt("lang", 0).coerceIn(0, LANGS.lastIndex)
    fun setLang(c: Context, v: Int) = p(c).edit().putInt("lang", v).apply()

    fun engine(c: Context) = p(c).getInt("engine", 0).coerceIn(0, ENGINES.lastIndex)
    fun setEngine(c: Context, v: Int) = p(c).edit().putInt("engine", v).apply()

    fun punctuation(c: Context) = p(c).getBoolean("punct", true)
}
