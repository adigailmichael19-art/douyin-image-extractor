package com.douyin.extractor;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Offline regression tests for the extraction engine.
 *
 * They mirror the behaviour observed on the real share page: it only renders
 * item data when the request carries the ttwid that the union-register endpoint
 * handed out, so a client without a cookie jar always saw an empty shell.
 *
 * A hand-written socket server is used instead of com.sun.net.httpserver,
 * because AGP compiles unit tests against android.jar where that package is
 * not available.
 */
public class ExtractorTest {

    private static final String AWEME_ID = "7710508486577620859";
    private static final String TTWID = "test-visitor-ttwid";

    private TinyHttpServer server;
    private int port;
    private boolean issueCookie = true;
    private boolean deleted = false;
    private File tempRoot;

    @Before
    public void setUp() throws Exception {
        server = new TinyHttpServer(this::route);
        port = server.port();
        tempRoot = Files.createTempDirectory("douyin-extractor-test").toFile();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
        if (tempRoot != null) {
            deleteRecursive(tempRoot);
        }
    }

    // ------------------------------------------------------------------
    // Mock Douyin endpoints
    // ------------------------------------------------------------------

    private TinyHttpServer.Response route(String method, String path, Map<String, String> headers,
                                          String body) throws IOException {
        if (path.equals("/ttwid/union/register/")) {
            TinyHttpServer.Response response = TinyHttpServer.Response.of(200, "application/json", "{}");
            if (issueCookie) {
                response.headers.put("Set-Cookie",
                        "ttwid=" + TTWID + "; Path=/; Domain=bytedance.com; Max-Age=31536000; HttpOnly");
            }
            return response;
        }
        if (path.startsWith("/share/note/" + AWEME_ID + "/")
                || path.startsWith("/share/video/" + AWEME_ID + "/")
                || path.startsWith("/note/" + AWEME_ID)) {
            if (!hasTtwid(headers)) {
                // Real page without the visitor cookie: HTML loads, no item data.
                return TinyHttpServer.Response.of(200, "text/html; charset=utf-8",
                        "<html><body><script>window._ROUTER_DATA = "
                                + "{\"loaderData\":{\"note_page\":{\"page\":{\"ua\":\"iPhone\"}}}}"
                                + "</script></body></html>");
            }
            try {
                return TinyHttpServer.Response.of(200, "text/html; charset=utf-8", buildShareHtml());
            } catch (Exception e) {
                throw new IOException("构造分享页失败", e);
            }
        }
        if (path.equals("/media/1.jpg")) {
            return TinyHttpServer.Response.ofBytes(200, "image/jpeg", 4096);
        }
        if (path.equals("/media/2.jpg")) {
            return TinyHttpServer.Response.ofBytes(200, "image/jpeg", 8192);
        }
        if (path.equals("/media/1.webp")) {
            return TinyHttpServer.Response.ofBytes(404, "text/plain", 0);
        }
        if (path.equals("/")) {
            return TinyHttpServer.Response.of(200, "text/html; charset=utf-8",
                    "<html><body>douyin home</body></html>");
        }
        return TinyHttpServer.Response.ofBytes(404, "text/plain", 0);
    }

    private boolean hasTtwid(Map<String, String> headers) {
        String cookie = headers.get("cookie");
        return cookie != null && cookie.contains("ttwid=" + TTWID);
    }

