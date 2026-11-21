# Android 手机版

本目录是桌面版工具的原生 Android 工程，包名 `com.douyin.extractor`，最低支持 Android 7.0（API 24）。当前版本 `1.5`（versionCode 5）。

## 功能

- 粘贴抖音短链、完整链接或 App 分享口令，支持多行批量处理
- 从系统分享面板直接把抖音分享内容发到本 App
- 识别图文笔记与视频作品
- 自动申请访客 Cookie（ttwid），解决分享页只返回空壳的问题
- 可选 Cookie 输入，仅用于本人正常访问，不绕过登录、付费或访问控制
- 图文原图下载，并自动导出到系统相册 `Pictures/DouyinExtractor`
- 视频作品可选下载 MP4，导出到 `Movies/DouyinExtractor`
- 后台线程执行，界面显示日志、状态和进度
- 从系统图片选择器选择多张本地图片，合并为 A4 PDF
- 透明图片自动铺白底，EXIF 方向自动校正

## 提取流程

1. `ttwid.bytedance.com/ttwid/union/register/` 申请访客 Cookie，存入内存 Cookie 罐。
2. 请求短链，跟随 302 到 `iesdouyin.com/share/note/<id>/`，解析 `window._ROUTER_DATA`。
3. 从 `loaderData["note_(id)/page"].videoInfoRes.item_list[0]` 读取作者、文案和 `images[].url_list`。
4. 逐张图片按 JPEG 优先的候选顺序下载，失败换候选或换扩展名重试。

分享页没有 `ttwid` 时只返回约 3 KB 的页面配置（没有作品数据），这正是旧版本在手机上失败的根因。`Extractor.Config` 允许把上述地址指向本地测试服务，默认值即线上地址。

## 本地构建

需要 JDK 17、Android SDK 34、Build Tools 34.0.0、Gradle 8.7。

```bash
cd android
gradle :app:assembleDebug --no-daemon
```

产物位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

也可以用 Android Studio 打开 `android/` 目录后直接运行。

## 单元测试

```bash
cd android
gradle :app:testDebugUnitTest --no-daemon
```

`app/src/test/java/com/douyin/extractor/ExtractorTest.java` 用 JDK 自带的 socket 写了一个极简 HTTP 服务，模拟抖音的三类响应：必须带 ttwid 才给数据的分享页、只有页面配置的空壳、`filter_list` 删除提示。测试覆盖完整提取并落盘 2 张图片、缺 Cookie 报错、删除提示、无链接报错和文件名清洗，全程不访问真实抖音。

## CI

仓库根目录的 `.github/workflows/android.yml` 会在推送到 `main` 或提交 PR 时依次执行 `testDebugUnitTest`、`lintDebug` 和 `assembleDebug`，把 APK 作为 Actions 构件上传，并在推送到 `main` 后把 APK 上传到预发布版本 `android-debug`：

https://github.com/adigailmichael19-art/douyin-image-extractor/releases/tag/android-debug

该 APK 使用 Android debug 证书签名，仅供个人学习和真机测试；安装前请在手机上允许来自本仓库来源的安装包。

## 说明

抖音页面结构、风控策略和访问权限可能随时变化。如果返回验证码、滑块或空壳页面，纯网络请求不能保证解析成功；可在 App 内填写本人浏览器 Cookie 后重试。图片直链带签名有效期，过期后需要重新解析作品。本工具仅供个人学习与技术研究，下载内容应尊重原创版权与平台条款。
