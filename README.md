# 抖音图文提取器

一个面向个人学习与技术研究的抖音内容保存工具，包含 **Windows 桌面版** 和 **原生 Android 版**。支持批量解析公开作品页面，保存图文原图、作品文案、作者信息和可选 MP4 视频，也能把本地多张图片合并为 A4 PDF。

[下载 Android APK](https://github.com/adigailmichael19-art/douyin-image-extractor/releases/download/android-debug/app-debug.apk) · [更新记录](CHANGELOG.md) · [Android 构建说明](android/README.md)

> 仅供个人学习与技术研究。请遵守平台规则与适用法律，尊重原创作者版权，不要将下载内容用于未经授权的传播或商业用途。

## 下载与版本

| 平台 | 当前版本 | 获取方式 |
| --- | --- | --- |
| Android | `1.4-ui`（versionCode 4） | [下载 debug APK](https://github.com/adigailmichael19-art/douyin-image-extractor/releases/download/android-debug/app-debug.apk) |
| Windows | 源码运行 | 按下方步骤安装依赖并启动 |

Android APK 使用 Android debug 证书签名，仅供个人学习和真机测试。安装前需要在手机上允许安装来自该来源的应用。

## 项目结构

```text
.
├── .github/workflows/android.yml   # Android CI：lint、构建和发布
├── android/                        # 原生 Android 工程
│   ├── app/                        # Java 源码、布局和资源配置
│   ├── build.gradle
│   └── README.md
├── douyin_gui.py                   # Windows 桌面版主程序
├── test_douyin_gui.py              # 桌面版自动化测试
├── launch.vbs                      # Windows 静默启动器
├── make_github_icon.py             # 图标生成脚本
├── clean_icon.py                   # 旧图标处理脚本（可选）
├── requirements.txt                # 桌面版 Python 依赖
├── CHANGELOG.md                    # 版本变更记录
└── README.md
```

## 功能

- 支持抖音短链、完整作品链接和 App 分享口令
- 多链接批量处理，每行一个
- 自动识别图文（note）与视频（video）作品
- 图文原图批量下载，并保存作者、文案和来源链接
- 视频下载开关；取消勾选时仅解析并记录地址
- 可选 Cookie 输入，仅用于本人正常访问
- 实时日志、进度显示和停止任务
- 下载使用 `.part` 临时文件，失败时不会留下不完整文件
- 多张 JPG、PNG、WebP、BMP、TIFF 图片按选择顺序合并为 A4 PDF
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
python douyin_gui.py
```

也可以双击 `launch.vbs` 静默启动。启动器中的程序目录和 Python 路径需要按自己的电脑修改。

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
python test_douyin_gui.py
```

测试覆盖链接解析、内嵌 JSON 解析、作品对象定位、反爬识别、模拟下载、多页 PDF、透明图、EXIF 方向和 GUI 冒烟流程；抖音相关测试使用模拟会话，不会访问真实服务。

Android CI 位于 `.github/workflows/android.yml`，推送到 `main` 后执行 `lintDebug` 和 `assembleDebug`，并将 APK 上传到预发布版本 `android-debug`。纯文档提交不会触发重复构建。

## 已知限制

- 抖音页面结构、风控策略和访问权限可能随时变化。遇到验证码、滑块或反爬空壳时，纯网络请求不保证解析成功。
- Android 版已在 Android 9 模拟器完成安装、空输入校验、停止任务、日志清空、多图合并 PDF 和横竖屏测试；真实抖音页面仍可能返回安全验证空壳。
- 本工具不会绕过登录、付费、验证码、滑块或其他访问控制。
- 不要在公开仓库、截图或日志中泄露自己的 Cookie、Token 或签名材料。

## 仓库约定

- 不提交 APK、AAB、签名文件、本地 SDK 路径、Cookie 或下载结果。
- 构建产物和本地 IDE 配置由 `.gitignore` 排除。
- 版本变化记录在 [`CHANGELOG.md`](CHANGELOG.md)。

## 免责声明

本工具不绕过登录权限、付费限制或访问控制，不保证适用于所有作品。使用者应自行确认其使用行为符合平台条款、作者授权和当地法律。