    private String buildShareHtml() throws Exception {
        JSONObject image1 = new JSONObject();
        image1.put("uri", "img-1");
        image1.put("width", 1080);
        image1.put("height", 1440);
        image1.put("url_list", new JSONArray()
                .put(base() + "/media/1.jpg")
                .put(base() + "/media/1.webp"));

        JSONObject image2 = new JSONObject();
        image2.put("uri", "img-2");
        image2.put("width", 1080);
        image2.put("height", 1440);
        image2.put("url_list", new JSONArray()
                .put(base() + "/media/2.jpg"));

        JSONObject author = new JSONObject();
        author.put("nickname", "L.\uD83C\uDD85");

        JSONObject item = new JSONObject();
        item.put("aweme_id", AWEME_ID);
        item.put("aweme_type", 2);
        item.put("desc", "场强叠加问题。测试用例");
        item.put("author", author);
        item.put("images", new JSONArray().put(image1).put(image2));

        JSONObject videoInfoRes = new JSONObject();
        videoInfoRes.put("item_list", new JSONArray().put(deleted ? new JSONObject() : item));

        JSONObject page = new JSONObject();
        page.put("videoInfoRes", videoInfoRes);

        JSONObject loaderData = new JSONObject();
        loaderData.put("note_(" + AWEME_ID + ")/page", page);

        JSONObject router = new JSONObject();
        router.put("loaderData", loaderData);
        if (deleted) {
            JSONObject filter = new JSONObject();
            filter.put("detail_msg", "作品已删除");
            router.put("filter_list", new JSONArray().put(filter));
        }

        return "<!doctype html><html><head><script>window._ROUTER_DATA = "
                + router.toString() + "</script></head><body></body></html>";
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private Extractor.Config localConfig() {
        Extractor.Config config = new Extractor.Config();
        config.douyinHome = base() + "/";
        config.iesdouyinHome = base() + "/";
        config.shareBase = base() + "/share";
        config.desktopBase = base();
        config.ttwidRegister = base() + "/ttwid/union/register/";
        config.timeoutMs = 8000;
        return config;
    }

    /** Logs to stdout as well so a failing assertion shows the real request flow. */
    private Extractor.Logger tee(List<String> lines) {
        return message -> {
            System.out.println("[extractor] " + message);
            lines.add(message);
        };
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    public void extractsNoteAndDownloadsAllImagesWithVisitorCookie() throws Exception {
        List<String> log = new ArrayList<>();
        Extractor extractor = new Extractor("", true, localConfig());
        Extractor.Result result = extractor.extract(
                "8.48 复制打开抖音 " + base() + "/note/" + AWEME_ID + " K@W.ZM", tee(log));

        assertEquals("note", result.kind);
        assertEquals(AWEME_ID, result.awemeId);
        assertEquals(2, result.imageUrls.size());
        assertEquals(2, result.imageCandidates.size());
        assertTrue(result.desc.contains("场强叠加"));

        File dir = extractor.saveResult(result, tempRoot, tee(log));
        File first = new File(dir, "001.jpg");
        File second = new File(dir, "002.jpg");
        assertTrue("001.jpg 应存在", first.exists());
        assertTrue("002.jpg 应存在", second.exists());
        assertTrue("001.jpg 应大于 1024 字节", first.length() > 1024);
        assertEquals(4096, first.length());
        assertEquals(8192, second.length());
        assertTrue(new File(dir, "info.json").exists());
        assertTrue(new File(dir, "caption.txt").exists());

        String joined = String.join("\n", log);
        assertTrue("日志应记录访客 Cookie 获取: " + joined, joined.contains("访客 Cookie 就绪"));
        assertTrue("日志应记录 2/2 完成: " + joined, joined.contains("图片完成: 2/2"));
    }

    @Test
    public void reportsMissingDataWhenNoVisitorCookieCanBeObtained() throws Exception {
        issueCookie = false;
        List<String> log = new ArrayList<>();
        Extractor extractor = new Extractor("", true, localConfig());
        try {
            extractor.extract(base() + "/note/" + AWEME_ID, tee(log));
            fail("没有访客 Cookie 时应抛出异常");
        } catch (Extractor.ExtractorException expected) {
            assertTrue(expected.getMessage().contains("无法获取作品数据"));
        }
        String joined = String.join("\n", log);
        assertTrue("应提示未获取到 ttwid: " + joined, joined.contains("未获取到 ttwid"));
    }

    @Test
    public void reportsDeletedWorkFromFilterList() throws Exception {
        deleted = true;
        List<String> log = new ArrayList<>();
        Extractor extractor = new Extractor("", true, localConfig());
        try {
            extractor.extract(base() + "/share/note/" + AWEME_ID + "/", tee(log));
            fail("filter_list 命中时应抛出异常");
        } catch (Extractor.ExtractorException expected) {
            assertTrue(expected.getMessage().contains("作品不可访问"));
        }
    }

    @Test
    public void rejectsTextWithoutLink() {
        try {
            new Extractor("", true, localConfig()).extract("这里没有任何链接", message -> {
            });
            fail("无链接时应抛出异常");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("没有找到"));
        }
    }

    @Test
    public void sanitizesCaptionForFileNames() {
        assertEquals("douyin", Extractor.safeName("", 50));
        assertEquals("a_b_c", Extractor.safeName("a/b:c", 50));
        assertEquals("12345", Extractor.safeName("1234567890", 5));
        assertNotNull(Extractor.safeName("正常文案", 50));
    }

    private static void deleteRecursive(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }

    // ------------------------------------------------------------------
    // Tiny HTTP/1.1 server (java.base only, so it compiles against android.jar)
    // ------------------------------------------------------------------

    static final class TinyHttpServer implements Runnable {

        interface Handler {
            Response handle(String method, String path, Map<String, String> headers, String body)
                    throws IOException;
        }

        static final class Response {
            final int status;
            final String contentType;
            final byte[] body;
            final Map<String, String> headers = new LinkedHashMap<>();

            Response(int status, String contentType, byte[] body) {
                this.status = status;
                this.contentType = contentType;
                this.body = body;
            }

            static Response of(int status, String contentType, String body) {
                return new Response(status, contentType, body.getBytes(StandardCharsets.UTF_8));
            }

            static Response ofBytes(int status, String contentType, int size) {
                return new Response(status, contentType, new byte[size]);
            }
        }

        private final ServerSocket serverSocket;
        private final Handler handler;
        private final Thread thread;
        private volatile boolean running = true;

        TinyHttpServer(Handler handler) throws IOException {
            this.handler = handler;
            this.serverSocket = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
            this.thread = new Thread(this, "tiny-http");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        void close() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Socket already closed.
            }
        }

        @Override
        public void run() {
            while (running) {
                Socket client = null;
                try {
                    client = serverSocket.accept();
                    serve(client);
                } catch (IOException e) {
                    if (!running) {
                        return;
                    }
                } finally {
                    if (client != null) {
                        try {
                            client.close();
                        } catch (IOException ignored) {
                            // Nothing to do.
                        }
                    }
                }
            }
        }

        private void serve(Socket client) throws IOException {
            InputStream input = client.getInputStream();
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String target = parts.length > 1 ? parts[1] : "/";

            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(),
                            line.substring(colon + 1).trim());
                }
            }

            String body = "";
            String lengthHeader = headers.get("content-length");
            if (lengthHeader != null) {
                int length = Integer.parseInt(lengthHeader.trim());
                body = new String(readBytes(input, length), StandardCharsets.UTF_8);
            }

            int query = target.indexOf('?');
            String path = query >= 0 ? target.substring(0, query) : target;

            Response response;
            try {
                response = handler.handle(method, path, headers, body);
            } catch (RuntimeException e) {
                response = Response.of(500, "text/plain", e.toString());
            }
            write(client.getOutputStream(), response);
        }

        private void write(OutputStream output, Response response) throws IOException {
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(response.status).append(" OK\r\n");
            if (response.contentType != null) {
                head.append("Content-Type: ").append(response.contentType).append("\r\n");
            }
            head.append("Content-Length: ").append(response.body.length).append("\r\n");
            for (Map.Entry<String, String> entry : response.headers.entrySet()) {
                head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            output.write(head.toString().getBytes(StandardCharsets.UTF_8));
            output.write(response.body);
            output.flush();
        }

        private static String readLine(InputStream input) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int read;
            while ((read = input.read()) != -1) {
                if (read == '\n') {
                    break;
                }
                if (read != '\r') {
                    line.write(read);
                }
            }
            if (read == -1 && line.size() == 0) {
                return null;
            }
            return new String(line.toByteArray(), StandardCharsets.UTF_8);
        }

        private static byte[] readBytes(InputStream input, int length) throws IOException {
            byte[] buffer = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = input.read(buffer, offset, length - offset);
                if (read == -1) {
                    break;
                }
                offset += read;
            }
            if (offset == length) {
                return buffer;
            }
            byte[] partial = new byte[offset];
            System.arraycopy(buffer, 0, partial, 0, offset);
            return partial;
        }
    }
}
