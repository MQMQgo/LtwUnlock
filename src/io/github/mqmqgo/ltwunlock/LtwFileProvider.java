package io.github.mqmqgo.ltwunlock;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal FileProvider (no AndroidX) for sharing cache/files exports.
 */
public final class LtwFileProvider extends ContentProvider {
    private static String sAuthority;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        if (info.exported) {
            throw new SecurityException("Provider must not be exported");
        }
        if (!info.grantUriPermissions) {
            throw new SecurityException("Provider must grant uri permissions");
        }
        sAuthority = info.authority;
    }

    public static Uri getUriForFile(Context context, String authority, File file) {
        if (authority == null) {
            authority = context.getPackageName() + ".fileprovider";
        }
        sAuthority = authority;
        String path = file.getAbsolutePath();
        File cache = context.getCacheDir();
        File files = context.getFilesDir();
        String rel;
        if (path.startsWith(cache.getAbsolutePath())) {
            rel = "cache" + path.substring(cache.getAbsolutePath().length());
        } else if (path.startsWith(files.getAbsolutePath())) {
            rel = "files" + path.substring(files.getAbsolutePath().length());
        } else {
            throw new IllegalArgumentException("File outside cache/files: " + path);
        }
        // Encode path segments carefully
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .encodedPath(rel.replace("//", "/"))
                .build();
    }

    private File fileForUri(Uri uri) {
        String p = uri.getPath();
        if (p == null) {
            return null;
        }
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        if (p.startsWith("cache/")) {
            return new File(ctx.getCacheDir(), p.substring("cache/".length()));
        }
        if (p.startsWith("files/")) {
            return new File(ctx.getFilesDir(), p.substring("files/".length()));
        }
        if (p.equals("cache") || p.equals("files")) {
            return p.equals("cache") ? ctx.getCacheDir() : ctx.getFilesDir();
        }
        return null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileForUri(uri);
        if (f == null || !f.exists()) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        int m = ParcelFileDescriptor.parseMode(mode == null ? "r" : mode);
        return ParcelFileDescriptor.open(f, m);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = fileForUri(uri);
        if (f == null) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] values = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                values[i] = f.getName();
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                values[i] = f.length();
            } else {
                values[i] = null;
            }
        }
        MatrixCursor c = new MatrixCursor(cols, 1);
        c.addRow(values);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        File f = fileForUri(uri);
        if (f == null) {
            return "application/octet-stream";
        }
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) {
                return mime;
            }
        }
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
