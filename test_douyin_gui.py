#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""douyin_gui.py 测试脚本: 引擎函数 + GUI 冒烟 + 端到端线程流程"""

import json
import os
import queue
import re
import shutil
import sys
import tempfile
import threading
import time
from urllib.parse import quote

from PIL import Image

import douyin_gui as dg

PASS = 0
FAIL = 0


def check(name, cond):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"[PASS] {name}")
    else:
        FAIL += 1
        print(f"[FAIL] {name}")


# ---------- 1. 引擎函数 ----------
def test_extract_url():
    u = dg.extract_first_url("随便看看 https://v.douyin.com/abc123/ 很好")
    check("extract_first_url 分享口令", u == "https://v.douyin.com/abc123/")
    u2 = dg.extract_first_url("https://www.douyin.com/note/7288991100112233")
    check("extract_first_url 完整链接", u2.endswith("7288991100112233"))
    try:
        dg.extract_first_url("没有链接的文字")
        check("无链接抛异常", False)
    except dg.ExtractError:
        check("无链接抛异常", True)


def test_safe_name():
    check("safe_name 非法字符替换",
          dg.safe_name('a/b:c*d?e"f<g>h|i') == "a_b_c_d_e_f_g_h_i")
    check("safe_name 空名兜底", dg.safe_name("") == "douyin")
    check("safe_name 截断", len(dg.safe_name("x" * 100, limit=50)) == 50)


def test_id_regex():
    aweme_id = "7288991100112233"
    check("ID_RE /note/", bool(dg.ID_RE.search(f"https://www.douyin.com/note/{aweme_id}")))
    check("ID_RE /video/", bool(dg.ID_RE.search(f"https://www.douyin.com/video/{aweme_id}")))
    check("ID_RE modal_id", bool(dg.ID_RE.search(f"https://xxx?modal_id={aweme_id}")))
    m = dg.ID_RE.search(f"/note/{aweme_id}")
    check("ID_RE 取值正确", m and m.group(1) == aweme_id)


def test_parse_html():
    payload = {"a": {"b": {"images": [{"url_list": ["https://x/1.jpg"]}]}}}
    html_router = f"<html><script>window._ROUTER_DATA = {json.dumps(payload)} </script></html>"
    parsed = dg.parse_embedded_json(html_router)
    check("解析 _ROUTER_DATA", parsed is not None and parsed["a"]["b"]["images"])

    encoded = quote(json.dumps(payload))
    html_render = f'<script id="RENDER_DATA" type="application/json">{encoded}</script>'
    parsed2 = dg.parse_embedded_json(html_render)
    check("解析 RENDER_DATA", parsed2 is not None and parsed2["a"]["b"]["images"])

    check("无关 HTML 返回 None", dg.parse_embedded_json("<html>nothing</html>") is None)


def test_find_aweme():
    nested = {"x": [{"y": {"desc": "d", "images": [1, 2]}}]}
    found = dg.find_aweme_obj(nested)
    check("find_aweme_obj 递归命中", found is not None and len(found["images"]) == 2)
    check("find_aweme_obj 无目标返回 None", dg.find_aweme_obj({"a": 1}) is None)


def test_antibot():
    check("is_antibot 验证页", dg.is_antibot("<html>请完成安全验证验证码</html>"))
    check("is_antibot 正常页", not dg.is_antibot("<html>正常内容</html>"))


# ---------- 2. 端到端线程流程 (Fake Session) ----------
class FakeResponse:
    def __init__(self, url, text=None, content=None, status=200):
        self.url = url
        self._text = text
        self._content = content or b""
        self.status_code = status

    @property
    def text(self):
        return self._text if self._text is not None else self._content.decode("latin-1")

    def raise_for_status(self):
        if self.status_code >= 400:
            raise dg.requests.HTTPError(f"HTTP {self.status_code}")

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def iter_content(self, chunk_size):
        data = self._content
        for i in range(0, len(data), chunk_size):
            yield data[i:i + chunk_size]


class FakeSession:
    def __init__(self, page_html):
        self.page_html = page_html
        self.headers = {}

    def get(self, url, headers=None, allow_redirects=True, timeout=None, stream=False):
        if "v.douyin.com" in url:
            return FakeResponse("https://www.douyin.com/note/7288991100112233",
                                content=b"redirect")
        if url.endswith((".jpg", ".png")) or "douyinpic.com" in url:
            return FakeResponse(url, content=b"\xff\xd8\xff\xe0fake-image-bytes")
        if "douyin.com" in url or "iesdouyin.com" in url:
            return FakeResponse(url, text=self.page_html)
        return FakeResponse(url, content=b"", status=404)


