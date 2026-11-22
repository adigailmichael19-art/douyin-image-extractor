# -*- coding: utf-8 -*-
"""旧图标处理：超采样圆角矩形遮罩，切除外发光黑边，边缘抗锯齿。

把 app_icon.png 放在仓库根目录，然后运行：
    python tools/clean_icon.py
输出 app_icon_transparent.png 与 app.ico（同样在仓库根目录）。
"""

from pathlib import Path

from PIL import Image, ImageDraw

BASE_DIR = Path(__file__).resolve().parent.parent
SRC = BASE_DIR / "app_icon.png"
OUT_PNG = BASE_DIR / "app_icon_transparent.png"
OUT_ICO = BASE_DIR / "app.ico"

A = 324          # 亮边外沿到画布边的距离
R = 294          # 圆角半径
SS = 2           # 超采样倍数


def main():
    if not SRC.exists():
        raise SystemExit(f"找不到源图：{SRC}")

    rgba = Image.open(SRC).convert("RGBA")
    w, h = rgba.size

    # 在超采样尺寸上绘制遮罩, 再缩小实现抗锯齿
    big = Image.new("L", (w * SS, h * SS), 0)
    d = ImageDraw.Draw(big)
    box = [A * SS, A * SS, (w - A) * SS, (h - A) * SS]
    d.rounded_rectangle(box, radius=R * SS, fill=255)
    mask = big.resize((w, h), Image.LANCZOS)

    # 应用遮罩作为 alpha
    result = rgba.copy()
    result.putalpha(mask)
    result.save(OUT_PNG)

    # 重新生成多尺寸 ICO
    sizes = [(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    result.save(OUT_ICO, format="ICO", sizes=sizes)

    # 验证
    vp = result.load()
    print("四角 alpha:", vp[2, 2][3], vp[w - 3, 2][3], vp[2, h - 3][3], vp[w - 3, h - 3][3])
    print("中心 alpha:", vp[w // 2, h // 2][3])
    print("ICO 尺寸:", Image.open(OUT_ICO).info.get("sizes"))
    # 边缘采样: 遮罩边界附近应有平滑过渡
    print("边缘 alpha 采样(顶 x=1024):",
          [vp[w // 2, y][3] for y in range(318, 330, 2)])
    print("已保存:", OUT_ICO)


if __name__ == "__main__":
    main()
