package com.carlos.minube.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.carlos.minube.web.Downloads
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Copia temporal de lo compartido en cacheDir/share/<lote>/.
 * Hace falta porque el permiso de lectura que da Galería/WhatsApp caduca al cerrarse la pantalla de compartir,
 * y la subida (WorkManager) puede tardar o reintentarse más tarde.
 */
object ShareCache {
    private const val DIR = "share"
    private const val MAX_EDAD_MS = 24L * 60 * 60 * 1000
    private val LOTE_VALIDO = Regex("^[a-z0-9-]{6,40}$")

    data class Resultado(val lote: String, val copiados: Int, val fallidos: Int)

    private fun raiz(ctx: Context) = File(ctx.cacheDir, DIR)

    /** null si el identificador no es válido (evita rutas manipuladas como «../»). */
    fun dirLote(ctx: Context, lote: String): File? =
        if (LOTE_VALIDO.matches(lote)) File(raiz(ctx), lote) else null

    fun copiar(ctx: Context, uris: List<Uri>): Resultado {
        val lote = System.currentTimeMillis().toString(36) + "-" + UUID.randomUUID().toString().take(8)
        val dir = File(raiz(ctx), lote).apply { mkdirs() }
        val usados = HashSet<String>()
        var ok = 0
        var mal = 0
        for (u in uris) {
            try {
                val nombre = unico(nombreDe(ctx, u), usados)
                val destino = File(dir, nombre)
                val entrada = ctx.contentResolver.openInputStream(u) ?: throw IOException("sin datos")
                entrada.use { inp -> destino.outputStream().use { out -> inp.copyTo(out, 64 * 1024) } }
                ok++
            } catch (e: Exception) {
                mal++
            }
        }
        if (ok == 0) dir.deleteRecursively()
        return Resultado(lote, ok, mal)
    }

    fun archivos(ctx: Context, lote: String): List<File> =
        dirLote(ctx, lote)?.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    fun borrarLote(ctx: Context, lote: String) {
        dirLote(ctx, lote)?.deleteRecursively()
    }

    /** Se llama al abrir la app: borra lotes de más de 24 h (subidas fallidas o abandonadas). */
    fun limpiarAntiguos(ctx: Context) {
        val ahora = System.currentTimeMillis()
        raiz(ctx).listFiles()?.forEach { if (ahora - it.lastModified() > MAX_EDAD_MS) it.deleteRecursively() }
    }

    private fun nombreDe(ctx: Context, u: Uri): String {
        var n: String? = null
        try {
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) n = it.getString(0)
            }
        } catch (_: Exception) {
        }
        if (n.isNullOrBlank()) n = u.lastPathSegment?.substringAfterLast('/')
        var s = if (n.isNullOrBlank()) "archivo-${System.currentTimeMillis()}" else Downloads.sanear(n!!)
        if (!s.contains('.')) {
            val ext = runCatching { MimeTypeMap.getSingleton().getExtensionFromMimeType(ctx.contentResolver.getType(u)) }.getOrNull()
            if (!ext.isNullOrBlank()) s = "$s.$ext"
        }
        return s
    }

    private fun unico(nombre: String, usados: MutableSet<String>): String {
        var n = nombre
        var k = 2
        while (!usados.add(n.lowercase())) n = alternativo(nombre, k++)
        return n
    }

    /** «foto.jpg», 2 → «foto (2).jpg» (mismo estilo que la web para « (restaurado)»). */
    fun alternativo(nombre: String, k: Int): String {
        val punto = nombre.lastIndexOf('.')
        return if (punto > 0) "${nombre.substring(0, punto)} ($k)${nombre.substring(punto)}" else "$nombre ($k)"
    }
}
