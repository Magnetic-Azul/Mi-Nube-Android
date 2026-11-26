package com.carlos.minube.net

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Diagnóstico de conexión: decide qué mensaje claro mostrar en lugar del error técnico del navegador. */
object Connectivity {
    const val TAILSCALE_PKG = "com.tailscale.ipn"

    enum class Estado { OK, SIN_CONFIG, SIN_INTERNET, TAILSCALE_NO_INSTALADO, TAILSCALE_APAGADO, SERVIDOR_NO_RESPONDE }

    suspend fun comprobar(ctx: Context, serverUrl: String, timeoutMs: Int = 2500): Estado = withContext(Dispatchers.IO) {
        when {
            serverUrl.isBlank() -> Estado.SIN_CONFIG
            servidorResponde(serverUrl, timeoutMs) -> Estado.OK
            !hayInternet(ctx) -> Estado.SIN_INTERNET
            !tailscaleInstalado(ctx) -> Estado.TAILSCALE_NO_INSTALADO
            !vpnActiva(ctx) -> Estado.TAILSCALE_APAGADO
            else -> Estado.SERVIDOR_NO_RESPONDE
        }
    }

    /**
     * HEAD a la página principal con `timeoutMs` (2,5 s por defecto) para conectar y otro tanto para responder (con Tailscale recién activado
     * la conexión TCP puede abrirse y la respuesta quedarse colgada). Cualquier respuesta < 500 significa que nginx contesta.
     */
    fun servidorResponde(serverUrl: String, timeoutMs: Int = 2500): Boolean {
        var c: HttpURLConnection? = null
        return try {
            c = (URL("$serverUrl/").openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                instanceFollowRedirects = true
            }
            c.responseCode in 200..499
        } catch (e: IOException) {
            false
        } finally {
            c?.disconnect()
        }
    }

    fun tailscaleInstalado(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(TAILSCALE_PKG, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun hayInternet(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@any false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun vpnActiva(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }
}
