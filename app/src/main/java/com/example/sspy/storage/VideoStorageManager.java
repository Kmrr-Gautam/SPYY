package com.example.sspy.storage;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import androidx.camera.video.MediaStoreOutputOptions;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class VideoStorageManager {
    private static final String TAG = "SSPY.VideoStorage";
    private static final String RELATIVE_PATH = "Movies/SSPY/";
    private static final DateTimeFormatter FILE_NAME_FORMAT =
            DateTimeFormatter.ofPattern("'SSPY_'yyyyMMdd_HHmmss_SSS");

    private final ContentResolver resolver;

    public VideoStorageManager(Context context) {
        resolver = context.getContentResolver();
    }

    public MediaStoreOutputOptions createPendingOutput() {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                uniqueDisplayName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        return new MediaStoreOutputOptions.Builder(
                resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values)
                .build();
    }

    private String uniqueDisplayName() {
        String baseName = LocalDateTime.now().format(FILE_NAME_FORMAT);
        String candidate = baseName + ".mp4";
        int suffix = 1;
        while (displayNameExists(candidate)) {
            candidate = baseName + "_" + suffix++ + ".mp4";
        }
        return candidate;
    }

    private boolean displayNameExists(String displayName) {
        try (Cursor cursor = resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                        + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{RELATIVE_PATH, displayName},
                null)) {
            if (cursor == null) {
                throw new IllegalStateException("MediaStore did not return a filename query result");
            }
            return cursor.moveToFirst();
        }
    }

    public boolean publishCompletedVideo(Uri uri) {
        if (uri == null || getSize(uri) <= 0L) {
            return false;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        int updatedRows = resolver.update(uri, values, null, null);
        if (updatedRows != 1) {
            return false;
        }
        return getSize(uri) > 0L;
    }

    public void discard(Uri uri) {
        if (uri == null) {
            return;
        }
        int deletedRows = resolver.delete(uri, null, null);
        if (deletedRows != 1) {
            Log.e(TAG, "Failed to remove incomplete MediaStore item: " + uri);
        }
    }

    private long getSize(Uri uri) {
        try (Cursor cursor = resolver.query(
                uri,
                new String[]{MediaStore.MediaColumns.SIZE},
                null,
                null,
                null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return 0L;
            }
            return cursor.getLong(0);
        }
    }
}
