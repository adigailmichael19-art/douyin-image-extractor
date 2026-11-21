# 更新记录

## 1.7 - 2026-11-21

### 变更

- 合并 1.5 的手机端修复：访客 `ttwid` Cookie 引导、Cookie 罐、作品 ID 正则修复、新分享页结构、JPEG 候选优先、相册导出与系统分享入口。
- 图文（note）作品改为公开 HTTP 数据优先：优先向 `https://ttwid.bytedance.com/ttwid/union/register/` 申请匿名 `ttwid`；失败时再请求 `https://www.iesdouyin.com/share/slides/<id>/` 接收匿名 Cookie；同一会话内所有请求自动携带。
- 数据页：携带 Cookie 请求移动分享页 `https://www.iesdouyin.com/share/video/<id>/`，读取 `window._ROUTER_DATA.loaderData['video_(id)/page'].videoInfoRes.item_list[0]`，取 `desc`、`author.nickname` 与 `images[].url_list`；该公开页对图文作品同样返回完整 `images` 数组。
- 图片候选：`url_list` 中优先 `.jpeg/.jpg`，逐候选重试并自动切换扩展名；小于 1024 字节的响应视为可能被风控拦截，不会当作成功。
- 保留隐藏 WebView 兜底：HTTP 未拿到图片时加载 `share/video` / `share/note` 移动分享页，读取页面路由数据或已捕获的 `douyinpic` 图片请求；仍失败则如实报错。
- 图文图片全部下载成功后，自动按原始顺序合成独立 PDF，每个作品一个文件；保存到应用外部文件目录 `Documents/douyin_pdfs/`，日志与结束弹窗显示路径。
- 原图自动导出到系统相册 `Pictures/DouyinExtractor`（视频为 `Movies/DouyinExtractor`）；Android 10+ 使用 MediaStore，Android 7-9 使用公共 Pictures 目录并申请 `WRITE_EXTERNAL_STORAGE`。
- 支持从系统分享面板把抖音分享内容直接发送到本 App（`ACTION_SEND` + `singleTop`）。
- 成功对话框新增“查看图片”，直接打开刚导出的第一张图。
- 保留手动“图片转 PDF”入口。
- debug 构建开启 WebView DevTools 远程调试，release 不开启。
- 版本号更新为 `1.7`（versionCode 8）。

### 修复与加固

- 修复旧版 WebView 执行分享页脚本失败、DOM 长期为空、只能捕获首张图的问题；HTTP 主链路不再依赖 WebView 渲染。
- 修复 `resolveAweme` 对同一个 `Matcher` 连续调用 `find()` 导致含 `/note/<id>` 的最终地址解析失败；改为每次新建 matcher。
- 修复 `_ROUTER_DATA` 正则字符类在部分 Android ICU 实现上抛 `PatternSyntaxException`；改为不含反斜杠的字符类写法。
- 图文图片下载完成后自动 PDF 仅在 `ok == total` 时生成；0 张图、部分失败、停止或提取失败均不生成。
- 不点击、不破解验证码、滑块或登录弹窗，不绕过任何访问控制；命中验证环境时记录并回退/停止。
- release 不开启 WebView 调试，不信任用户安装的 CA；debug 网络配置仅用于模拟器测试环境。

### 验证

- `gradle :app:testDebugUnitTest`：5 个离线用例全部通过（本地 HTTP 服务模拟 Cookie 门槛、空壳页面、`filter_list` 删除提示、无链接、文件名清洗）。
- `gradle :app:lintDebug :app:assembleDebug` 通过。
- Android 11（API 30，WebView 83）模拟器，干净安装最终 `1.7`（versionCode 8），真实短链 `https://v.douyin.com/lz694_pfyK4/` 连续 4 轮成功；其中 3 轮从应用内输入开始，第 4 轮从系统分享面板直达。
- 每轮提取作品 `7710508486577620859` 的 2 张原图（1176×835、2048×1536），自动生成 2 页 A4 PDF（446,671 字节，四轮 SHA256 一致）；`pdfimages` 显示 2 张图片，页面为对应题目与手写解答。
- 原图同时导出到系统相册 `Pictures/DouyinExtractor`，应用私有目录保留 `001.jpg`、`002.jpg`、`info.json`、`caption.txt`。
- DSH 独立 Node 22 fetch 复现同一公开 HTTP 链路，3/3 轮成功，2 张图尺寸一致，2 页 PDF 215,058 字节；未使用浏览器、mock 或第三方解析站。

## 1.4-ui - 2026-11-21

### 修复

- 修复 Android 9 上点击“开始提取”时由 `_ROUTER_DATA` 正则转义不兼容导致的 `PatternSyntaxException` 崩溃。
- 提取器初始化失败时改为显示错误并结束任务，不再让异常直接杀死应用。
- 增加取消状态处理：停止后显示“已停止”，不再误报为全部失败。

### 验证

- Android 9 模拟器完成安装启动、空输入提示、任务运行与停止、日志清空、多图合并 A4 PDF、横竖屏切换测试。
- Android lint：`0 errors, 1 warning`；唯一警告为 `PdfExporter` 使用系统 `ExifInterface`，不影响运行。
- GitHub Actions 构建成功，Release APK 可下载并通过版本、签名和干净安装核验。

## 1.3-ui - 2026-11-15

### 变更

- Android 界面改为浅色青绿工作台风格。
- 重构作品链接、访问与下载、图片转 PDF、任务进度和运行日志的层级。
- 增加任务状态胶囊、主操作按钮和停止任务状态。
- 发布 `1.3-ui` debug APK。

> 更早的 Android 构建没有单独记录版本变更。