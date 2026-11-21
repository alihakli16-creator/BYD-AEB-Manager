package com.byd.afskapat

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Geriye dönük uyumluluk köprüsü.
 * Ana kullanıcı arayüzü com.byd.aebtoggle.MainActivity sınıfı üzerinden çalışır.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val intent = Intent(this, Class.forName("com.byd.aebtoggle.MainActivity"))
            startActivity(intent)
        } catch (_: Exception) {}
        finish()
    }
}
