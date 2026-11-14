# 抖音图文提取器

一个 Windows 桌面工具，使用 Tkinter 图形界面批量解析抖音分享链接，提取图文原图、作品文案与作者信息，并支持视频下载。

> 仅供个人学习与技术研究使用。请遵守平台规则及适用法律，尊重原创作者版权，不要将下载内容用于未经授权的传播或商业用途。

## 功能

- 支持抖音短链、完整作品链接和 App 分享口令
- 多链接批量处理，每行一个
- 自动识别图文（note）与视频（video）作品
- 图文原图批量下载，保存作者、文案和来源链接
- 视频下载开关；取消勾选时仅解析地址
- 可选 Cookie 输入、实时日志、进度显示、停止任务
- 下载使用 `.part` 临时文件，避免残留不完整文件
- 后台线程执行，界面不会因网络任务阻塞

## 环境要求

- Windows 10/11
- Python 3.12 或更高版本（已在 Python 3.14.7 验证）
- Python 自带 Tkinter
- 依赖见 `requirements.txt`

安装依赖：

```powershell
python -m pip install -r requirements.txt
```

`requests` 是工具运行必需依赖；`Pillow` 仅用于重新生成图标。

## 启动

```powershell
python douyin_gui.py
```

也可以双击 `launch.vbs` 静默启动。当前启动器按本机环境配置为：

- 程序目录：`D:\DouyinExtractor`
- Python：`D:\python\python.exe`

如果你的路径不同，请用记事本修改 `launch.vbs` 中的这两个路径。

## 使用方法

1. 粘贴一个或多个抖音作品链接/分享口令，每行一个。
2. 选择输出目录。
3. 如遇到平台风控，可在高级选项中填写本人浏览器里的 Cookie；留空则不带 Cookie 请求。
4. 根据需要选择是否下载视频，点击“开始提取”。
5. 每个作品保存到独立目录，包含图片或视频、`info.json` 和 `caption.txt`。

注意：抖音页面结构和风控策略可能变化。遇到验证码、滑块或反爬空壳时，纯 `requests` 不保证解析成功，可稍后重试或使用合法合规的方式调整网络与 Cookie。不要在公开仓库、截图或日志中泄露自己的 Cookie。

## 测试

```powershell
python test_douyin_gui.py
```

测试覆盖链接解析、内嵌 JSON 解析、作品对象定位、反爬识别、端到端模拟下载以及 GUI 冒烟流程。测试使用 `FakeSession`，不会访问真实抖音服务。

## 图标

仓库不依赖二进制图标文件。需要应用图标时，运行：

```powershell
python make_github_icon.py
```

脚本会生成 `app.ico`、`app_icon_github.png` 和 `app_icon_github_transparent.png`。如果目录中已有自己设计的 `app_icon_github.png`，脚本会沿用该原图；否则会绘制深灰底、白色叠层图片与音符的 GitHub 风图标。ICO 包含 16、32、48、64、128、256 像素尺寸。

## 文件说明

| 文件 | 用途 |
| --- | --- |
| `douyin_gui.py` | GUI 主程序与提取引擎 |
| `test_douyin_gui.py` | 自动化测试脚本 |
| `launch.vbs` | Windows 静默启动器 |
| `make_github_icon.py` | 自包含图标生成脚本，可生成多尺寸 ICO |
| `clean_icon.py` | 旧版图标的可选处理脚本，需要自行提供旧版 PNG 源图 |

## 免责声明

本工具不绕过登录权限、付费限制或访问控制，不保证适用于所有作品。使用者应自行确认其使用行为符合平台条款、作者授权和当地法律。
