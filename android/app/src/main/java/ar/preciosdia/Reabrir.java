package ar.preciosdia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Después de una actualización hecha con "Actualizar": aviso "Precios Día se actualizó · Tocá para abrir".
 * Android no deja que la app (ni el instalador) la vuelva a abrir sola: probado en el emulador (Android 14),
 * los dos caminos quedan bloqueados ("Background activity launch blocked"). El aviso es lo mismo que hace la tienda.
 * Las actualizaciones solas al salir de la app no avisan (el usuario ya se había ido).
 */
public class Reabrir extends BroadcastReceiver {
    static final String MARCA = "abrirTrasActualizar";   // la pone Actualizacion.instalar justo antes de instalar a mano

    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        SharedPreferences sp = c.getSharedPreferences("preciosdia", Context.MODE_PRIVATE);
        long cuando = sp.getLong(MARCA, 0);
        sp.edit().remove(MARCA).apply();
        if (System.currentTimeMillis() - cuando > 10 * 60_000L) return;   // no fue a mano (o fue hace mucho)
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("actualizacion", "Actualizaciones de la app", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent abrir = PendingIntent.getActivity(c, 1, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(c, "actualizacion")
                    .setSmallIcon(R.drawable.ic_frente)
                    .setColor(0xFFE30613)
                    .setContentTitle("Precios Día se actualizó")
                    .setContentText("Tocá para abrir la versión nueva")
                    .setContentIntent(abrir)
                    .setAutoCancel(true)
                    .build();
            nm.notify(1, n);
        } catch (Exception e) { Log.w("PreciosDia", "aviso de actualización: " + e); }
    }
}
