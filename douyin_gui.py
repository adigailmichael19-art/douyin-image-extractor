#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
抖音图文提取器 (GUI 版)
- Tkinter 图形界面, 支持多链接批量提取图文原图 / 无水印视频
- 后台线程执行, 实时日志与进度, 可随时停止
- 支持可选 Cookie 输入以降低风控概率
- 支持多张图片合并为 PDF, 自动处理透明背景与 EXIF 方向
依赖: pip install -r requirements.txt
"""

import json
import os
import queue
import re
import sys
import threading
import time
from datetime import datetime
from urllib.parse import unquote

import requests
from PIL import Image, ImageOps

import tkinter as tk
from tkinter import ttk, filedialog, messagebox, scrolledtext

APP_TITLE = "抖音图文提取器"
APP_VERSION = "1.1"

UA_MOBILE = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
)
UA_DESKTOP = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
)

URL_RE = re.compile(r'https?://[^\s，。；、“”‘’）)】]+')
ID_RE = re.compile(r'(?:/note/|/video/|modal_id=|aweme_id=)(\d{10,})')
ROUTER_RE = re.compile(r'window\._ROUTER_DATA\s*=\s*(\{.*?\})\s*;?\s*</script>', re.S)
RENDER_RE = re.compile(r'id="RENDER_DATA"[^>]*>(.*?)</script>', re.S)
ANTIBOT_MARKERS = ("acrawler", "verify", "captcha", "验证码", "安全验证", "访问过于频繁", "滑块")

# 线程 -> UI 的消息类型: log / status / progress / done


class ExtractError(Exception):
    """业务错误, 消息可直接展示给用户"""


# ============================ 核心引擎 ============================

def extract_first_url(text):
    m = URL_RE.search(text.strip())
    if not m:
        raise ExtractError("内容中没有找到 http(s) 链接")
    return m.group(0)


def resolve_aweme(url, session):
    """返回 (aweme_id, 'note'|'video')"""
    m = ID_RE.search(url)
    kind = "note" if "/note/" in url else "video"
    if m:
        return m.group(1), kind

    resp = session.get(url, headers={"User-Agent": UA_MOBILE},
                       allow_redirects=True, timeout=15)
    target = resp.url
    m = ID_RE.search(target) or ID_RE.search(resp.text)
    if not m:
        raise ExtractError(f"无法解析作品 ID, 最终地址: {target}")
    kind = "note" if "/note/" in target else "video"
    return m.group(1), kind


def parse_embedded_json(html):
    m = ROUTER_RE.search(html)
    if m:
        try:
            return json.loads(m.group(1))
        except json.JSONDecodeError:
            pass
    m = RENDER_RE.search(html)
    if m:
        try:
            return json.loads(unquote(m.group(1)))
        except json.JSONDecodeError:
            pass
    return None


def find_aweme_obj(obj):
    """递归查找作品字典"""
    if isinstance(obj, dict):
        if obj.get("images") is not None or ("video" in obj and "desc" in obj):
            return obj
        for value in obj.values():
            found = find_aweme_obj(value)
            if found is not None:
                return found
    elif isinstance(obj, list):
        for value in obj:
            found = find_aweme_obj(value)
            if found is not None:
                return found
    return None


def safe_name(name, limit=60):
    name = re.sub(r'[\\/:*?"<>|\r\n\t]', "_", name or "").strip()
    return (name[:limit] or "douyin").rstrip(".")


def is_antibot(html):
    low = html.lower()
    return any(marker.lower() in low for marker in ANTIBOT_MARKERS)


def fetch_aweme_data(aweme_id, kind, session, log, stop_event, retries=2):
    pages = [
        (f"https://www.iesdouyin.com/share/{kind}/{aweme_id}", UA_MOBILE),
        (f"https://www.douyin.com/{kind}/{aweme_id}", UA_DESKTOP),
    ]
    for attempt in range(retries + 1):
        if stop_event.is_set():
            raise ExtractError("任务已停止")
        for page_url, ua in pages:
            if stop_event.is_set():
                raise ExtractError("任务已停止")
            log(f"  请求页面: {page_url}")
            try:
                resp = session.get(page_url, headers={"User-Agent": ua}, timeout=15)
            except requests.RequestException as e:
                log(f"  请求异常: {e}")
                continue

            html = resp.text
            data = parse_embedded_json(html)
            if data is not None:
                aweme = find_aweme_obj(data)
                if aweme is not None:
                    log("  页面数据解析成功")
                    return aweme

            if is_antibot(html):
                log(f"  返回安全验证/反爬空壳 (HTTP {resp.status_code}, {len(html)} 字节)")
            else:
                log(f"  未找到内嵌数据 (HTTP {resp.status_code}, {len(html)} 字节)")

        if attempt < retries:
            wait = 2 * (attempt + 1)
            log(f"  {wait} 秒后进行第 {attempt + 2} 次尝试...")
            if stop_event.wait(wait):
                raise ExtractError("任务已停止")

    raise ExtractError("无法获取作品数据, 可能触发风控; 可在高级选项中填写 Cookie 后重试")


def download_file(url, path, session, stop_event, log, retries=3):
    part_path = path + ".part"
    for attempt in range(retries):
        if stop_event.is_set():
            raise ExtractError("任务已停止")
        try:
            headers = {"User-Agent": UA_MOBILE, "Referer": "https://www.douyin.com/"}
            with session.get(url, headers=headers, stream=True, timeout=30) as r:
                r.raise_for_status()
                with open(part_path, "wb") as f:
                    for chunk in r.iter_content(1 << 16):
                        if stop_event.is_set():
                            f.close()
                            if os.path.exists(part_path):
                                os.remove(part_path)
                            raise ExtractError("任务已停止")
                        f.write(chunk)
            os.replace(part_path, path)
            return True
        except requests.RequestException as e:
            if os.path.exists(part_path):
                try:
                    os.remove(part_path)
                except OSError:
                    pass
            if attempt == retries - 1:
                log(f"  [下载失败] {e}")
                return False
            time.sleep(1.5 * (attempt + 1))
    return False


def _image_to_rgb(source):
    """按 EXIF 修正方向，并把透明图片合成到白色背景。"""
    oriented = ImageOps.exif_transpose(source)
    if oriented.mode in ("RGBA", "LA") or "transparency" in oriented.info:
        rgba = oriented.convert("RGBA")
        background = Image.new("RGBA", rgba.size, (255, 255, 255, 255))
        background.alpha_composite(rgba)
        return background.convert("RGB")
    return oriented.convert("RGB")


def images_to_pdf(image_paths, output_pdf, progress=None):
    """按给定顺序把多张图片合并为一个 PDF，返回输出绝对路径。"""
    paths = list(image_paths)
    if not paths:
        raise ExtractError("请选择至少一张图片")
    if not output_pdf:
        raise ExtractError("请选择 PDF 保存位置")

    output_pdf = os.path.abspath(os.fspath(output_pdf))
    os.makedirs(os.path.dirname(output_pdf), exist_ok=True)
    part_path = output_pdf + ".part"
    frames = []
    try:
        total = len(paths)
        for index, path in enumerate(paths, 1):
            with Image.open(path) as source:
                frames.append(_image_to_rgb(source))
            if progress:
                progress(index, total)

        frames[0].save(part_path, format="PDF", save_all=True,
                       append_images=frames[1:], resolution=150.0)
        os.replace(part_path, output_pdf)
        return output_pdf
    except Exception as e:
        if os.path.exists(part_path):
            try:
                os.remove(part_path)
            except OSError:
                pass
        if isinstance(e, ExtractError):
            raise
        raise ExtractError(f"图片转 PDF 失败: {e}") from e
    finally:
        for frame in frames:
            frame.close()


def process_one(raw_text, out_root, want_video, session, log, stop_event):
    url = extract_first_url(raw_text)
    log(f"链接: {url}")

    aweme_id, kind = resolve_aweme(url, session)
    log(f"作品 ID: {aweme_id}    类型: {kind}")

    aweme = fetch_aweme_data(aweme_id, kind, session, log, stop_event)

    desc = aweme.get("desc", "")
    author = ""
    author_info = aweme.get("author")
    if isinstance(author_info, dict):
        author = author_info.get("nickname", "")
    log(f"作者: {author or '未知'}    文案: {desc or '(无)'}")

    save_dir = os.path.join(out_root, f"{safe_name(desc)}_{aweme_id}")
    os.makedirs(save_dir, exist_ok=True)

    meta = {"aweme_id": aweme_id, "type": kind, "desc": desc,
            "author": author, "source_url": url}
    with open(os.path.join(save_dir, "info.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    with open(os.path.join(save_dir, "caption.txt"), "w", encoding="utf-8") as f:
        f.write(f"作者: {author}\n文案: {desc}\n链接: {url}\n")

    images = aweme.get("images")
    if images:
        log(f"图文笔记共 {len(images)} 张图片, 开始下载...")
        ok = 0
        for idx, img in enumerate(images, 1):
            if stop_event.is_set():
                raise ExtractError("任务已停止")
            url_list = img.get("url_list", []) if isinstance(img, dict) else []
            if not url_list:
                log(f"  ({idx}/{len(images)}) 无可用地址, 跳过")
                continue
            img_url = url_list[-1]
            suffix = ".jpg"
            m = re.search(r'\.(jpe?g|png|webp|heic)', img_url, re.I)
            if m:
                suffix = "." + m.group(1).lower().replace("jpeg", "jpg")
            path = os.path.join(save_dir, f"{idx:03d}{suffix}")
            log(f"  ({idx}/{len(images)}) 下载中...")
            if download_file(img_url, path, session, stop_event, log):
                ok += 1
        log(f"图片完成: {ok}/{len(images)} 张 -> {save_dir}")
        return "note", ok

    video_info = aweme.get("video", {})
    play_addr = video_info.get("play_addr", {})
    video_urls = play_addr.get("url_list", [])
    if not video_urls:
        raise ExtractError("未找到视频地址, 作品可能权限受限")
    video_url = video_urls[0].replace("playwm", "play")
    if want_video:
        log("视频作品, 开始下载...")
        path = os.path.join(save_dir, "video.mp4")
        if download_file(video_url, path, session, stop_event, log):
            log(f"视频完成 -> {path}")
            return "video", 1
        raise ExtractError("视频下载失败")
    log(f"已跳过视频下载, 地址: {video_url}")
    return "video", 0


def worker_main(link_lines, out_root, want_video, cookie, msg_q, stop_event):
    """后台线程入口"""
    def log(msg):
        msg_q.put(("log", msg))

    session = requests.Session()
    session.headers.update({"Accept-Language": "zh-CN,zh;q=0.9"})
    if cookie.strip():
        session.headers.update({"Cookie": cookie.strip()})

    total = len(link_lines)
    fail = 0
    log(f"任务开始, 共 {total} 个链接, 输出目录: {out_root}")

    for i, raw in enumerate(link_lines, 1):
        msg_q.put(("status", f"正在处理 {i}/{total}"))
        msg_q.put(("progress", i - 1, total))
        log("-" * 64)
        log(f"[{i}/{total}]")
        try:
            process_one(raw, out_root, want_video, session, log, stop_event)
        except ExtractError as e:
            log(f"[失败] {e}")
            fail += 1
        except Exception as e:  # 兜底, 保证线程不崩
            log(f"[失败] 未预期错误: {type(e).__name__}: {e}")
            fail += 1

    msg_q.put(("progress", total, total))
    msg_q.put(("status", "任务结束"))
    msg_q.put(("done", fail))


def pdf_worker(image_paths, output_pdf, msg_q):
    """后台转换图片，避免文件较多时阻塞 GUI。"""
    try:
        total = len(image_paths)
        msg_q.put(("status", f"正在转换 {total} 张图片"))
        msg_q.put(("log", f"开始图片转 PDF, 共 {total} 张"))

        def progress(current, total_n):
            msg_q.put(("progress", current, total_n))

        result = images_to_pdf(image_paths, output_pdf, progress=progress)
        msg_q.put(("log", f"PDF 完成 -> {result}"))
        msg_q.put(("pdf_done", True, result))
    except Exception as e:
        msg_q.put(("log", f"[失败] 图片转 PDF: {e}"))
        msg_q.put(("pdf_done", False, str(e)))


# ============================ GUI ============================

class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f"{APP_TITLE} v{APP_VERSION}")
        self.geometry("880x760")
        self.minsize(760, 640)

        self.msg_q = queue.Queue()
        self.stop_event = threading.Event()
        self.worker = None

        self._setup_style()
        self._build_ui()
        self.after(100, self._poll_queue)
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    def _setup_style(self):
        style = ttk.Style(self)
        try:
            style.theme_use("vista")
        except tk.TclError:
            try:
                style.theme_use("clam")
            except tk.TclError:
                pass
        style.configure("TLabel", font=("Microsoft YaHei UI", 10))
        style.configure("Header.TLabel", font=("Microsoft YaHei UI", 16, "bold"),
                        foreground="#1e3a8a")
        style.configure("Sub.TLabel", font=("Microsoft YaHei UI", 9),
                        foreground="#64748b")
        style.configure("TButton", font=("Microsoft YaHei UI", 10), padding=6)
        style.configure("Accent.TButton", font=("Microsoft YaHei UI", 10, "bold"),
                        padding=8, foreground="#ffffff")
        style.map("Accent.TButton",
                  background=[("active", "#1d4ed8"), ("!disabled", "#2563eb"),
                              ("disabled", "#93c5fd")])
        style.configure("TCheckbutton", font=("Microsoft YaHei UI", 10))
        style.configure("TEntry", padding=4)
        style.configure("TProgressbar", thickness=14)

    def _build_ui(self):
        pad = {"padx": 14, "pady": 6}
        root = ttk.Frame(self)
        root.pack(fill="both", expand=True)

        ttk.Label(root, text=f"{APP_TITLE}", style="Header.TLabel").grid(
            row=0, column=0, columnspan=3, sticky="w", **pad)
        ttk.Label(root, text="批量提取抖音图文原图与无水印视频, 仅供个人学习使用",
                  style="Sub.TLabel").grid(row=1, column=0, columnspan=3,
                                           sticky="w", padx=14)

        # 链接输入
        ttk.Label(root, text="作品链接 / 分享口令(每行一个, 支持多个):").grid(
            row=2, column=0, columnspan=3, sticky="w", padx=14, pady=(12, 2))
        link_frame = ttk.Frame(root)
        link_frame.grid(row=3, column=0, columnspan=3, sticky="ew", padx=14)
        self.link_text = scrolledtext.ScrolledText(
            link_frame, height=7, wrap="word", font=("Consolas", 10),
            relief="solid", borderwidth=1)
        self.link_text.pack(fill="x")
        self._placeholder = "例如: https://v.douyin.com/xxxxxx/  或直接粘贴整段 App 分享口令"
        self._add_placeholder()
        self.link_text.bind("<FocusIn>", self._clear_placeholder)
        self.link_text.bind("<FocusOut>", self._restore_placeholder)

        # 输出目录
        ttk.Label(root, text="输出目录:").grid(row=4, column=0, sticky="w",
                                               padx=14, pady=(12, 2))
        dir_frame = ttk.Frame(root)
        dir_frame.grid(row=5, column=0, columnspan=3, sticky="ew", padx=14)
        self.out_var = tk.StringVar(value=os.path.join(os.getcwd(), "douyin_downloads"))
        ttk.Entry(dir_frame, textvariable=self.out_var).pack(
            side="left", fill="x", expand=True)
        ttk.Button(dir_frame, text="浏览...", command=self._choose_dir).pack(
            side="left", padx=(8, 0))

        # 高级: Cookie
        ttk.Label(root, text="Cookie (可选, 遇风控时填写浏览器中的 Cookie):").grid(
            row=6, column=0, sticky="w", padx=14, pady=(12, 2))
        self.cookie_var = tk.StringVar()
        ttk.Entry(root, textvariable=self.cookie_var).grid(
            row=7, column=0, columnspan=3, sticky="ew", padx=14)

        # 选项
        opt_frame = ttk.Frame(root)
        opt_frame.grid(row=8, column=0, columnspan=3, sticky="w", padx=14, pady=10)
        self.video_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(opt_frame, text="视频作品自动下载 mp4 (取消勾选则只解析地址)",
                        variable=self.video_var).pack(side="left")

        # 按钮
        btn_frame = ttk.Frame(root)
        btn_frame.grid(row=9, column=0, columnspan=3, sticky="ew", padx=14, pady=4)
        self.start_btn = ttk.Button(btn_frame, text="开始提取", style="Accent.TButton",
                                    command=self._start)
        self.start_btn.pack(side="left")
        self.stop_btn = ttk.Button(btn_frame, text="停止", command=self._stop,
                                   state="disabled")
        self.stop_btn.pack(side="left", padx=8)
        ttk.Button(btn_frame, text="打开输出目录", command=self._open_dir).pack(
            side="left")
        ttk.Button(btn_frame, text="清空日志", command=self._clear_log).pack(
            side="left", padx=8)
        self.pdf_btn = ttk.Button(btn_frame, text="图片转 PDF",
                                  command=self._convert_images_to_pdf)
        self.pdf_btn.pack(side="left")

        # 进度
        prog_frame = ttk.Frame(root)
        prog_frame.grid(row=10, column=0, columnspan=3, sticky="ew", padx=14,
                        pady=(10, 2))
        self.progress = ttk.Progressbar(prog_frame, mode="determinate", maximum=100)
        self.progress.pack(side="left", fill="x", expand=True)
        self.status_var = tk.StringVar(value="就绪")
        ttk.Label(prog_frame, textvariable=self.status_var, width=16,
                  anchor="e").pack(side="left", padx=(10, 0))

        # 日志
        ttk.Label(root, text="运行日志:").grid(row=11, column=0, sticky="w",
                                               padx=14, pady=(10, 2))
        self.log_box = scrolledtext.ScrolledText(
            root, height=13, wrap="word", font=("Consolas", 9),
            bg="#0f172a", fg="#e2e8f0", relief="flat", state="disabled")
        self.log_box.grid(row=12, column=0, columnspan=3, sticky="nsew",
                          padx=14, pady=(0, 14))
        self.log_box.tag_configure("err", foreground="#fca5a5")

        root.columnconfigure(0, weight=1)
        root.rowconfigure(12, weight=1)

    # ---- placeholder ----
    def _add_placeholder(self):
        self.link_text.insert("1.0", self._placeholder)
        self.link_text.config(foreground="#94a3b8")
        self._placeholder_active = True

    def _clear_placeholder(self, _event=None):
        if getattr(self, "_placeholder_active", False):
            self.link_text.delete("1.0", "end")
            self.link_text.config(foreground="#0f172a")
            self._placeholder_active = False

    def _restore_placeholder(self, _event=None):
        if not self.link_text.get("1.0", "end").strip():
            self._add_placeholder()

    # ---- 按钮回调 ----
    def _choose_dir(self):
        path = filedialog.askdirectory(initialdir=self.out_var.get() or os.getcwd())
        if path:
            self.out_var.set(path)

    def _collect_links(self):
        if getattr(self, "_placeholder_active", False):
            return []
        lines = []
        for line in self.link_text.get("1.0", "end").splitlines():
            line = line.strip()
            if line and URL_RE.search(line):
                lines.append(line)
        return lines

    def _start(self):
        links = self._collect_links()
        if not links:
            messagebox.showwarning(APP_TITLE, "请先粘贴至少一个有效的抖音链接")
            return
        out_dir = self.out_var.get().strip()
        if not out_dir:
            messagebox.showwarning(APP_TITLE, "请选择输出目录")
            return
        try:
            os.makedirs(out_dir, exist_ok=True)
        except OSError as e:
            messagebox.showerror(APP_TITLE, f"输出目录无法创建: {e}")
            return

        self.stop_event.clear()
        self.start_btn.config(state="disabled")
        self.stop_btn.config(state="normal")
        self.progress.config(value=0)
        self._append_log(f"===== {datetime.now():%Y-%m-%d %H:%M:%S} 启动 =====")

        self.worker = threading.Thread(
            target=worker_main,
            args=(links, out_dir, self.video_var.get(), self.cookie_var.get(),
                  self.msg_q, self.stop_event),
            daemon=True)
        self.worker.start()

    def _convert_images_to_pdf(self):
        paths = filedialog.askopenfilenames(
            title="选择要合并的图片",
            filetypes=[
                ("图片文件", "*.jpg *.jpeg *.png *.webp *.bmp *.tif *.tiff"),
                ("所有文件", "*.*"),
            ])
        if not paths:
            return
        output_pdf = filedialog.asksaveasfilename(
            title="保存 PDF",
            defaultextension=".pdf",
            initialfile="图片合集.pdf",
            filetypes=[("PDF 文件", "*.pdf")])
        if not output_pdf:
            return

        self.pdf_btn.config(state="disabled")
        self.progress.config(value=0)
        threading.Thread(target=pdf_worker, args=(paths, output_pdf, self.msg_q),
                         daemon=True).start()

    def _on_pdf_done(self, success, result):
        self.pdf_btn.config(state="normal")
        if success:
            self.progress.config(value=100)
            self.status_var.set("PDF 转换完成")
            messagebox.showinfo(APP_TITLE, f"PDF 已保存:\n{result}")
        else:
            self.status_var.set("PDF 转换失败")
            messagebox.showerror(APP_TITLE, f"图片转 PDF 失败:\n{result}")

    def _stop(self):
        if self.worker and self.worker.is_alive():
            self.stop_event.set()
            self.status_var.set("正在停止...")
            self._append_log("收到停止指令, 等待当前操作结束...")
        self.stop_btn.config(state="disabled")

    def _open_dir(self):
        path = self.out_var.get().strip()
        if not path or not os.path.isdir(path):
            messagebox.showinfo(APP_TITLE, "输出目录尚不存在, 开始提取后会自动创建")
            return
        if sys.platform.startswith("win"):
            os.startfile(path)  # type: ignore[attr-defined]
        elif sys.platform == "darwin":
            os.system(f'open "{path}"')
        else:
            os.system(f'xdg-open "{path}"')

    def _clear_log(self):
        self.log_box.config(state="normal")
        self.log_box.delete("1.0", "end")
        self.log_box.config(state="disabled")

    def _append_log(self, text, error=False):
        self.log_box.config(state="normal")
        tags = ("err",) if error else ()
        self.log_box.insert("end", text + "\n", tags)
        self.log_box.see("end")
        self.log_box.config(state="disabled")

    # ---- 队列轮询 ----
    def _poll_queue(self):
        try:
            while True:
                message = self.msg_q.get_nowait()
                kind = message[0]
                if kind == "log":
                    payload = message[1]
                    is_err = payload.startswith("[失败]") or "失败" in payload[:8]
                    self._append_log(payload, error=is_err)
                elif kind == "status":
                    self.status_var.set(message[1])
                elif kind == "progress":
                    _, current, total_n = message
                    self.progress.config(value=current / total_n * 100)
                elif kind == "done":
                    self._on_task_done(message[1])
                elif kind == "pdf_done":
                    _, success, result = message
                    self._on_pdf_done(success, result)
        except queue.Empty:
            pass
        self.after(100, self._poll_queue)

    def _on_task_done(self, fail):
        self.start_btn.config(state="normal")
        self.stop_btn.config(state="disabled")
        self.progress.config(value=100)
        if fail == 0:
            self.status_var.set("全部成功")
            messagebox.showinfo(APP_TITLE, "全部链接提取完成!")
        else:
            self.status_var.set(f"结束, {fail} 个失败")
            messagebox.showwarning(APP_TITLE, f"任务结束, {fail} 个链接失败, 请查看日志")

    def _on_close(self):
        if self.worker and self.worker.is_alive():
            self.stop_event.set()
        self.destroy()


def main():
    app = App()
    app.mainloop()


if __name__ == "__main__":
    main()
