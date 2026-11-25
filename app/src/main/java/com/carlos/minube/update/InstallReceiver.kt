package com.carlos.minube.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import com.carlos.minube.MainActivity
import com.carlos.minube.Notifs
import com.carlos.minube.R

/**
 * Resultado de la sesión de PackageInstaller.
 * STATUS_PENDING_USER_ACTION: Android pide que el usuario confirme. Si Mi Nube está en pantalla se abre
 * el diálogo directamente; si no (Android bloquea abrir pantallas desde segundo plano), se avisa con una notificación.
 */
class InstallReceiver : BroadcastReceiver() {

    companion object {
        const val ACCION = "com.carlos.minube.RESULTADO_INSTALACION"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACCION) return
        when (val estado = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmar = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val lanzado = MainActivity.enPrimerPlano && try {
                    ctx.startActivity(confirmar); true
                } catch (e: Exception) {
                    false
                }
                if (!lanzado) notificar(ctx, confirmar)
            }
            PackageInstaller.STATUS_SUCCESS -> NotificationManagerCompat.from(ctx).cancel(Notifs.ID_INSTALAR)
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit  // el usuario pulsó «Cancelar»
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "código $estado"
                Toast.makeText(ctx, ctx.getString(R.string.upd_fallo_instalar, msg), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun notificar(ctx: Context, confirmar: Intent) {
        Notifs.crearCanales(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 0, confirmar, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        Notifs.mostrar(
            ctx, Notifs.ID_INSTALAR,
            NotificationCompat.Builder(ctx, Notifs.CANAL_ACTUALIZACIONES)
                .setSmallIcon(R.drawable.ic_stat_nube)
                .setContentTitle(ctx.getString(R.string.upd_titulo))
                .setContentText(ctx.getString(R.string.upd_confirmar))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }
}
