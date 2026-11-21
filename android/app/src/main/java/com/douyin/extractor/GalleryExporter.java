package com.douyin.extractor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies a finished file into the public gallery so other apps (相册/微信/相册备份)
 * can see it. Returns null when the copy fails; callers then keep the private copy.
 */
public final class GalleryExporter {

    private GalleryExporter() {
    }

    public static Uri publish(Context context, File source, String album) {
        if (source == null || !source.exists() || source.length() == 0) {
            return null;
        }
        String name = source.getName();
        boolean video = name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4");
        String mime = video ? "video/mp4" : mimeFor(name);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return publishScoped(context, source, album, video, mime);
            }
            return publishLegacy(context, source, album, video, mime);
        } catch (Exception e) {
            return null;
        }
    }

    private static Uri publishScoped(Context context, File source, String album,
                                     boolean video, String mime) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, source.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES)
                        + File.separator + album);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri collection = video
                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri target = resolver.insert(collection, values);
        if (target == null) {
            throw new IOException("媒体库插入失败");
        }

        OutputStream output = null;
        InputStream input = null;
        try {
            output = resolver.openOutputStream(target);
            if (output == null) {
                throw new IOException("媒体库输出流不可用");
            }
            input = new FileInputStream(source);
            copy(input, output);
        } catch (IOException e) {
            resolver.delete(target, null, null);
            throw e;
        } finally {
            closeQuietly(input);
            closeQuietly(output);
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(target, done, null, null);
        return target;
    }

    @SuppressWarnings("deprecation")
    private static Uri publishLegacy(Context context, File source, String album,
                                     boolean video, String mime) throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), album);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("相册目录创建失败");
        }
        File target = new File(dir, source.getName());
        if (target.exists() && !target.delete()) {
            throw new IOException("无法覆盖相册旧文件");
        }
        InputStream input = null;
        OutputStream output = null;
        try {
            input = new FileInputStream(source);
            output = new java.io.FileOutputStream(target);
            copy(input, output);
        } finally {
            closeQuietly(input);
            closeQuietly(output);
        }
        MediaScannerConnection.scanFile(context,
                new String[]{target.getAbsolutePath()}, new String[]{mime}, null);
        return Uri.fromFile(target);
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[65536];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    private static String mimeFor(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Nothing useful to do here.
        }
    }
}
