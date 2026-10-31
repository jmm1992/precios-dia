package ar.preciosdia;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
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
    private WebView web;

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
        // Los enlaces de afuera (la web de Día) se abren en el navegador; la página propia, acá
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme()) || u.toString().startsWith(WEB)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
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
        else web.loadUrl(WEB);
        setContentView(web);
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
