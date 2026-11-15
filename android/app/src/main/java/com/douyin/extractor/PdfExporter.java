package com.douyin.extractor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Merge selected local images into one PDF. Transparency is flattened on white,
 * EXIF rotation is normalized, and pages are A4-sized at 595x842 points.
 */
public final class PdfExporter {

    public interface ProgressCallback {
        void onProgress(int current, int total);
    }

    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN = 32;

    private PdfExporter() {
    }

    public static File export(Context context, List<Uri> uris, File outputPdf,
                               ProgressCallback callback) throws Exception {
        if (uris == null || uris.isEmpty()) {
            throw new Exception("请选择至少一张图片");
        }
        if (outputPdf == null) {
            throw new Exception("请选择 PDF 保存位置");
        }

        File parent = outputPdf.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        File part = new File(outputPdf.getParentFile(), outputPdf.getName() + ".part");
        PdfDocument document = new PdfDocument();
        int total = uris.size();

        try {
            for (int index = 0; index < total; index++) {
                Bitmap bitmap = decodeBitmap(context, uris.get(index));
                Bitmap oriented = normalizeOrientation(context, uris.get(index), bitmap);
                Bitmap flat = flattenTransparency(oriented);

                PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                        PAGE_WIDTH, PAGE_HEIGHT, index + 1).create();
                PdfDocument.Page page = document.startPage(pageInfo);
                Canvas canvas = page.getCanvas();
                canvas.drawColor(Color.WHITE);

                float availableWidth = PAGE_WIDTH - MARGIN * 2f;
                float availableHeight = PAGE_HEIGHT - MARGIN * 2f;
                float scale = Math.min(
                        availableWidth / Math.max(1, flat.getWidth()),
                        availableHeight / Math.max(1, flat.getHeight()));
                float drawnWidth = flat.getWidth() * scale;
                float drawnHeight = flat.getHeight() * scale;
                float left = (PAGE_WIDTH - drawnWidth) / 2f;
                float top = (PAGE_HEIGHT - drawnHeight) / 2f;

                Matrix matrix = new Matrix();
                matrix.postScale(scale, scale);
                matrix.postTranslate(left, top);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                canvas.drawBitmap(flat, matrix, paint);
                document.finishPage(page);

                flat.recycle();
                if (callback != null) {
                    callback.onProgress(index + 1, total);
                }
            }

            FileOutputStream output = new FileOutputStream(part);
            try {
                document.writeTo(output);
            } finally {
                output.close();
            }
            if (outputPdf.exists() && !outputPdf.delete()) {
                throw new IOException("无法覆盖旧 PDF 文件");
            }
            if (!part.renameTo(outputPdf)) {
                throw new IOException("PDF 临时文件改名失败");
            }
            return outputPdf;
        } finally {
            document.close();
            if (part.exists()) {
                part.delete();
            }
        }
    }

    private static Bitmap decodeBitmap(Context context, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream boundsStream = context.getContentResolver().openInputStream(uri);
        if (boundsStream == null) {
            throw new IOException("无法读取所选图片");
        }
        BitmapFactory.decodeStream(boundsStream, null, bounds);
        boundsStream.close();

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, 1600);
        InputStream imageStream = context.getContentResolver().openInputStream(uri);
        if (imageStream == null) {
            throw new IOException("无法打开所选图片");
        }
        try {
            Bitmap bitmap = BitmapFactory.decodeStream(imageStream, null, options);
            if (bitmap == null) {
                throw new IOException("图片解码失败: " + uri);
            }
            return bitmap;
        } finally {
            imageStream.close();
        }
    }

    private static int calculateSampleSize(int width, int height, int maxDimension) {
        int sample = 1;
        int longSide = Math.max(width, height);
        while (longSide / sample > maxDimension) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap normalizeOrientation(Context context, Uri uri, Bitmap source)
            throws IOException {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        InputStream exifStream = context.getContentResolver().openInputStream(uri);
        if (exifStream != null) {
            try {
                ExifInterface exif = new ExifInterface(exifStream);
                orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } finally {
                exifStream.close();
            }
        }

        int degrees;
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                degrees = 90;
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                degrees = 180;
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                degrees = 270;
                break;
            default:
                degrees = 0;
        }

        if (degrees == 0) {
            return source;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(),
                source.getHeight(), matrix, true);
        if (rotated != source) {
            source.recycle();
        }
        return rotated;
    }

    private static Bitmap flattenTransparency(Bitmap source) {
        if (source.hasAlpha()) {
            Bitmap background = Bitmap.createBitmap(
                    source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(background);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(source, 0f, 0f, null);
            source.recycle();
            return background;
        }
        return source;
    }
}
