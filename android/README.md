# Android 手机版

本目录是桌面版工具的原生 Android 工程，包名 `com.douyin.extractor`，最低支持 Android 7.0（API 24）。当前版本 `1.7`（versionCode 8）。

## 功能

- 粘贴抖音短链、完整链接或 App 分享口令，支持多行批量处理
- 支持从系统分享面板把抖音分享内容直接发送到本 App
- 识别图文笔记与视频作品
- 可选 Cookie 输入，仅用于本人正常访问，不绕过登录、付费或访问控制
- 图文原图下载到应用外部文件目录，并自动导出到系统相册 `Pictures/DouyinExtractor`（视频为 `Movies/DouyinExtractor`）
- 图文作品优先走公开 HTTP：先通过 `ttwid.bytedance.com` 访客接口或 `share/slides/<id>` 获取匿名 `ttwid` Cookie，再携带 Cookie 请求 `share/video/<id>`，读取 `_ROUTER_DATA.loaderData['video_(id)/page'].videoInfoRes.item_list[0].images`，按原顺序下载全部原图
- 图片候选优先 `.jpeg/.jpg`，逐候选重试；小于 1024 字节的响应视为可能被风控拦截，不当作成功
- HTTP 未拿到图片时，隐藏 WebView 加载 `share/video` / `share/note` 移动分享页兜底，读取路由数据或页面图片请求；仍失败则如实报错
- 不点击、不破解验证码、滑块或登录弹窗；命中验证环境时记录并停止该路径
- debug 构建开启 WebView DevTools 远程调试，release 不开启
- 图文作品图片全部下载成功后，自动按原始顺序合成独立 PDF（每个作品一个文件），保存到外部文件目录 Documents 下的 `douyin_pdfs/`；日志与结束弹窗显示 PDF 数量和路径
- 0 张图、部分图片下载失败、任务停止或提取失败时不生成 PDF；单个作品或单个 PDF 生成失败不阻断其余作品
- 视频作品可选下载 MP4；取消勾选时仅记录解析结果
- 后台线程执行，界面显示日志、状态和进度
- 提取失败时区分网络请求失败、安全验证/反爬空壳、页面未包含作品数据三类并在日志说明
- 从系统图片选择器选择多张本地图片，手动合并为 A4 PDF
- 透明图片自动铺白底，EXIF 方向自动校正

## 使用

1. 在抖音里打开作品，点“分享” → “复制链接”，把整段分享口令粘贴到链接框；也可以直接把分享内容发送到本 App。
2. 选择是否下载视频 MP4，点击“开始提取”。
3. 原图保存到应用专属目录，并自动复制到系统相册；图片全部下载成功后会生成一个独立 PDF。
4. 如果提示“无法获取作品数据”，先确认网络能正常打开该作品，再展开“Cookie（可选）”粘贴本人浏览器 Cookie 重试。App 会先自动申请访客 Cookie，失败时才需要手动填写。

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

仓库根目录的 `.github/workflows/android.yml` 会在推送到 `main` 或提交 PR 时依次执行 `testDebugUnitTest`、`lintDebug`、`assembleDebug`，并把 APK 作为 Actions 构件上传。推送到 `main` 后，CI 还会把 APK 上传到预发布版本 `android-debug`：

https://github.com/adigailmichael19-art/douyin-image-extractor/releases/tag/android-debug

该 APK 使用 Android debug 证书签名，仅供个人学习和真机测试；安装前请在手机上允许来自本仓库来源的安装包。

## 说明

抖音页面结构、风控策略和访问权限可能随时变化。公开分享页是否下发 `videoInfoRes` 取决于匿名 Cookie、UA 与页面版本；数据中心/机房 IP 下可能只返回 SSR 空壳或风控页面。旧版系统 WebView 可能无法执行新版分享页脚本，但 HTTP 主链路不依赖 WebView。应用不绕过任何验证，不保证对所有链接成功。本工具仅供个人学习与技术研究，下载内容应尊重原创版权与平台条款。
