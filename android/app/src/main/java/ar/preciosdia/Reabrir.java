package ar.preciosdia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Android cierra la app al actualizarla y, como la actualización la hace la propia app, no muestra "Abrir".
 * Esto la vuelve a abrir apenas quedó instalada la versión nueva, SALVO que se haya actualizado sola al salir
 * (ahí el usuario ya se había ido: abrirla le aparecería de golpe). Pedido del dueño (31/10/2026).
 * La actualización sola deja anotado "actuSolaIntento" = la versión que instaló (Actualizacion.instalarSiHay).
 */
public class Reabrir extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        SharedPreferences sp = c.getSharedPreferences("preciosdia", Context.MODE_PRIVATE);
        if (sp.getLong("actuSolaIntento", 0) == Actualizacion.miVersion(c)) return;   // fue sola, al salir
        try {
            c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (Exception e) { Log.w("PreciosDia", "no se pudo reabrir: " + e); }
    }
}
