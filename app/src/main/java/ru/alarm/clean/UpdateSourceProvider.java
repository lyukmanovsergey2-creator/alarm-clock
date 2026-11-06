package ru.alarm.clean;

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
 * Отдаёт системному установщику уже скачанный APK обновления
 * (cache/update/update.apk) по content:// — без внешних библиотек.
 * Провайдер закрытый (exported=false), доступ выдаётся разово
 * через FLAG_GRANT_READ_URI_PERMISSION.
 */
public class UpdateSourceProvider extends ContentProvider {

    static final String AUTHORITY = "ru.alarm.clean.apk";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("только чтение");
        }
        File apk = latestApk();
        if (apk == null || !apk.exists()) {
            throw new FileNotFoundException("файл обновления не найден");
        }
        return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File apk = latestApk();
        if (projection == null) {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }
        MatrixCursor cursor = new MatrixCursor(projection, 1);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) {
                row[i] = "AlarmClock-update.apk";
            } else if (OpenableColumns.SIZE.equals(projection[i])) {
                row[i] = apk != null && apk.exists() ? apk.length() : 0L;
            } else {
                row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    private File latestApk() {
        if (getContext() == null) {
            return null;
        }
        return new File(getContext().getCacheDir(), "update/update.apk");
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
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
