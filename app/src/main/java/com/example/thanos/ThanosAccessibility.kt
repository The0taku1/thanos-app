package com.example.thanos

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ThanosAccessibility : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {

        @Volatile
        var instance: ThanosAccessibility? = null

        private fun nettoyer(s: String): String =
            java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                .lowercase(java.util.Locale.FRANCE)
                .replace(Regex("[^a-z]"), "")

        // Vrai si ce nœud porte le libellé « Forcer l'arrêt » (ou « Force stop »),
        // que ce soit son texte, sa description ou l'identifiant de sa vue.
        private fun estBoutonArret(n: AccessibilityNodeInfo): Boolean {
            val t = nettoyer(n.text?.toString() ?: "") + " " +
                nettoyer(n.contentDescription?.toString() ?: "") + " " +
                nettoyer(n.viewIdResourceName ?: "")
            if (t.contains("forcer") && t.contains("arret")) return true
            return t.contains("force") && t.contains("stop")
        }

        private fun cliquerDepuis(n: AccessibilityNodeInfo?): Boolean {
            var c: AccessibilityNodeInfo? = n
            while (c != null && !c.isClickable) c = c.parent
            return c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        // Parcours complet de l'arbre : certains téléphones n'exposent pas le
        // libellé à la recherche classique.
        private fun chercherArret(n: AccessibilityNodeInfo?): Boolean {
            if (n == null) return false
            if (estBoutonArret(n) && cliquerDepuis(n)) return true
            for (i in 0 until n.childCount) {
                if (chercherArret(n.getChild(i))) return true
            }
            return false
        }

        // Bouton d'une boîte de dialogue de confirmation (« OK », « D'accord »…),
        // affichée par certains constructeurs après « Forcer l'arrêt ».
        fun cliquerConfirmation(): Boolean {
            val racine = instance?.rootInActiveWindow ?: return false
            for (mot in listOf("D'accord", "Daccord", "Forcer", "OK", "Oui", "Fermer")) {
                val noeuds = racine.findAccessibilityNodeInfosByText(mot) ?: continue
                for (n in noeuds) if (cliquerDepuis(n)) return true
            }
            return false
        }

        // Clique sur « Forcer l'arrêt » de la page d'informations de l'application,
        // que Thanos vient d'ouvrir pour l'utilisateur.
        fun cliquerForceArret(): Boolean {
            val svc = instance ?: return false
            val racine = svc.rootInActiveWindow ?: return false
            val variants = listOf("Forcer l'arrêt", "Forcer l'arret", "Force stop", "Force Stop")
            for (mot in variants) {
                val noeuds = racine.findAccessibilityNodeInfosByText(mot) ?: continue
                for (n in noeuds) if (cliquerDepuis(n)) return true
            }
            return chercherArret(racine)
        }
    }
}
