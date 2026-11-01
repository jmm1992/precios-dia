package ar.preciosdia;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Le presta al instalador de Android la versión nueva bajada (cache/actualizacion/precios-dia.apk), así la
 * actualización a mano pasa por su pantalla de siempre: "¿Actualizar esta app?" → "App actualizada · Abrir".
 * Sólo ese archivo, sólo para leer, y sólo a quien se le da permiso (no está exportado).
 */
public class Archivos extends ContentProvider {
    static final String AUTORIDAD = "ar.preciosdia.archivos";
    static final Uri APK = Uri.parse("content://" + AUTORIDAD + "/precios-dia.apk");

    static File apk(android.content.Context c) {
        File d = new File(c.getCacheDir(), "actualizacion");
        d.mkdirs();
        return new File(d, "precios-dia.apk");
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(apk(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{"precios-dia.apk", apk(getContext()).length()});
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
