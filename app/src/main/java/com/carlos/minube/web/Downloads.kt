package com.carlos.minube.web

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import java.net.URLDecoder

/** Descargas de la web → DownloadManager (el token va en la URL como ?auth=, así que no hacen falta cookies). */
object Downloads {

    /** Devuelve el nombre del archivo si la descarga se encoló, o null si falló. */
    fun iniciar(ctx: Context, url: String, userAgent: String?, contentDisposition: String?, mime: String?): String? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val nombre = nombreArchivo(url, contentDisposition, mime)
        return try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(nombre)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nombre)
            if (!mime.isNullOrBlank() && mime != "application/octet-stream") req.setMimeType(mime)
            if (!userAgent.isNullOrBlank()) req.addRequestHeader("User-Agent", userAgent)
            ctx.getSystemService(DownloadManager::class.java).enqueue(req)
            nombre
        } catch (e: Exception) {
            null
        }
    }

    /** Mismo criterio que download() de la web: nombre del archivo, «carpeta.zip» o «archivos.zip». */
    fun nombreArchivo(url: String, contentDisposition: String?, mime: String?): String {
        val n = nombreDesdeQuantum(url)
            ?: contentDisposition?.let { nombreDesdeCabecera(it) }
            ?: URLUtil.guessFileName(url, contentDisposition, mime)
        return sanear(n)
    }

    private fun nombreDesdeQuantum(url: String): String? {
        val u = Uri.parse(url)
        if (u.path?.endsWith("/api/resources/download") != true) return null
        val files = u.getQueryParameters("file")
        if (files.isEmpty()) return null
        if (files.size > 1) return "archivos.zip"
        val base = files[0].trimEnd('/').substringAfterLast('/').ifBlank { "archivo" }
        return if (u.getQueryParameter("algo") == "zip") "$base.zip" else base
    }

    private fun nombreDesdeCabecera(cd: String): String? {
        Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val charset = m.groupValues[1].ifBlank { "UTF-8" }
            runCatching { return URLDecoder.decode(m.groupValues[2].trim().replace("+", "%2B"), charset) }
        }
        Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(cd)?.let { return it.groupValues[1] }
        Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.let { return it.groupValues[1].trim() }
        return null
    }

    /** Quita caracteres que no valen en un nombre de archivo de Android. */
    fun sanear(n: String): String =
        n.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trimStart('.').take(150).ifBlank { "archivo" }
}
