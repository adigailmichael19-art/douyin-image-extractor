# -*- coding: utf-8 -*-
"""GitHub 风格图标: 圆角透明遮罩 -> 多尺寸 ICO"""
from PIL import Image, ImageDraw

SRC = r"D:\DouyinExtractor\app_icon_github.png"
OUT_PNG = r"D:\DouyinExtractor\app_icon_github_transparent.png"
OUT_ICO = r"D:\DouyinExtractor\app.ico"

A = 0            # 深灰底占满, 无边距
R = 450          # 圆角半径 (约边长 22%, iOS 标准比例)
SS = 2           # 超采样倍数

rgba = Image.open(SRC).convert("RGBA")
w, h = rgba.size

big = Image.new("L", (w * SS, h * SS), 0)
d = ImageDraw.Draw(big)
d.rounded_rectangle([A * SS, A * SS, (w - A) * SS, (h - A) * SS],
                    radius=R * SS, fill=255)
mask = big.resize((w, h), Image.LANCZOS)

result = rgba.copy()
result.putalpha(mask)
result.save(OUT_PNG)

sizes = [(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
result.save(OUT_ICO, format="ICO", sizes=sizes)

vp = result.load()
print("四角 alpha:", vp[2, 2][3], vp[w - 3, 2][3], vp[2, h - 3][3], vp[w - 3, h - 3][3])
print("中心 alpha:", vp[w // 2, h // 2][3])
print("ICO 尺寸:", Image.open(OUT_ICO).info.get("sizes"))
print("已保存:", OUT_ICO)
