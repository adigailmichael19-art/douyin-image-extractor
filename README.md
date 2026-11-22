# 抖音图文提取器

<p>
  <img alt="Platform" src="https://img.shields.io/badge/Platform-Windows%20%7C%20Android-475569">
  <img alt="Python" src="https://img.shields.io/badge/Python-3.12%2B-3776AB?logo=python&logoColor=white">
  <img alt="Android" src="https://img.shields.io/badge/Android-1.7-3DDC84?logo=android&logoColor=white">
  <img alt="License" src="https://img.shields.io/badge/License-MIT-2EA44F">
</p>

一个面向个人学习与技术研究的抖音内容保存工具，包含 **Windows 桌面版** 和 **原生 Android 版**。支持批量解析公开作品页面，保存图文原图、作品文案、作者信息和可选 MP4 视频；图文作品优先读取公开分享页内嵌数据，失败时用隐藏 WebView 兜底；提取成功后可自动按顺序合成独立 PDF、自动导出系统相册，也能把本地多张图片手动合并为 A4 PDF。

[下载 Android APK](https://github.com/adigailmichael19-art/douyin-image-extractor/releases/download/android-debug/app-debug.apk) · [更新记录](CHANGELOG.md) · [Android 构建说明](android/README.md) · [贡献指南](CONTRIBUTING.md)

> 仅供个人学习与技术研究。请遵守平台规则与适用法律，尊重原创作者版权，不要将下载内容用于未经授权的传播或商业用途。

## 下载与版本

| 平台 | 当前版本 | 获取方式 |
| --- | --- | --- |
| Android | `1.7`（versionCode 8） | [下载 debug APK](https://github.com/adigailmichael19-art/douyin-image-extractor/releases/download/android-debug/app-debug.apk) |
| Windows | 源码运行 | 按下方步骤安装依赖并启动 |

Android APK 使用 Android debug 证书签名，仅供个人学习和真机测试。安装前需要在手机上允许安装来自该来源的应用。

## 项目结构

根目录只保留入口、依赖和文档，代码按用途分层：

```text
.
├── src/
│   └── douyin_gui.py               # Windows 桌面版主程序（入口 + 提取引擎 + Tkinter 界面）
├── tests/
│   └── test_douyin_gui.py          # 桌面版自动化测试（导入时会自动把 src/ 加入路径）
├── tools/
│   ├── make_github_icon.py         # 生成圆角透明图标与多尺寸 ICO
│   └── clean_icon.py               # 旧图标清理（可选，需要根目录放 app_icon.png）
├── android/                        # 原生 Android 工程（包名 com.douyin.extractor）
├── .github/
│   ├── workflows/android.yml       # Android CI：单测、lint、构建和发布
│   ├── ISSUE_TEMPLATE/             # Issue 表单
│   └── PULL_REQUEST_TEMPLATE.md
├── launch.vbs                      # Windows 静默启动器
├── requirements.txt                # 桌面版 Python 依赖
├── CHANGELOG.md                    # 版本变更记录
├── CONTRIBUTING.md                 # 贡献指南
├── SECURITY.md                     # 安全政策
├── LICENSE                         # MIT
└── README.md
```

## 功能

- 支持抖音短链、完整作品链接和 App 分享口令
- 多链接批量处理，每行一个
- 自动识别图文（note）与视频（video）作品
- 图文原图批量下载，并保存作者、文案和来源链接
- 图文作品图片全部下载成功后，自动按原始顺序合成独立 PDF（每个作品一个文件）；日志和结束弹窗显示 PDF 数量与路径
- 图文作品优先请求公开分享页：先通过 `ttwid` 访客接口或 `share/slides` 公开页取得匿名 Cookie，再读取 `share/video` 页面内嵌 `videoInfoRes.item_list[0].images`，稳定拿到全部原图；HTTP 失败时用隐藏 WebView 兜底，不点击或破解验证控件
- 原图下载完成后自动导出到系统相册 `Pictures/DouyinExtractor`（视频为 `Movies/DouyinExtractor`），应用专属目录保留原始文件
- 支持从系统分享面板把抖音分享口令直接发送到本 App；“查看图片”可直接打开刚导出的图片
- 视频下载开关；取消勾选时仅解析并记录地址
- 可选 Cookie 输入，仅用于本人正常访问
- 实时日志、进度显示和停止任务
- 提取失败时区分网络请求失败、安全验证/反爬空壳、页面未包含作品数据三类，并在日志中说明
- 下载使用 `.part` 临时文件，失败时不会留下不完整文件
- 手动“图片转 PDF”：从系统图片选择器选择多张 JPG、PNG、WebP、BMP、TIFF 图片，按选择顺序合并为 A4 PDF
- PDF 转换自动处理透明背景和 EXIF 方向
- Android 版支持横竖屏切换，停止状态和错误提示与桌面版保持一致

## Windows 桌面版

### 环境要求

- Windows 10/11
- Python 3.12 或更高版本
- Python 自带 Tkinter
- 依赖见 `requirements.txt`

```powershell
python -m pip install -r requirements.txt
python src/douyin_gui.py
```

也可以双击 `launch.vbs` 静默启动。启动器会自动定位到本文件所在目录，只需把文件里的 `PYTHON_EXE` 改成你本机的 Python 路径（默认 `D:\python\python.exe`）。

### 使用步骤

1. 粘贴一个或多个抖音作品链接或分享口令，每行一个。
2. 选择输出目录。
3. 遇到风控时，可在高级选项中填写本人浏览器里的 Cookie；留空则不带 Cookie 请求。
4. 根据需要选择是否下载视频，点击“开始提取”。
5. 每个作品保存到独立目录，包含图片或视频、`info.json` 和 `caption.txt`。

### 图片转 PDF

在主界面点击“图片转 PDF”，从文件选择框中选择一张或多张图片。转换会在后台线程执行，日志和进度条同步更新，完成后提示 PDF 保存路径。

## Android 版

包名 `com.douyin.extractor`，最低支持 Android 7.0（API 24），compile/target SDK 34。

### 手机上的使用步骤

1. 在抖音里打开想保存的图文作品，点“分享” → “复制链接”，把整段分享口令粘贴到 App 的链接框；也可以直接把分享内容“发送”到本 App。
2. 勾选或取消“同时下载视频作品的 MP4 文件”，点击“开始提取”。
3. 提取完成后，原图会自动出现在系统相册的 `Pictures/DouyinExtractor` 目录（视频在 `Movies/DouyinExtractor`），应用专属下载目录里同时保留一份原始文件。
4. 如果提示“无法获取作品数据”，先确认网络能正常打开该作品，再展开“Cookie（可选）”粘贴浏览器里本人账号的 Cookie 重试。App 会先自动申请访客 Cookie（ttwid），这一步失败时才会出现该提示。

### 图文提取链路

- 先解析短链，得到作品 ID 与类型；图文（note）作品优先走公开 HTTP 链路。
- 访客 Cookie：优先向 `https://ttwid.bytedance.com/ttwid/union/register/` 申请匿名 `ttwid`，失败时再请求公开页 `https://www.iesdouyin.com/share/slides/<id>/` 接收服务端下发的匿名 Cookie；同一会话内所有请求自动携带，不使用账号 Cookie。
- 数据来源：携带匿名 Cookie 请求 `https://www.iesdouyin.com/share/video/<id>/`（移动 UA），读取 `window._ROUTER_DATA.loaderData['video_(id)/page'].videoInfoRes.item_list[0]`，从中取 `desc`、`author.nickname` 与 `images[].url_list`。该公开页对图文作品同样返回 `images` 数组，按数组顺序保存原图。
- 图片候选：`url_list` 中优先选择 `.jpeg/.jpg` 变体，逐候选重试；下载内容小于 1024 字节视为可能被风控拦截，不会当作成功。
- WebView 兜底：HTTP 链路未拿到图片时，隐藏 WebView 依次加载 `share/video` 与 `share/note` 移动分享页，读取同一份路由数据或页面已加载的 `douyinpic` 图片请求；仍失败则如实报错。
- 安全边界：不点击、不破解验证码、滑块或登录弹窗，不绕过任何访问控制；命中验证环境时直接记录并停止该路径。
- 调试：debug 构建开启 WebView DevTools 远程调试（adb forward + chrome inspect）；release 不开启远程调试。

### 自动生成 PDF

- 图文作品图片全部下载成功后，应用自动把本次图片按原始顺序合成一个独立 PDF，每个作品一个文件，保存在应用外部文件目录 `Documents/douyin_pdfs/`（即 `getExternalFilesDir(DIRECTORY_DOCUMENTS)/douyin_pdfs/`）。
- 0 张图片、部分图片下载失败、任务被停止或提取失败时，不会为该作品生成 PDF；单个作品或单个 PDF 生成失败不会阻断其余作品继续处理。
- 手动“图片转 PDF”入口保留：从系统图片选择器选择本地图片合并为 A4 PDF。

### 直接安装

从 [Release](https://github.com/adigailmichael19-art/douyin-image-extractor/releases/tag/android-debug) 下载 `app-debug.apk` 后安装。该版本使用 debug 证书签名，仅用于个人学习和测试。

### 本地构建

需要 JDK 17、Android SDK 34、Build Tools 34.0.0 和 Gradle 8.7。

```bash
cd android
gradle :app:assembleDebug --no-daemon
```

产物位于 `android/app/build/outputs/apk/debug/app-debug.apk`。更多说明见 [`android/README.md`](android/README.md)。

## 测试与 CI

桌面版测试：

```powershell
python tests/test_douyin_gui.py
```

测试覆盖链接解析、内嵌 JSON 解析、作品对象定位、反爬识别、模拟下载、多页 PDF、透明图、EXIF 方向和 GUI 冒烟流程；抖音相关测试使用模拟会话，不会访问真实服务。脚本自带路径引导，会在导入前把 `src/` 加入 `sys.path`，所以不需要设置 `PYTHONPATH`。

Android 单元测试（本地 JVM，无需真机，不访问真实抖音）：

```bash
cd android
gradle :app:testDebugUnitTest --no-daemon
```

`ExtractorTest` 用内置的本地 HTTP 服务模拟“没有访客 Cookie 就只有空壳页面”的真实行为，覆盖完整提取与 2 张图片落盘、缺 Cookie 报错、`filter_list` 删除提示、无链接报错和文件名清洗五个用例。

2026-11-21 真实环境验收：

- Android 11（API 30，WebView 83）模拟器，干净安装最终 `1.7`（versionCode 8），真实短链 `https://v.douyin.com/lz694_pfyK4/` 连续 4 轮成功；其中 3 轮从应用内输入开始，第 4 轮从系统分享面板直达。
- 每轮提取作品 `7710508486577620859` 的 2 张原图（1176×835、2048×1536），自动生成 2 页 A4 PDF（446,671 字节，四轮 SHA256 一致）。
- 图片同时导出到系统相册 `Pictures/DouyinExtractor`，应用私有目录保留 `001.jpg`、`002.jpg`、`info.json`、`caption.txt`。
- 另有 DSH 独立 Node 22 fetch 复现同一公开 HTTP 链路，3/3 轮成功，2 张图尺寸一致，2 页 PDF 215,058 字节；未使用浏览器、mock 或第三方解析站。

Android CI 位于 `.github/workflows/android.yml`，推送到 `main` 后依次执行 `testDebugUnitTest`、`lintDebug` 和 `assembleDebug`，并将 APK 上传到预发布版本 `android-debug`。纯文档提交不会触发重复构建。

## 已知限制

- 抖音页面结构、风控策略和访问权限可能随时变化；公开分享页是否下发 `videoInfoRes` 取决于匿名 Cookie、UA 与页面版本，不保证对所有链接长期成功。
- 数据中心/机房 IP 下，分享页可能只返回 SSR 空壳或风控页面；应用会在日志中区分网络请求失败、安全验证空壳与页面无数据。
- 旧版系统 WebView（如 Android 9 自带 WebView 66）可能无法执行新版分享页脚本；HTTP 主链路不依赖 WebView，WebView 仅在 HTTP 失败时兜底。
- 本工具不会绕过登录、付费、验证码、滑块或其他访问控制。
- 不要在公开仓库、截图或日志中泄露自己的 Cookie、Token 或签名材料。

## 仓库约定

- 不提交 APK、AAB、签名文件、本地 SDK 路径、Cookie 或下载结果。
- 构建产物和本地 IDE 配置由 `.gitignore` 排除。
- 版本变化记录在 [`CHANGELOG.md`](CHANGELOG.md)。

## 贡献与安全

- 提交代码前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)，里面写了环境、测试和提交信息的约定。
- 安全问题请按 [SECURITY.md](SECURITY.md) 走私密报告，不要开公开 Issue。

## 许可证

本项目使用 [MIT License](LICENSE)。

## 免责声明

本工具不绕过登录权限、付费限制或访问控制，不保证适用于所有作品。使用者应自行确认其使用行为符合平台条款、作者授权和当地法律。
