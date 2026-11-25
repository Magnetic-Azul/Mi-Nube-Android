package com.carlos.minube.update

import android.app.AlertDialog
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.carlos.minube.BuildConfig
import com.carlos.minube.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Auto-actualizador por GitHub Releases.
 *  1. Lee latest.json de https://github.com/<repo>/releases/latest/download/latest.json (sin límite de la API).
 *     Si falla, usa la API (60 peticiones/h por IP) con ETag: las respuestas 304 no gastan cuota.
 *  2. Compara versionCode (entero) con BuildConfig.VERSION_CODE.
 *  3. Descarga el APK con DownloadManager, comprueba su SHA-256 e instala con PackageInstaller.
 */
class Updater(private val act: ComponentActivity) {

    data class Version(val code: Int, val nombre: String, val apkUrl: String, val sha256: String, val notas: String)

    private val prefs = act.getSharedPreferences("actualizador", Context.MODE_PRIVATE)
    private val repo = BuildConfig.GITHUB_REPO
    private val activo = repo.isNotBlank() && !BuildConfig.DEBUG
    private var pendientePermiso: Version? = null
    private var receptor: BroadcastReceiver? = null

    companion object {
        private const val CADA_MS = 6L * 60 * 60 * 1000
        private const val K_ULTIMA = "ultima"
        private const val K_ETAG = "etag"
        private const val K_API = "api_json"
        private const val K_DESCARGA = "descarga_id"
        private const val K_SHA = "descarga_sha"
        private const val K_RUTA = "descarga_ruta"
    }

    // ---------- comprobar ----------

    fun comprobarAlArrancar() {
        if (!activo) return
        // APK de actualizaciones ya instaladas: fuera (unos MB)
        if (!prefs.contains(K_DESCARGA)) act.getExternalFilesDir("updates")?.listFiles()?.forEach { it.delete() }
        val ultima = prefs.getLong(K_ULTIMA, 0)
        if (System.currentTimeMillis() - ultima < CADA_MS) return
        comprobar(manual = false)
    }

    fun comprobar(manual: Boolean) {
        if (!activo) {
            if (manual) toast(act.getString(R.string.upd_desactivado))
            return
        }
        act.lifecycleScope.launch {
            val v = withContext(Dispatchers.IO) { runCatching { obtener() }.getOrNull() }
            if (v == null) {
                if (manual) toast(act.getString(R.string.upd_sin_conexion))
                return@launch
            }
            prefs.edit { putLong(K_ULTIMA, System.currentTimeMillis()) }
            if (v.code <= BuildConfig.VERSION_CODE) {
                if (manual) toast(act.getString(R.string.upd_al_dia, BuildConfig.VERSION_NAME))
                return@launch
            }
            if (act.isFinishing || act.isDestroyed) return@launch
            AlertDialog.Builder(act)
                .setTitle(R.string.upd_titulo)
                .setMessage(act.getString(R.string.upd_mensaje, v.nombre, v.notas.ifBlank { "Mejoras y correcciones." }))
                .setPositiveButton(R.string.upd_si) { _, _ -> empezar(v) }
                .setNegativeButton(R.string.upd_no, null)
                .show()
        }
    }

    private fun obtener(): Version {
        val json = try {
            get("https://github.com/$repo/releases/latest/download/latest.json").second
        } catch (e: IOException) {
            porApi()
        }
        val o = JSONObject(json)
        val v = Version(
            code = o.getInt("versionCode"),
            nombre = o.getString("versionName"),
            apkUrl = o.getString("apkUrl"),
            sha256 = o.getString("sha256").lowercase(),
            notas = o.optString("notas", ""),
        )
        // Solo se aceptan APK publicados en este mismo repositorio
        if (!v.apkUrl.startsWith("https://github.com/$repo/releases/download/")) throw IOException("URL de APK no válida")
        return v
    }

