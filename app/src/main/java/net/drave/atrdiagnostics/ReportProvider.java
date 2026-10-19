package net.drave.atrdiagnostics;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

public final class ReportProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public String getType(Uri uri) { return "application/json"; }
    private File file(Uri uri) throws FileNotFoundException {
        if (!"/atr-report.json".equals(uri.getPath())) throw new FileNotFoundException();
        return new File(getContext().getCacheDir(), "atr-report.json");
    }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File f = file(uri);
            String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor c = new MatrixCursor(cols); Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
                else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            }
            c.addRow(row); return c;
        } catch (IOException e) { return null; }
    }
    public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
