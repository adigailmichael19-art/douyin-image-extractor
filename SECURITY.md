# 安全政策

## 怎么报告

请通过 GitHub 私密漏洞报告提交：

[Security → Report a vulnerability](https://github.com/adigailmichael19-art/douyin-image-extractor/security/advisories/new)

不要在公开 Issue、讨论区或截图里披露可利用细节、真实 Cookie 或他人账号信息。

## 覆盖范围

- 桌面版 `src/douyin_gui.py`：链接解析、下载、文件名处理、PDF 生成
- Android 版 `android/app`：提取链路、文件导出、WebView 兜底
- CI 配置 `.github/workflows/android.yml` 与发布流程

## 不在范围

- 抖音平台自身的安全问题（请报告给平台）
- 需要账号 Cookie 才能复现、且属于平台正常风控行为的问题
- 绕过访问控制、破解验证码一类的需求，本仓库不会实现

## 处理方式

这是个人维护项目，会尽量在 7 天内回复。确认并修复后，会在对应版本的变更记录里说明，并按你的意愿致谢。
