package com.eightball.pro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val infoText = findViewById<TextView>(R.id.info_text)
        val closeBtn = findViewById<Button>(R.id.close_btn)
        val whatsappBtn = findViewById<Button>(R.id.whatsapp_btn)

        infoText.text = "تم عمل البرنامج بواسطة\nمحمد هشام"

        closeBtn.setOnClickListener {
            finish()
        }

        whatsappBtn.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("https://wa.me/2015551336208")
            startActivity(intent)
        }
    }
}
