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
    private data class Rep(val statut: Statut, val texte: String, val recommencer: Boolean = false)

    private var model: Model? = null
    private var reconnu: Recognizer? = null
    private var modeleArabe: Model? = null
    private var reconnuArabe: Recognizer? = null
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
    private var geminiEnAttente: String? = null
    private val lancerGeminiDiffere = Runnable {
        val q = geminiEnAttente
        geminiEnAttente = null
        if (q != null) demanderGemini(q)
    }

    private val personnalite =
        "Tu es Thanos, l'assistant vocal personnel d'une utilisatrice francophone. " +
        "Ton style est celui de Jarvis dans Iron Man : poli, dévoué, calme et légèrement pince-sans-rire. " +
        "Tu tutoies l'utilisatrice. Tes réponses sont lues à voix haute : écris une à trois phrases courtes, " +
        "sans markdown, sans listes, sans émojis. " +
        "Si on te demande une information très récente que tu ne peux pas vérifier, dis-le honnêtement. " +
        "Si l'utilisateur parle en arabe algérien (darija) ou écrit en lettres arabes, " +
        "réponds en darija algérienne, naturelle et courte."

    private val messageHorsLigne =
        "Je suis hors connexion internet. Je peux te donner l'heure et la date, le niveau de batterie, " +
        "appeler, ouvrir ou fermer une appli, l'agenda, l'alarme et les réglages. " +
        "Les réponses de Gemini attendront le retour du réseau."

    private val rxEmail = Regex("\\b(?:envoie|ecris|envoyer)\\s+(?:un\\s+)?(?:email|mail|courriel)\\s+a\\s+(.+)$")
    private val rxSms = Regex("\\b(?:envoie|ecris|envoyer)\\s+(?:un\\s+)?(?:sms|texto|message)\\s+a\\s+(.+)$")
    private val rxHeure = Regex("\\b(\\d{1,2})\\s*h\\s*(\\d{0,2})\\b")
    private var raisonEchec = "Pas de connexion internet"
    private val urlModeleFr = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
    private val urlModeleAr = "https://alphacephei.com/vosk/models/vosk-model-small-ar-tn-0.1-linto.zip"
    // Modèle Gemini le plus rapide ; s'il est indisponible pour la clé,
    // on bascule automatiquement sur l'alias générique.
    private var modeleGemini = "gemini-3.5-flash-lite"

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
                    handler.postDelayed({ enPause = false }, 250)
                }
                override fun onError(utteranceId: String?) {
                    handler.postDelayed({ enPause = false }, 250)
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
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val reseau = cm.activeNetwork ?: return false
            cm.getNetworkCapabilities(reseau)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- Modèle Vosk ----------

    private fun preparerModele(nom: String, libelle: String, url: String): File? {
        val dossier = File(filesDir, nom)
        if (File(dossier, "am").exists()) return dossier
        var raison: String? = raisonEchec
        for (essai in 1..3) {
            majNotif("Téléchargement du modèle $libelle (essai $essai/3)...")
            raison = telechargerModele(dossier, url)
            if (raison == null) return dossier
            try { Thread.sleep(2000) } catch (e: Exception) { break }
        }
        raisonEchec = raison ?: raisonEchec
        return null
    }

    // Retourne null en cas de succès, sinon la raison de l'échec.
    private fun telechargerModele(dossier: File, url: String): String? {
        val zip = File(cacheDir, "model.zip")
        val temp = File(filesDir, dossier.name + ".tmp")
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 60000
            val code = c.responseCode
            if (code !in 200..299) {
                return "Serveur de modèles inaccessible (erreur $code)"
            }
            c.inputStream.use { entree ->
                FileOutputStream(zip).use { sortie -> entree.copyTo(sortie) }
            }
            // Extraction dans un dossier temporaire : seul un modèle complet est conservé.
            temp.deleteRecursively()
            val racine = temp.canonicalPath
            ZipInputStream(FileInputStream(zip)).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val chemin = e.name.substringAfter("/", "")
                    if (chemin.isNotEmpty()) {
                        val f = File(temp, chemin)
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
            if (!File(temp, "am").exists()) {
                temp.deleteRecursively()
                return "Téléchargement incomplet"
            }
            dossier.deleteRecursively()
            if (!temp.renameTo(dossier)) {
                temp.copyRecursively(dossier, overwrite = true)
                temp.deleteRecursively()
            }
            if (File(dossier, "am").exists()) null else "Téléchargement incomplet"
        } catch (e: Exception) {
            try { zip.delete() } catch (x: Exception) {}
            temp.deleteRecursively()
            when (e) {
                is java.net.UnknownHostException -> "Pas de connexion internet"
                is java.net.SocketTimeoutException -> "Connexion trop lente, réessaie"
                is javax.net.ssl.SSLException -> "Connexion sécurisée impossible"
                is java.io.IOException -> "Erreur réseau : " + (e.message ?: "inconnue")
                else -> "Erreur : " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun demarrerVosk() {
        val dossier = preparerModele("model-fr", "français", urlModeleFr)
        if (dossier == null) {
            majNotif("Modèle vocal non installé : $raisonEchec. Active internet puis relance Thanos.")
            charge = false
            return
        }
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(dossier.absolutePath)
            reconnu = Recognizer(model, 16000.0f)
        } catch (e: Exception) {
            // Modèle probablement corrompu : on le supprime pour forcer un retéléchargement propre.
            try { File(filesDir, "model-fr").deleteRecursively() } catch (x: Exception) {}
            majNotif("Modèle vocal illisible : " + (e.message ?: "erreur") +
                ". Relance Thanos pour le retélécharger.")
            charge = false
            return
        }
        if (getSharedPreferences("thanos", MODE_PRIVATE).getBoolean("arabe", false)) chargerModeleArabe()
        if (!demarrerMicro()) return
        majNotif(
            (if (enLigne()) "Dis « Thanos »" else "Dis « Thanos » — hors ligne") +
                (if (reconnuArabe != null) " — arabe actif" else ""))
        handler.post { dire("Thanos est prêt") }
    }

    // Modèle arabe (darija maghrébine) : optionnel, activé depuis l'application.
    private fun chargerModeleArabe() {
        val dossier = preparerModele("model-ar", "arabe (darija)", urlModeleAr) ?: return
        try {
            modeleArabe = Model(dossier.absolutePath)
            reconnuArabe = Recognizer(modeleArabe, 16000.0f)
        } catch (e: Exception) {
            // Modèle probablement corrompu : suppression pour un retéléchargement propre.
            try { File(filesDir, "model-ar").deleteRecursively() } catch (x: Exception) {}
            majNotif("Modèle arabe illisible : arrête puis relance Thanos pour le retélécharger.")
        }
    }

    // ---------- Micro : gain automatique + suppression du bruit ----------

    private fun demarrerMicro(): Boolean {
        val config = AudioFormat.ENCODING_PCM_16BIT
        val canal = AudioFormat.CHANNEL_IN_MONO
        val taille = maxOf(AudioRecord.getMinBufferSize(16000, canal, config), 8192)
        val a = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, canal, config, taille)
        if (a.state != AudioRecord.STATE_INITIALIZED) {
            a.release()
            majNotif("Micro indisponible : vérifie l'autorisation du micro")
            charge = false
            return false
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
                val ra = reconnuArabe
                val finAr = ra?.acceptWaveForm(data, data.size) == true
                if (fin || finAr) {
                    val arabe = if (finAr && ra != null) {
                        JSONObject(ra.getResult()).optString("text")
                    } else ""
                    val francais = if (fin) {
                        JSONObject(r.getResult()).optString("text")
                    } else ""
                    // Le texte arabe prime : sur une phrase en darija, le modèle
                    // français ne crache souvent que du bruit.
                    val texte = if (arabe.isNotBlank()) arabe else francais
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
        return true
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
            .replace(Regex("[^a-z0-9 \\u0600-\\u06FF]"), " ")
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

    // Variantes strictes de « Thanos » : le modèle entend parfois « tanos »,
    // « danos », « sanos » ou même « anos ».
    private fun estMotActivationStrict(mot: String): Boolean {
        val s = mot.replace("h", "")
        if (s.length !in 4..6) return false
        return distance(s, "tanos") <= 1
    }

    // Variantes faibles : le modèle confond aussi « Thanos » avec « annonce »
    // ou « os ». Ce sont de vrais mots français, on ne les accepte donc qu'en
    // tout début de phrase pour éviter les déclenchements intempestifs.
    private val activationsFaibles = setOf("os", "annonce", "anonce")

    private fun estMotActivation(mot: String): Boolean =
        estMotActivationStrict(mot) || mot in activationsFaibles

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
        // Question Gemini en cours d'assemblage : on complète avec la suite entendue
        // (le détecteur de fin de phrase coupe parfois les longues phrases en deux).
        val enCours = geminiEnAttente
        if (enCours != null) {
            val suite = p.split(" ").filter { it.isNotEmpty() && !estMotActivation(it) }
                .joinToString(" ")
            if (suite.isNotBlank()) {
                geminiEnAttente = enCours + " " + suite
                majNotif("J'assemble la question : $geminiEnAttente")
                handler.removeCallbacks(lancerGeminiDiffere)
                handler.postDelayed(lancerGeminiDiffere, 2500)
            }
            return
        }
        val mots = p.split(" ").filter { it.isNotEmpty() }
        var idx = mots.indexOfFirst { estMotActivationStrict(it) }
        // Mot d'activation en arabe : « Thanos » écrit en lettres arabes.
        if (idx < 0) idx = mots.indexOfFirst { it.contains("ثانوس") || it.contains("تانوس") }
        // Variantes faibles : uniquement seules ou en tête d'une très courte phrase.
        if (idx < 0 && mots.size <= 3) idx = mots.indexOfFirst { it in activationsFaibles }
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
        var reponse = geminiAppel(cle, prompt, systeme, avecHistorique, modeleGemini)
        if (reponse.texte.contains("erreur 404")) {
            // Modèle indisponible pour cette clé : on bascule sur l'alias générique.
            modeleGemini = "gemini-flash-latest"
            reponse = geminiAppel(cle, prompt, systeme, avecHistorique, modeleGemini)
        }
        var essai = 1
        while (reponse.recommencer && essai < 2) {
            essai++
            majNotif("Gemini met du temps… nouvel essai")
            try { Thread.sleep(1500) } catch (e: Exception) { break }
            reponse = geminiAppel(cle, prompt, systeme, avecHistorique, modeleGemini)
        }
        return reponse
    }

    private fun geminiAppel(
        cle: String, prompt: String, systeme: String, avecHistorique: Boolean, modele: String
    ): Rep {
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

            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$modele:generateContent")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", cle)
            c.connectTimeout = 15000
            c.readTimeout = 45000
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
                500, 502, 503, 504 -> Rep(
                    Statut.RESEAU,
                    "Gemini est momentanément saturé. Réessaie dans un instant.",
                    recommencer = true)
                else -> Rep(Statut.CLE, "Gemini a renvoyé l'erreur $code.")
            }
        } catch (e: Exception) {
            val detail = e.javaClass.simpleName + " : " + (e.message ?: "inconnue")
            majNotif("Gemini — $detail")
            val cause = when (e) {
                is java.net.UnknownHostException ->
                    "Je ne trouve pas le serveur Gemini. Le réseau interdit peut-être l'accès à Google."
                is java.net.SocketTimeoutException ->
                    "Gemini met trop de temps à répondre, réessaie dans un instant."
                is javax.net.ssl.SSLException ->
                    "Connexion sécurisée refusée : vérifie la date et l'heure de ton téléphone."
                else ->
                    "Je n'arrive pas à joindre Gemini : " + (e.message ?: "erreur inconnue") +
                    ". Vérifie ta connexion."
            }
            // Un délai dépassé ou une connexion coupée se répare souvent tout seul :
            // on demande un second essai. Les erreurs de clé ou de DNS, non.
            val recommencer = e is java.net.SocketTimeoutException ||
                (e is java.io.IOException &&
                    e !is java.net.UnknownHostException &&
                    e !is javax.net.ssl.SSLException)
            return Rep(Statut.RESEAU, cause, recommencer)
        }
    }

    private fun demanderGemini(question: String) {
        if (!enLigne()) {
            dire(messageHorsLigne)
            return
        }
        majNotif("Gemini réfléchit…")
        Thread {
            val r = geminiSync(question, personnalite, avecHistorique = true)
            handler.post {
                // En cas d'échec, on laisse le détail réel du problème dans la notification.
                if (r.statut == Statut.OK) majNotif(r.texte)
                dire(r.texte)
            }
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

    private var langueVocale = Locale.FRANCE

    private fun dire(m: String) {
        if (!ttsPret) return
        // Réponse en arabe/darija : on bascule la synthèse vocale en arabe.
        val loc = if (m.any { it in '\u0600'..'\u06FF' }) Locale("ar") else Locale.FRANCE
        if (loc != langueVocale) {
            tts.language = loc
            langueVocale = loc
        }
        tts.speak(m, TextToSpeech.QUEUE_FLUSH, null, "thanos")
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
            // On clique dès que la page apparaît, puis on revient tout de suite
            // à l'accueil : les paramètres ne font plus qu'un bref passage à l'écran.
            var essais = 0
            val tenter = object : Runnable {
                override fun run() {
                    essais++
                    val ok = ThanosAccessibility.cliquerForceArret() ||
                        (essais >= 3 && ThanosAccessibility.cliquerConfirmation())
                    if (ok) {
                        majNotif("$nom est fermé")
                        handler.postDelayed({ allerAccueilSilencieux() }, 400)
                    } else if (essais < 9) {
                        handler.postDelayed(this, 300)
                    } else {
                        majNotif("Touche « Forcer l'arrêt » pour fermer $nom")
                        dire("Je n'ai pas réussi à cliquer sur « Forcer l'arrêt ». " +
                            "La page est ouverte, touche-le toi-même.")
                    }
                }
            }
            handler.postDelayed(tenter, 400)
        } else {
            dire("Ouvre les informations de $nom et touche Forcer l'arrêt. " +
                "Tu peux aussi activer la fermeture automatique dans l'application.")
            lancer(details, "les informations de $nom")
        }
    }

    private fun appliAudio(): AudioManager =
        getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun allerAccueilSilencieux(): Boolean = try {
        startActivity(Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    private fun allerAccueil() {
        if (allerAccueilSilencieux()) dire("À l'accueil")
        else dire("Je ne peux pas revenir à l'accueil")
    }

    private fun executer(p: String) {
        when {
            envoyerEmail(p) -> {}
            envoyerSms(p) -> {}

            p == "ferme" || p == "arrete" || p == "quitte" || p == "stop" ||
                p.startsWith("ferme ") || p.startsWith("arrete ") ||
                p.startsWith("quitte ") || p.startsWith("stop ") -> {
                fermerAppli(p.substringAfter(" ", "").trim())
            }

            p == "accueil" || p == "home" || p.contains("ecran d accueil") ||
                p.contains("page d accueil") || p.contains("retour a l accueil") ||
                p.contains("retourne a l accueil") || p.contains("va a l accueil") -> {
                allerAccueil()
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

            p.startsWith("cherche ") || p.startsWith("recherche ") || p.startsWith("trouve ") -> {
                val requete = p.substringAfter(" ")
                    .removeSuffix(" sur google").removeSuffix(" sur internet").trim()
                if (requete.isEmpty()) {
                    dire("Que veux-tu que je cherche ?")
                } else {
                    dire("Je cherche $requete sur Google")
                    lancer(
                        Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/search?q=" + Uri.encode(requete))),
                        "la recherche Google")
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

            else -> {
                // On attend 2,5 s de silence pour réunir d'éventuels morceaux
                // d'une phrase coupée par le détecteur de fin de phrase.
                geminiEnAttente = p
                handler.removeCallbacks(lancerGeminiDiffere)
                handler.postDelayed(lancerGeminiDiffere, 2500)
            }
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
        try { reconnuArabe?.close() } catch (e: Exception) {}
        try { model?.close() } catch (e: Exception) {}
        try { modeleArabe?.close() } catch (e: Exception) {}
        tts.shutdown()
        super.onDestroy()
    }
}
