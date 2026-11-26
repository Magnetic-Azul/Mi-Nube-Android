package com.carlos.minube.web

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/** Navegación de la web: lo del servidor se queda dentro; los enlaces externos se abren fuera. */
class MiNubeWebClient(private val host: String?, private val l: Listener) : WebViewClient() {

    /** true solo si la página principal cargada es https://<nuestro servidor>. La lee el puente JS (otro hilo). */
    @Volatile
    var enServidor = false
        private set

    private fun marcar(url: String?) {
        val u = url?.let { Uri.parse(it) }
        enServidor = u != null && host != null && u.scheme == "https" && u.host.equals(host, ignoreCase = true)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = marcar(url)

    interface Listener {
        fun onPaginaEmpieza()
        fun onPaginaTerminada()
        fun onErrorPrincipal()
        fun abrirFuera(uri: Uri)
        fun onRendererMuerto()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val u = request.url
        when (u.scheme) {
            "blob", "data", "about", "javascript" -> return false
        }
        if (host != null && u.host.equals(host, ignoreCase = true)) return false
        l.abrirFuera(u)
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        marcar(url)
        l.onPaginaEmpieza()
    }

    override fun onPageFinished(view: WebView, url: String?) = l.onPaginaTerminada()

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (esLaPagina(request)) l.onErrorPrincipal()
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (esLaPagina(request) && response.statusCode >= 500) l.onErrorPrincipal()
    }

    /** Solo cuenta el documento principal (no descargas ni llamadas a /api/ que la web ya gestiona). */
    private fun esLaPagina(r: WebResourceRequest): Boolean =
        r.isForMainFrame && r.url.path?.startsWith("/api/") != true

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        l.onRendererMuerto()
        return true
    }
}
