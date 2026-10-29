package co.giguclone

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var pendente: PermissionRequest? = null

    private val pedirPermissao =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resultado ->
            val liberado = resultado.values.all { it }
            val req = pendente
            if (req != null) {
                if (liberado) req.grant(req.resources) else req.deny()
                pendente = null
            }
        }

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        web = WebView(this)
        web.setBackgroundColor(0xFF070D1A.toInt())
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val falta = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED

                if (falta) {
                    pendente = request
                    pedirPermissao.launch(arrayOf(Manifest.permission.CAMERA))
                } else {
                    request.grant(request.resources)
                }
            }
        }

        web.addJavascriptInterface(Ponte(), "Android")
        web.loadUrl("file:///android_asset/index.html")

        GiguAccessibilityService.aoReceber = { valor, km, min ->
            runOnUiThread {
                val js = "window.ofertaRecebida && window.ofertaRecebida(" +
                    JSONObject.quote(valor) + "," +
                    JSONObject.quote(km) + "," +
                    JSONObject.quote(min) + ")"
                if (this::web.isInitialized) web.evaluateJavascript(js, null)
            }
        }
    }

    private fun acessibilidadeLigada(): Boolean {
        val ligados = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return ligados.contains(packageName)
    }

    inner class Ponte {

        @JavascriptInterface
        fun salvar(nome: String, base64: String) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val valores = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, nome)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/webm")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GigU")
                    }
                }
                val destino: Uri? = contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, valores
                )
                destino?.let {
                    contentResolver.openOutputStream(it)?.use { saida -> saida.write(bytes) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun config(custoKm: String, metaKm: String, metaH: String, overlay: Boolean) {
            getSharedPreferences("gigu", Context.MODE_PRIVATE).edit()
                .putFloat("custoKm", custoKm.replace(",", ".").toFloatOrNull() ?: 1.2f)
                .putFloat("metaKm", metaKm.replace(",", ".").toFloatOrNull() ?: 1.8f)
                .putFloat("metaH", metaH.replace(",", ".").toFloatOrNull() ?: 25f)
                .putBoolean("overlay", overlay)
                .apply()
        }

        @JavascriptInterface
        fun acessibilidadeAtiva(): Boolean = acessibilidadeLigada()

        @JavascriptInterface
        fun abrirAcessibilidade() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    override fun onDestroy() {
        GiguAccessibilityService.aoReceber = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) web.goBack()
        else super.onBackPressed()
    }
}
