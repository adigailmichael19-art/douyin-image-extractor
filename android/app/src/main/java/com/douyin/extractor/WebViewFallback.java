package com.douyin.extractor;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Hidden WebView rendering fallback for note works. The HTTP-only share page
 * can be an SSR shell in some network environments; this session loads the
 * public note page in a hidden WebView, waits for hydration, then reads the
 * rendered DOM for aweme image URLs, author nickname and description.
 * <p>
 * The session runs its WebView on the UI thread and blocks the extraction
 * worker until either candidates are found, a verify/login wall appears, the
 * 30s timeout elapses, or the task is stopped. It never solves, clicks or
 * bypasses any captcha; on any failure the caller falls back to the HTTP path.
 */
final class WebViewFallback {

    private static final long TOTAL_TIMEOUT_MS = 30_000L;
    private static final long POLL_INTERVAL_MS = 1_000L;
    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final String EXTRACT_JS =
            "(function(){"
            + "var seen={};var images=[];var desc='';var author='';"
            + "try{"
            + "var rd=window._ROUTER_DATA;"
            + "var pg=null;if(rd&&rd.loaderData){for(var kk in rd.loaderData){var cand=rd.loaderData[kk];if(cand&&cand.videoInfoRes&&cand.videoInfoRes.item_list){pg=cand;break;}}}"
            + "var it=pg&&pg.videoInfoRes&&pg.videoInfoRes.item_list&&pg.videoInfoRes.item_list[0];"
            + "if(it){"
            + "desc=it.desc||'';author=(it.author&&it.author.nickname)||'';"
            + "var arr=it.images||[];"
            + "for(var i=0;i<arr.length;i++){"
            + "var im=arr[i];var urls=im&&im.url_list?im.url_list:[];var best='';"
            + "for(var j=0;j<urls.length;j++){var u=urls[j]||'';"
            + "if(u.indexOf('douyinpic')<0||u.indexOf('aweme_images')<0){continue;}"
            + "if(!best){best=u;}"
            + "if(u.indexOf('.webp')>=0&&best.indexOf('.webp')<0){best=u;}"
            + "}"
            + "var key=(im&&im.uri)||best.split('?')[0].split('~')[0];"
            + "if(best&&!seen[key]){seen[key]=1;images.push(best);}"
            + "}"
            + "}"
            + "}catch(e){}"
            + "if(images.length===0){"
            + "var domSeen={};var imgs=document.getElementsByTagName('img');"
            + "for(var i=0;i<imgs.length;i++){"
            + "var img=imgs[i];var src=img.currentSrc||img.src||img.getAttribute('data-src')||'';"
            + "if(!src||src.indexOf('data:')===0){continue;}"
            + "if(src.indexOf('douyinpic')<0||src.indexOf('aweme_images')<0){continue;}"
            + "var w=img.naturalWidth||0;var h=img.naturalHeight||0;if(w<80||h<80){continue;}"
            + "var key=src.split('?')[0].split('~')[0];var area=w*h;"
            + "if(!domSeen[key]||area>domSeen[key].area){domSeen[key]={src:src,area:area};}"
            + "}"
            + "for(var k in domSeen){if(domSeen[k].src){images.push(domSeen[k].src);}}"
            + "}"
            + "if(!desc){var d=document.querySelector('meta[name=\"description\"]');var meta=d?(d.getAttribute('content')||''):'';"
            + "if(meta){var parts=meta.split(' - ');desc=parts[0]||'';if(!author&&parts.length>1){author=parts[1].replace(/于\\d{8}发布.*$/,'');}}}"
            + "if(!desc){var t=document.title||'';desc=t.replace(/\\s*-\\s*抖音\\s*$/,'');}"
            + "var og=document.querySelector('meta[property=\"og:description\"]');if(!desc&&og){desc=og.getAttribute('content')||'';}"
            + "var ot=document.querySelector('meta[property=\"og:title\"]');if(!author&&ot){author=ot.getAttribute('content')||'';}"
            + "var body=(document.body&&document.body.innerText)||'';"
            + "var blocked=false;var blockedReason='';"
            + "if(location.href&&/verify|captcha|passport/i.test(location.href)){blocked=true;blockedReason='url';}"
            + "if(!blocked){var cap=document.querySelector('[class*=captcha],[id*=captcha],[class*=verify-container],iframe[src*=captcha]');if(cap){blocked=true;blockedReason='container';}}"
            + "if(!blocked){var ph=/请完成安全验证|拖动滑块|点击完成验证|验证码错误|安全验证中|访问过于频繁/i.test(body);if(ph){blocked=true;blockedReason='phrase';}}"
            + "window.__wvResult={images:images,desc:desc,author:author,blocked:blocked,blockedReason:blockedReason};"
            + "})()";

