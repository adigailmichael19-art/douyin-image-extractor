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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mobile extraction engine ported from the desktop Python tool.
 *
 * The public Douyin share page only renders the aweme payload when the request
 * carries a visitor cookie issued by Douyin itself (ttwid). A bare
 * HttpURLConnection sends no cookies, so the page degrades into a ~3KB shell
 * with no item data — that was the reason extraction failed on real phones.
 * This engine keeps an in-memory cookie jar and performs a visitor bootstrap
 * before touching the share page.
 *
 * It parses public share pages only. It does not bypass login, paywall, captcha
 * or any other access control.
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
        public List<List<String>> imageCandidates = new ArrayList<>();
        public List<String> videoCandidates = new ArrayList<>();
        public String videoUrl = "";
    }

    /** Endpoints and headers; overridable so JVM tests can point at a local server. */
    static final class Config {
        String douyinHome = "https://www.douyin.com/";
        String iesdouyinHome = "https://www.iesdouyin.com/";
        String shareBase = "https://www.iesdouyin.com/share";
        String desktopBase = "https://www.douyin.com";
        String ttwidRegister = "https://ttwid.bytedance.com/ttwid/union/register/";
        String ttwidBody = "{\"region\":\"cn\",\"aid\":1768,\"needFid\":false,"
                + "\"service\":\"www.ixigua.com\",\"migrate_info\":{\"ticket\":\"\",\"source\":\"node\"},"
                + "\"cbUrlProtocol\":\"https\",\"union\":true}";
        String mobileUa =
                "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) "
                        + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1";
        String desktopUa =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
        int timeoutMs = 20000;
    }

    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^，。；、“”‘’）)】\\s]+");
    private static final Pattern ID_PATTERN =
            Pattern.compile("(?:/note/|/video/|/slides/|modal_id=|aweme_id=)([0-9]{10,})");
    // No backslash escapes inside the character classes: some Android releases
    // run an ICU regex engine that rejects "\\{" / "\\}", which used to crash here.
    private static final Pattern ROUTER_PATTERN =
            Pattern.compile("window[.]_ROUTER_DATA[ ]*=[ ]*([{].*?[}])[ ]*;?[ ]*</script>",
                    Pattern.DOTALL);
    private static final Pattern RENDER_PATTERN =
            Pattern.compile("id=\"RENDER_DATA\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern EXTENSION_PATTERN =
            Pattern.compile("[.](jpe?g|png|webp|heic)", Pattern.CASE_INSENSITIVE);

    private static final String[] ANTIBOT_MARKERS = {
            "acrawler", "captcha", "验证码", "安全验证", "访问过于频繁", "滑块"
    };

    private final Config config;
    private final String cookieSeed;
    private final boolean wantVideo;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private volatile boolean stopped;
    private boolean cookiesReady;

    public Extractor(String cookie, boolean wantVideo) {
        this(cookie, wantVideo, new Config());
    }

    Extractor(String cookie, boolean wantVideo, Config config) {
        this.config = config;
        this.cookieSeed = cookie == null ? "" : cookie.trim();
        this.wantVideo = wantVideo;
        parseCookieHeader(this.cookieSeed);
    }

    public void stop() {
        stopped = true;
    }

    public Result extract(String rawText, Logger logger)
            throws ExtractorException, IOException, JSONException, InterruptedException {
        String url = extractFirstUrl(rawText);
        logger.log("链接: " + url);

        ensureVisitorCookies(logger);

        String[] resolved = resolveAweme(url, logger);
        String awemeId = resolved[0];
        String kind = resolved[1];
        logger.log("作品 ID: " + awemeId + "    类型: " + kind);

        JSONObject aweme = fetchAwemeData(awemeId, kind, logger);
        return buildResult(url, awemeId, aweme, logger);
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
        String awemeId = matchAwemeId(url);
        String kind = url.contains("/note/") || url.contains("/slides/") ? "note" : "video";
        if (awemeId != null) {
            return new String[]{awemeId, kind};
        }

        FetchResult result = fetch(url, config.mobileUa, config.timeoutMs);
        awemeId = matchAwemeId(result.finalUrl);
        if (awemeId == null) {
            awemeId = matchAwemeId(result.body);
        }
        if (awemeId == null) {
            throw new ExtractorException("无法解析作品 ID, 最终地址: " + result.finalUrl);
        }
        kind = result.finalUrl.contains("/note/") || result.finalUrl.contains("/slides/")
                ? "note" : "video";
        return new String[]{awemeId, kind};
    }

    /** Fresh matcher per attempt: calling find() twice on one matcher resumes past the match. */
    private String matchAwemeId(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = ID_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    // ------------------------------------------------------------------
    // Visitor cookie bootstrap
    // ------------------------------------------------------------------

    /**
     * Douyin issues a visitor ttwid through its union-register endpoint. It is
     * the only bootstrap that works from a plain Android HttpURLConnection: a
     * direct hit on iesdouyin.com gets redirected to douyin.com's JS anti-bot
     * page instead of issuing a cookie. Without ttwid the share page refuses to
     * render item data.
     */
    private void ensureVisitorCookies(Logger logger) throws InterruptedException {
        if (cookiesReady) {
            return;
        }
        // A user supplied Cookie already carrying ttwid is enough.
        if (cookies.containsKey("ttwid")) {
            cookiesReady = true;
            logger.log("  使用提供的 Cookie: " + describeCookieNames());
            return;
        }

        for (int round = 1; round <= 2 && !cookies.containsKey("ttwid"); round++) {
            checkStopped();
            logger.log("  获取访客 Cookie (第 " + round + " 次)");
            try {
                fetchTtwid();
            } catch (IOException e) {
                logger.log("    ttwid 注册失败: " + e.getMessage());
            }
            if (!cookies.containsKey("ttwid")) {
                try {
                    fetch(config.douyinHome, config.mobileUa, config.timeoutMs);
                } catch (IOException e) {
                    logger.log("    douyin.com 引导失败: " + e.getMessage());
                }
                try {
                    fetch(config.iesdouyinHome, config.mobileUa, config.timeoutMs);
                } catch (IOException e) {
                    logger.log("    iesdouyin.com 引导失败: " + e.getMessage());
                }
            }
            if (cookies.isEmpty()) {
                Thread.sleep(500L * round);
            }
        }

        cookiesReady = true;
        if (cookies.containsKey("ttwid")) {
            logger.log("  访客 Cookie 就绪: " + describeCookieNames());
        } else {
            logger.log("  未获取到 ttwid, 仍尝试直接请求 (可在高级选项填写 Cookie)");
        }
    }

    private String describeCookieNames() {
        StringBuilder builder = new StringBuilder();
        for (String name : cookies.keySet()) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(name);
        }
        return builder.toString();
    }

    private void parseCookieHeader(String header) {
        if (header == null || header.isEmpty()) {
            return;
        }
        for (String part : header.split(";")) {
            String pair = part.trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (!name.isEmpty()) {
                cookies.put(name, value);
            }
        }
    }

    private void absorbCookies(HttpURLConnection connection) {
        Map<String, List<String>> fields = connection.getHeaderFields();
        if (fields == null) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
            String key = entry.getKey();
            if (key == null || !"Set-Cookie".equalsIgnoreCase(key)) {
                continue;
            }
            List<String> values = entry.getValue();
            if (values == null) {
                continue;
            }
            for (String raw : values) {
                if (raw == null) {
                    continue;
                }
                int semi = raw.indexOf(';');
                String pair = semi >= 0 ? raw.substring(0, semi) : raw;
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                if (!name.isEmpty()) {
                    cookies.put(name, value);
                }
            }
        }
    }

    private String cookieHeader() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------
    // Share page -> aweme payload
    // ------------------------------------------------------------------

    private JSONObject fetchAwemeData(String awemeId, String kind, Logger logger)
            throws ExtractorException, IOException, JSONException, InterruptedException {
        String alternate = "note".equals(kind) ? "video" : "note";
        String[][] pages = {
                {config.shareBase + "/" + kind + "/" + awemeId + "/", config.mobileUa},
                {config.shareBase + "/" + alternate + "/" + awemeId + "/", config.mobileUa},
                {config.desktopBase + "/" + kind + "/" + awemeId, config.desktopUa}
        };

        for (int attempt = 0; attempt <= 2; attempt++) {
            for (String[] page : pages) {
                checkStopped();
                String pageUrl = page[0];
                String ua = page[1];
                logger.log("  请求页面: " + pageUrl);
                FetchResult result;
                try {
                    result = fetch(pageUrl, ua, config.timeoutMs);
                } catch (IOException e) {
                    logger.log("  请求异常: " + e.getMessage());
                    continue;
                }

                JSONObject data = parseEmbeddedJson(result.body);
                if (data != null) {
                    JSONObject aweme = pickAweme(data);
                    if (aweme != null) {
                        logger.log("  页面数据解析成功");
                        return aweme;
                    }
                    String reason = emptyReason(data);
                    if (reason != null) {
                        throw new ExtractorException(reason);
                    }
                }

                if (isAntibot(result.body)) {
                    logger.log("  返回安全验证/反爬空壳 (HTTP " + result.statusCode
                            + ", " + result.body.length() + " 字节)");
                } else if (result.body.length() < 8000 && data != null) {
                    logger.log("  页面缺少作品数据 (HTTP " + result.statusCode
                            + ", " + result.body.length() + " 字节, 未携带有效访客 Cookie)");
                } else {
                    logger.log("  未找到内嵌数据 (HTTP " + result.statusCode
                            + ", " + result.body.length() + " 字节)");
                }
            }

            if (attempt < 2) {
                if (attempt == 0 && !cookies.containsKey("ttwid")) {
                    cookiesReady = false;
                    cookies.clear();
                    parseCookieHeader(cookieSeed);
                    ensureVisitorCookies(logger);
                }
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

    /**
     * The 2026 share page keeps the payload at
     * loaderData["note_(id)/page"].videoInfoRes.item_list[0].
     */
    private JSONObject pickAweme(JSONObject data) {
        JSONObject loader = data.optJSONObject("loaderData");
        if (loader != null) {
            Iterator<String> keys = loader.keys();
            while (keys.hasNext()) {
                Object page = loader.opt(keys.next());
                if (!(page instanceof JSONObject)) {
                    continue;
                }
                JSONObject pageObject = (JSONObject) page;
                JSONObject videoInfo = pageObject.optJSONObject("videoInfoRes");
                if (videoInfo != null) {
                    JSONArray items = videoInfo.optJSONArray("item_list");
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.optJSONObject(i);
                            if (item != null && (item.has("images") || item.has("video"))) {
                                return item;
                            }
                        }
                    }
                }
                Object nested = findAweme(pageObject);
                if (nested instanceof JSONObject) {
                    return (JSONObject) nested;
                }
            }
        }
        Object fallback = findAweme(data);
        return fallback instanceof JSONObject ? (JSONObject) fallback : null;
    }

    private Object findKeyValue(Object node, String key, int depth) {
        if (depth > 8 || node == null) {
            return null;
        }
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.has(key)) {
                Object value = object.opt(key);
                if (value != null) {
                    return value;
                }
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                Object found = findKeyValue(object.opt(keys.next()), key, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Object found = findKeyValue(array.opt(i), key, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String emptyReason(JSONObject data) {
        Object filterList = findKeyValue(data, "filter_list", 0);
        if (filterList instanceof JSONArray && ((JSONArray) filterList).length() > 0) {
            JSONObject first = ((JSONArray) filterList).optJSONObject(0);
            if (first != null) {
                String message = first.optString("detail_msg", "");
                if (message.isEmpty()) {
                    message = first.optString("filter_reason", "");
                }
                if (!message.isEmpty()) {
                    return "作品不可访问: " + message;
                }
            }
            return "作品不可访问: 可能已删除、仅作者可见或区域限制";
        }
        Object status = findKeyValue(data, "status_code", 0);
        if (status instanceof Number && ((Number) status).intValue() != 0) {
            return "接口返回状态码 " + status + ", 作品可能已删除或权限受限";
        }
        return null;
    }

    private Object findAweme(Object node) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.has("images") || (object.has("video") && object.has("desc"))) {
                return object;
            }
            Iterator<String> keys = object.keys();
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
        if (images != null && images.length() > 0) {
            result.kind = "note";
            for (int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                if (image == null) {
                    continue;
                }
                List<String> candidates = orderedImageUrls(image);
                if (candidates.isEmpty()) {
                    continue;
                }
                result.imageUrls.add(candidates.get(0));
                result.imageCandidates.add(candidates);
            }
            logger.log("图文作品, 共 " + result.imageUrls.size() + " 张图片");
            return result;
        }

        result.kind = "video";
        JSONObject video = aweme.optJSONObject("video");
        if (video != null) {
            collectVideoUrls(result, video.optJSONObject("play_addr"));
            if (result.videoCandidates.isEmpty()) {
                collectVideoUrls(result, video.optJSONObject("play_addr_h264"));
            }
            if (result.videoCandidates.isEmpty()) {
                collectVideoUrls(result, video.optJSONObject("download_addr"));
            }
        }
        if (!result.videoCandidates.isEmpty()) {
            result.videoUrl = result.videoCandidates.get(0);
        }
        return result;
    }

    private void collectVideoUrls(Result result, JSONObject address) {
        if (address == null) {
            return;
        }
        JSONArray list = address.optJSONArray("url_list");
        if (list == null) {
            return;
        }
        Set<String> unique = new LinkedHashSet<>();
        for (int i = 0; i < list.length(); i++) {
            String url = list.optString(i, "");
            if (url.isEmpty()) {
                continue;
            }
            unique.add(url.replace("playwm", "play"));
        }
        for (String url : unique) {
            if (!result.videoCandidates.contains(url)) {
                result.videoCandidates.add(url);
            }
        }
    }

    private List<String> orderedImageUrls(JSONObject image) {
        List<String> jpeg = new ArrayList<>();
        List<String> others = new ArrayList<>();
        JSONArray list = image.optJSONArray("url_list");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                String url = list.optString(i, "");
                if (url.isEmpty()) {
                    continue;
                }
                String lower = url.toLowerCase(Locale.ROOT);
                if (lower.contains(".jpeg") || lower.contains(".jpg")) {
                    jpeg.add(url);
                } else {
                    others.add(url);
                }
            }
        }
        Set<String> unique = new LinkedHashSet<>(jpeg);
        unique.addAll(others);
        return new ArrayList<>(unique);
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    public File saveResult(Result result, File root, Logger logger)
            throws IOException, InterruptedException, ExtractorException, JSONException {
        String dirName = safeName(result.desc, 50) + "_" + result.awemeId;
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
        meta.put("image_count", result.imageUrls.size());
        meta.put("video_url", result.videoUrl);
        String nl = String.valueOf((char) 10);
        writeText(new File(saveDir, "info.json"), meta.toString(2));
        writeText(new File(saveDir, "caption.txt"),
                "作者: " + result.author + nl + "文案: " + result.desc + nl + "链接: " + result.sourceUrl + nl);

        if ("note".equals(result.kind)) {
            logger.log("图文笔记共 " + result.imageUrls.size() + " 张图片, 开始下载...");
            int ok = 0;
            for (int i = 0; i < result.imageCandidates.size(); i++) {
                checkStopped();
                List<String> candidates = result.imageCandidates.get(i);
                logger.log("  (" + (i + 1) + "/" + result.imageCandidates.size() + ") 下载中...");
                File saved = downloadImage(candidates, saveDir, i + 1, logger);
                if (saved != null) {
                    ok++;
                }
            }
            logger.log("图片完成: " + ok + "/" + result.imageCandidates.size() + " 张 -> " + saveDir);
            if (ok == 0) {
                throw new ExtractorException("图片全部下载失败, 可稍后重试或填写 Cookie");
            }
        } else if (wantVideo && !result.videoCandidates.isEmpty()) {
            logger.log("视频作品, 开始下载...");
            File target = new File(saveDir, "video.mp4");
            if (downloadAny(result.videoCandidates, target, logger)) {
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

    private File downloadImage(List<String> candidates, File dir, int index, Logger logger) {
        for (String url : candidates) {
            if (stopped) {
                return null;
            }
            String ext = extensionFor(url);
            File target = new File(dir, String.format(Locale.ROOT, "%03d%s", index, ext));
            if (download(url, target)) {
                return target;
            }
            logger.log("    候选地址失败, 尝试下一个");
        }
        return null;
    }

    private boolean downloadAny(List<String> candidates, File target, Logger logger) {
        for (String url : candidates) {
            if (stopped) {
                return false;
            }
            if (download(url, target)) {
                return true;
            }
            logger.log("    候选地址失败, 尝试下一个");
        }
        return false;
    }

    private boolean download(String url, File target) {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        for (int attempt = 0; attempt < 3; attempt++) {
            if (stopped) {
                return false;
            }
            try {
                FetchResult result = downloadToFile(url, part);
                if (result.statusCode >= 400) {
                    throw new IOException("HTTP " + result.statusCode);
                }
                long size = part.length();
                if (size < 1024) {
                    throw new IOException("内容过小 (" + size + " 字节), 可能被风控拦截");
                }
                if (part.renameTo(target)) {
                    return true;
                }
                throw new IOException("写入目标文件失败");
            } catch (IOException e) {
                if (part.exists()) {
                    part.delete();
                }
                if (attempt == 2) {
                    return false;
                }
                try {
                    Thread.sleep(1200L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private FetchResult downloadToFile(String url, File target) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(url, config.mobileUa, 30000);
            connection.setRequestProperty("Referer", "https://www.douyin.com/");
            connection.setRequestProperty("Accept", "image/*,video/*,*/*;q=0.8");
            int code = connection.getResponseCode();
            InputStream input = code >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) {
                return new FetchResult("", connection.getURL().toString(), code);
            }
            FileOutputStream output = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (stopped) {
                        throw new IOException("任务已停止");
                    }
                    output.write(buffer, 0, read);
                }
            } finally {
                output.close();
                input.close();
            }
            return new FetchResult("", connection.getURL().toString(), code);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Union-register hands out a fresh anonymous ttwid cookie. */
    private void fetchTtwid() throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(config.ttwidRegister, config.mobileUa, config.timeoutMs);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] payload = config.ttwidBody.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            java.io.OutputStream output = connection.getOutputStream();
            try {
                output.write(payload);
            } finally {
                output.close();
            }
            int code = connection.getResponseCode();
            absorbCookies(connection);
            InputStream input = code >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            if (input != null) {
                input.close();
            }
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
            int code = connection.getResponseCode();
            absorbCookies(connection);
            InputStream input = code >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            String body = "";
            if (input != null) {
                body = readAll(input);
            }
            return new FetchResult(body, connection.getURL().toString(), code);
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
        connection.setRequestProperty("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        String cookie = cookieHeader();
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
        String source = (name == null ? "" : name).trim();
        StringBuilder cleaned = new StringBuilder();
        int[] illegalCodePoints = {92, 47, 58, 42, 63, 34, 60, 62, 124, 13, 10, 9};
        for (char c : source.toCharArray()) {
            boolean illegal = false;
            for (int code : illegalCodePoints) {
                if (c == code) {
                    illegal = true;
                    break;
                }
            }
            cleaned.append(illegal ? '_' : c);
        }
        if (cleaned.length() == 0) {
            cleaned.append("douyin");
        }
        if (cleaned.length() > limit) {
            cleaned.delete(limit, cleaned.length());
        }
        while (cleaned.length() > 0 && cleaned.charAt(cleaned.length() - 1) == '.') {
            cleaned.deleteCharAt(cleaned.length() - 1);
        }
        return cleaned.length() == 0 ? "douyin" : cleaned.toString();
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
