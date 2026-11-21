# Android 手机版

本目录是桌面版工具的原生 Android 工程，包名 `com.douyin.extractor`，最低支持 Android 7.0（API 24）。当前版本 `1.4-ui`（versionCode 4）。

## 功能

- 粘贴抖音短链、完整链接或 App 分享口令，支持多行批量处理
- 识别图文笔记与视频作品
- 可选 Cookie 输入，仅用于本人正常访问，不绕过登录、付费或访问控制
- 图文原图下载到应用外部文件目录
- 视频作品可选下载 MP4；取消勾选时仅记录解析结果
- 后台线程执行，界面显示日志、状态和进度
- 从系统图片选择器选择多张本地图片，合并为 A4 PDF
- 透明图片自动铺白底，EXIF 方向自动校正

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

## CI

仓库根目录的 `.github/workflows/android.yml` 会在推送到 `main` 或提交 PR 时构建 debug APK，并把 APK 作为 Actions 构件上传。推送到 `main` 后，CI 还会把 APK 上传到预发布版本 `android-debug`：

https://github.com/adigailmichael19-art/douyin-image-extractor/releases/tag/android-debug

该 APK 使用 Android debug 证书签名，仅供个人学习和真机测试；安装前请在手机上允许来自本仓库来源的安装包。

## 说明

抖音页面结构、风控策略和访问权限可能随时变化。如果返回验证码、滑块或空壳页面，纯网络请求不能保证解析成功。本工具仅供个人学习与技术研究，下载内容应尊重原创版权与平台条款。
