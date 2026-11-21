# 更新记录

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