    private static final String READ_JS =
            "window.__wvResult ? JSON.stringify(window.__wvResult) : '{}'";

    private static volatile Session activeSession;

    private WebViewFallback() {
    }

    /** Result of one rendering attempt. */
    static final class Outcome {
        final boolean ok;
        final boolean blocked;
        final boolean timedOut;
        final boolean stopped;
        final List<String> images;
        final String desc;
        final String author;
        final String error;

        Outcome(boolean ok, boolean blocked, boolean timedOut, boolean stopped,
                List<String> images, String desc, String author, String error) {
            this.ok = ok;
            this.blocked = blocked;
            this.timedOut = timedOut;
            this.stopped = stopped;
            this.images = images;
            this.desc = desc;
            this.author = author;
            this.error = error;
        }

        boolean hasImages() {
            return images != null && !images.isEmpty();
        }
    }

    static Outcome fetch(Activity activity, String awemeId, Extractor.Logger logger,
                         BooleanSupplier stopped) {
        Session session = new Session(activity, awemeId, logger, stopped);
        activeSession = session;
        session.start();

        try {
            while (session.latch.getCount() != 0) {
                if (stopped != null && stopped.getAsBoolean()) {
                    session.cancel();
                    return new Outcome(false, false, false, true,
                            new ArrayList<>(), "", "", "任务已停止");
                }
                if (!session.latch.await(300, TimeUnit.MILLISECONDS)) {
                    continue;
                }
                break;
            }
        } catch (InterruptedException e) {
            session.cancel();
            return new Outcome(false, false, false, true,
                    new ArrayList<>(), "", "", "任务已停止");
        } finally {
            session.destroy();
            if (activeSession == session) {
                activeSession = null;
            }
        }
        return session.toOutcome();
    }

    static void cancelActive() {
        Session session = activeSession;
        if (session != null) {
            session.cancel();
        }
    }

    /** Per-session state; WebView operations must run on the UI thread. */
    private static final class Session {
        final Activity activity;
        final String awemeId;
        final Extractor.Logger logger;
        final BooleanSupplier stopped;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> payload = new AtomicReference<>();
        final AtomicBoolean blocked = new AtomicBoolean();
        final AtomicBoolean timedOut = new AtomicBoolean();
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicBoolean polling = new AtomicBoolean();
        final Object networkLock = new Object();
        final LinkedHashMap<String, String> networkImages = new LinkedHashMap<>();
        long lastNetworkImageAt;

        WebView webView;
        android.os.Handler handler;
        long startedAt;
        long attemptStartedAt;
        /** 0 = primary douyin.com/note, 1 = secondary iesdouyin share page. */
        int attempt = 0;

        Session(Activity activity, String awemeId, Extractor.Logger logger,
                BooleanSupplier stopped) {
            this.activity = activity;
            this.awemeId = awemeId;
            this.logger = logger;
            this.stopped = stopped;
        }

        void start() {
            handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(this::onUiStart);
        }

