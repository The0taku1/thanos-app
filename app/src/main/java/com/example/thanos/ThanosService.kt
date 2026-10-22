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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ThanosService : Service(), TextToSpeech.OnInitListener {

    private enum class Statut { OK, RESEAU, CLE, LIMITE }
    private data class Rep(val statut: Statut, val texte: String)

    private var model: Model? = null
    private var reconnu: Recognizer? = null
    private var audio: AudioRecord? = null
    private var ecoute: Thread? = null
    private lateinit var tts: TextToSpeech
    private var ttsPret = false
    private var charge = false
    @Volatile private var attendCommande = false
    @Volatile private var enMarche = false
    @Volatile private var enPause = false
    private var gain = 1.0f
    private val handler = Handler(Looper.getMainLooper())
    private val historique = mutableListOf<Pair<String, String>>()
    private val finFenetre = Runnable { attendCommande = false }

    private val personnalite =
        "Tu es Thanos, l'assistant vocal personnel d'une utilisatrice francophone. " +
        "Ton style est celui de Jarvis dans Iron Man : poli, dévoué, calme et légèrement pince-sans-rire. " +
        "Tu tutoies l'utilisatrice. Tes réponses sont lues à voix haute : écris une à trois phrases courtes, " +
        "sans markdown, sans listes, sans émojis. " +
        "Si on te demande une information très récente que tu ne peux pas vérifier, dis-le honnêtement."

    private val messageHorsLigne =
        "Je suis hors connexion internet. Je peux te donner l'heure et la date, le niveau de batterie, " +
        "appeler, ouvrir ou fermer une appli, l'agenda, l'alarme et les réglages. " +
        "Les réponses de Gemini attendront le retour du réseau."

    private val rxEmail = Regex("\\b(?:envoie|ecris|envoyer)\\s+(?:un\\s+)?(?:email|mail|courriel)\\s+a\\s+(.+)$")
    private val rxSms = Regex("\\b(?:envoie|ecris|envoyer)\\s+(?:un\\s+)?(?:sms|texto|message)\\s+a\\s+(.+)$")
    private val rxHeure = Regex("\\b(\\d{1,2})\\s*h\\s*(\\d{0,2})\\b")

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
                    enPause = true
                }
                override fun onDone(utteranceId: String?) {
                    handler.postDelayed({ enPause = false }, 400)
                }
                override fun onError(utteranceId: String?) {
                    handler.postDelayed({ enPause = false }, 400)
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

    // ---------- Réseau ----------

    private fun enLigne(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val reseau = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(reseau)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
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
            reconnu = Recognizer(model, 16000.0f)
            demarrerMicro()
            majNotif(if (enLigne()) "Dis « Thanos »" else "Dis « Thanos » — hors ligne")
            handler.post { dire("Thanos est prêt") }
        } catch (e: Exception) {
            majNotif("Erreur : " + e.message)
            charge = false
        }
    }

    // ---------- Micro : gain automatique + suppression du bruit ----------

    private fun demarrerMicro() {
        val config = AudioFormat.ENCODING_PCM_16BIT
        val canal = AudioFormat.CHANNEL_IN_MONO
        val taille = maxOf(AudioRecord.getMinBufferSize(16000, canal, config), 8192)
        val a = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, canal, config, taille)
        if (a.state != AudioRecord.STATE_INITIALIZED) {
            a.release()
            majNotif("Micro indisponible : vérifie l'autorisation du micro")
            charge = false
            return
        }
        audio = a
        enMarche = true
        ecoute = Thread {
            val r = reconnu ?: return@Thread
            a.startRecording()
            val tampon = ShortArray(1024)
            var compteur = 0
            while (enMarche) {
                val n = a.read(tampon, 0, tampon.size)
                if (n <= 0) continue
                if (enPause) continue
                amplifier(tampon, n)
                val data = ByteArray(n * 2)
                ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().put(tampon, 0, n)
                val fin = r.acceptWaveForm(data, data.size)
                if (fin) {
                    val texte = JSONObject(r.getResult()).optString("text")
                    if (texte.isNotBlank()) handler.post { traiter(texte) }
                } else if (attendCommande && compteur % 4 == 0) {
                    val partiel = JSONObject(r.getPartialResult()).optString("partial")
                    if (partiel.isNotBlank()) handler.post { majNotif("Je t'écoute : $partiel") }
                }
                compteur++
            }
            try { a.stop() } catch (e: Exception) {}
            a.release()
        }.apply { start() }
    }

    // Amplification automatique (AGC logiciel) : on vise un niveau sonore constant
    // pour que Thanos entende une voix normale, même parlée doucement.
    private fun amplifier(tampon: ShortArray, n: Int) {
        var somme = 0.0
        for (i in 0 until n) {
            val v = tampon[i].toDouble()
            somme += v * v
        }
        val rms = sqrt(somme / n)
        // En silence, on revient à 1 pour ne pas amplifier le bruit de fond.
        val cible = if (rms < 80.0) 1.0f
                    else (3000.0 / rms).toFloat().coerceIn(0.5f, 6.0f)
        gain += (cible - gain) * 0.08f
        for (i in 0 until n) {
            val v = (tampon[i] * gain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            tampon[i] = v.toShort()
        }
    }

    // ---------- Écoute ----------

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

    // Mot d'activation strict : « Thanos » et ses variantes très proches uniquement.
    private fun estMotActivation(mot: String): Boolean {
        val s = mot.replace("h", "")
        if (s == "tanos") return true
        return s.length in 5..6 && s.startsWith("t") && distance(s, "tanos") <= 1
    }

    private fun traiter(texte: String) {
        val p = norm(texte)
        if (p.isBlank()) return
        majNotif("J'ai entendu : $p")
        if (attendCommande) {
            attendCommande = false
            handler.removeCallbacks(finFenetre)
            executer(p)
            return
        }
        val mots = p.split(" ").filter { it.isNotEmpty() }
        val idx = mots.indexOfFirst { estMotActivation(it) }
        if (idx < 0) return
        val reste = mots.drop(idx + 1).joinToString(" ")
        if (reste.isEmpty()) {
            attendCommande = true
            dire("Oui ?")
            handler.removeCallbacks(finFenetre)
            handler.postDelayed(finFenetre, 8000)
        } else {
            executer(reste)
        }
    }

    // ---------- Cerveau Gemini (synchrone, à appeler hors du thread principal) ----------

    private fun geminiSync(prompt: String, systeme: String, avecHistorique: Boolean): Rep {
        val cle = getSharedPreferences("thanos", MODE_PRIVATE).getString("cle", "") ?: ""
        if (cle.isBlank()) {
            return Rep(Statut.CLE, "Il me faut une clé Gemini. Ouvre l'application pour la saisir.")
        }
        if (!enLigne()) return Rep(Statut.RESEAU, messageHorsLigne)
        try {
            val contenu = JSONArray()
            if (avecHistorique) {
                for (h in historique.takeLast(6)) {
                    contenu.put(
                        JSONObject().put("role", h.first).put(
                            "parts", JSONArray().put(JSONObject().put("text", h.second))))
                }
            }
            contenu.put(
                JSONObject().put("role", "user").put(
                    "parts", JSONArray().put(JSONObject().put("text", prompt))))
            val corps = JSONObject()
                .put("system_instruction", JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", systeme))))
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
                var texte = sb.toString().replace("*", "").replace("#", "").trim()
                if (texte.isEmpty()) texte = "Je n'ai pas de réponse à cela."
                if (avecHistorique) {
                    historique.add(Pair("user", prompt))
                    historique.add(Pair("model", texte))
                }
                return Rep(Statut.OK, texte)
            }
            return when (code) {
                429 -> Rep(Statut.LIMITE, "J'ai atteint ma limite gratuite. Réessaie dans une minute.")
                400, 401, 403 -> Rep(Statut.CLE, "Ta clé Gemini semble invalide. Vérifie-la dans l'application.")
                else -> Rep(Statut.CLE, "Gemini a renvoyé l'erreur $code.")
            }
        } catch (e: Exception) {
            return Rep(Statut.RESEAU, "Je n'arrive pas à joindre Gemini. Vérifie ta connexion internet.")
        }
    }

    private fun demanderGemini(question: String) {
        if (!enLigne()) {
            dire(messageHorsLigne)
            return
        }
        Thread {
            val r = geminiSync(question, personnalite, avecHistorique = true)
            handler.post { dire(r.texte) }
        }.start()
    }

    // ---------- Écriture d'email / SMS par Gemini ----------

    private fun couper(suite: String): Pair<String, String> {
        val seps = listOf(
            " pour lui dire que ", " pour lui dire ", " pour dire que ", " pour dire ",
            " pour ", " dire que ", " dire "
        )
        for (sep in seps) {
            val i = suite.indexOf(sep)
            if (i > 0) {
                val contenu = suite.substring(i + sep.length).trim().removePrefix("que ").trim()
                return Pair(suite.substring(0, i).trim(), contenu)
            }
        }
        return Pair(suite.trim(), "")
    }

    private fun extraireBrouillon(brut: String): Pair<String, String> {
        var s = brut.trim()
        if (s.startsWith("```")) s = s.removePrefix("```").removePrefix("json").trim()
        if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        return try {
            val o = JSONObject(s)
            val objet = o.optString("objet", "Message").ifBlank { "Message" }
            val texte = o.optString("texte", "").ifBlank { s }
            Pair(objet, texte)
        } catch (e: Exception) {
            val lignes = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lignes.size >= 2) {
                val objet = lignes[0].removePrefix("Objet :").removePrefix("Objet:").trim()
                Pair(objet.ifBlank { "Message" }, lignes.drop(1).joinToString("\n"))
            } else {
                Pair("Message", s)
            }
        }
    }

    private fun redigerEtEnvoyer(nom: String, destination: String, contenu: String, estEmail: Boolean) {
        if (!enLigne()) {
            dire("Sans connexion, je ne peux pas rédiger. Réessaie dès que le réseau revient.")
            return
        }
        val demande = if (contenu.isEmpty()) {
            "L'utilisateur veut contacter $nom. Rédige un message de politesse à son intention."
        } else {
            "L'utilisateur veut que tu écrives ceci à $nom : $contenu"
        }
        val consigne = if (estEmail) {
            "Tu rédiges un email envoyé au nom de l'utilisateur. " +
            "Réponds UNIQUEMENT avec un objet et un texte, sans aucun texte autour, au format JSON exact : " +
            "{\"objet\": \"...\", \"texte\": \"...\"}. " +
            "Objet court et clair. Texte poli de trois à six phrases, à la première personne, " +
            "sans signature, sans markdown."
        } else {
            "Tu rédiges un SMS court envoyé au nom de l'utilisateur. " +
            "Réponds UNIQUEMENT avec ce JSON : {\"objet\": \"SMS\", \"texte\": \"...\"}. " +
            "Trois phrases maximum, naturel, prêt à envoyer, sans markdown."
        }
        dire(if (estEmail) "Je rédige l'email à $nom…" else "Je rédige le SMS à $nom…")
        Thread {
            val r = geminiSync(demande, consigne, avecHistorique = false)
            if (r.statut != Statut.OK) {
                handler.post { dire(r.texte) }
                return@Thread
            }
            val (objet, texte) = extraireBrouillon(r.texte)
            handler.post {
                try {
                    val i = if (estEmail) {
                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$destination")).apply {
                            putExtra(Intent.EXTRA_EMAIL, arrayOf(destination))
                            putExtra(Intent.EXTRA_SUBJECT, objet)
                            putExtra(Intent.EXTRA_TEXT, texte)
                        }
                    } else {
                        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$destination")).apply {
                            putExtra("sms_body", texte)
                        }
                    }
                    val choix = Intent.createChooser(
                        i, if (estEmail) "Envoyer l'email" else "Envoyer le SMS")
                    choix.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(choix)
                    majNotif("Prêt pour $nom : touche envoyer")
                    dire(
                        if (estEmail) "L'email à $nom est prêt. Vérifie-le et touche envoyer."
                        else "Le SMS à $nom est prêt. Vérifie-le et touche envoyer.")
                } catch (e: Exception) {
                    dire("Je ne trouve pas d'application pour envoyer ça.")
                }
            }
        }.start()
    }

    private fun envoyerEmail(p: String): Boolean {
        val m = rxEmail.find(p) ?: return false
        val (nom, contenu) = couper(m.groupValues[1])
        if (nom.isEmpty()) {
            dire("À qui veux-tu que j'écrive ?")
            return true
        }
        val adresse = chercherEmail(nom)
        if (adresse == null) {
            dire("Je ne trouve pas d'adresse email pour $nom")
            return true
        }
        redigerEtEnvoyer(nom, adresse, contenu, estEmail = true)
        return true
    }

    private fun envoyerSms(p: String): Boolean {
        val m = rxSms.find(p) ?: return false
        val (nom, contenu) = couper(m.groupValues[1])
        if (nom.isEmpty()) {
            dire("À qui veux-tu que j'envoie le SMS ?")
            return true
        }
        val numero = chercherNumero(nom)
        if (numero == null) {
            dire("Je ne trouve pas $nom dans tes contacts")
            return true
        }
        redigerEtEnvoyer(nom, numero, contenu, estEmail = false)
        return true
    }

    // ---------- Actions ----------

    private fun dire(m: String) {
        if (ttsPret) tts.speak(m, TextToSpeech.QUEUE_FLUSH, null, "thanos")
    }

    private fun lancer(intent: Intent, titre: String, avecNotif: Boolean = true) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            dire("Je ne peux pas ouvrir $titre")
            return
        }
        if (!avecNotif) return
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

    private fun trouverAppli(nom: String): String? {
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return apps.firstOrNull { norm(it.loadLabel(pm).toString()).contains(nom) }
            ?.activityInfo?.packageName
    }

    private fun fermerAppli(nom: String) {
        if (nom.isEmpty()) {
            dire("Fermer quelle application ?")
            return
        }
        val pkg = trouverAppli(nom)
        if (pkg == null) {
            dire("Je ne trouve pas l'application $nom")
            return
        }
        if (pkg == packageName) {
            dire("Je préfère ne pas me fermer moi-même.")
            return
        }
        val details = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        if (ThanosAccessibility.instance != null) {
            try {
                details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(details)
            } catch (e: Exception) {
            }
            dire("Je ferme $nom")
            handler.postDelayed({
                val ok = ThanosAccessibility.cliquerForceArret()
                majNotif(if (ok) "$nom est fermé" else "Touche « Forcer l'arrêt » pour fermer $nom")
            }, 1200)
        } else {
            dire("Ouvre les informations de $nom et touche Forcer l'arrêt. " +
                "Tu peux aussi activer la fermeture automatique dans l'application.")
            lancer(details, "les informations de $nom")
        }
    }

    private fun appliAudio(): AudioManager =
        getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun executer(p: String) {
        when {
            envoyerEmail(p) -> {}
            envoyerSms(p) -> {}

            p.startsWith("ferme ") || p.startsWith("arrete ") ||
                p.startsWith("quitte ") || p.startsWith("stop ") -> {
                fermerAppli(p.substringAfter(" ").trim())
            }

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

            p.contains("batterie") || p.contains("niveau de charge") -> {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val phrase = when {
                    pct <= 0 -> "Je n'arrive pas à lire le niveau de batterie."
                    bm.isCharging -> "La batterie est à $pct pour cent et elle est en charge."
                    else -> "La batterie est à $pct pour cent."
                }
                dire(phrase)
            }

            p.contains("mode silencieux") || p.contains("met le son a zero") -> {
                appliAudio().setRingerMode(AudioManager.RINGER_MODE_SILENT)
                dire("Mode silencieux activé")
            }
            p.contains("mode vibre") || p.contains("mode vibration") -> {
                appliAudio().setRingerMode(AudioManager.RINGER_MODE_VIBRATE)
                dire("Le téléphone vibre")
            }
            p.contains("mode sonnerie") || p.contains("enleve le silencieux") ||
                p.contains("remets le son") -> {
                appliAudio().setRingerMode(AudioManager.RINGER_MODE_NORMAL)
                dire("Sonnerie rétablie")
            }
            p.contains("monte le son") || p.contains("monte le volume") || p.contains("plus fort") -> {
                appliAudio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                dire("Je monte le son")
            }
            p.contains("baisse le son") || p.contains("baisse le volume") || p.contains("moins fort") -> {
                appliAudio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                dire("Je baisse le son")
            }

            p.contains("reveil") && rxHeure.containsMatchIn(p) -> {
                val m = rxHeure.find(p)
                val h = m?.groupValues?.get(1)?.toIntOrNull()
                val mn = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
                if (h != null && h in 0..23 && mn in 0..59) {
                    val quand = if (mn > 0) "$h h $mn" else "$h heures"
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, h)
                        .putExtra(AlarmClock.EXTRA_MINUTES, mn)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "Thanos")
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    dire("Réveil réglé à $quand")
                    lancer(i, "le réveil", avecNotif = false)
                } else {
                    dire("Je n'ai pas compris l'heure du réveil")
                }
            }

            p.contains("bluetooth") -> {
                dire("Je t'ouvre les réglages Bluetooth")
                lancer(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "les réglages Bluetooth", avecNotif = false)
            }
            p.contains("wifi") || p.contains("wi fi") -> {
                dire("Je t'ouvre les réglages Wi-Fi")
                lancer(Intent(Settings.ACTION_WIFI_SETTINGS), "les réglages Wi-Fi", avecNotif = false)
            }
            p.contains("luminosite") -> {
                dire("Je t'ouvre les réglages d'affichage")
                lancer(Intent(Settings.ACTION_DISPLAY_SETTINGS), "les réglages d'affichage", avecNotif = false)
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
                val pkg = trouverAppli(nom)
                val intent = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
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

    private fun chercherEmail(nom: String): String? {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val curseur = contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Email.ADDRESS
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
        enMarche = false
        attendCommande = false
        try { ecoute?.join(500) } catch (e: Exception) {}
        try { audio?.release() } catch (e: Exception) {}
        try { reconnu?.close() } catch (e: Exception) {}
        try { model?.close() } catch (e: Exception) {}
        tts.shutdown()
        super.onDestroy()
    }
}
