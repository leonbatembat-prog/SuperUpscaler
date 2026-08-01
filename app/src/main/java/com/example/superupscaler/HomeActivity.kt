package com.example.superupscaler

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<android.widget.LinearLayout>(R.id.cardUpscale).setOnClickListener {
            startActivity(Intent(this, UpscaleActivity::class.java))
        }

        findViewById<android.widget.LinearLayout>(R.id.cardSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
