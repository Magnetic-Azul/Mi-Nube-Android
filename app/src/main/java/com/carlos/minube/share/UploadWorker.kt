package com.carlos.minube.share

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.carlos.minube.Notifs
import com.carlos.minube.R
import com.carlos.minube.net.Quantum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

/**
 * Sube un lote compartido a /Recibidos/ por streaming (sin cargar el archivo en memoria).
 * Recibe token, source y servidor por workDataOf desde MainActivity: este worker NUNCA evalúa JS.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val TAG = "subida-compartido"
        const val K_LOTE = "lote"
        const val K_TOKEN = "token"
        const val K_SOURCE = "source"
        const val K_SERVER = "server"
        const val K_HECHOS = "hechos"
        const val K_TOTAL = "total"
        const val K_ERROR = "error"
        const val K_SUBIDOS = "subidos"
        const val CARPETA = "/Recibidos"
        const val SOURCE_POR_DEFECTO = "Archivos"
        private const val MAX_INTENTOS = 5
        private const val MAX_NOMBRES = 50
    }

    private class SesionCaducada : Exception()
    private class ErrorServidor(msg: String) : Exception(msg)

    private val ctx get() = applicationContext
    private var token = ""
    private var server = ""
    private var source = SOURCE_POR_DEFECTO
    private var ultimaNotif = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo = info(0, 1, 0)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val lote = inputData.getString(K_LOTE) ?: return@withContext fallo("Faltan los archivos.")
        server = inputData.getString(K_SERVER).orEmpty().trimEnd('/')
        source = inputData.getString(K_SOURCE).orEmpty().ifBlank { SOURCE_POR_DEFECTO }
        token = inputData.getString(K_TOKEN).orEmpty()

        val archivos = ShareCache.archivos(ctx, lote)
        if (archivos.isEmpty()) return@withContext Result.success(workDataOf(K_SUBIDOS to 0))
        if (server.isEmpty() || token.isEmpty()) return@withContext fallo("Falta la sesión. Abre Mi Nube, entra y vuelve a compartir.")

        Notifs.crearCanales(ctx)
        try { setForeground(info(0, archivos.size, 0)) } catch (_: Exception) { /* sigue sin primer plano */ }

        try {
            renovar()                     // alarga la sesión; si ya caducó, se avisa
            crearCarpeta()
            archivos.forEachIndexed { i, f ->
                setProgress(workDataOf(K_HECHOS to i, K_TOTAL to archivos.size))
                subir(f, i, archivos.size)
                f.delete()                // ya está en el servidor: si hay reintento no se repite
            }
            ShareCache.borrarLote(ctx, lote)
            resultado(ctx.getString(R.string.share_ok))
            Result.success(workDataOf(K_SUBIDOS to archivos.size))
        } catch (e: SesionCaducada) {
            fallo("La sesión caducó. Abre Mi Nube, entra en tu carpeta y vuelve a compartir.")
        } catch (e: ErrorServidor) {
            fallo(e.message ?: "error del servidor")
        } catch (e: IOException) {
            if (runAttemptCount < MAX_INTENTOS) Result.retry()
            else fallo("No hay conexión con el servidor. Comprueba Tailscale y vuelve a compartir.")
        }
    }

    // ---------- API (rutas de realApi) ----------

    private fun renovar() {
        val c = abrir(Quantum.urlRenovar(server))
        try {
            c.setFixedLengthStreamingMode(0)
            c.outputStream.close()
            when (val code = c.responseCode) {
                in 200..299 -> {
                    val t = Quantum.limpiarToken(c.inputStream.bufferedReader().use { it.readText() })
                    if (t.isNotEmpty()) token = t
                }
                401, 403 -> throw SesionCaducada()
                else -> throw ErrorServidor("El servidor respondió con un error ($code).")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun crearCarpeta() {
        val code = enviar(Quantum.urlCrearCarpeta(server, source, "$CARPETA/"), null) { }
        if (code == 401) throw SesionCaducada()
        if (code !in 200..299 && code != 409) throw ErrorServidor("No se pudo crear la carpeta «Recibidos» ($code).")
    }

    private fun subir(f: File, i: Int, total: Int) {
        var nombre = f.name
        var renovado = false
        var k = 2
        while (true) {
            val code = enviar(Quantum.urlSubir(server, source, "$CARPETA/$nombre"), f) { enviados ->
                val pct = if (f.length() > 0) (enviados * 100 / f.length()).toInt() else 100
                notificarProgreso(i, total, pct)
            }
            when {
                code in 200..299 -> return
                code == 409 -> {                              // ya existe: «foto (2).jpg», «foto (3).jpg»…
                    if (k > MAX_NOMBRES) throw ErrorServidor("Ya existe «${f.name}» en Recibidos.")
                    nombre = ShareCache.alternativo(f.name, k++)
                }
                code == 401 && !renovado -> { renovar(); renovado = true }
                code == 401 -> throw SesionCaducada()
                code == 403 -> throw ErrorServidor("No tienes permiso para guardar en esta carpeta.")
                code == 413 -> throw ErrorServidor("«${f.name}» es demasiado grande para el servidor.")
                code == 507 -> throw ErrorServidor("No queda espacio en tu carpeta.")
                code >= 500 -> throw IOException("servidor $code")  // se reintenta más tarde
                else -> throw ErrorServidor("Error al subir «${f.name}» ($code).")
            }
        }
    }

    private fun abrir(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
        }

    /** POST con el archivo como cuerpo (igual que x.send(file) en la web). Devuelve el código HTTP. */
    private fun enviar(url: String, f: File?, progreso: (Long) -> Unit): Int {
        val c = abrir(url)
        try {
            if (f == null) {
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
            } else {
                c.setRequestProperty("Content-Type", URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream")
                c.setFixedLengthStreamingMode(f.length())
                c.outputStream.use { out ->
                    f.inputStream().use { inp ->
                        val buf = ByteArray(64 * 1024)
                        var enviados = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            enviados += n
                            progreso(enviados)
                        }
                    }
                }
            }
            val code = c.responseCode
            runCatching { (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } }
            return code
        } finally {
            c.disconnect()
        }
    }

    // ---------- notificaciones ----------

    private fun notificarProgreso(i: Int, total: Int, pct: Int) {
        val ahora = SystemClock.elapsedRealtime()
        if (ahora - ultimaNotif < 700 && pct < 100) return
        ultimaNotif = ahora
        setProgressAsync(workDataOf(K_HECHOS to i, K_TOTAL to total))
        Notifs.mostrar(ctx, Notifs.ID_SUBIDA_PROGRESO, notificacion(i, total, pct))
    }

    private fun notificacion(i: Int, total: Int, pct: Int) =
        NotificationCompat.Builder(ctx, Notifs.CANAL_SUBIDAS)
            .setSmallIcon(R.drawable.ic_stat_nube)
            .setContentTitle(ctx.getString(R.string.notif_subiendo))
            .setContentText(ctx.getString(R.string.share_subiendo, minOf(i + 1, total), total))
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    private fun info(i: Int, total: Int, pct: Int): ForegroundInfo {
        Notifs.crearCanales(ctx)
        val n = notificacion(i, total, pct)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notifs.ID_SUBIDA_PROGRESO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifs.ID_SUBIDA_PROGRESO, n)
        }
    }

    private fun resultado(texto: String) {
        Notifs.mostrar(
            ctx, Notifs.ID_SUBIDA_RESULTADO,
            NotificationCompat.Builder(ctx, Notifs.CANAL_SUBIDAS)
                .setSmallIcon(R.drawable.ic_stat_nube)
                .setContentTitle(ctx.getString(R.string.app_name))
                .setContentText(texto)
                .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
                .setAutoCancel(true)
                .build()
        )
    }

    private fun fallo(msg: String): Result {
        resultado(ctx.getString(R.string.share_error, msg))
        return Result.failure(workDataOf(K_ERROR to msg))
    }
}
