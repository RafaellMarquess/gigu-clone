package co.giguclone

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

class GiguAccessibilityService : AccessibilityService() {

    companion object {
        var aoReceber: ((String, String, String) -> Unit)? = null

        private val PACOTES = setOf(
            "com.ubercab",
            "com.ubercab.driver",
            "com.ubercab.eats",
            "com.taxis99"
        )

        private val RE_DINHEIRO = Regex(
            "R\\$\\s*([0-9]{1,3}(?:\\.[0-9]{3})*,[0-9]{2}|[0-9]+(?:[.,][0-9]{2})?)"
        )
        private val RE_KM = Regex(
            "([0-9]{1,3}(?:[.,][0-9]{1,2})?)\\s*(?:km|quilometros|quilômetros)",
            RegexOption.IGNORE_CASE
        )
        private val RE_MIN = Regex(
            "([0-9]{1,3})\\s*(?:min|minutos?)",
            RegexOption.IGNORE_CASE
        )
    }

    private val principal = Handler(Looper.getMainLooper())
    private val tarefaVarrer = Runnable { varrer() }
    private val tarefaEsconder = Runnable { removerCard() }

    private var janela: WindowManager? = null
    private var card: View? = null
    private var tvValor: TextView? = null
    private var tvDetalhe: TextView? = null
    private var tvVeredito: TextView? = null
    private var tvTaxas: TextView? = null
    private var ultimaAssinatura = ""

    private val prefs by lazy { getSharedPreferences("gigu", Context.MODE_PRIVATE) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        janela = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pacote = event?.packageName?.toString() ?: return
        if (pacote !in PACOTES) return
        principal.removeCallbacks(tarefaVarrer)
        principal.postDelayed(tarefaVarrer, 220)
    }

    private fun varrer() {
        val raiz = rootInActiveWindow ?: return
        val textos = ArrayList<String>()
        coletar(raiz, textos, 0)
        if (textos.isEmpty()) return

        val bruto = textos.joinToString(" | ")
        if (!bruto.contains("R$")) return

        val assinatura = bruto.hashCode().toString()
        if (assinatura == ultimaAssinatura) return
        ultimaAssinatura = assinatura

        val valor = extrairMaior(RE_DINHEIRO, bruto, 2.0, 500.0) ?: return
        val km = extrairMaior(RE_KM, bruto, 0.1, 200.0) ?: 0.0
        val min = extrairMaior(RE_MIN, bruto, 0.0, 240.0) ?: 0.0

        val valorTxt = String.format("%.2f", valor).replace(".", ",")
        val kmTxt = String.format("%.1f", km).replace(".", ",")
        val minTxt = String.format("%.0f", min)

        aoReceber?.invoke(valorTxt, kmTxt, minTxt)

        if (prefs.getBoolean("overlay", true)) {
            mostrarCard(valor, km, min, valorTxt, kmTxt, minTxt)
        }
    }

    private fun coletar(node: AccessibilityNodeInfo?, saida: MutableList<String>, nivel: Int) {
        if (node == null || nivel > 25) return
        node.text?.toString()?.let { if (it.isNotBlank()) saida.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) saida.add(it) }
        for (i in 0 until node.childCount) {
            coletar(node.getChild(i), saida, nivel + 1)
        }
    }

    private fun extrairMaior(regex: Regex, texto: String, minimo: Double, maximo: Double): Double? {
        var melhor: Double? = null
        for (achado in regex.findAll(texto)) {
            val numero = converter(achado.groupValues[1]) ?: continue
            if (numero < minimo || numero > maximo) continue
            if (melhor == null || numero > melhor!!) melhor = numero
        }
        return melhor
    }

    private fun converter(trecho: String): Double? {
        val limpo = trecho.replace(".", "").replace(",", ".")
        return limpo.toDoubleOrNull()
    }

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density).toInt()

    private fun mostrarCard(
        valor: Double,
        km: Double,
        min: Double,
        valorTxt: String,
        kmTxt: String,
        minTxt: String
    ) {
        val custoKm = prefs.getFloat("custoKm", 1.2f).toDouble()
        val metaKm = prefs.getFloat("metaKm", 1.8f).toDouble()
        val metaH = prefs.getFloat("metaH", 25f).toDouble()

        val custo = km * custoKm
        val liquido = valor - custo
        val porKm = if (km > 0) liquido / km else 0.0
        val porHora = if (min > 0) liquido / (min / 60.0) else 0.0

        var fundo = 0xF20E2C1C.toInt()
        var tinta = 0xFF7BE7A6.toInt()
        var veredito = "ACEITAR"

        if (porKm < metaKm && porHora < metaH) {
            fundo = 0xF2331414.toInt()
            tinta = 0xFFF79A9A.toInt()
            veredito = "RECUSAR"
        } else if (porKm < metaKm || (min > 0 && porHora < metaH)) {
            fundo = 0xF233260A.toInt()
            tinta = 0xFFF3C46A.toInt()
            veredito = "AVALIAR"
        }

        val contexto = this

        if (card == null) {
            val raiz = LinearLayout(contexto).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(fundo)
                    setStroke(dp(1), tinta)
                }
            }

            tvValor = TextView(contexto).apply {
                setTextColor(Color.WHITE)
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
            }
            tvDetalhe = TextView(contexto).apply {
                setTextColor(0xFFB9C7DA.toInt())
                textSize = 12f
            }
            tvVeredito = TextView(contexto).apply {
                setTextColor(tinta)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(5), 0, 0)
            }
            tvTaxas = TextView(contexto).apply {
                setTextColor(0xFF8A9AB4.toInt())
                textSize = 11f
                setPadding(0, dp(3), 0, 0)
            }

            raiz.addView(tvValor)
            raiz.addView(tvDetalhe)
            raiz.addView(tvVeredito)
            raiz.addView(tvTaxas)

            card = raiz
        }

        (card?.background as? GradientDrawable)?.let {
            it.setColor(fundo)
            it.setStroke(dp(1), tinta)
        }

        tvValor?.text = "R$ $valorTxt"
        tvDetalhe?.text = if (km > 0) "$kmTxt km · $minTxt min" else "distância não lida"
        tvVeredito?.apply {
            text = veredito
            setTextColor(tinta)
        }
        tvTaxas?.text = "R$ ${String.format("%.2f", porKm).replace(".", ",")}/km · " +
            if (min > 0) "R$ ${String.format("%.2f", porHora).replace(".", ",")}/h" else "—"

        val gerenciador = janela ?: return
        val atual = card ?: return

        if (atual.parent == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = dp(110)
            try {
                gerenciador.addView(atual, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        principal.removeCallbacks(tarefaEsconder)
        principal.postDelayed(tarefaEsconder, 9000)
    }

    private fun removerCard() {
        val atual = card ?: return
        try {
            if (atual.parent != null) janela?.removeView(atual)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onInterrupt() {
        removerCard()
    }

    override fun onDestroy() {
        principal.removeCallbacksAndMessages(null)
        removerCard()
        card = null
        super.onDestroy()
    }
}
