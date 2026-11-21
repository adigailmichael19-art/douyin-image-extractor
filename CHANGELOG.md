# 更新记录

## 1.5 - 2026-11-21

### 修复（手机端提取失败的三个根因）

- **分享页缺少访客 Cookie**：抖音分享页只有在请求携带 `ttwid` 时才返回内嵌作品数据，旧版裸 `HttpURLConnection` 不保存 Cookie，只能拿到约 3 KB 的空壳页面，这是手机上一直“未找到作品数据”的根本原因。现在每次任务先向 `ttwid.bytedance.com/ttwid/union/register/` 申请访客 Cookie，并内置 Cookie 罐，所有请求复用同一份 Cookie；直接 GET 抖音首页拿 `ttwid` 在 Java 侧会被重定向到 JS 反爬页，因此不再依赖该路径。
- **作品 ID 正则二次 `find()`**：`resolveAweme` 里连续调用两次 `Matcher.find()`，第二次会从上次匹配位置继续查找，导致最终地址含 `/note/<id>` 时反而解析失败。改为每次新建 `Matcher`，并抽出 `matchAwemeId()`。
- **`_ROUTER_DATA` 正则转义**：字符类中的 `\{` / `\}` 在部分 Android ICU 正则实现上会抛 `PatternSyntaxException`，改为不含反斜杠的写法。

### 新增

- 适配 2026 分享页的新结构：`loaderData["note_(id)/page"].videoInfoRes.item_list[0]`，并保留递归兜底；`filter_list.detail_msg` 会给出“作品不可访问: 作品已删除”这类明确原因。
- 图片候选按 JPEG 优先排序，逐候选重试并自动切换扩展名；下载小于 1024 字节视为风控拦截，拦截后重新引导 Cookie 再试，最多 3 轮。
- 下载完成后自动导出到系统相册：Android 10+ 走 `MediaStore` + `RELATIVE_PATH=Pictures/DouyinExtractor`，Android 7-9 走公共 Pictures 目录 + `MediaScanner`，并在 Android 7-9 上申请 `WRITE_EXTERNAL_STORAGE`。
- 支持从系统分享面板把抖音分享内容直接发到本 App（`ACTION_SEND` + `singleTop`）。
- 成功对话框新增“查看图片”，直接调用系统看图应用打开刚导出的第一张图。
- 新增 5 个离线单元测试（本地 HTTP 服务模拟 Cookie 门槛、空壳页面和风控），CI 增加 `testDebugUnitTest` 步骤。

### 验证

- JVM 全链路（真实抖音短链 `https://v.douyin.com/lz694_pfyK4/`）：访客 Cookie 就绪 → 分享页解析成功 → 图文 2 张 → 图片完成 2/2，落盘 94 KB / 117 KB 的 JPG，另含 `caption.txt`、`info.json`。
- `gradle :app:testDebugUnitTest`：5 个用例全部通过。
- `gradle :app:lintDebug` 与 `:app:assembleDebug` 通过，APK 版本 1.5（versionCode 5）。
- 未在真机上复测：本次构建环境没有可连接的安卓设备，真机安装后如遇风控仍可在 App 内填写本人 Cookie 重试。

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
