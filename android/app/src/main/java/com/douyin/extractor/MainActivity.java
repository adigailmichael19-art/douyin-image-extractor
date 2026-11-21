package com.douyin.extractor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int REQUEST_PDF_IMAGES = 1001;
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s，。；、“”‘’）)】]+");

    private EditText linksInput;
    private EditText cookieInput;
    private CheckBox downloadVideo;
    private Button extractButton;
    private Button stopButton;
    private Button pdfButton;
    private Button clearButton;
    private TextView logView;
    private TextView statusView;
    private TextView progressPercent;
    private ProgressBar progress;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Thread worker;
    private volatile Extractor extractor;
    private List<Uri> pendingImages = new ArrayList<>();

    private File outputDir(String type, String child) {
        File base = getExternalFilesDir(type);
        if (base == null) {
            base = getExternalFilesDir(null);
        }
        if (base == null) {
            base = getFilesDir();
        }
        return new File(base, child);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        linksInput = findViewById(R.id.links_input);
        cookieInput = findViewById(R.id.cookie_input);
        downloadVideo = findViewById(R.id.download_video);
        extractButton = findViewById(R.id.extract_button);
        stopButton = findViewById(R.id.stop_button);
        pdfButton = findViewById(R.id.pdf_button);
        clearButton = findViewById(R.id.clear_button);
        logView = findViewById(R.id.log_view);
        statusView = findViewById(R.id.status_view);
        progressPercent = findViewById(R.id.progress_percent);
        progress = findViewById(R.id.progress);

        extractButton.setOnClickListener(v -> startExtraction());
        stopButton.setOnClickListener(v -> stopExtraction());
        pdfButton.setOnClickListener(v -> openImagePicker());
        clearButton.setOnClickListener(v -> {
            logView.setText(R.string.log_cleared);
            Toast.makeText(this, R.string.log_cleared_toast, Toast.LENGTH_SHORT).show();
        });
    }

    private List<String> collectLinks() {
        List<String> links = new ArrayList<>();
        String[] lines = linksInput.getText().toString().split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            Matcher matcher = URL_PATTERN.matcher(trimmed);
            if (!trimmed.isEmpty() && matcher.find()) {
                links.add(trimmed);
            }
        }
        return links;
    }

    private void startExtraction() {
        List<String> links = collectLinks();
        if (links.isEmpty()) {
            Toast.makeText(this, "请先粘贴至少一个有效的抖音链接", Toast.LENGTH_SHORT).show();
            return;
        }

        File root = outputDir(Environment.DIRECTORY_DOWNLOADS, "douyin_extractor");
        if (!root.exists() && !root.mkdirs()) {
            Toast.makeText(this, "输出目录无法创建", Toast.LENGTH_SHORT).show();
            return;
        }

        setExtractionWorking(true);
        logView.setText("");
        postProgress(0, 100);
        postLog("===== 开始提取, 共 " + links.size() + " 个链接 =====");
        postStatus("正在处理");

        String cookie = cookieInput.getText().toString();
        boolean includeVideo = downloadVideo.isChecked();
        worker = new Thread(() -> runExtraction(links, root, cookie, includeVideo));
        worker.start();
    }

    private void runExtraction(List<String> links, File root, String cookie, boolean includeVideo) {
        int fail = 0;
        boolean cancelled = false;
        try {
            extractor = new Extractor(cookie, includeVideo);
        } catch (Throwable t) {
            postLog("[失败] 提取器初始化失败: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
            handler.post(() -> finishExtraction(links.size(), false));
            return;
        }

        for (int index = 0; index < links.size(); index++) {
            if (worker == null || Thread.currentThread().isInterrupted()) {
                cancelled = true;
                break;
            }
            String line = links.get(index);
            int current = index + 1;
            postStatus("正在处理 " + current + "/" + links.size());
            postProgress(index, links.size());
            postLog("---------- [" + current + "/" + links.size() + "] ----------");
            try {
                Extractor.Result result = extractor.extract(line, this::postLog);
                extractor.saveResult(result, root, this::postLog);
            } catch (InterruptedException e) {
                cancelled = true;
                postLog("[停止] 任务已停止");
                break;
            } catch (Exception e) {
                postLog("[失败] " + e.getClass().getSimpleName() + ": " + e.getMessage());
                fail++;
            }
        }

        postProgress(links.size(), links.size());
        final int finalFail = fail;
        final boolean finalCancelled = cancelled;
        handler.post(() -> finishExtraction(finalFail, finalCancelled));
    }

    private void finishExtraction(int fail, boolean cancelled) {
        setExtractionWorking(false);
        postProgress(100, 100);
        if (cancelled) {
            postStatus(fail == 0 ? "已停止" : "已停止, " + fail + " 个失败");
            return;
        }
        if (fail == 0) {
            postStatus("全部成功");
            new AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage("全部链接提取完成!")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } else {
            postStatus("结束, " + fail + " 个失败");
            new AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage("任务结束, " + fail + " 个链接失败, 请查看日志")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void stopExtraction() {
        if (extractor != null) {
            extractor.stop();
            postLog("收到停止指令, 等待当前操作结束...");
        }
        stopButton.setText(R.string.stopping);
        stopButton.setEnabled(false);
    }

    private void setExtractionWorking(boolean working) {
        extractButton.setVisibility(working ? View.GONE : View.VISIBLE);
        extractButton.setEnabled(!working);
        stopButton.setVisibility(working ? View.VISIBLE : View.GONE);
        stopButton.setEnabled(working);
        stopButton.setText(R.string.stop);
        linksInput.setEnabled(!working);
        cookieInput.setEnabled(!working);
        downloadVideo.setEnabled(!working);
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        pendingImages = new ArrayList<>();
        try {
            startActivityForResult(intent, REQUEST_PDF_IMAGES);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开图片选择器: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PDF_IMAGES || resultCode != RESULT_OK || data == null) {
            return;
        }

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        pendingImages = uris;
        if (uris.isEmpty()) {
            Toast.makeText(this, "没有选择图片", Toast.LENGTH_SHORT).show();
            return;
        }
        exportSelectedImages(uris);
    }

    private void exportSelectedImages(List<Uri> uris) {
        File dir = outputDir(Environment.DIRECTORY_DOCUMENTS, "douyin_pdfs");
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(this, "PDF 目录无法创建", Toast.LENGTH_SHORT).show();
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        File output = new File(dir, "图片合集_" + stamp + ".pdf");

        pdfButton.setEnabled(false);
        postProgress(0, 100);
        Thread pdfWorker = new Thread(() -> {
            try {
                postStatus("正在转换 " + uris.size() + " 张图片");
                postLog("开始图片转 PDF, 共 " + uris.size() + " 张");
                PdfExporter.export(this, uris, output, (current, total) ->
                        postProgress(current, total));
                postLog("PDF 完成 -> " + output.getAbsolutePath());
                handler.post(() -> {
                    pdfButton.setEnabled(true);
                    postProgress(100, 100);
                    postStatus("PDF 转换完成");
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.app_name)
                            .setMessage("PDF 已保存:\n" + output.getAbsolutePath())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            } catch (Exception e) {
                postLog("[失败] 图片转 PDF: " + e.getMessage());
                handler.post(() -> {
                    pdfButton.setEnabled(true);
                    postStatus("PDF 转换失败");
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.app_name)
                            .setMessage("图片转 PDF 失败:\n" + e.getMessage())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            }
        });
        pdfWorker.start();
    }

    private void postLog(String message) {
        handler.post(() -> logView.append(message + "\n"));
    }

    private void postStatus(String status) {
        handler.post(() -> {
            statusView.setText(status);
            int color = status.contains("失败")
                    ? R.color.danger
                    : (status.contains("成功") || status.contains("完成"))
                    ? R.color.accent
                    : R.color.muted;
            statusView.setTextColor(getColor(color));
        });
    }

    private void postProgress(int current, int total) {
        int percent = total == 0 ? 0 : (int) Math.round(current * 100.0 / total);
        handler.post(() -> {
            progress.setProgress(percent);
            progressPercent.setText(String.format(Locale.ROOT, "%d%%", percent));
        });
    }
}
