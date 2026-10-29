package co.giguclone

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
import kotlin.math.abs
import kotlin.math.min

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
            "R\\$\\s*([0-9]{1,3}(?:\\.[0-9]{3})*,[0-9]{2}|[0-9]{1,4}(?:[.,][0-9]{2}))"
        )
        private val RE_SOLTO = Regex("^([0-9]{1,3}[.,][0-9]{2})$")
        private val RE_SIMBOLO = Regex("^R\\$:?$")
        private val RE_KM = Regex(
            "([0-9]{1,3}(?:[.,][0-9]{1,2})?)\\s*(?:km|quilometros|quilômetros)",
            RegexOption.IGNORE_CASE
        )
        private val RE_MIN = Regex(
            "([0-9]{1,3})\\s*(?:min|minutos?)",
            RegexOption.IGNORE_CASE
        )
        private val RE_LIXO = Regex(
            "saldo|carteira|ganhos|hoje|semana|conta|promo|cash|taxa|total",
            RegexOption.IGNORE_CASE
        )
        private val RE_PURO = Regex("^R?\\$?\\s*[0-9.,]+$")
    }

    private data class No(
        val texto: String,
        val x: Int,
        val y: Int,
        val largura: Int,
        val altura: Int
    ) {
        val centroY: Int get() = y + altura / 2
    }

    private data class Candidato(val valor: Double, val no: No, val pontos: Double)

    private val principal = Handler(Looper.getMainLooper())
    private val tarefaVarrer = Runnable { varrer() }
    private val tarefaEsconder = Runnable { removerCard() }

    private var janela: WindowManager? = null
    private var card: View? = null
    private var tvValor: TextView? = null
    private var tvDetalhe: TextView? = null
    private var tvVeredito: TextView? = null
    private var tvTaxas: TextView? = null
    private var tvDebug: TextView? = null

    private var tentativas = 0
    private var ultimoPublicado = ""
    private var momentoPublicado = 0L

    private val prefs by lazy { getSharedPreferences("gigu", Context.MODE_PRIVATE) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        janela = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pacote = event?.packageName?.toString() ?: return

        // Diagnóstico: registra TODO pacote visto, mesmo fora da whitelist,
        // pra dar pra descobrir no app se o popup é de um pacote diferente
        // do esperado (ex: processo separado do app principal).
        registrarPacoteVisto(pacote)

        if (pacote !in PACOTES) return
        principal.removeCallbacks(tarefaVarrer)
        principal.postDelayed(tarefaVarrer, 200)
    }

    private fun registrarPacoteVisto(pacote: String) {
        val atuais = prefs.getString("diagPacotes", "") ?: ""
        val lista = atuais.split(",").filter { it.isNotBlank() }.toMutableList()
        if (lista.remove(pacote)) {
            // já visto: só reordena pra frente
        }
        lista.add(0, pacote)
        val recorte = lista.distinct().take(6)
        prefs.edit().putString("diagPacotes", recorte.joinToString(",")).apply()
    }

    private fun varrer() {
        val nos = ArrayList<No>()

        // Varre todas as janelas visíveis, não só a "ativa": o popup de nova
        // corrida costuma abrir numa janela separada (overlay), que não entra
        // em rootInActiveWindow sem isto.
        val janelasAbertas = windows
        if (janelasAbertas != null && janelasAbertas.isNotEmpty()) {
            for (w in janelasAbertas) {
                val r = w.root ?: continue
                coletar(r, nos, 0)
            }
        } else {
            val raiz = rootInActiveWindow ?: return
            coletar(raiz, nos, 0)
        }

        // Diagnóstico: quantas janelas/nós foram varridos nesta rodada, e quais
        // nós "parecem" dinheiro antes de qualquer filtro de lixo/faixa/posição.
        // Cobre os 3 níveis de detecção: "R$ X,XX" junto, símbolo "R$" separado
        // do número, e números soltos sem símbolo nenhum.
        val brutos = nos.filter { RE_DINHEIRO.containsMatchIn(it.texto) }
        val simbolosVistos = nos.filter { RE_SIMBOLO.matches(it.texto.trim()) }
        val soltosVistos = nos.filter { RE_SOLTO.matches(it.texto.trim()) }
        val amostra = (brutos + simbolosVistos + soltosVistos).distinct()
        // Dump bruto: TODOS os textos vistos nesta rodada, sem filtro nenhum,
        // pra confirmar se o popup de oferta tem algum texto legível ou se
        // está totalmente fora da árvore de acessibilidade (ex: desenhado
        // como imagem/canvas, não como view de texto nativa).
        val dumpTudo = nos.map { it.texto.take(28).replace("\n", " ") }.distinct()
        prefs.edit()
            .putInt("diagJanelas", janelasAbertas?.size ?: 0)
            .putInt("diagNos", nos.size)
            .putInt("diagBrutos", brutos.size)
            .putInt("diagSimbolos", simbolosVistos.size)
            .putInt("diagSoltos", soltosVistos.size)
            .putString("diagTextos", amostra.take(6).joinToString(" / ") { it.texto.take(40) })
            .putString("diagDump", dumpTudo.take(40).joinToString(" | "))
            .putLong("diagMomento", System.currentTimeMillis())
            .apply()

        if (nos.isEmpty()) return

        val tela = resources.displayMetrics
        val alturaTela = tela.heightPixels
        val larguraTela = tela.widthPixels
        if (alturaTela <= 0) return

        val faixaTopo = (alturaTela * 0.12).toInt()
        val raio = dp(300)
        val raioLateral = dp(90)

        fun pontuar(no: No, bonusExtra: Double): Double {
            val vizinhos = nos.count { outro ->
                (RE_KM.containsMatchIn(outro.texto) || RE_MIN.containsMatchIn(outro.texto)) &&
                    abs(outro.centroY - no.centroY) <= raio
            }
            val puro = RE_PURO.matches(no.texto.trim())
            var pontos = vizinhos * 12.0
            if (puro) pontos += 8.0
            pontos += min(no.altura, dp(80)) / dp(10).toDouble()
            pontos -= abs(no.centroY - alturaTela / 2) / (alturaTela * 0.12)
            pontos -= abs(no.x + no.largura / 2 - larguraTela / 2) / (larguraTela * 0.35)
            if (no.centroY < faixaTopo) pontos -= 10.0
            pontos += bonusExtra
            return pontos
        }

        val dinheiro = ArrayList<Candidato>()

        // Nível 1: "R$ 12,50" inteiro no mesmo nó de texto (caso comum).
        for (no in nos) {
            if (RE_LIXO.containsMatchIn(no.texto)) continue
            if (no.centroY < faixaTopo * 0.5) continue

            val achado = RE_DINHEIRO.find(no.texto) ?: continue
            val valor = converter(achado.groupValues[1]) ?: continue
            if (valor < 2.0 || valor > 500.0) continue

            dinheiro.add(Candidato(valor, no, pontuar(no, 0.0)))
        }

        // Nível 2: símbolo "R$" e o número vêm em nós separados (comum em telas
        // que estilizam o símbolo menor que o valor). Casa um nó "R$" com um
        // nó de número solto bem próximo (mesma linha).
        if (dinheiro.isEmpty()) {
            val simbolos = nos.filter { RE_SIMBOLO.matches(it.texto.trim()) }
            val soltos = nos.filter { RE_SOLTO.matches(it.texto.trim()) }
            for (simbolo in simbolos) {
                val par = soltos
                    .filter { abs(it.centroY - simbolo.centroY) <= dp(20) }
                    .minByOrNull { abs(it.x - (simbolo.x + simbolo.largura)) } ?: continue
                val valor = converter(par.texto.trim()) ?: continue
                if (valor < 2.0 || valor > 500.0) continue
                dinheiro.add(Candidato(valor, par, pontuar(par, 6.0)))
            }
        }

        // Nível 3: nenhum símbolo "R$" em texto (pode ser ícone/imagem). Aceita
        // um número solto plausível, mas só se tiver km ou min por perto —
        // evidência de que é uma oferta de corrida, não avaliação/outro número.
        if (dinheiro.isEmpty()) {
            for (no in nos) {
                if (!RE_SOLTO.matches(no.texto.trim())) continue
                val valor = converter(no.texto.trim()) ?: continue
                if (valor < 2.0 || valor > 500.0) continue
                val temVizinho = nos.any { outro ->
                    (RE_KM.containsMatchIn(outro.texto) || RE_MIN.containsMatchIn(outro.texto)) &&
                        abs(outro.centroY - no.centroY) <= raio
                }
                if (!temVizinho) continue
                dinheiro.add(Candidato(valor, no, pontuar(no, -4.0)))
            }
        }

        val escolhido = dinheiro.maxByOrNull { it.pontos } ?: run {
            tentativas = 0
            return
        }

        val kmTotal = somarVizinhos(nos, escolhido.no.centroY, raio, raioLateral, RE_KM, 0.1, 200.0)
        val minTotal = somarVizinhos(nos, escolhido.no.centroY, raio, raioLateral, RE_MIN, 0.0, 240.0)

        val valorTxt = String.format("%.2f", escolhido.valor).replace(".", ",")
        val kmTxt = String.format("%.1f", kmTotal).replace(".", ",")
        val minTxt = String.format("%.0f", minTotal)

        tentativas++
        val marca = "$valorTxt|$kmTxt|$minTxt"
        if (tentativas < 2) {
            principal.removeCallbacks(tarefaVarrer)
            principal.postDelayed(tarefaVarrer, 320)
            return
        }
        tentativas = 0

        val agora = System.currentTimeMillis()
        if (marca == ultimoPublicado && agora - momentoPublicado < 2500) return
        ultimoPublicado = marca
        momentoPublicado = agora

        aoReceber?.invoke(valorTxt, kmTxt, minTxt)

        if (prefs.getBoolean("overlay", true)) {
            mostrarCard(
                escolhido.valor, kmTotal, minTotal, valorTxt, kmTxt, minTxt,
                escolhido.no.texto
            )
        }
    }

    private fun coletar(node: AccessibilityNodeInfo?, saida: MutableList<No>, nivel: Int) {
        if (node == null || nivel > 30) return

        val texto = node.text?.toString()
        if (!texto.isNullOrBlank()) {
            val retangulo = Rect()
            node.getBoundsInScreen(retangulo)
            if (retangulo.width() > 0 && retangulo.height() > 0) {
                saida.add(
                    No(texto, retangulo.left, retangulo.top, retangulo.width(), retangulo.height())
                )
            }
        }

        for (i in 0 until node.childCount) {
            coletar(node.getChild(i), saida, nivel + 1)
        }
    }

    private fun somarVizinhos(
        nos: List<No>,
        ancoraY: Int,
        raioVertical: Int,
        raioLateral: Int,
        regex: Regex,
        minimo: Double,
        maximo: Double
    ): Double {
        var total = 0.0
        val centroX = resources.displayMetrics.widthPixels / 2
        for (no in nos) {
            if (abs(no.centroY - ancoraY) > raioVertical) continue
            if (abs(no.x + no.largura / 2 - centroX) > raioLateral * 4) continue
            val achado = regex.find(no.texto) ?: continue
            val numero = converter(achado.groupValues[1]) ?: continue
            if (numero < minimo || numero > maximo) continue
            total += numero
        }
        return total
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
        minTxt: String,
        bruto: String
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
            tvDebug = TextView(contexto).apply {
                setTextColor(0xFF5E6E88.toInt())
                textSize = 9f
                setPadding(0, dp(4), 0, 0)
            }

            raiz.addView(tvValor)
            raiz.addView(tvDetalhe)
            raiz.addView(tvVeredito)
            raiz.addView(tvTaxas)
            raiz.addView(tvDebug)

            card = raiz
        }

        (card?.background as? GradientDrawable)?.let {
            it.setColor(fundo)
            it.setStroke(dp(1), tinta)
        }

        tvValor?.text = "R$ $valorTxt"
        tvDetalhe?.text = when {
            km > 0 && min > 0 -> "$kmTxt km · $minTxt min"
            km > 0 -> "$kmTxt km"
            else -> "distância não lida"
        }
        tvVeredito?.apply {
            text = veredito
            setTextColor(tinta)
        }
        tvTaxas?.text = "R$ ${String.format("%.2f", porKm).replace(".", ",")}/km · " +
            if (min > 0) "R$ ${String.format("%.2f", porHora).replace(".", ",")}/h" else "—"
        tvDebug?.text = if (prefs.getBoolean("debug", false)) bruto else ""
        tvDebug?.visibility = if (prefs.getBoolean("debug", false)) View.VISIBLE else View.GONE

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