def test_end_to_end():
    tmp = tempfile.mkdtemp(prefix="douyin_test_")
    try:
        aweme = {
            "desc": "端到端测试文案",
            "aweme_id": "7288991100112233",
            "author": {"nickname": "测试博主"},
            "images": [
                {"url_list": ["https://p3.douyinpic.com/img1.jpg"]},
                {"url_list": ["https://p3.douyinpic.com/img2.png"]},
            ],
        }
        page = f"<script>window._ROUTER_DATA = {json.dumps({'loader': {'x': aweme}})}</script>"
        fake = FakeSession(page)

        msg_q = queue.Queue()
        stop = threading.Event()
        t = threading.Thread(target=dg.worker_main,
                             args=(["https://v.douyin.com/short/"], tmp, True, "",
                                   msg_q, stop),
                             daemon=True)
        # 注入 FakeSession 替换 requests.Session
        original = dg.requests.Session
        dg.requests.Session = lambda: fake
        t.start()
        t.join(timeout=15)
        dg.requests.Session = original
        check("工作线程正常结束", not t.is_alive())

        messages = []
        while not msg_q.empty():
            messages.append(msg_q.get_nowait())
        kinds = [m[0] for m in messages]
        check("队列含 done 消息", "done" in kinds)
        done_msg = [m for m in messages if m[0] == "done"][0]
        check("端到端零失败", done_msg[1] == 0)

        entries = os.listdir(tmp)
        check("生成作品目录", len(entries) == 1 and entries[0].startswith("端到端测试文案"))
        work = os.path.join(tmp, entries[0])
        files = sorted(os.listdir(work))
        check("图片+元信息齐全",
              any(f.endswith(".jpg") for f in files)
              and any(f.endswith(".png") for f in files)
              and "info.json" in files and "caption.txt" in files)
        check("无残留 .part 文件", not any(f.endswith(".part") for f in files))
        with open(os.path.join(work, "info.json"), encoding="utf-8") as f:
            info = json.load(f)
        check("info.json 内容正确", info["author"] == "测试博主"
              and info["aweme_id"] == "7288991100112233")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------- 3. 图片转 PDF ----------
