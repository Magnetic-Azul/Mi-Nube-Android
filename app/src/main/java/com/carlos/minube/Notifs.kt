package com.carlos.minube

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Canales de notificación y envío seguro (si no hay permiso, simplemente no se muestra). */
object Notifs {
    const val CANAL_SUBIDAS = "subidas"
    const val CANAL_ACTUALIZACIONES = "actualizaciones"

    const val ID_SUBIDA_PROGRESO = 4101
    const val ID_SUBIDA_RESULTADO = 4102
    const val ID_INSTALAR = 4201

    fun crearCanales(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CANAL_SUBIDAS, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(ctx.getString(R.string.notif_canal_subidas)).build()
        )
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CANAL_ACTUALIZACIONES, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(ctx.getString(R.string.notif_canal_actualizaciones)).build()
        )
    }

    fun puedeNotificar(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    @SuppressLint("MissingPermission")
    fun mostrar(ctx: Context, id: Int, n: Notification) {
        if (!puedeNotificar(ctx)) return
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
