package com.example.thanos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    private lateinit var texte: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        texte = TextView(this).apply {
            text = "Thanos écoute en arrière-plan, même écran éteint, une fois activé."
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val activer = Button(this).apply {
            text = "🎤 Activer Thanos 24h/24"
            setOnClickListener { activer() }
        }
        val arreter = Button(this).apply {
            text = "⏹ Arrêter Thanos"
            setOnClickListener {
                startService(Intent(this@MainActivity, ThanosService::class.java).setAction("STOP"))
                texte.text = "Thanos est arrêté."
            }
        }
        val reglages = Button(this).apply {
            text = "⚙ Réglages de Thanos (batterie)"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:" + packageName))
                )
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(texte)
            addView(activer)
            addView(arreter)
            addView(reglages)
        }
        setContentView(layout)
    }

    private fun activer() {
        val voulues = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) {
            voulues.add("android.permission.POST_NOTIFICATIONS")
        }
        val manquantes = voulues.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (manquantes.isNotEmpty()) {
            requestPermissions(manquantes.toTypedArray(), 1)
            return
        }
        lancerService()
    }

    private fun lancerService() {
        startForegroundService(Intent(this, ThanosService::class.java))
        texte.text = "Thanos est activé. Regarde la notification, puis dis « Thanos »."
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            lancerService()
        } else {
            texte.text = "Il faut autoriser le micro pour que Thanos t'écoute."
        }
    }
}
