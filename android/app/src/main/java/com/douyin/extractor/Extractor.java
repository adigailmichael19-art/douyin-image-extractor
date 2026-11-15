package com.douyin.extractor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mobile extraction engine ported from the desktop Python tool.
 * It parses public share pages, locates the embedded aweme payload, and downloads
 * images or the playable video URL. It does not bypass login, paywall, or access control.
 */
public final class Extractor {

    public interface Logger {
        void log(String message);
    }

    public static final class ExtractorException extends Exception {
        public ExtractorException(String message) {
            super(message);
        }

        public ExtractorException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class Result {
        public String awemeId = "";
        public String kind = "video";
        public String desc = "";
        public String author = "";
        public String sourceUrl = "";
        public List<String> imageUrls = new ArrayList<>();
        public String videoUrl = "";
    }

    private static final String UA_MOBILE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) "
                    + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1";
    private static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\\\s，。；、“”‘’）)】]+");
    private static final Pattern ID_PATTERN =
            Pattern.compile("(?:/note/|/video/|modal_id=|aweme_id=)(\\\\d{10,})");
    private static final Pattern ROUTER_PATTERN =
            Pattern.compile("window\\\\._ROUTER_DATA\\\\s*=\\\\s*(\\\\{.*?})\\\\s*;?\\\\s*</script>",
                    Pattern.DOTALL);
    private static final Pattern RENDER_PATTERN =
            Pattern.compile("id=\"RENDER_DATA\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern EXTENSION_PATTERN =
            Pattern.compile("\\\\.(jpe?g|png|webp|heic)", Pattern.CASE_INSENSITIVE);

    private static final String[] ANTIBOT_MARKERS = {
            "acrawler", "verify", "captcha", "验证码", "安全验证", "访问过于频繁", "滑块"
    };

    private final String cookie;
    private final boolean wantVideo;
    private volatile boolean stopped;

    public Extractor(String cookie, boolean wantVideo) {
        this.cookie = cookie == null ? "" : cookie.trim();
        this.wantVideo = wantVideo;
    }

    public void stop() {
        stopped = true;
    }

    public Result extract(String rawText, Logger logger)
            throws ExtractorException, IOException, JSONException, InterruptedException {
        String url = extractFirstUrl(rawText);
        logger.log("链接: " + url);

        String[] resolved = resolveAweme(url, logger);
        String awemeId = resolved[0];
        String kind = resolved[1];
        logger.log("作品 ID: " + awemeId + "    类型: " + kind);

        JSONObject aweme = fetchAwemeData(awemeId, kind, logger);
        Result result = buildResult(url, awemeId, aweme, logger);
        return result;
    }

    private String extractFirstUrl(String text) throws ExtractorException {
        Matcher matcher = URL_PATTERN.matcher(text == null ? "" : text.trim());
        if (!matcher.find()) {
            throw new ExtractorException("内容中没有找到 http(s) 链接");
        }
        return matcher.group(0);
    }

    private String[] resolveAweme(String url, Logger logger)
            throws ExtractorException, IOException, JSONException, InterruptedException {
        Matcher matcher = ID_PATTERN.matcher(url);
        String kind = url.contains("/note/") ? "note" : "video";
        if (matcher.find()) {
            return new String[]{matcher.group(1), kind};
        }

        FetchResult result = fetch(url, UA_MOBILE, 15000);
        matcher = ID_PATTERN.matcher(result.finalUrl);
        if (!matcher.find()) {
            matcher = ID_PATTERN.matcher(result.body);
        }
        if (!matcher.find()) {
            throw new ExtractorException("无法解析作品 ID, 最终地址: " + result.finalUrl);
        }
        kind = result.finalUrl.contains("/note/") ? "note" : "video";
        return new String[]{matcher.group(1), kind};
    }

    private JSONObject fetchAwemeData(String awemeId, String kind, Logger logger)
            throws ExtractorException, IOException, JSONException, InterruptedException {
        String[][] pages = {
                {"https://www.iesdouyin.com/share/" + kind + "/" + awemeId, UA_MOBILE},
                {"https://www.douyin.com/" + kind + "/" + awemeId, UA_DESKTOP}
        };

        for (int attempt = 0; attempt <= 2; attempt++) {
            for (String[] page : pages) {
                checkStopped();
                String pageUrl = page[0];
                String ua = page[1];
                logger.log("  请求页面: " + pageUrl);
                FetchResult result;
                try {
                    result = fetch(pageUrl, ua, 15000);
                } catch (IOException e) {
                    logger.log("  请求异常: " + e.getMessage());
                    continue;
                }

                JSONObject data = parseEmbeddedJson(result.body);
                if (data != null) {
                    Object aweme = findAweme(data);
                    if (aweme instanceof JSONObject) {
                        logger.log("  页面数据解析成功");
                        return (JSONObject) aweme;
                    }
                }

                if (isAntibot(result.body)) {
                    logger.log("  返回安全验证/反爬空壳 (HTTP " + result.statusCode
                            + ", " + result.body.length() + " 字节)");
                } else {
                    logger.log("  未找到内嵌数据 (HTTP " + result.statusCode
                            + ", " + result.body.length() + " 字节)");
                }
            }

            if (attempt < 2) {
                long waitSeconds = 2L * (attempt + 1);
                logger.log("  " + waitSeconds + " 秒后进行第 " + (attempt + 2) + " 次尝试...");
                Thread.sleep(waitSeconds * 1000L);
            }
        }
        throw new ExtractorException("无法获取作品数据, 可能触发风控; 可填写 Cookie 后重试");
    }

    private JSONObject parseEmbeddedJson(String html) throws JSONException {
        Matcher router = ROUTER_PATTERN.matcher(html);
        if (router.find()) {
            try {
                return new JSONObject(router.group(1));
            } catch (JSONException ignored) {
                // Continue to RENDER_DATA fallback.
            }
        }

        Matcher render = RENDER_PATTERN.matcher(html);
        if (render.find()) {
            String decoded;
            try {
                decoded = URLDecoder.decode(render.group(1), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                decoded = render.group(1);
            }
            return new JSONObject(decoded);
        }
        return null;
    }

    private Object findAweme(Object node) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.has("images") || (object.has("video") && object.has("desc"))) {
                return object;
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                Object found = findAweme(object.opt(keys.next()));
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Object found = findAweme(array.opt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Result buildResult(String sourceUrl, String awemeId, JSONObject aweme, Logger logger)
            throws JSONException {
        Result result = new Result();
        result.awemeId = awemeId;
        result.sourceUrl = sourceUrl;
        result.desc = aweme.optString("desc", "");
        JSONObject author = aweme.optJSONObject("author");
        result.author = author == null ? "" : author.optString("nickname", "");
        logger.log("作者: " + (result.author.isEmpty() ? "未知" : result.author)
                + "    文案: " + (result.desc.isEmpty() ? "(无)" : result.desc));

        JSONArray images = aweme.optJSONArray("images");
        if (images != null) {
            result.kind = "note";
            for (int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                if (image == null) {
                    continue;
                }
                JSONArray urlList = image.optJSONArray("url_list");
                if (urlList != null && urlList.length() > 0) {
                    result.imageUrls.add(urlList.optString(urlList.length() - 1, ""));
                }
            }
            return result;
        }

        result.kind = "video";
        JSONObject video = aweme.optJSONObject("video");
        JSONObject playAddr = video == null ? null : video.optJSONObject("play_addr");
        JSONArray urlList = playAddr == null ? null : playAddr.optJSONArray("url_list");
        if (urlList != null && urlList.length() > 0) {
            result.videoUrl = urlList.optString(0, "").replace("playwm", "play");
        }
        return result;
    }

    public File saveResult(Result result, File root, Logger logger)
            throws IOException, InterruptedException, ExtractorException, JSONException {
        String dirName = safeName(result.desc, 60) + "_" + result.awemeId;
        File saveDir = new File(root, dirName);
        if (!saveDir.exists() && !saveDir.mkdirs()) {
            throw new ExtractorException("无法创建保存目录: " + saveDir.getAbsolutePath());
        }

        JSONObject meta = new JSONObject();
        meta.put("aweme_id", result.awemeId);
        meta.put("type", result.kind);
        meta.put("desc", result.desc);
        meta.put("author", result.author);
        meta.put("source_url", result.sourceUrl);
        writeText(new File(saveDir, "info.json"), meta.toString(2));
        writeText(new File(saveDir, "caption.txt"),
                "作者: " + result.author + "\n文案: " + result.desc + "\n链接: " + result.sourceUrl + "\n");

        if ("note".equals(result.kind)) {
            logger.log("图文笔记共 " + result.imageUrls.size() + " 张图片, 开始下载...");
            int ok = 0;
            for (int i = 0; i < result.imageUrls.size(); i++) {
                checkStopped();
                String imageUrl = result.imageUrls.get(i);
                String suffix = extensionFor(imageUrl);
                File target = new File(saveDir, String.format(Locale.ROOT, "%03d%s", i + 1, suffix));
                logger.log("  (" + (i + 1) + "/" + result.imageUrls.size() + ") 下载中...");
                if (download(imageUrl, target)) {
                    ok++;
                }
            }
            logger.log("图片完成: " + ok + "/" + result.imageUrls.size() + " 张 -> " + saveDir);
        } else if (wantVideo && !result.videoUrl.isEmpty()) {
            logger.log("视频作品, 开始下载...");
            File target = new File(saveDir, "video.mp4");
            if (download(result.videoUrl, target)) {
                logger.log("视频完成 -> " + target);
            } else {
                throw new ExtractorException("视频下载失败");
            }
        } else if (!result.videoUrl.isEmpty()) {
            logger.log("已跳过视频下载, 地址: " + result.videoUrl);
        } else {
            throw new ExtractorException("未找到视频地址, 作品可能权限受限");
        }
        return saveDir;
    }

    private boolean download(String url, File target) throws InterruptedException {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        for (int attempt = 0; attempt < 3; attempt++) {
            checkStopped();
            try {
                FetchResult result = downloadToFile(url, part);
                if (result.statusCode >= 400) {
                    throw new IOException("HTTP " + result.statusCode);
                }
                if (part.exists() && part.renameTo(target)) {
                    return true;
                }
            } catch (IOException e) {
                if (part.exists()) {
                    part.delete();
                }
                if (attempt == 2) {
                    return false;
                }
                Thread.sleep(1500L * (attempt + 1));
            }
        }
        return false;
    }

    private FetchResult downloadToFile(String url, File target) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(url, UA_MOBILE, 30000);
            connection.setRequestProperty("Referer", "https://www.douyin.com/");
            InputStream input = connection.getResponseCode() >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) {
                return new FetchResult("", connection.getURL().toString(), connection.getResponseCode());
            }
            java.io.FileOutputStream output = new java.io.FileOutputStream(target);
            try {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            } finally {
                output.close();
                input.close();
            }
            return new FetchResult("", connection.getURL().toString(), connection.getResponseCode());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private FetchResult fetch(String url, String ua, int timeoutMs) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(url, ua, timeoutMs);
            InputStream input = connection.getResponseCode() >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            String body = "";
            if (input != null) {
                body = readAll(input);
            }
            return new FetchResult(body, connection.getURL().toString(), connection.getResponseCode());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(String url, String ua, int timeoutMs) throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", ua);
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        if (!cookie.isEmpty()) {
            connection.setRequestProperty("Cookie", cookie);
        }
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        return connection;
    }

    private String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private void writeText(File file, String content) throws IOException {
        FileOutputStream output = new FileOutputStream(file);
        OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }

    private boolean isAntibot(String html) {
        String lower = html.toLowerCase(Locale.ROOT);
        for (String marker : ANTIBOT_MARKERS) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String extensionFor(String url) {
        Matcher matcher = EXTENSION_PATTERN.matcher(url);
        if (matcher.find()) {
            String ext = matcher.group(1).toLowerCase(Locale.ROOT).replace("jpeg", "jpg");
            return "." + ext;
        }
        return ".jpg";
    }

    static String safeName(String name, int limit) {
        String cleaned = (name == null ? "" : name).trim()
                .replaceAll("[\\\\/:*?\"<>|\\\\r\\\\n\\\\t]", "_");
        if (cleaned.isEmpty()) {
            cleaned = "douyin";
        }
        if (cleaned.length() > limit) {
            cleaned = cleaned.substring(0, limit);
        }
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.isEmpty() ? "douyin" : cleaned;
    }

    private void checkStopped() throws InterruptedException {
        if (stopped) {
            throw new InterruptedException("任务已停止");
        }
    }

    private static final class FetchResult {
        final String body;
        final String finalUrl;
        final int statusCode;

        FetchResult(String body, String finalUrl, int statusCode) {
            this.body = body;
            this.finalUrl = finalUrl;
            this.statusCode = statusCode;
        }
    }
}
