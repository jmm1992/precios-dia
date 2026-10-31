package ar.preciosdia;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Actualizaciones de la app (lo que NO viaja con la página: el ícono, el botón Atrás, abrir la app de Día…).
 * La app nueva se publica en GitHub Pages junto con la página: apk/version.txt (versionCode) y apk/precios-dia.apk.
 * Android sólo acepta una actualización firmada con la misma clave, que vive en la PC (no en GitHub).
 *  - Al salir de la app: si hay una versión nueva, se instala sola, sin preguntar (Android 12 o más nuevo y
 *    si la versión instalada la puso la propia app). Si Android quiere que se toque "Instalar", no insiste.
 *  - Al abrir: si hay una versión nueva que no se pudo instalar sola, un cartel ofrece "Actualizar".
 * Mismo esquema que CamCasa (pedido del dueño, 31/10/2026: "que lleguen directamente las actualizaciones como en camcasa").
 */
final class Actualizacion {
    private static final String TAG = "PreciosDia";
    static final String VERSION = MainActivity.WEB + "apk/version.txt";
    static final String APK = MainActivity.WEB + "apk/precios-dia.apk";
    private Actualizacion() { }

    static long miVersion(Context c) {
        try {
            android.content.pm.PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return Long.MAX_VALUE; }
    }

    /** versionCode publicado; 0 si no se pudo saber. Bloquea: fuera del hilo de la pantalla. */
    static long publicada() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(VERSION + "?t=" + System.currentTimeMillis()).openConnection();
            c.setConnectTimeout(6000); c.setReadTimeout(6000); c.setUseCaches(false);
            if (c.getResponseCode() != 200) return 0;
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[64]; int n = in.read(b);
                return n > 0 ? Long.parseLong(new String(b, 0, n, "UTF-8").trim()) : 0;
            }
        } catch (Exception e) { return 0; }
        finally { if (c != null) c.disconnect(); }
    }

    /** Al salir: si hay versión nueva, intenta instalarla sola (una vez por versión). */
    static void instalarSiHay(Activity a) {
        try {
            if (!a.getPackageManager().canRequestPackageInstalls()) return;
            long v = publicada();
            if (v <= miVersion(a)) return;
            SharedPreferences sp = a.getSharedPreferences("preciosdia", Context.MODE_PRIVATE);
            if (sp.getLong("actuSolaIntento", 0) == v) return;
            sp.edit().putLong("actuSolaIntento", v).apply();
            instalar(a, true);
        } catch (Exception e) { Log.w(TAG, "actualización sola: " + e); }
    }

    /** Al abrir: si hay versión nueva, el cartel para actualizar a mano. */
    static void ofrecerSiHay(Activity a) {
        long v = publicada();
        if (v <= miVersion(a)) return;
        a.runOnUiThread(() -> {
            if (a.isFinishing()) return;
            new AlertDialog.Builder(a)
                .setTitle("Hay una versión nueva")
                .setMessage("Precios Día tiene una actualización. Se instala en unos segundos y no se pierde nada.")
                .setPositiveButton("Actualizar", (d, w) -> {
                    if (!a.getPackageManager().canRequestPackageInstalls()) {
                        try { a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))); }
                        catch (Exception ignored) { }
                        android.widget.Toast.makeText(a, "Permití «Instalar apps» para Precios Día y volvé: la actualización se instala sola", android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    android.widget.Toast.makeText(a, "Descargando la actualización…", android.widget.Toast.LENGTH_SHORT).show();
                    new Thread(() -> instalar(a, false), "actualizar").start();
                })
                .setNegativeButton("Después", null)
                .show();
        });
    }

    /** Baja la app nueva y la instala. Bloquea: fuera del hilo de la pantalla. */
    static void instalar(Activity a, boolean sola) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(APK + "?t=" + System.currentTimeMillis()).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(30000); c.setUseCaches(false);
            if (c.getResponseCode() != 200) return;
            PackageInstaller pi = a.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(a.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            int id = pi.createSession(sp);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream out = s.openWrite("precios-dia.apk", 0, total)) {
                    byte[] b = new byte[64 * 1024]; int r;
                    while ((r = in.read(b)) > 0) out.write(b, 0, r);
                    s.fsync(out);
                }
                String accion = a.getPackageName() + ".INSTALADO";
                BroadcastReceiver rec = new BroadcastReceiver() {
                    @Override public void onReceive(Context ctx, Intent i) {
                        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION && !sola) {
                            Intent confirmar = i.getParcelableExtra(Intent.EXTRA_INTENT);
                            if (confirmar != null) a.runOnUiThread(() -> {
                                try { a.startActivity(confirmar); }
                                catch (Exception e) { confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(confirmar); }
                            });
                            return;
                        }
                        // Sola y Android pide confirmar: no se abre ningún cartel; al abrir la app se ofrece a mano.
                        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                            try { a.getPackageManager().getPackageInstaller().abandonSession(id); } catch (Exception ignored) { }
                        }
                        try { ctx.unregisterReceiver(this); } catch (Exception ignored) { }
                    }
                };
                if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(rec, new IntentFilter(accion), Context.RECEIVER_NOT_EXPORTED);
                else a.registerReceiver(rec, new IntentFilter(accion));
                Intent i = new Intent(accion).setPackage(a.getPackageName());
                PendingIntent pend = PendingIntent.getBroadcast(a, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                s.commit(pend.getIntentSender());
            }
        } catch (Exception e) {
            Log.w(TAG, "actualizar: " + e);
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
