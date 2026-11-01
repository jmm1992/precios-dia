package ar.preciosdia;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Precios Día: la pantalla es la página publicada en GitHub Pages (WEB), así cada cambio que se publica
 * llega solo la próxima vez que se abre, sin reinstalar. Sin internet se abre la copia que viene adentro
 * (assets/index.html). Los datos los trae la página de la función dia-precios de Supabase.
 */
public class MainActivity extends Activity {
    static final String WEB = "https://jmm1992.github.io/precios-dia/";
    static final String COPIA = "file:///android_asset/index.html";
    static final String APP_DIA = "ar.com.supermercadosdia.customers";   // la app Día Online (sale de assetlinks.json de su web)
    private WebView web;
    private volatile boolean hayNueva = false;        // hay una app más nueva publicada (lo avisa la página, con su propio cartel)
    private volatile boolean esperandoPermiso = false; // se fue a dar el permiso de "Instalar apps": al volver, se instala

    /** Lo que la página puede pedirle a la app (sólo se cargan la página propia y su copia: lo de afuera va al navegador). */
    class Puente {
        @JavascriptInterface public boolean hayNueva() { return hayNueva; }
        @JavascriptInterface public void actualizar() { runOnUiThread(() -> empezarActualizacion()); }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pintarBarras();
        web = new WebView(this);
        web.setBackgroundColor(oscuro() ? Color.parseColor("#0F1218") : Color.parseColor("#F4F5F8"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // la página guarda la tienda, los filtros y lo último que se vio
        s.setAllowFileAccess(true);
        // La página reconoce que es la app y muestra su versión
        s.setUserAgentString(s.getUserAgentString() + " PreciosDiaApp/" + versionApp());
        web.addJavascriptInterface(new Puente(), "AppPD");
        // Los enlaces de afuera (la web de Día) se abren en el navegador; la página propia, acá
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme()) || u.toString().startsWith(WEB)) return false;
                // Un producto de Día se abre en la app de Día si está instalada (ahí está la sesión y la tienda del usuario)
                if ("diaonline.supermercadosdia.com.ar".equals(u.getHost()) && abrirEnAppDia(u)) return true;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
            // Si la app ya sabe que hay una versión nueva cuando la página termina de cargar, se lo dice
            // (la página no le pregunta: esa pregunta, hecha mientras cargaba, la dejaba en negro)
            @Override
            public void onPageFinished(WebView v, String url) {
                if (hayNueva) v.evaluateJavascript("window.avisoActualizacion && avisoActualizacion()", null);
            }
            // Si la publicada no se puede abrir (sin internet, GitHub caído), se usa la copia de adentro
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (r.isForMainFrame() && r.getUrl().toString().startsWith(WEB)) v.loadUrl(COPIA);
            }
            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest r, android.webkit.WebResourceResponse e) {
                if (r.isForMainFrame() && r.getUrl().toString().startsWith(WEB)) v.loadUrl(COPIA);
            }
        });
        if (b != null) web.restoreState(b);
        else web.loadUrl(WEB + "?v=" + System.currentTimeMillis());   // siempre la página recién publicada, nunca la guardada
        setContentView(web);
        resultadoInstalacion(getIntent());
        // ¿Hay una versión nueva de la app? (la página se actualiza sola; esto es para el ícono, el botón Atrás, etc.)
        if (b == null) new Thread(() -> {
            if (Actualizacion.publicada() > Actualizacion.miVersion(this)) {
                hayNueva = true;
                runOnUiThread(() -> web.evaluateJavascript("window.avisoActualizacion && avisoActualizacion()", null));
            }
        }, "ver-actualizacion").start();
    }

    /**
     * "Ver en Día": 1) si la app Día Online acepta el enlace del producto, se abre ahí;
     * 2) si está instalada pero no lo acepta, se abre la app y el nombre del producto queda copiado para pegarlo en su buscador;
     * 3) si no está instalada, devuelve false y se abre la web.
     */
    private boolean abrirEnAppDia(Uri u) {
        // No se sabe qué enlaces acepta la app de Día (no se pudo leer su configuración): se prueban, en orden,
        // las formas conocidas de producto y de búsqueda, y se usa la primera que ella diga que abre.
        String nombre = nombreDelEnlace(u), q = Uri.encode(nombre), id = idDelEnlace(u);
        String base = "https://diaonline.supermercadosdia.com.ar/";
        String[] candidatos = {
            u.toString(),                                   // el producto, tal cual
            base + q + "?_q=" + q + "&map=ft",              // la búsqueda de la web de Día
            base + "busca?ft=" + q,
            base + "search?q=" + q,
            "diaonline://product/" + id, "diaonline://search?q=" + q,
            "dia://product/" + id, "dia://search?q=" + q,
            "supermercadosdia://product/" + id, "supermercadosdia://search?q=" + q,
        };
        for (String c : candidatos) {
            if (c.contains("//product/") && id.isEmpty()) continue;
            Intent ver = new Intent(Intent.ACTION_VIEW, Uri.parse(c)).setPackage(APP_DIA).addCategory(Intent.CATEGORY_BROWSABLE);
            if (ver.resolveActivity(getPackageManager()) != null) {
                try { startActivity(ver); return true; } catch (Exception ignored) { }
            }
        }
        // Ninguno: se abre la app de Día con el nombre del producto copiado para pegarlo en su buscador
        Intent app = getPackageManager().getLaunchIntentForPackage(APP_DIA);
        if (app == null) return false;
        if (!nombre.isEmpty()) {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Producto", nombre));
            android.widget.Toast.makeText(this, "Copiado: «" + nombre + "». Pegalo en el buscador de la app de Día", android.widget.Toast.LENGTH_LONG).show();
        }
        try { startActivity(app); return true; } catch (Exception e) { return false; }
    }

    /** "/yerba-mate-playadito-suave-500-gr-269578/p" → "269578" (vacío si no hay número al final) */
    static String idDelEnlace(Uri u) {
        String s = "";
        for (String p : u.getPathSegments()) if (!"p".equals(p)) s = p;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-(\\d+)$").matcher(s);
        return m.find() ? m.group(1) : "";
    }

    /** "/yerba-mate-playadito-suave-500-gr-269578/p" → "yerba mate playadito suave 500 gr" */
    static String nombreDelEnlace(Uri u) {
        String s = "";
        for (String p : u.getPathSegments()) if (!"p".equals(p)) s = p;
        return s.replaceAll("-\\d+$", "").replace('-', ' ').trim();
    }

    /** El instalador de Android avisa cómo fue la actualización hecha a mano (ver Actualizacion.instalar). */
    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        resultadoInstalacion(i);
    }
    private void resultadoInstalacion(Intent i) {
        if (i == null || !i.hasExtra(android.content.pm.PackageInstaller.EXTRA_STATUS)) return;
        int st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE);
        if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmar = i.getParcelableExtra(Intent.EXTRA_INTENT);
            estado("Tocá «Actualizar» en el cartel de Android");
            if (confirmar != null) try { startActivity(confirmar); } catch (Exception ignored) { }
        } else if (st != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            estado("No se instaló la actualización. Probá de nuevo");
        }
    }

    /** "Actualizar" en el cartel de la página: pide el permiso si falta (y sigue al volver) o baja e instala. */
    private void empezarActualizacion() {
        // Para el aviso "se actualizó · tocá para abrir" Android pide (una sola vez) permiso de notificaciones
        android.content.SharedPreferences sp = getSharedPreferences("preciosdia", MODE_PRIVATE);
        if (android.os.Build.VERSION.SDK_INT >= 33 && !sp.getBoolean("pidioAvisos", false)
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sp.edit().putBoolean("pidioAvisos", true).apply();
            estado("Permití los avisos: así, cuando termine, te avisa para abrirla");
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            esperandoPermiso = true;
            estado("Activá «Permitir desde esta fuente» y volvé: la actualización sigue sola");
            try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
            catch (Exception ignored) { }
            return;
        }
        estado("Descargando la versión nueva…");
        new Thread(() -> Actualizacion.instalar(this, false), "actualizar").start();
    }

    /** Muestra en el cartel de la página cómo va la actualización. */
    void estado(String texto) {
        String js = "window.estadoActualizacion && estadoActualizacion(" + org.json.JSONObject.quote(texto) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    public void onRequestPermissionsResult(int pedido, String[] permisos, int[] resultados) {
        super.onRequestPermissionsResult(pedido, permisos, resultados);
        if (pedido == 7) empezarActualizacion();   // con o sin permiso, la actualización sigue
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (esperandoPermiso && getPackageManager().canRequestPackageInstalls()) { esperandoPermiso = false; empezarActualizacion(); }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Al salir, si hay una versión nueva se instala sola (como CamCasa)
        new Thread(() -> Actualizacion.instalarSiHay(this), "actualizar-sola").start();
    }

    @Override
    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        if (web != null) web.saveState(b);
    }

    /** Atrás: primero cierra lo que esté abierto en la página (detalle, rubro...); si no hay nada, sale. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.atrasApp ? atrasApp() : false", v -> { if (!"true".equals(v)) finish(); });
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        pintarBarras();
    }

    private String versionApp() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    private boolean oscuro() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Las barras del celular del mismo color que el encabezado de la página (claro u oscuro, según el teléfono). */
    private void pintarBarras() {
        boolean o = oscuro();
        int c = o ? Color.parseColor("#191E27") : Color.WHITE;
        getWindow().setStatusBarColor(c);
        getWindow().setNavigationBarColor(o ? Color.parseColor("#0F1218") : Color.parseColor("#F4F5F8"));
        int f = 0;
        if (!o) f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(f);
    }
}
