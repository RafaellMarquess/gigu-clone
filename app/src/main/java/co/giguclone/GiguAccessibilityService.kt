package co.giguclone

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent

class GiguAccessibilityService : AccessibilityService() {

    companion object {
        var ultimoValor: String = ""
        var ultimaDistancia: String = ""
        var ultimoTempo: String = ""
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val rootNode: AccessibilityNodeInfo = rootInActiveWindow ?: return
        analisarNos(rootNode)
        rootNode.recycle()
    }

    private fun analisarNos(node: AccessibilityNodeInfo) {
        val texto = node.text?.toString() ?: ""
        
        if (texto.contains("R$")) {
            val limpo = texto.replace("R$", "").replace(",", ".").trim()
            val num = limpo.toFloatOrNull()
            if (num != null && num > 2 && num < 500) {
                ultimoValor = num.toString()
            }
        }
        
        if (texto.contains("km", ignoreCase = true) || texto.contains("quilômetro", ignoreCase = true)) {
            val limpo = texto.lowercase().replace("km", "").replace("quilometros", "").replace(",", ".").trim()
            val num = limpo.toFloatOrNull()
            if (num != null && num > 0 && num < 100) {
                ultimaDistancia = num.toString()
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            analisarNos(child)
            child.recycle()
        }
    }

    override fun onInterrupt() {}
}
