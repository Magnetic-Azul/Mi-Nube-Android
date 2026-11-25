package com.carlos.minube.web

import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.carlos.minube.BuildConfig

/** Selector de archivos (<input type="file">) y vídeo a pantalla completa. */
class MiNubeChromeClient(private val l: Listener) : WebChromeClient() {

    interface Listener {
        fun elegirArchivos(callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean
        fun mostrarPantallaCompleta(view: View, callback: CustomViewCallback)
        fun ocultarPantallaCompleta(desdeWeb: Boolean)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = l.elegirArchivos(filePathCallback, fileChooserParams)

    override fun onShowCustomView(view: View, callback: CustomViewCallback) = l.mostrarPantallaCompleta(view, callback)

    override fun onHideCustomView() = l.ocultarPantallaCompleta(desdeWeb = true)

    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
        if (BuildConfig.DEBUG) Log.d("MiNubeWeb", "${m.messageLevel()}: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
        return true
    }
}
