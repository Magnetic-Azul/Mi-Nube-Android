package com.carlos.minube.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.carlos.minube.MainActivity
import com.carlos.minube.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla invisible del menú Compartir: copia lo recibido a la caché y abre Mi Nube con el lote,
 * que se sube a /Recibidos/ en cuanto haya sesión.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }  // ya se procesó antes de recrearse

        val uris = recogerUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.share_nada, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Toast.makeText(this, resources.getQuantityString(R.plurals.share_preparando, uris.size, uris.size), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { ShareCache.copiar(applicationContext, uris) }
            if (r.fallidos > 0) {
                Toast.makeText(applicationContext, resources.getQuantityString(R.plurals.share_fallo_copia, r.fallidos, r.fallidos), Toast.LENGTH_LONG).show()
            }
            if (r.copiados > 0) {
                startActivity(
                    Intent(this@ShareActivity, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_SUBIR)
                        .putExtra(MainActivity.EXTRA_LOTE, r.lote)
                        .putExtra(MainActivity.EXTRA_N, r.copiados)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
            finish()
        }
    }

    private fun recogerUris(i: Intent): List<Uri> {
        val lista = mutableListOf<Uri>()
        when (i.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { lista += it }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { lista += it }
        }
        if (lista.isEmpty()) {
            i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { lista += it } }
        }
        // Solo content:// (nunca file://, que podría apuntar a archivos privados de esta app)
        return lista.filter { it.scheme == "content" }.distinct()
    }
}