    private fun porApi(): String {
        val etag = prefs.getString(K_ETAG, null)
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            if (etag != null && prefs.contains(K_API)) put("If-None-Match", etag)
        }
        val (code, body, nuevoEtag) = getConCodigo("https://api.github.com/repos/$repo/releases/latest", headers)
        val rel = when (code) {
            304 -> prefs.getString(K_API, null) ?: throw IOException("sin caché")
            in 200..299 -> body.also { prefs.edit { putString(K_ETAG, nuevoEtag); putString(K_API, it) } }
            else -> throw IOException("API $code")
        }
        val assets = JSONObject(rel).getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name") == "latest.json") return get(a.getString("browser_download_url")).second
        }
        throw IOException("latest.json no está en la release")
    }

    private fun get(url: String): Pair<Int, String> {
        val (code, body, _) = getConCodigo(url, emptyMap())
        if (code !in 200..299) throw IOException("HTTP $code")
        return code to body
    }

    private fun getConCodigo(url: String, headers: Map<String, String>): Triple<Int, String, String?> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MiNubeApp/${BuildConfig.VERSION_NAME}")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = c.responseCode
            val body = if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else ""
            return Triple(code, body, c.getHeaderField("ETag"))
        } finally {
            c.disconnect()
        }
    }

    // ---------- permiso + descarga ----------

    private fun empezar(v: Version) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            pendientePermiso = v
            AlertDialog.Builder(act)
                .setTitle(R.string.upd_permiso_titulo)
                .setMessage(R.string.upd_permiso)
                .setPositiveButton(R.string.upd_permiso_ir) { _, _ ->
                    act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                }
                .setNegativeButton(R.string.upd_no) { _, _ -> pendientePermiso = null }
                .show()
            return
        }
        descargar(v)
    }

    /** Llamar en onResume: continúa tras conceder el permiso y recoge descargas terminadas con la app cerrada. */
    fun alVolver() {
        val v = pendientePermiso
        if (v != null && act.packageManager.canRequestPackageInstalls()) {
            pendientePermiso = null
            descargar(v)
        }
        revisarDescarga()
    }

    private fun descargar(v: Version) {
        val dir = act.getExternalFilesDir("updates") ?: run { toast(act.getString(R.string.upd_fallo_descarga)); return }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "MiNube-${v.code}.apk")
        val dm = act.getSystemService(DownloadManager::class.java)
        val id = try {
            dm.enqueue(
                DownloadManager.Request(Uri.parse(v.apkUrl))
                    .setTitle("Mi Nube ${v.nombre}")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationUri(Uri.fromFile(f))
            )
        } catch (e: Exception) {
            toast(act.getString(R.string.upd_fallo_descarga)); return
        }
        prefs.edit { putLong(K_DESCARGA, id); putString(K_SHA, v.sha256); putString(K_RUTA, f.absolutePath) }
        toast(act.getString(R.string.upd_descargando))
        escuchar()
    }

    private fun escuchar() {
        if (receptor != null) return
        receptor = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == prefs.getLong(K_DESCARGA, -2)) revisarDescarga()
            }
        }
        // El aviso lo envía el proveedor de descargas (otra app): hace falta EXPORTED
        ContextCompat.registerReceiver(act, receptor, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    fun liberar() {
        receptor?.let { runCatching { act.unregisterReceiver(it) } }
        receptor = null
    }

    private fun revisarDescarga() {
        val id = prefs.getLong(K_DESCARGA, -1)
        if (id < 0) return
        val dm = act.getSystemService(DownloadManager::class.java)
        val estado = dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else DownloadManager.STATUS_FAILED
        } ?: return
        when (estado) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                val ruta = prefs.getString(K_RUTA, null)
                val sha = prefs.getString(K_SHA, null)
                olvidarDescarga()                  // antes de instalar: evita instalar dos veces
                if (ruta != null && sha != null) verificarEInstalar(File(ruta), sha)
            }
            DownloadManager.STATUS_FAILED -> {
                olvidarDescarga()
                toast(act.getString(R.string.upd_fallo_descarga))
            }
            else -> escuchar()                     // sigue descargando
        }
    }

    private fun olvidarDescarga() = prefs.edit { remove(K_DESCARGA); remove(K_SHA); remove(K_RUTA) }

    // ---------- instalación ----------

    private fun verificarEInstalar(f: File, shaEsperado: String) {
        act.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { f.exists() && sha256(f) == shaEsperado }
            if (!ok) {
                f.delete()
                toast(act.getString(R.string.upd_fallo_integridad))
                return@launch
            }
            val error = withContext(Dispatchers.IO) { runCatching { instalar(f) }.exceptionOrNull() }
            if (error != null) toast(act.getString(R.string.upd_fallo_instalar, error.message ?: "error"))
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun instalar(f: File) {
        val pi = act.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(act.packageName)
            setSize(f.length())
            // A partir de la 2.ª actualización, Android 12+ puede no pedir confirmación (Mi Nube ya es quien la instaló)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            f.inputStream().use { inp ->
                s.openWrite("base.apk", 0, f.length()).use { out ->
                    inp.copyTo(out, 256 * 1024)
                    s.fsync(out)
                }
            }
            val intent = Intent(act, InstallReceiver::class.java).setAction(InstallReceiver.ACCION)
            // FLAG_MUTABLE: el sistema tiene que añadir EXTRA_STATUS y el intent de confirmación (obligatorio en Android 12+)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(act, id, intent, flags)
            s.commit(pending.intentSender)
        }
    }

    /** La actividad lo rellena: si la web puede mostrar el aviso (con su estilo y sin duplicarse), devuelve true. */
    var avisador: ((String, () -> Unit) -> Unit)? = null

    private fun toast(t: String) {
        val av = avisador
        if (av == null) Toast.makeText(act, t, Toast.LENGTH_LONG).show()
        else av(t) { Toast.makeText(act, t, Toast.LENGTH_LONG).show() }
    }
}
