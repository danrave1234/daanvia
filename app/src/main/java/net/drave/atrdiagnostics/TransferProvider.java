package net.drave.atrdiagnostics;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Hands this app's data to the new Daanvia (app id net.drave.daanvia, signed with the private release key) so a rider
 * moving to it keeps their downloaded maps, saved places, bike pairing and settings. The new app lists the files with
 * query() and reads each with openFile(). Nothing is written or deleted here, and no other app may read anything: the
 * caller must be that package, signed with that key (debug builds also accept the shared test key, for emulator tests).
 */
public final class TransferProvider extends ContentProvider {
    static final String AUTHORITY = "net.drave.atrdiagnostics.transfer";
    private static final String NEW_APP = "net.drave.daanvia";
    // SHA-256 of the new app's signing certificates: the private release key, and the shared test key used for debug builds.
    private static final String RELEASE_CERT = "AEA0F1875FD90E23A6EC6A65ABAA1C7836C50E20F4744F2377A01103DC80A853";
    private static final String TEST_CERT = "C86BB007EEC3B5AF30BE9D2F6F92E5E995B3F58E37B872765AE08C746D09FA4F";
    /** The parts of the app's private folder that move: not cache or code_cache, which Android rebuilds. */
    private static final String[] ROOTS = {"files", "shared_prefs", "databases"};

    public boolean onCreate() { return true; }

    private void allow() {
        Context c = getContext(); String caller = getCallingPackage();
        if (c == null || !NEW_APP.equals(caller)) throw new SecurityException("Only the new Daanvia can read this");
        PackageManager pm = c.getPackageManager();
        boolean ok = pm.hasSigningCertificate(NEW_APP, unhex(RELEASE_CERT), PackageManager.CERT_INPUT_SHA256);
        if (!ok && (c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            ok = pm.hasSigningCertificate(NEW_APP, unhex(TEST_CERT), PackageManager.CERT_INPUT_SHA256);
        if (!ok) throw new SecurityException("The new Daanvia isn't signed with Daanvia's key");
    }

    /** Every file to move, as rows of (path relative to the app folder, size in bytes). */
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        allow();
        MatrixCursor out = new MatrixCursor(new String[]{"path", "size"});
        File data = getContext().getDataDir();
        for (String root : ROOTS) list(new File(data, root), root, out);
        return out;
    }
    private static void list(File dir, String rel, MatrixCursor out) {
        File[] kids = dir.listFiles(); if (kids == null) return;
        for (File f : kids) { String r = rel + "/" + f.getName(); if (f.isDirectory()) list(f, r, out); else if (f.isFile()) out.addRow(new Object[]{r, f.length()}); }
    }

    /** One file, read-only: content://net.drave.atrdiagnostics.transfer/file?path=files/regions/... */
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        allow();
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        String rel = uri.getQueryParameter("path"); if (rel == null) throw new FileNotFoundException();
        File data = getContext().getDataDir(), f = new File(data, rel);
        try {
            String path = f.getCanonicalPath(); boolean inside = false;
            for (String root : ROOTS) if (path.startsWith(new File(data, root).getCanonicalPath() + File.separator)) inside = true;
            if (!inside || !f.isFile()) throw new FileNotFoundException(rel);
        } catch (IOException e) { throw new FileNotFoundException(rel); }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static byte[] unhex(String h) { byte[] b = new byte[h.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte)Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16); return b; }

    public String getType(Uri uri) { return null; }
    public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