        private void onUiStart() {
            if (activity.isFinishing()) {
                finish("Activity 已结束");
                return;
            }
            startedAt = System.currentTimeMillis();
            WebView view = new WebView(activity);
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUserAgentString(MOBILE_UA);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);

            // Full-size but transparent so viewport-based lazy loading still runs;
            // added at the bottom layer so it never intercepts touches.
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            view.setLayoutParams(params);
            view.setAlpha(0f);
            view.setVisibility(View.VISIBLE);

            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content != null) {
                content.addView(view, 0, params);
            }
            webView = view;

            view.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                    try {
                        if (request != null && request.getUrl() != null) {
                            captureNetworkImage(request.getUrl().toString());
                        }
                    } catch (Throwable ignored) {
                        // Observation must never block the page request.
                    }
                    return null;
                }

                @Override
                public void onPageFinished(WebView v, String url) {
                    String lower = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("verify") || lower.contains("captcha")
                            || lower.contains("passport")) {
                        blocked.set(true);
                        if (attempt == 0) {
                            logger.log("[WebView] 主路径(douyin.com/note) 跳转验证页");
                            startSecondary();
                        } else {
                            logger.log("[WebView] 备用路径(iesdouyin/share) 跳转验证页，回退 HTTP");
                            finish("页面跳转到验证环境");
                        }
                        return;
                    }
                    schedulePoll();
                }

                @Override
                public void onReceivedError(WebView v, int errorCode, String description,
                                             String failingUrl) {
                    logger.log("[WebView] 加载错误: " + description + " (" + errorCode + ")");
                }
            });

            loadPrimary();

            handler.postDelayed(this::onTimeout, TOTAL_TIMEOUT_MS);
        }

        private String primaryUrl() {
            return "https://www.iesdouyin.com/share/video/" + awemeId + "/";
        }

        private String secondaryUrl() {
            return "https://www.iesdouyin.com/share/note/" + awemeId + "/";
        }

        private void loadPrimary() {
            attempt = 0;
            blocked.set(false);
            attemptStartedAt = System.currentTimeMillis();
            if (webView != null) {
                webView.getSettings().setUserAgentString(MOBILE_UA);
            }
            String url = primaryUrl();
            logger.log("[WebView] 加载移动视频分享页 " + url);
            if (webView != null) {
                webView.loadUrl(url);
            }
        }

        /**
         * Single fallback attempt: only reached when the primary note page
         * explicitly hits a verify/captcha wall. Still DOM scraping only;
         * no captcha solving or control clicking.
         */
        private void startSecondary() {
            if (finished.get() || webView == null) {
                return;
            }
            attempt = 1;
            blocked.set(false);
            attemptStartedAt = System.currentTimeMillis();
            polling.set(false);
            if (handler != null) {
                handler.removeCallbacks(this::poll);
            }
            if (webView != null) {
                webView.getSettings().setUserAgentString(MOBILE_UA);
            }
            String url = secondaryUrl();
            logger.log("[WebView] 备用路径(iesdouyin/share) 尝试");
            logger.log("[WebView] 加载 " + url);
            if (webView != null) {
                webView.loadUrl(url);
            }
            if (handler != null) {
                handler.postDelayed(this::onTimeout, TOTAL_TIMEOUT_MS);
            }
        }

        private void schedulePoll() {
            if (finished.get() || handler == null) {
                return;
            }
            // Only one poll chain may be active at a time; onPageFinished can
            // fire multiple times for SPA navigation.
            if (!polling.compareAndSet(false, true)) {
                return;
            }
            handler.postDelayed(this::poll, POLL_INTERVAL_MS);
        }

        private void poll() {
            if (finished.get() || webView == null) {
                polling.set(false);
                return;
            }
            if (System.currentTimeMillis() - attemptStartedAt > TOTAL_TIMEOUT_MS) {
                polling.set(false);
                onTimeout();
                return;
            }
            webView.evaluateJavascript(EXTRACT_JS, null);
            webView.evaluateJavascript(READ_JS, value -> handlePayload(value));
        }

        private void handlePayload(String value) {
            if (finished.get()) {
                return;
            }
            try {
                String decoded = decodeJsString(value);
                if (decoded == null || decoded.isEmpty() || "{}".equals(decoded)) {
                    polling.set(false);
                    schedulePoll();
                    return;
                }
                JSONObject json = new JSONObject(decoded);
                JSONArray images = json.optJSONArray("images");
                List<String> urls = new ArrayList<>();
                if (images != null) {
                    for (int i = 0; i < images.length(); i++) {
                        String imageUrl = images.optString(i, "");
                        if (!imageUrl.isEmpty()) {
                            urls.add(imageUrl);
                        }
                    }
                }
                boolean isBlocked = json.optBoolean("blocked", false);
                if (isBlocked) {
                    blocked.set(true);
                    String reason = json.optString("blockedReason", "");
                    if (attempt == 0) {
                        if ("url".equals(reason)) {
                            logger.log("[WebView] 主路径(douyin.com/note) 跳转验证页");
                        } else {
                            logger.log("[WebView] 主路径(douyin.com/note) 命中验证容器");
                        }
                        startSecondary();
                    } else {
                        if ("url".equals(reason)) {
                            logger.log("[WebView] 备用路径(iesdouyin/share) 跳转验证页，回退 HTTP");
                        } else {
                            logger.log("[WebView] 备用路径(iesdouyin/share) 命中验证容器，回退 HTTP");
                        }
                        finish("页面出现安全验证");
                    }
                    return;
                }
                boolean fromNetwork = false;
                if (urls.isEmpty()) {
                    List<String> captured = snapshotNetworkImages();
                    if (!captured.isEmpty()) {
                        urls.addAll(captured);
                        fromNetwork = true;
                    }
                }
                if (!urls.isEmpty()) {
                    if (fromNetwork) {
                        long quietMs = System.currentTimeMillis() - lastNetworkImageAt;
                        if (quietMs < 2500L) {
                            // Give the carousel's second request a moment to arrive.
                            polling.set(false);
                            schedulePoll();
                            return;
                        }
                        logger.log("[WebView] DOM 未暴露图片，网络请求已捕获 " + urls.size() + " 张目标图");
                    } else {
                        logger.log("[WebView] 已水合，检测到 " + urls.size() + " 张候选图");
                    }
                    payload.set(payloadWithImages(decoded, urls));
                    finish(null);
                    return;
                }
                polling.set(false);
                schedulePoll();
            } catch (Exception e) {
                logger.log("[WebView] 页面数据解析异常: " + e.getMessage());
                polling.set(false);
                schedulePoll();
            }
        }

        private void onTimeout() {
            if (finished.get()) {
                return;
            }
            if (attempt == 0) {
                logger.log("[WebView] 视频分享页未拿到图片，切换到图文分享页");
                startSecondary();
                return;
            }
            if (finished.compareAndSet(false, true)) {
                polling.set(false);
                List<String> captured = snapshotNetworkImages();
                if (!captured.isEmpty()) {
                    logger.log("[WebView] 超时前网络请求已捕获 " + captured.size() + " 张目标图，直接使用");
                    payload.set(payloadWithImages(payload.get(), captured));
                    timedOut.set(false);
                    latch.countDown();
                    return;
                }
                timedOut.set(true);
                if (attempt >= 1) {
                    logger.log("[WebView] 两条渲染路径均未拿到图片，回退 HTTP");
                } else {
                    logger.log("[WebView] 超时未获取图片，回退 HTTP");
                }
                latch.countDown();
            }
        }

        private void captureNetworkImage(String url) {
            if (url == null || url.isEmpty()) {
                return;
            }
            if (!url.contains("douyinpic") || !url.contains("aweme_images")) {
                return;
            }
            if (url.contains("PackSourceEnum_IMAGE_RELATED_FEED") || url.contains("sc=cover")) {
                return;
            }
            synchronized (networkLock) {
                String key = url.split("\\?")[0].split("~")[0];
                String old = networkImages.get(key);
                if (old == null || imageScore(url) > imageScore(old)) {
                    networkImages.put(key, url);
                    logger.log("[WebView] 捕获目标图请求: " + url.substring(0, Math.min(100, url.length())));
                }
                lastNetworkImageAt = System.currentTimeMillis();
            }
        }

        private List<String> snapshotNetworkImages() {
            synchronized (networkLock) {
                return new ArrayList<>(networkImages.values());
            }
        }

        private String payloadWithImages(String base, List<String> urls) {
            JSONObject out;
            try {
                out = base == null ? new JSONObject() : new JSONObject(base);
            } catch (Exception ignored) {
                out = new JSONObject();
            }
            try {
                JSONArray arr = new JSONArray();
                for (String url : urls) {
                    arr.put(url);
                }
                out.put("images", arr);
            } catch (Exception ignored) {
                // Caller still sees the raw payload; images are added below.
            }
            return out.toString();
        }

        private int imageScore(String url) {
            int score = 0;
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile(":(\\d{2,5})").matcher(url);
                while (m.find()) {
                    score = Math.max(score, Integer.parseInt(m.group(1)));
                }
            } catch (Exception ignored) {
                // Unparseable CDN template; first URL wins.
            }
            return score;
        }

        private void finish(String error) {
            if (finished.compareAndSet(false, true)) {
                if (error != null && payload.get() == null) {
                    payload.set(null);
                }
                latch.countDown();
            }
        }

        void cancel() {
            if (finished.compareAndSet(false, true)) {
                latch.countDown();
            }
        }

        void destroy() {
            handler.post(this::onUiDestroy);
        }

        private void onUiDestroy() {
            if (webView == null) {
                return;
            }
            try {
                webView.stopLoading();
                ViewGroup parent = (ViewGroup) webView.getParent();
                if (parent != null) {
                    parent.removeView(webView);
                }
                webView.destroy();
            } catch (Throwable ignored) {
                // WebView teardown must never crash the extraction flow.
            }
            webView = null;
        }

        Outcome toOutcome() {
            List<String> images = new ArrayList<>();
            String desc = "";
            String author = "";
            String raw = payload.get();
            if (raw != null) {
                try {
                    JSONObject json = new JSONObject(raw);
                    JSONArray arr = json.optJSONArray("images");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            String imageUrl = arr.optString(i, "");
                            if (!imageUrl.isEmpty()) {
                                images.add(imageUrl);
                            }
                        }
                    }
                    desc = json.optString("desc", "");
                    author = json.optString("author", "");
                } catch (Exception ignored) {
                    // Returned without images; caller falls back to HTTP.
                }
            }
            String error;
            if (timedOut.get()) {
                error = "WebView 渲染未拿到数据（可能被安全验证拦截或 WebView 版本过旧）: 超时";
            } else if (blocked.get()) {
                error = "WebView 渲染未拿到数据（可能被安全验证拦截或 WebView 版本过旧）: 命中验证";
            } else {
                error = "WebView 渲染未拿到数据（可能被安全验证拦截或 WebView 版本过旧）";
            }
            return new Outcome(!images.isEmpty(), blocked.get(), timedOut.get(),
                    false, images, desc, author, error);
        }
    }

    private static String decodeJsString(String value) {
        if (value == null) {
            return null;
        }
        String decoded = value.trim();
        if (decoded.startsWith("\"") && decoded.endsWith("\"") && decoded.length() >= 2) {
            decoded = decoded.substring(1, decoded.length() - 1)
                    .replace("\\\\", "\\")
                    .replace("\\\"", "\"")
                    .replace("\\n", " ")
                    .replace("\\/", "/");
        }
        return decoded;
    }
}