def test_pdf_conversion():
    tmp = tempfile.mkdtemp(prefix="pdf_test_")
    try:
        jpg = os.path.join(tmp, "one.jpg")
        png = os.path.join(tmp, "two.png")
        bmp = os.path.join(tmp, "three.bmp")
        Image.new("RGB", (32, 24), (200, 30, 40)).save(jpg)
        Image.new("RGBA", (20, 20), (0, 0, 0, 0)).save(png)
        Image.new("L", (16, 16), 128).save(bmp)

        output = os.path.join(tmp, "nested", "combined.pdf")
        events = []
        result = dg.images_to_pdf([jpg, png, bmp], output,
                                  progress=lambda c, t: events.append((c, t)))
        check("PDF 返回绝对路径", result == os.path.abspath(output))
        check("PDF 文件生成", os.path.isfile(output))
        with open(output, "rb") as f:
            data = f.read()
        check("PDF 文件头正确", data.startswith(b"%PDF-"))
        pages = len(re.findall(rb"/Type\s*/Page(?!s)\b", data))
        check("PDF 多页数量正确", pages == 3)
        check("PDF 转换进度完整", events == [(1, 3), (2, 3), (3, 3)])
        check("PDF 无残留临时文件", not os.path.exists(output + ".part"))

        with Image.open(png) as source:
            flattened = dg._image_to_rgb(source)
        check("透明图片转 RGB 白底",
              flattened.mode == "RGB" and flattened.getpixel((1, 1)) == (255, 255, 255))
        flattened.close()

        exif_path = os.path.join(tmp, "oriented.jpg")
        exif = Image.Exif()
        exif[274] = 6
        Image.new("RGB", (40, 20), (10, 20, 30)).save(exif_path, exif=exif)
        with Image.open(exif_path) as source:
            oriented = dg._image_to_rgb(source)
        check("EXIF 方向自动修正", oriented.size == (20, 40))
        oriented.close()

        bad = os.path.join(tmp, "bad.txt")
        with open(bad, "w", encoding="utf-8") as f:
            f.write("not an image")
        bad_pdf = os.path.join(tmp, "bad.pdf")
        try:
            dg.images_to_pdf([bad], bad_pdf)
            check("损坏图片抛出可读异常", False)
        except dg.ExtractError as e:
            check("损坏图片抛出可读异常", "图片转 PDF 失败" in str(e))
        check("失败时不留 PDF 或临时文件",
              not os.path.exists(bad_pdf) and not os.path.exists(bad_pdf + ".part"))

        try:
            dg.images_to_pdf([], os.path.join(tmp, "empty.pdf"))
            check("空图片列表被拒绝", False)
        except dg.ExtractError:
            check("空图片列表被拒绝", True)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_pdf_worker():
    tmp = tempfile.mkdtemp(prefix="pdf_worker_test_")
    try:
        paths = []
        for index in range(2):
            path = os.path.join(tmp, f"{index}.png")
            Image.new("RGB", (12, 12), (index * 100, 20, 30)).save(path)
            paths.append(path)
        output = os.path.join(tmp, "worker.pdf")
        msg_q = queue.Queue()
        dg.pdf_worker(paths, output, msg_q)
        messages = []
        while not msg_q.empty():
            messages.append(msg_q.get_nowait())
        done = [m for m in messages if m[0] == "pdf_done"]
        progress = [m for m in messages if m[0] == "progress"]
        check("PDF 后台任务成功消息", len(done) == 1 and done[0][1] is True)
        check("PDF 后台任务进度消息", progress and progress[-1][1:] == (2, 2))
        check("PDF 后台任务生成文件", os.path.isfile(output))

        bad = os.path.join(tmp, "invalid.png")
        with open(bad, "wb") as f:
            f.write(b"invalid")
        failed_output = os.path.join(tmp, "failed.pdf")
        dg.pdf_worker([bad], failed_output, msg_q)
        failed_messages = []
        while not msg_q.empty():
            failed_messages.append(msg_q.get_nowait())
        failed_done = [m for m in failed_messages if m[0] == "pdf_done"]
        check("PDF 后台异常可读返回", len(failed_done) == 1 and failed_done[0][1] is False)
        check("PDF 后台异常无残缺文件", not os.path.exists(failed_output + ".part"))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------- 4. GUI 冒烟 ----------
def test_gui():
    try:
        app = dg.App()
        app.update_idletasks()
        app.update()
        check("GUI 主窗口创建", app.winfo_exists())

        # 模拟用户输入
        app._clear_placeholder()
        app.link_text.insert("1.0",
                             "https://v.douyin.com/aaa/\nhttps://v.douyin.com/bbb/\n")
        links = app._collect_links()
        check("GUI 收集多链接", len(links) == 2)

        # 日志与进度控件可用
        app._append_log("测试日志行")
        app.progress.config(value=50)
        app.status_var.set("测试状态")
        app.update()
        check("GUI 日志/进度更新无异常", True)

        # 停止逻辑(无任务时)
        app._stop()
        app.update()
        check("GUI 停止按钮安全调用", True)

        check("GUI 图片转 PDF 按钮存在",
              hasattr(app, "pdf_btn") and app.pdf_btn.cget("text") == "图片转 PDF")
        original_info = dg.messagebox.showinfo
        try:
            dg.messagebox.showinfo = lambda *args, **kwargs: None
            app.msg_q.put(("progress", 2, 2))
            app.msg_q.put(("pdf_done", True, "demo.pdf"))
            app._poll_queue()
            app.update()
            check("GUI PDF 队列消息处理",
                  app.status_var.get() == "PDF 转换完成"
                  and float(app.progress["value"]) == 100.0
                  and str(app.pdf_btn["state"]) == "normal")
        finally:
            dg.messagebox.showinfo = original_info

        app.destroy()
        try:
            exists_after = app.winfo_exists()
        except dg.tk.TclError:
            exists_after = 0
        check("GUI 窗口销毁", not exists_after)
    except Exception as e:
        check(f"GUI 冒烟 (异常: {type(e).__name__}: {e})", False)


if __name__ == "__main__":
    print("=" * 60)
    test_extract_url()
    test_safe_name()
    test_id_regex()
    test_parse_html()
    test_find_aweme()
    test_antibot()
    test_end_to_end()
    test_pdf_conversion()
    test_pdf_worker()
    test_gui()
    print("=" * 60)
    print(f"结果: {PASS} 通过, {FAIL} 失败")
    sys.exit(1 if FAIL else 0)
