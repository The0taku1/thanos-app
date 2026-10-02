package com.example.thanos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private lateinit var texte: TextView
    private lateinit var bouton: Button
    private val handler = Handler(Looper.getMainLooper())
    private var actif = false
    private var attendCommande = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        texte = TextView(this).apply {
            text = "Appuie sur le bouton pour activer Thanos"
            textSize = 20f
            gravity = Gravity.CENTER
        }
        bouton = Button(this).apply {
            text = "🎤 Activer Thanos"
            setOnClickListener { if (actif) arreter() else demarrer() }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(texte)
            addView(bouton)
        }
        setContentView(layout)

        tts = TextToSpeech(this, this)

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val liste = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val phrase = liste?.firstOrNull()
                    if (phrase == null) {
                        relancer(300)
                    } else {
                        traiter(phrase)
                    }
                }
                override fun onError(error: Int) {
                    if (attendCommande) {
                        attendCommande = false
                        if (actif) texte.text = "Je repasse en veille. Dis « Thanos »"
                    }
                    relancer(if (error == 8) 1000 else 400)
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } else {
            texte.text = "Reconnaissance vocale indisponible sur ce téléphone"
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.FRANCE
        }
    }

    private fun demarrer() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.CALL_PHONE
                ), 1)
            return
        }
        actif = true
        attendCommande = false
        bouton.text = "⏹ Arrêter Thanos"
        texte.text = "Je t'écoute. Dis « Thanos »"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        relancer(0)
    }

    private fun arreter() {
        actif = false
        attendCommande = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        bouton.text = "🎤 Activer Thanos"
        texte.text = "Thanos est en pause"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun relancer(delai: Long) {
        handler.postDelayed({
            if (!actif) return@postDelayed
            if (tts.isSpeaking) {
                relancer(500)
                return@postDelayed
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            recognizer?.startListening(intent)
        }, delai)
    }

    private fun traiter(phrase: String) {
        if (attendCommande) {
            attendCommande = false
            texte.text = "Tu as dit : $phrase"
            executer(phrase)
            relancer(1500)
            return
        }
        val p = phrase.lowercase(Locale.FRANCE).trim()
        var reste: String? = null
        for (m in listOf("thanos", "tanos", "tannos")) {
            val i = p.indexOf(m)
            if (i >= 0) {
                reste = p.substring(i + m.length).trim(' ', ',', '.')
                break
            }
        }
        if (reste == null) {
            relancer(300)
        } else if (reste.isEmpty()) {
            attendCommande = true
            dire("Oui ?")
            relancer(1200)
        } else {
            executer(reste)
            relancer(1500)
        }
    }

    private fun dire(m: String) {
        texte.text = m
        tts.speak(m, TextToSpeech.QUEUE_FLUSH, null, "thanos")
    }

    private fun executer(phrase: String) {
        val p = phrase.lowercase(Locale.FRANCE).trim()
        when {
            p.contains("agenda") || p.contains("calendrier") -> {
                dire("J'ouvre l'agenda")
                val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
            p.startsWith("appelle ") -> {
                val nom = p.removePrefix("appelle ").trim()
                val numero = chercherNumero(nom)
                if (numero != null) {
                    dire("J'appelle $nom")
                    val action =
                        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
                            Intent.ACTION_CALL else Intent.ACTION_DIAL
                    startActivity(Intent(action, Uri.parse("tel:" + Uri.encode(numero))))
                } else {
                    dire("Je ne trouve pas $nom dans tes contacts")
                }
            }
            p.startsWith("ouvre ") -> {
                val nom = p.removePrefix("ouvre ").trim()
                val pm = packageManager
                val apps = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                val trouve = apps.firstOrNull {
                    it.loadLabel(pm).toString().lowercase().contains(nom)
                }
                val lancer = trouve?.let {
                    pm.getLaunchIntentForPackage(it.activityInfo.packageName)
                }
                if (lancer != null) {
                    dire("J'ouvre $nom")
                    startActivity(lancer)
                } else {
                    dire("Je ne trouve pas l'application $nom")
                }
            }
            else -> dire(phrase)
        }
    }

    private fun chercherNumero(nom: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val curseur = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%" + nom + "%"),
            null
        )
        var resultat: String? = null
        if (curseur != null) {
            if (curseur.moveToFirst()) {
                resultat = curseur.getString(0)
            }
            curseur.close()
        }
        return resultat
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}
