# -*- coding: utf-8 -*-
"""生成 GitHub 风透明圆角图标；没有 PNG 源图时自动绘制。"""

from pathlib import Path

from PIL import Image, ImageDraw

BASE_DIR = Path(__file__).resolve().parent
SRC = BASE_DIR / "app_icon_github.png"
OUT_PNG = BASE_DIR / "app_icon_github_transparent.png"
OUT_ICO = BASE_DIR / "app.ico"


def draw_fallback_icon(size=2048):
    """绘制深灰底、白色叠层图片与音符，风格与当前图标一致。"""
    image = Image.new("RGBA", (size, size), (36, 41, 47, 255))
    draw = ImageDraw.Draw(image)
    s = size / 2048

    def box(values):
        return tuple(round(value * s) for value in values)

    width = round(50 * s)
    # 后层与前层图片轮廓。
    draw.rounded_rectangle(box((500, 360, 1240, 1100)), radius=round(85 * s),
                           outline=(240, 246, 252, 255), width=width)
    draw.rounded_rectangle(box((350, 540, 1090, 1280)), radius=round(85 * s),
                           fill=(36, 41, 47, 255), outline=(240, 246, 252, 255),
                           width=width)
    # 图片内部的山形。
    draw.line([box((500, 1080))[0:2], box((720, 860))[0:2],
               box((900, 1040))[0:2]], fill=(240, 246, 252, 255),
              width=width, joint="curve")
    # 抖音音符的抽象单色造型。
    draw.line([box((1300, 450))[0:2], box((1300, 1220))[0:2]],
              fill=(240, 246, 252, 255), width=round(92 * s))
    draw.line([box((1300, 450))[0:2], box((1630, 570))[0:2]],
              fill=(240, 246, 252, 255), width=round(92 * s))
    draw.ellipse(box((1020, 1110, 1340, 1390)), fill=(240, 246, 252, 255))
    return image


def main():
    if SRC.exists():
        rgba = Image.open(SRC).convert("RGBA")
    else:
        rgba = draw_fallback_icon()
        rgba.save(SRC)

    w, h = rgba.size
    scale = 2
    mask_large = Image.new("L", (w * scale, h * scale), 0)
    draw = ImageDraw.Draw(mask_large)
    draw.rounded_rectangle([0, 0, w * scale, h * scale],
                           radius=round(w * 0.22 * scale), fill=255)
    mask = mask_large.resize((w, h), Image.LANCZOS)

    result = rgba.copy()
    result.putalpha(mask)
    result.save(OUT_PNG)
    sizes = [(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    result.save(OUT_ICO, format="ICO", sizes=sizes)

    pixels = result.load()
    print("四角 alpha:", pixels[2, 2][3], pixels[w - 3, 2][3],
          pixels[2, h - 3][3], pixels[w - 3, h - 3][3])
    print("中心 alpha:", pixels[w // 2, h // 2][3])
    print("ICO 尺寸:", Image.open(OUT_ICO).info.get("sizes"))
    print("已保存:", OUT_ICO)


if __name__ == "__main__":
    main()
