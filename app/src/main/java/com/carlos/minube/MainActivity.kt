package com.carlos.minube

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.view.animation.PathInterpolator
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.carlos.minube.net.Connectivity
import com.carlos.minube.net.Connectivity.Estado
import com.carlos.minube.share.UploadWorker
import com.carlos.minube.update.Updater
import com.carlos.minube.web.Downloads
import com.carlos.minube.web.MiNubeChromeClient
import com.carlos.minube.web.MiNubeWebClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity(), MiNubeWebClient.Listener, MiNubeChromeClient.Listener {

    companion object {
        const val ACTION_SUBIR = "com.carlos.minube.SUBIR_COMPARTIDO"
        const val ACTION_BUSCAR_ACTUALIZACION = "com.carlos.minube.BUSCAR_ACTUALIZACION"
        const val EXTRA_LOTE = "lote"
        const val EXTRA_N = "n"

        /** Lo consulta InstallReceiver: solo se puede abrir el diálogo de instalación con la app en pantalla. */
        @Volatile
        var enPrimerPlano = false

        private const val RECOMPROBAR_TRAS_MS = 5 * 60_000L
        private const val TIMEOUT_CARGA_MS = 25_000L

        /** Margen para que el túnel de Tailscale acabe de enrutar antes de dar la conexión por fallida. */
        private const val MARGEN_TUNEL_MS = 20_000L
        private val ESPERAS_TUNEL_MS = longArrayOf(500, 1000, 1500, 2000)
        private const val REINTENTOS_WEB = 2          // recargas automáticas si la web falla justo tras conectar
        private const val ASENTAR_MAX_MS = 1_500L     // tope de espera a que la web esté quieta antes de la entrada animada tras un error

        /** Marca window.__nubeQuieta cuando la web lleva ~12 fotogramas seguidos fluidos (sin tareas largas ocupando el hilo). */
        private const val JS_QUIETA = """(function(){window.__nubeQuieta=false;var n=0,last=performance.now();
function t(){var now=performance.now();if(now-last>40)n=0;else n++;last=now;if(n>=12){window.__nubeQuieta=true;return;}requestAnimationFrame(t);}
requestAnimationFrame(t);})()"""

        /** Token y fuente: contrato window.MiNube (v6); si no existe, las variables globales de la v5. */
        private const val JS_SESION = """(function(){try{
if(window.MiNube){var t=MiNube.token();return t?[t,(MiNube.source&&MiNube.source())||'Archivos']:null;}
if(typeof TOKEN!=='undefined'&&TOKEN&&!document.querySelector('#app').hidden){return [TOKEN,(typeof CONFIG!=='undefined'&&CONFIG.source)||'Archivos'];}
return null;}catch(e){return null;}})()"""

        private const val JS_REFRESCAR = """(function(){try{
if(window.MiNube&&MiNube.refresh){MiNube.refresh();return;}
if(typeof go==='function'&&typeof S!=='undefined'&&S.mode==='browse')go(S.path,{silent:true});}catch(e){}})()"""

        /** Color real de la cabecera de la web (vale para oklab/color-mix): se pinta igual la zona de la barra de estado. */
        private const val JS_COLOR = """(function(){try{
var cv=document.createElement('canvas');cv.width=cv.height=1;var x=cv.getContext('2d');
function rgba(c){x.clearRect(0,0,1,1);x.fillStyle='#000';x.fillStyle=c;x.fillRect(0,0,1,1);return x.getImageData(0,0,1,1).data;}
var el=document.elementFromPoint(innerWidth/2,2)||document.body;
while(el){var c=getComputedStyle(el).backgroundColor;if(c&&c!=='transparent'&&!/,\s*0\)$/.test(c)){var d=rgba(c);if(d[3]>200)return [d[0],d[1],d[2]];}el=el.parentElement;}
var d2=rgba(getComputedStyle(document.documentElement).backgroundColor||'#fff');return [d2[0],d2[1],d2[2]];}catch(e){return null;}})()"""
    }

    private val serverUrl = BuildConfig.SERVER_URL
    private val serverHost: String? = Uri.parse(serverUrl).host

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var gateTitulo: TextView
    private lateinit var conexion: View
    private lateinit var conexionPanel: View
    private lateinit var conexionScroll: View
    private lateinit var reintentarSpinner: View
    private lateinit var conexionFondo: View
    private lateinit var conexionLogo: View
    private lateinit var conexionSpinner: View
    private lateinit var conexionTexto: TextView
    private lateinit var gateMensaje: TextView
    private lateinit var gateVersion: TextView
    private lateinit var btnReintentar: Button
    private lateinit var btnTailscale: Button
    private lateinit var btnActualizar: Button
    private lateinit var shareBar: TextView
    private lateinit var fullscreen: FrameLayout
    private lateinit var updater: Updater
    private lateinit var webClient: MiNubeWebClient

    private var webCargada = false        // se ha pedido cargar la web al menos una vez
    private var webLista = false          // la web terminó de cargar sin error
    private var errorPrincipal = false
    private var mostrandoError = false
    private var primeraConexion = true
    private var reintentosWeb = 0
    private var vieneDeError = false      // se mostró el error: la entrada animada espera un momento a que todo se asiente
    private var yaRevelada = false        // la entrada animada ya se hizo: tras un error se vuelve a la web sin repetirla
    private var desdeTailscale = false    // se salió a Tailscale: al volver, la VPN puede tardar un poco en figurar activa
    private val despertar = Channel<Unit>(Channel.CONFLATED)   // «Reintentar» durante la espera entre intentos
    private var revelando = false
    private var preparando = false
    private var latido: ObjectAnimator? = null
    private val avisoLento = Runnable { if (conexion.visibility == View.VISIBLE && !revelando) conexionTexto.setText(R.string.conectando_lento) }
    private val suave = PathInterpolator(0.16f, 1f, 0.3f, 1f)   // desaceleración exponencial: llega con calma
    private var comprobando: Job? = null
    private var timeoutCarga: Job? = null
    private var pausadaDesde = 0L
    private var colorActual = 0
    // Alto de la barra de estado y de la de navegación en px CSS (los lee la web al arrancar, por el puente)
    @Volatile private var insetArriba = 0f
    @Volatile private var insetAbajo = 0f

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private var loteEsperando: String? = null
    private var loteN = 0
    private var esperaSesion: Job? = null
    private var descargaPendiente: (() -> Unit)? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val ocultarBarra = Runnable { shareBar.visibility = View.GONE }

    // ---------- resultados de otras pantallas ----------

    private val elegirArchivosLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = fileCallback
        fileCallback = null
        cb?.onReceiveValue(urisDe(r.resultCode, r.data))
    }

    private val permisoAlmacenamiento = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val accion = descargaPendiente
        descargaPendiente = null
        if (ok) accion?.invoke() else toast(getString(R.string.descarga_fallo))
    }

    private val permisoNotificaciones = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // ---------- ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // La entrada es clara aunque el sistema esté en oscuro: iconos oscuros en las barras hasta que la web diga su color
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        if (Build.VERSION.SDK_INT >= 31) {
            // El splash del sistema ya muestra el mismo logo en el mismo sitio: se funde hacia la pantalla de conexión
            splashScreen.setOnExitAnimationListener { sp ->
                sp.animate().alpha(0f).setDuration(220).withEndAction { sp.remove() }.start()
            }
        }

        root = findViewById(R.id.root)
        web = findViewById(R.id.web)
        gateTitulo = findViewById(R.id.gateTitulo)
        conexion = findViewById(R.id.conexion)
        conexionPanel = findViewById(R.id.conexionPanel)
        conexionScroll = findViewById(R.id.conexionScroll)
        reintentarSpinner = findViewById(R.id.reintentarSpinner)
        conexionFondo = findViewById(R.id.conexionFondo)
        conexionLogo = findViewById(R.id.conexionLogo)
        conexionSpinner = findViewById(R.id.conexionSpinner)
        conexionTexto = findViewById(R.id.conexionTexto)
        gateMensaje = findViewById(R.id.gateMensaje)
        gateVersion = findViewById(R.id.gateVersion)
        btnReintentar = findViewById(R.id.btnReintentar)
        btnTailscale = findViewById(R.id.btnTailscale)
        btnActualizar = findViewById(R.id.btnActualizar)
        shareBar = findViewById(R.id.shareBar)
        fullscreen = findViewById(R.id.fullscreen)

        aplicarInsets()
        com.carlos.minube.share.ShareCache.limpiarAntiguos(this)   // lotes de más de 24 h
        Notifs.crearCanales(this)
        updater = Updater(this)
        // Los avisos de actualización los pinta la web (uno solo, mismo estilo); si no puede, aviso del sistema
        updater.avisador = { msg, alternativa ->
            if (webLista && !mostrandoError) {
                web.evaluateJavascript("(window.MiNubeAviso?MiNubeAviso(${JSONObject.quote(msg)}):false)") { r -> if (r != "true") alternativa() }
            } else alternativa()
        }
        configurarWebView()

        btnReintentar.setOnClickListener { comprobarYCargar(manual = true) }
        btnTailscale.setOnClickListener { abrirTailscale() }
        btnActualizar.setOnClickListener { updater.comprobar(manual = true) }
        shareBar.setOnClickListener { shareBar.visibility = View.GONE }
        gateVersion.text = getString(R.string.version_actual, BuildConfig.VERSION_NAME)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> ocultarPantallaCompleta(desdeWeb = false)
                    webLista && !mostrandoError && web.canGoBack() -> web.goBack()   // la web decide (popstate)
                    else -> finish()
                }
            }
        })

        registrarAtajo()
        escucharRed()
        sincronizarColorPeriodicamente()
        pedirNotificacionesUnaVez()

        comprobarYCargar()
        procesarIntent(intent)
        updater.comprobarAlArrancar()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        procesarIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        enPrimerPlano = true
        web.onResume()
        web.resumeTimers()
        updater.alVolver()
        val fuera = if (pausadaDesde > 0) SystemClock.elapsedRealtime() - pausadaDesde else 0
        pausadaDesde = 0
        when {
            mostrandoError -> comprobarYCargar()                         // p. ej. al volver de Tailscale
            webLista && fuera > RECOMPROBAR_TRAS_MS -> verificarSinTapar()
            !webLista && comprobando?.isActive != true -> {              // la comprobación se cortó en onStop
                if (!webCargada) comprobarYCargar() else if (errorPrincipal) diagnosticarYMostrar()
            }
        }
    }

    override fun onStop() {
        // Nada de peticiones en segundo plano: si se sale de la app mientras reintenta, se corta y los botones quedan normales
        comprobando?.cancel(); comprobando = null
        mostrarComprobando(false)
        super.onStop()
    }

    override fun onPause() {
        enPrimerPlano = false
        pausadaDesde = SystemClock.elapsedRealtime()
        web.onPause()
        web.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        updater.liberar()
        shareBar.removeCallbacks(ocultarBarra)
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    // ---------- intents (Compartir y atajo «Buscar actualización») ----------

    private fun procesarIntent(i: Intent?) {
        when (i?.action) {
            ACTION_SUBIR -> {
                val lote = i.getStringExtra(EXTRA_LOTE) ?: return
                loteEsperando = lote
                loteN = i.getIntExtra(EXTRA_N, 0)
                i.action = Intent.ACTION_MAIN        // no repetir el mismo lote
                esperarSesionYSubir()
            }
            ACTION_BUSCAR_ACTUALIZACION -> {
                i.action = Intent.ACTION_MAIN
                updater.comprobar(manual = true)
            }
        }
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configurarWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true                 // preferencias y sesión guardada (localStorage)
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)                    // la web tiene sus propios gestos
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            userAgentString = "$userAgentString MiNubeApp/${BuildConfig.VERSION_NAME}"
        }
        web.addJavascriptInterface(Puente(), "MiNubeAndroid")
        webClient = MiNubeWebClient(serverHost, this)
        web.webViewClient = webClient
        web.webChromeClient = MiNubeChromeClient(this)
        web.setDownloadListener { url, ua, cd, mime, _ ->
            val accion = { if (Downloads.iniciar(this, url, ua, cd, mime, serverHost) == null) toast(getString(R.string.descarga_fallo)) }
            if (Build.VERSION.SDK_INT < 29 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                descargaPendiente = accion
                permisoAlmacenamiento.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                accion()
            }
        }
    }

    /**
     * Puente con la web (window.MiNubeAndroid), usado por el menú inferior de index-quantum-v6.html.
     * Los métodos corren en el hilo «JavaBridge»: todo lo que toca el WebView o la UI va dentro de runOnUiThread,
     * y solo se atiende si la página cargada es la de nuestro servidor.
     */
    private inner class Puente {
        /** El puente solo atiende a nuestra propia web (no a otras páginas que pudieran cargarse). */
        private val ok get() = webClient.enServidor

        @JavascriptInterface
        fun version(): String = if (ok) BuildConfig.VERSION_NAME else ""

        /**
         * Teclado a la orden de la web. En un WebView, ocultar o desenfocar un campo desde JS no siempre cierra el teclado
         * (se quedaba abierto sobre la app al entrar) ni abrirlo desde un temporizador lo abre (pierde el gesto del usuario).
         */
        @JavascriptInterface
        fun teclado(mostrar: Boolean) {
            if (!ok) return
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val imm = getSystemService(InputMethodManager::class.java)
                if (mostrar) {
                    web.requestFocus()
                    imm?.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
                } else {
                    WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
                    imm?.hideSoftInputFromWindow(web.windowToken, 0)
                }
            }
        }

        /** La web, al verlo, deja su entrada preparada para que la app la dispare con MiNubeEntrada(). */
        @JavascriptInterface
        fun entrada(): Boolean = ok

        /** «arriba,abajo» en px CSS: la web lo lee al arrancar para no dar un salto antes de onPageFinished. */
        @JavascriptInterface
        fun insets(): String = if (ok) "$insetArriba,$insetAbajo" else "0,0"

        /** Persona recordada («Recordar a X»): se guarda aquí y no solo en el localStorage del WebView, que puede perderse. */
        @JavascriptInterface
        fun persona(): String = if (!ok) "" else getSharedPreferences("minube", MODE_PRIVATE).getString("persona", "") ?: ""

        @JavascriptInterface
        fun guardarPersona(id: String) {
            if (!ok) return
            val limpio = id.take(40).filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            getSharedPreferences("minube", MODE_PRIVATE).edit { putString("persona", limpio) }
        }

        @JavascriptInterface
        fun buscarActualizaciones() {
            if (!ok) return
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val host = web.url?.let { Uri.parse(it).host }
                if (host != null && serverHost != null && host.equals(serverHost, ignoreCase = true)) {
                    updater.comprobar(manual = true)
                }
            }
        }
    }

    /**
     * Comprueba la conexión y, si responde, carga la web. Solo hay una comprobación a la vez: los avisos automáticos
     * (onResume, red disponible) se ignoran si ya hay una en marcha, y un «Reintentar» manual despierta la espera
     * entre intentos para lanzar el siguiente al instante (sin duplicar corrutinas).
     *
     * Si Tailscale va por buen camino pero el túnel aún no enruta (servidor sin respuesta, o VPN que tarda en figurar
     * activa tras volver de Tailscale) se reintenta unos 8 s antes de dar el error. Si ya se está mostrando el error,
     * no se cambia de pantalla: solo el botón pasa a «Comprobando…».
     */
    private fun comprobarYCargar(manual: Boolean = false) {
        if (comprobando?.isActive == true) {
            if (manual) despertar.trySend(Unit)
            return
        }
        if (manual) reintentosWeb = 0
        despertar.tryReceive()                       // descarta un despertar viejo
        if (mostrandoError) mostrarComprobando(true) else mostrarConectando()
        comprobando = lifecycleScope.launch {
            val inicio = SystemClock.elapsedRealtime()
            var intento = 0
            var e: Estado
            while (true) {
                e = Connectivity.comprobar(this@MainActivity, serverUrl, if (intento == 0) 2500 else 4000)
                val tunelEnCamino = e == Estado.SERVIDOR_NO_RESPONDE || (e == Estado.TAILSCALE_APAGADO && desdeTailscale)
                val espera = ESPERAS_TUNEL_MS[minOf(intento, ESPERAS_TUNEL_MS.size - 1)]
                if (e == Estado.OK || !tunelEnCamino || SystemClock.elapsedRealtime() - inicio + espera > MARGEN_TUNEL_MS) break
                mostrarComprobando(true, puedeReintentar = true)   // durante la espera, Reintentar adelanta el siguiente intento
                withTimeoutOrNull(espera) { despertar.receive() }
                mostrarComprobando(true)
                intento++
            }
            desdeTailscale = false
            if (e == Estado.OK) {
                // El servidor responde: se deja de estar «en error» (si no, onPaginaTerminada no mostraría la web y la
                // pantalla se quedaría en «Comprobando…»). El panel sigue a la vista hasta que la web esté lista.
                mostrandoError = false
                cargarWeb()
            } else {
                mostrarError(e)
            }
        }
    }

    /** Botón «Reintentar» del panel de error: «Comprobando…» con indicador mientras hay una comprobación. */
    private fun mostrarComprobando(activo: Boolean, puedeReintentar: Boolean = false) {
        if (conexionPanel.visibility != View.VISIBLE) return
        btnReintentar.setText(if (activo) R.string.btn_comprobando else R.string.btn_reintentar)
        // Mientras comprueba, el mensaje de error anterior (p. ej. «Tailscale está apagado») ya no es cierto
        if (activo) gateMensaje.setText(R.string.gate_comprobando)
        btnReintentar.isEnabled = !activo || puedeReintentar
        btnReintentar.alpha = if (activo) 0.85f else 1f
        reintentarSpinner.visibility = if (activo) View.VISIBLE else View.GONE
    }

    private fun cargarWeb() {
        if (webCargada && webLista) { mostrarWeb(); return }
        webCargada = true
        webLista = false
        errorPrincipal = false
        // no-cache: revalida el HTML para ver al momento lo último subido al servidor (miniaturas, etc. siguen en caché)
        web.loadUrl("$serverUrl/", mapOf("Cache-Control" to "no-cache"))
        if (conexion.visibility == View.VISIBLE) { web.alpha = 0.01f; web.visibility = View.VISIBLE }   // se calienta ya, tapada por la conexión
        timeoutCarga?.cancel()
        timeoutCarga = lifecycleScope.launch {
            delay(TIMEOUT_CARGA_MS)
            if (!webLista && !mostrandoError) diagnosticarYMostrar()
        }
    }

    /** Al volver tras un rato: si el servidor ya no contesta se avisa, sin recargar ni tapar la web si todo va bien. */
    private fun verificarSinTapar() {
        lifecycleScope.launch {
            val e = Connectivity.comprobar(this@MainActivity, serverUrl)
            if (e != Estado.OK) mostrarError(e)
        }
    }

    private fun diagnosticarYMostrar() {
        comprobando?.cancel()
        comprobando = lifecycleScope.launch {
            val e = Connectivity.comprobar(this@MainActivity, serverUrl)
            mostrarError(if (e == Estado.OK) Estado.SERVIDOR_NO_RESPONDE else e)
        }
    }

    // MiNubeWebClient.Listener
    override fun onPaginaEmpieza() {
        errorPrincipal = false
    }

    override fun onPaginaTerminada() {
        if (errorPrincipal) return
        webLista = true
        reintentosWeb = 0
        timeoutCarga?.cancel()
        enviarInsets()
        if (!mostrandoError) mostrarWeb()
        sincronizarColor()
    }

    override fun onErrorPrincipal() {
        errorPrincipal = true
        webLista = false
        timeoutCarga?.cancel()
        // Recién conectado el túnel, la primera carga puede fallar aunque la comprobación diera OK: se reintenta sola
        if (reintentosWeb < REINTENTOS_WEB && comprobando?.isActive != true) {
            reintentosWeb++
            comprobarYCargar()
            return
        }
        diagnosticarYMostrar()
    }

    override fun abrirFuera(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.abrir_enlace_fallo))
        }
    }

    override fun onRendererMuerto() {
        // El motor de la web se cerró (poca memoria): se rehace la pantalla; la v6 recupera la sesión sola
        recreate()
    }

    // MiNubeChromeClient.Listener
    override fun elegirArchivos(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        val tipos = params.acceptTypes.orEmpty()
            .flatMap { it.split(',') }.map { it.trim() }.filter { it.contains('/') }
        val i = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (tipos.size == 1) tipos[0] else "*/*")
        if (tipos.size > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, tipos.toTypedArray())
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        return try {
            elegirArchivosLauncher.launch(i)
            true
        } catch (e: ActivityNotFoundException) {
            fileCallback = null
            false
        }
    }

    private fun urisDe(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) return Array(clip.itemCount) { clip.getItemAt(it).uri }
        return data.data?.let { arrayOf(it) }
    }

    override fun mostrarPantallaCompleta(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden(); return
        }
        customView = view
        customCallback = callback
        fullscreen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreen.visibility = View.VISIBLE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun ocultarPantallaCompleta(desdeWeb: Boolean) {
        val v = customView ?: return
        customView = null
        fullscreen.removeView(v)
        fullscreen.visibility = View.GONE
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        val cb = customCallback
        customCallback = null
        if (!desdeWeb) cb?.onCustomViewHidden()
    }

    // ---------- puerta de conexión ----------

    /** Conectando: solo el logo, quieto y centrado (igual que el splash); respira despacio y, si tarda, aparece un indicador pequeño. */
    private fun mostrarConectando() {
        mostrandoError = false
        if (webLista) return                       // la web ya está a la vista: no se tapa
        conexionPanel.animate().cancel(); conexionPanel.visibility = View.GONE
        conexionSpinner.visibility = View.VISIBLE
        if (conexion.visibility == View.VISIBLE && !revelando && latido != null) return   // ya se está mostrando
        revelando = false
        conexion.animate().cancel(); conexionFondo.animate().cancel(); conexionLogo.animate().cancel(); conexionSpinner.animate().cancel(); conexionTexto.animate().cancel()
        conexion.visibility = View.VISIBLE
        conexion.alpha = 1f; conexionFondo.alpha = 1f
        conexionLogo.translationY = 0f; conexionLogo.scaleX = 1f; conexionLogo.scaleY = 1f; conexionLogo.alpha = 1f
        web.visibility = View.INVISIBLE
        web.alpha = 1f; preparando = false
        if (primeraConexion && Build.VERSION.SDK_INT < 31) {
            // Sin splash con logo (Android 11 o menos): el logo entra con un fundido en vez de aparecer de golpe
            conexionLogo.alpha = 0f
            conexionLogo.animate().alpha(1f).setDuration(280).setInterpolator(suave).start()
        }
        primeraConexion = false
        // Se ve que está conectando: el indicador y el texto entran enseguida (0,35 s) y, si tarda, el texto avisa de qué mirar
        conexionTexto.removeCallbacks(avisoLento); conexionTexto.setText(R.string.conectando_texto)
        conexionSpinner.alpha = 0f; conexionTexto.alpha = 0f
        conexionSpinner.animate().alpha(1f).setStartDelay(350).setDuration(350).start()
        conexionTexto.animate().alpha(1f).setStartDelay(450).setDuration(350).start()
        conexionTexto.postDelayed(avisoLento, 7000)
        latido?.cancel()
        latido = ObjectAnimator.ofPropertyValuesHolder(
            conexionLogo,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.045f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.045f),
        ).apply {
            duration = 1300; startDelay = 700; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator(); start()
        }
    }

    private fun quitarConexion() {
        latido?.cancel(); latido = null
        conexionTexto.removeCallbacks(avisoLento)
        conexion.animate().cancel(); conexionFondo.animate().cancel(); conexionLogo.animate().cancel(); conexionSpinner.animate().cancel(); conexionTexto.animate().cancel()
        web.alpha = 1f
        conexion.alpha = 1f; conexionSpinner.visibility = View.VISIBLE
        conexionPanel.animate().cancel(); conexionPanel.visibility = View.GONE
        conexion.visibility = View.GONE
        if (revelando) { yaRevelada = true; vieneDeError = false }
        revelando = false; preparando = false
    }

    /**
     * Error: NO se cambia de pantalla. Se queda la de conexión (mismo fondo claro, mismo logo, quieto) y bajo el logo
     * aparece el panel con el mensaje y los botones. Así no hay destellos entre claro y oscuro al reintentar.
     */
    private fun mostrarError(e: Estado) {
        mostrandoError = true
        vieneDeError = true
        latido?.cancel(); latido = null
        conexionTexto.removeCallbacks(avisoLento)
        conexion.animate().cancel(); conexionFondo.animate().cancel(); conexionLogo.animate().cancel()
        conexionSpinner.animate().cancel(); conexionTexto.animate().cancel()
        conexion.visibility = View.VISIBLE
        conexion.alpha = 1f; conexionFondo.alpha = 1f
        conexionLogo.translationY = 0f; conexionLogo.scaleX = 1f; conexionLogo.scaleY = 1f; conexionLogo.alpha = 1f
        conexionSpinner.visibility = View.GONE; conexionSpinner.alpha = 0f
        conexionTexto.alpha = 0f
        revelando = false; preparando = false
        web.visibility = View.INVISIBLE
        web.alpha = 1f
        if (conexionPanel.visibility != View.VISIBLE) {
            conexionScroll.scrollTo(0, 0)
            conexionPanel.alpha = 0f
            conexionPanel.visibility = View.VISIBLE
            conexionPanel.animate().alpha(1f).setStartDelay(0).setDuration(220).start()
        }
        mostrarComprobando(false)
        gateTitulo.setText(R.string.gate_titulo_error)
        gateMensaje.visibility = View.VISIBLE
        gateMensaje.setText(
            when (e) {
                Estado.SIN_CONFIG -> R.string.gate_sin_config
                Estado.SIN_INTERNET -> R.string.gate_sin_internet
                Estado.TAILSCALE_NO_INSTALADO -> R.string.gate_tailscale_falta
                Estado.TAILSCALE_APAGADO -> R.string.gate_tailscale_apagado
                Estado.SERVIDOR_NO_RESPONDE, Estado.OK -> R.string.gate_servidor
            }
        )
        val conTailscale = e == Estado.TAILSCALE_NO_INSTALADO || e == Estado.TAILSCALE_APAGADO || e == Estado.SERVIDOR_NO_RESPONDE
        btnTailscale.visibility = if (conTailscale) View.VISIBLE else View.GONE
        btnTailscale.setText(if (e == Estado.TAILSCALE_NO_INSTALADO) R.string.btn_instalar_tailscale else R.string.btn_abrir_tailscale)
        // La pantalla es siempre clara: iconos oscuros en las barras del sistema, sea cual sea el tema del móvil.
        // colorActual = 0 obliga a recalcular el color de la web cuando vuelva.
        root.setBackgroundColor(getColor(R.color.entrada_fondo))
        colorActual = 0
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    private fun mostrarWeb() {
        mostrandoError = false
        conexionPanel.animate().cancel()
        if (conexion.visibility != View.VISIBLE) { conexionPanel.visibility = View.GONE; web.alpha = 1f; web.visibility = View.VISIBLE; yaRevelada = true; return }
        if (!yaRevelada && conexionPanel.visibility == View.VISIBLE) {
            // Primera entrada tras un error: el panel se funde mientras la web se calienta (no se quita de golpe)
            conexionPanel.animate().alpha(0f).setStartDelay(0).setDuration(150).withEndAction { conexionPanel.visibility = View.GONE }.start()
        } else {
            conexionPanel.visibility = View.GONE
        }
        if (yaRevelada) {
            // Vuelve tras un error (la entrada animada ya se hizo): la pantalla de conexión se funde y aparece la web
            web.alpha = 1f; web.visibility = View.VISIBLE
            conexion.animate().cancel()
            conexion.animate().alpha(0f).setDuration(220).withEndAction { if (!mostrandoError) { quitarConexion(); sincronizarColor() } }.start()
            return
        }
        prepararYRevelar()
    }

    /**
     * Para que el viaje del logo vaya fluido, la web se «calienta» ANTES de empezar: se pone visible (pero tapada por la
     * pantalla de conexión) para que pinte su primer fotograma y decodifique lo suyo; el viaje empieza cuando el WebView
     * avisa de que su estado ya está listo para dibujarse (así el primer fotograma costoso no cae a mitad de la animación).
     * Si el aviso no llega, a los 0,7 s se revela igualmente.
     */
    private fun prepararYRevelar() {
        if (preparando || revelando) return
        preparando = true
        web.alpha = 0.01f                       // casi invisible, pero el WebView sí compone
        web.visibility = View.VISIBLE
        var asentando = false
        lateinit var listo: Runnable
        listo = Runnable {
            if (preparando && !asentando) {
                if (vieneDeError) {
                    // Llegando desde el error (túnel recién activado, red y WebView aún ocupados) se deja un momento
                    // para que el primer fotograma y las cargas pendientes no caigan a mitad de la animación
                    asentando = true
                    esperarWebQuieta { if (preparando) { preparando = false; revelarWeb() } }
                } else {
                    preparando = false; revelarWeb()
                }
            }
        }
        web.postDelayed(listo, 700)
        if (Build.VERSION.SDK_INT >= 23) {
            web.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { web.removeCallbacks(listo); listo.run() }
            })
        }
    }

    /** Espera a que la web esté fluida (o, como mucho, ASENTAR_MAX_MS) antes de lanzar la entrada animada. */
    private fun esperarWebQuieta(alTerminar: () -> Unit) {
        val t0 = SystemClock.elapsedRealtime()
        web.evaluateJavascript(JS_QUIETA, null)
        lateinit var sondeo: Runnable
        sondeo = Runnable {
            if (!preparando) return@Runnable
            web.evaluateJavascript("window.__nubeQuieta===true") { r ->
                if (!preparando) return@evaluateJavascript
                if (r == "true" || SystemClock.elapsedRealtime() - t0 > ASENTAR_MAX_MS) alTerminar() else web.postDelayed(sondeo, 80)
            }
        }
        web.postDelayed(sondeo, 80)
    }

    /**
     * El viaje del logo ya no lo anima la app: lo anima la propia web (una animación de transformación que corre en el
     * compositor, en el mismo reloj que el resto de la entrada). La app solo:
     *  1. funde el indicador y el texto de «conectando»;
     *  2. le dice a la web que empiece (MiNubeEntrada), pasándole la escala actual del logo para que el relevo no se note;
     *  3. cuando el WebView confirma que ya ha pintado el logo en el mismo sitio, retira su pantalla de conexión de golpe.
     * Así no hay dos animaciones en dos relojes distintos ni trabajo de la app durante el viaje.
     */
    private fun revelarWeb() {
        if (revelando) return
        revelando = true
        conexionPanel.animate().cancel(); conexionPanel.visibility = View.GONE
        latido?.cancel(); latido = null
        conexionTexto.removeCallbacks(avisoLento)
        conexionSpinner.animate().cancel(); conexionSpinner.animate().alpha(0f).setStartDelay(0).setDuration(140).start()
        conexionTexto.animate().cancel(); conexionTexto.animate().alpha(0f).setStartDelay(0).setDuration(140).start()
        web.alpha = 1f
        web.visibility = View.VISIBLE
        val escala = conexionLogo.scaleX
        conexion.postDelayed({
            if (!revelando) return@postDelayed
            conexionSpinner.visibility = View.GONE      // un ProgressBar sigue invalidando cada fotograma aunque esté transparente
            web.evaluateJavascript("(window.MiNubeEntrada?MiNubeEntrada($escala):null)") { res ->
                if (!revelando) return@evaluateJavascript
                if (res == "\"v\"" && Build.VERSION.SDK_INT >= 23) {
                    // La web ya tiene su logo volando exactamente donde estaba el nuestro: se retira la conexión
                    // El aviso del WebView dice que su estado está listo, pero el logo nuevo puede no estar aún rasterizado en la GPU:
                    // se deja la conexión 140 ms más (el logo de la web está debajo, en el mismo sitio y quieto, y no empieza a moverse
                    // hasta los 260 ms) para que el relevo no deje un fotograma sin logo.
                    val salvaguarda = Runnable { if (revelando) quitarConexion() }
                    web.postDelayed(salvaguarda, 600)
                    web.postVisualStateCallback(2L, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            web.removeCallbacks(salvaguarda)
                            web.postDelayed({ if (revelando) quitarConexion() }, 140)
                        }
                    })
                } else {
                    // Sin viaje de logo: la conexión se funde y aparece la web
                    conexion.animate().alpha(0f).setDuration(320).setInterpolator(suave).withEndAction { if (revelando) quitarConexion() }.start()
                }
            }
        }, 150)
    }

    private fun abrirTailscale() {
        val i = packageManager.getLaunchIntentForPackage(Connectivity.TAILSCALE_PKG)
        if (i != null) {
            desdeTailscale = true                  // al volver, la VPN puede tardar en figurar activa: se reintenta unos segundos
            startActivity(i); return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Connectivity.TAILSCALE_PKG}")))
        } catch (e: ActivityNotFoundException) {
            abrirFuera(Uri.parse("https://play.google.com/store/apps/details?id=${Connectivity.TAILSCALE_PKG}"))
        }
    }

    private fun escucharRed() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { if (mostrandoError && !isFinishing) comprobarYCargar() }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
    }

    // ---------- insets y colores ----------

    private fun aplicarInsets() {
        val margenBarra = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val teclado = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            // Pantalla completa: la web se dibuja bajo la barra de estado y la de navegación, y recibe su alto
            // como --sat / --sab para apartar de ahí la cabecera y la barra inferior. Los lados (recorte de cámara
            // en horizontal) y el teclado siguen como padding: el teclado nunca tapa el buscador ni los campos.
            v.setPadding(barras.left, 0, barras.right, teclado)
            val abajo = if (teclado > 0) 0 else barras.bottom
            conexionScroll.setPadding(0, 0, 0, abajo)   // el panel de error no pisa la barra de gestos (el logo no se mueve)
            (shareBar.layoutParams as ViewGroup.MarginLayoutParams).topMargin = margenBarra + barras.top
            shareBar.requestLayout()
            val d = resources.displayMetrics.density
            insetArriba = barras.top / d
            insetAbajo = abajo / d
            enviarInsets()
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun enviarInsets() {
        web.evaluateJavascript(
            "(function(s){s.setProperty('--sat','${insetArriba}px');s.setProperty('--sab','${insetAbajo}px')})(document.documentElement.style)",
            null
        )
    }

    private fun sincronizarColorPeriodicamente() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    sincronizarColor()
                    delay(3000)
                }
            }
        }
    }

    private fun sincronizarColor() {
        if (!webLista || mostrandoError || revelando || preparando) return
        web.evaluateJavascript(JS_COLOR) { res ->
            val a = runCatching { JSONArray(res) }.getOrNull() ?: return@evaluateJavascript
            val c = Color.rgb(a.optInt(0), a.optInt(1), a.optInt(2))
            if (c == colorActual) return@evaluateJavascript
            colorActual = c
            root.setBackgroundColor(c)
            val claro = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) > 150
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = claro
                isAppearanceLightNavigationBars = claro
            }
        }
    }

    // ---------- Compartir: esperar sesión → WorkManager ----------

    private fun esperarSesionYSubir() {
        val lote = loteEsperando ?: return
        esperaSesion?.cancel()
        mostrarBarra(resources.getQuantityString(R.plurals.share_entra, loteN, loteN))
        esperaSesion = lifecycleScope.launch {
            while (isActive) {
                if (webLista) {
                    val s = leerSesion()
                    if (s != null) {
                        loteEsperando = null
                        confirmarSubida(lote, s.first, s.second)
                        return@launch
                    }
                }
                delay(1000)
            }
        }
    }

    private suspend fun leerSesion(): Pair<String, String>? {
        val d = CompletableDeferred<String?>()
        web.evaluateJavascript(JS_SESION) { d.complete(it) }
        val res = d.await() ?: return null
        val a = runCatching { JSONArray(res) }.getOrNull() ?: return null
        val token = a.optString(0).takeIf { it.isNotBlank() && it != "null" } ?: return null
        val source = a.optString(1).ifBlank { UploadWorker.SOURCE_POR_DEFECTO }
        return token to source
    }

    /** Nada se sube sin que la persona lo confirme (cualquier app puede usar el menú Compartir). */
    private fun confirmarSubida(lote: String, token: String, source: String) {
        val n = com.carlos.minube.share.ShareCache.archivos(this, lote).size
        if (n == 0 || isFinishing || isDestroyed) { shareBar.visibility = View.GONE; return }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.share_label)
            .setMessage(resources.getQuantityString(R.plurals.share_confirmar, n, n))
            .setPositiveButton(R.string.share_confirmar_si) { _, _ -> encolarSubida(lote, token, source) }
            .setNegativeButton(R.string.share_confirmar_no) { _, _ -> descartarLote(lote) }
            .setOnCancelListener { descartarLote(lote) }
            .show()
    }

    private fun descartarLote(lote: String) {
        com.carlos.minube.share.ShareCache.borrarLote(this, lote)
        mostrarBarra(getString(R.string.share_cancelado), 4000)
    }

    private fun encolarSubida(lote: String, token: String, source: String) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    UploadWorker.K_LOTE to lote,
                    UploadWorker.K_TOKEN to token,
                    UploadWorker.K_SOURCE to source,
                    UploadWorker.K_SERVER to serverUrl,
                )
            )
            .addTag(UploadWorker.TAG)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("subida-$lote", ExistingWorkPolicy.KEEP, req)
        mostrarBarra(getString(R.string.share_subiendo, 1, loteN))
        observarSubida(req.id)
    }

    private fun observarSubida(id: UUID) {
        lifecycleScope.launch {
            WorkManager.getInstance(this@MainActivity).getWorkInfoByIdFlow(id)
                .transformWhile { emit(it); it != null && !it.state.isFinished }
                .collect { info ->
                    if (info == null) return@collect
                    when (info.state) {
                        WorkInfo.State.RUNNING -> {
                            val total = info.progress.getInt(UploadWorker.K_TOTAL, loteN).coerceAtLeast(1)
                            val hechos = info.progress.getInt(UploadWorker.K_HECHOS, 0)
                            mostrarBarra(getString(R.string.share_subiendo, minOf(hechos + 1, total), total))
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            mostrarBarra(getString(R.string.share_ok), 5000)
                            if (webLista) web.evaluateJavascript(JS_REFRESCAR, null)
                        }
                        WorkInfo.State.FAILED -> {
                            val msg = info.outputData.getString(UploadWorker.K_ERROR) ?: "error desconocido"
                            mostrarBarra(getString(R.string.share_error, msg), 12_000)
                        }
                        WorkInfo.State.CANCELLED -> shareBar.visibility = View.GONE
                        else -> Unit
                    }
                }
        }
    }

    private fun mostrarBarra(texto: String, ocultarTrasMs: Long = 0) {
        shareBar.removeCallbacks(ocultarBarra)
        shareBar.text = texto
        shareBar.visibility = View.VISIBLE
        if (ocultarTrasMs > 0) shareBar.postDelayed(ocultarBarra, ocultarTrasMs)
    }

    // ---------- varios ----------

    private fun pedirNotificacionesUnaVez() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("notif_pedido", false)) return
        prefs.edit { putBoolean("notif_pedido", true) }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Pulsación larga en el icono → «Buscar actualización» (sin tocar el HTML). */
    private fun registrarAtajo() {
        runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(
                this,
                ShortcutInfoCompat.Builder(this, "actualizar")
                    .setShortLabel(getString(R.string.btn_buscar_actualizacion))
                    .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                    .setIntent(Intent(this, MainActivity::class.java).setAction(ACTION_BUSCAR_ACTUALIZACION))
                    .build()
            )
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
