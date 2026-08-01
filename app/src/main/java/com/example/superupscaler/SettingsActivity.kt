package com.example.superupscaler

import android.content.Context
import android.os.Bundle
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

class SettingsActivity : AppCompatActivity() {

    private lateinit var radioTheme: RadioGroup
    private lateinit var txtOutputFolder: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        radioTheme = findViewById(R.id.radioTheme)
        txtOutputFolder = findViewById(R.id.txtOutputFolder)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        when (prefs.getString("theme", "system")) {
            "light" -> radioTheme.check(R.id.themeLight)
            "dark" -> radioTheme.check(R.id.themeDark)
            else -> radioTheme.check(R.id.themeSystem)
        }

        radioTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode: Int
            val value: String
            when (checkedId) {
                R.id.themeLight -> {
                    mode = AppCompatDelegate.MODE_NIGHT_NO
                    value = "light"
                }
                R.id.themeDark -> {
                    mode = AppCompatDelegate.MODE_NIGHT_YES
                    value = "dark"
                }
                else -> {
                    mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    value = "system"
                }
            }
            prefs.edit().putString("theme", value).apply()
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
