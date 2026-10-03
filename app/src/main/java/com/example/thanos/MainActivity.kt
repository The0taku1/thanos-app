package com.example.thanos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

class ThanosService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    private var model: Model? = null
    private var speech: SpeechService? = null
    private lateinit var tts: TextToSpeech
    private var ttsPret = false
    private var charge = false
    private var attendCommande = false
    private val handler = Handler(Looper.getMainLooper())
    private val historique = mutableListOf<Pair<String, String>>()

    private val personnalite =
        "Tu es Thanos, l'assistant vocal personnel d'une utilisatrice francophone. " +
        "Ton style est celui de Jarvis dans Iron Man : poli, dévoué, calme et légèrement pince-sans-rire. " +
        "Tu tutoies l'utilisatrice. Tes réponses sont lues à voix haute : écris une à trois phrases courtes, " +
        "sans markdown, sans listes, sans émojis. " +
        "Si on te demande une information très récente que tu ne peux pas vérifier, dis-le honnêtement."

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("thanos", "Thanos", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel("thanos_actions", "Actions de Thanos", NotificationManager.IMPORTANCE_HIGH))
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(1, notif("Thanos démarre..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (!charge) {
            charge = true
            Thread { demarrerVosk() }.start()
        }
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.FRANCE
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    handler.post { speech?.setPause(true) }
                }
                override fun onDone(utteranceId: String?) {
                    handler.postDelayed({ speech?.setPause(false) }, 400)
                }
                override fun onError(utteranceId: String?) {
                    handler.postDelayed({ speech?.setPause(false) }, 400)
                }
            })
            ttsPret = true
        }
    }

    // ---------- Notifications ----------

    private fun notif(t: String): Notification {
        val ouvrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ThanosService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "thanos")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Thanos")
            .setContentText(t)
            .setContentIntent(ouvrir)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Arrêter", stop
                ).build()
            )
            .build()
    }

    private fun majNotif(t: String) {
        getSystemService(NotificationManager::class.java).notify(1, notif(t))
    }

    // ---------- Modèle Vosk ----------

    private fun preparerModele(): File? {
        val dossier = File(filesDir, "model-fr")
        if (File(dossier, "am").exists()) return dossier
        try {
            majNotif("Téléchargement du modèle français (une seule fois)...")
            val zip = File(cacheDir, "model.zip")
            val c = URL("https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip")
                .openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.inputStream.use { entree ->
                FileOutputStream(zip).use { sortie -> entree.copyTo(sortie) }
            }
            dossier.deleteRecursively()
            val racine = dossier.canonicalPath
            ZipInputStream(FileInputStream(zip)).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val chemin = e.name.substringAfter("/", "")
                    if (chemin.isNotEmpty()) {
                        val f = File(dossier, chemin)
                        if (f.canonicalPath.startsWith(racine)) {
                            if (e.isDirectory) {
                                f.mkdirs()
                            } else {
                                f.parentFile?.mkdirs()
                                FileOutputStream(f).use { z.copyTo(it) }
                            }
                        }
                    }
                    e = z.nextEntry
                }
            }
            zip.delete()
            return if (File(dossier, "am").exists()) dossier else null
        } catch (e: Exception) {
            return null
        }
    }

    private fun demarrerVosk() {
        val dossier = preparerModele()
        if (dossier == null) {
            majNotif("Modèle indisponible : active internet puis relance Thanos")
            charge = false
            return
        }
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(dossier.absolutePath)
            val rec = Recognizer(model, 16000.0f)
            speech = SpeechService(rec, 16000.0f)
            speech?.startListening(this)
            majNotif("Dis « Thanos »")
            handler.post { dire("Thanos est prêt") }
        } catch (e: Exception) {
            majNotif("Erreur : " + e.message)
            charge = false
        }
    }

    // ---------- Écoute ----------

    override fun onPartialResult(hypothesis: String?) {}

    override fun onFinalResult(hypothesis: String?) {}

    override fun onResult(hypothesis: String?) {
        if (hypothesis == null) return
        val t = JSONObject(hypothesis).optString("text")
        if (t.isNotBlank()) traiter(t)
    }

    override fun onError(exception: Exception?) {
        majNotif("Erreur d'écoute : " + exception?.message)
    }

    override fun onTimeout() {}

    private fun norm(s: String): String {
        val d = Normalizer.normalize(s.lowercase(Locale.FRANCE), Normalizer.Form.NFD)
        return d.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + c)
            }
        }
        return d[a.length][b.length]
    }

    private fun ressemble(mot: String): Boolean {
        val m = mot.replace("h", "")
        return m.length in 4..6 && distance(m, "tanos") <= 1
    }

    private fun traiter(texte: String) {
        val p = norm(texte)
        majNotif("J'ai entendu : $p")
        if (attendCommande) {
            attendCommande = false
            executer(p)
            return
        }
        val mots = p.split(" ").filter { it.isNotEmpty() }
        var idx = -1
        var nb = 1
        for (i in mots.indices) {
            if (ressemble(mots[i])) {
                idx = i
                nb = 1
                break
            }
            if (i + 1 < mots.size && ressemble(mots[i] + mots[i + 1])) {
                idx = i
                nb = 2
                break
            }
        }
        if (idx < 0) return
        val reste = mots.drop(idx + nb).joinToString(" ")
        if (reste.isEmpty()) {
            attendCommande = true
            dire("Oui ?")
            handler.postDelayed({ attendCommande = false }, 12000)
        } else {
            executer(reste)
        }
    }

    // ---------- Cerveau Gemini ----------

    private fun demanderGemini(question: String) {
        val cle = getSharedPreferences("thanos", MODE_PRIVATE).getString("cle", "") ?: ""
        if (cle.isBlank()) {
            dire("Il me faut une clé Gemini. Ouvre l'application pour la saisir.")
            return
        }
        Thread {
            var reponse: String
            try {
                val contenu = JSONArray()
                for (h in historique.takeLast(6)) {
                    contenu.put(
                        JSONObject().put("role", h.first).put(
                            "parts", JSONArray().put(JSONObject().put("text", h.second))))
                }
                contenu.put(
                    JSONObject().put("role", "user").put(
                        "parts", JSONArray().put(JSONObject().put("text", question))))
                val corps = JSONObject()
                    .put("system_instruction", JSONObject().put(
                        "parts", JSONArray().put(JSONObject().put("text", personnalite))))
                    .put("contents", contenu)
                    .put("generationConfig", JSONObject().put("maxOutputTokens", 1000))

                val c = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent")
                    .openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", cle)
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.doOutput = true
                c.outputStream.use { it.write(corps.toString().toByteArray(Charsets.UTF_8)) }

                val code = c.responseCode
                val flux = if (code in 200..299) c.inputStream else c.errorStream
                val corpsReponse = flux?.bufferedReader()?.readText() ?: ""
                if (code in 200..299) {
                    val parts = JSONObject(corpsReponse).getJSONArray("candidates")
                        .getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) {
                        sb.append(parts.getJSONObject(i).optString("text"))
                    }
                    reponse = sb.toString().replace("*", "").replace("#", "").trim()
                    if (reponse.isEmpty()) reponse = "Je n'ai pas de réponse à cela."
                    historique.add(Pair("user", question))
                    historique.add(Pair("model", reponse))
                } else if (code == 429) {
                    reponse = "J'ai atteint ma limite gratuite. Réessaie dans une minute."
                } else if (code == 400 || code == 401 || code == 403) {
                    reponse = "Ta clé Gemini semble invalide. Vérifie-la dans l'application."
                } else {
                    reponse = "Gemini a renvoyé l'erreur $code."
                }
            } catch (e: Exception) {
                reponse = "Je n'arrive pas à joindre Gemini. Vérifie ta connexion internet."
            }
            handler.post { dire(reponse) }
        }.start()
    }

    // ---------- Actions ----------

    private fun dire(m: String) {
        if (ttsPret) tts.speak(m, TextToSpeech.QUEUE_FLUSH, null, "thanos")
    }

    private fun lancer(intent: Intent, titre: String) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
        }
        val pi = PendingIntent.getActivity(
            this, titre.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, "thanos_actions")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Thanos")
            .setContentText("Touche pour ouvrir : $titre")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(15000)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, n)
    }

    private fun executer(p: String) {
        when {
            p.contains("agenda") || p.contains("calendrier") -> {
                dire("J'ouvre l'agenda")
                val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()
                lancer(Intent(Intent.ACTION_VIEW, uri), "l'agenda")
            }
            p.contains("quelle heure") -> {
                dire("Il est " + SimpleDateFormat("HH 'heures' mm", Locale.FRANCE).format(Date()))
            }
            p.contains("quel jour") -> {
                dire("Nous sommes le " + SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date()))
            }
            p.startsWith("appelle ") -> {
                val nom = p.removePrefix("appelle ").trim()
                val numero = chercherNumero(nom)
                if (numero == null) {
                    dire("Je ne trouve pas $nom dans tes contacts")
                } else {
                    dire("J'appelle $nom")
                    try {
                        val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                        tm.placeCall(Uri.parse("tel:" + Uri.encode(numero)), Bundle())
                    } catch (e: Exception) {
                        dire("Je n'arrive pas à lancer l'appel")
                    }
                }
            }
            p.startsWith("ouvre ") -> {
                val nom = p.removePrefix("ouvre ").trim()
                val pm = packageManager
                val apps = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                val trouve = apps.firstOrNull { norm(it.loadLabel(pm).toString()).contains(nom) }
                val intent = trouve?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
                if (intent != null) {
                    dire("J'ouvre $nom")
                    lancer(intent, nom)
                } else {
                    dire("Je ne trouve pas l'application $nom")
                }
            }
            else -> demanderGemini(p)
        }
    }

    private fun chercherNumero(nom: String): String? {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val curseur = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )
        var resultat: String? = null
        if (curseur != null) {
            while (resultat == null && curseur.moveToNext()) {
                if (norm(curseur.getString(0) ?: "").contains(nom)) {
                    resultat = curseur.getString(1)
                }
            }
            curseur.close()
        }
        return resultat
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        speech?.stop()
        speech?.shutdown()
        model?.close()
        tts.shutdown()
        super.onDestroy()
    }
}
