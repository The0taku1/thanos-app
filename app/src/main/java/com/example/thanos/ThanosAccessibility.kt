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

        // Clique sur « Forcer l'arrêt » de la page d'informations de l'application,
        // que Thanos vient d'ouvrir pour l'utilisateur.
        fun cliquerForceArret(): Boolean {
            val svc = instance ?: return false
            val racine = svc.rootInActiveWindow ?: return false
            val variants = listOf("Forcer l'arrêt", "Forcer l'arret", "Force stop", "Force Stop")
            for (mot in variants) {
                val noeuds = racine.findAccessibilityNodeInfosByText(mot) ?: continue
                for (n in noeuds) {
                    var c: AccessibilityNodeInfo? = n
                    while (c != null && !c.isClickable) c = c.parent
                    if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
            }
            return false
        }
    }
}
