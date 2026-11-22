# 贡献指南

这是一个个人学习向的项目，欢迎提 Issue 和 PR。为了让改动可复现、可回滚，提交前请先看下面的约定。

## 开发环境

- Windows 桌面版：Python 3.12+（Tkinter 随 Python 自带）
- Android 版：JDK 17、Android SDK 34、Build Tools 34.0.0、Gradle 8.7

```powershell
python -m pip install -r requirements.txt
python src/douyin_gui.py          # 启动桌面版
python tests/test_douyin_gui.py   # 运行桌面版测试
```

```bash
cd android
gradle :app:testDebugUnitTest --no-daemon
gradle :app:assembleDebug --no-daemon
```

## 提交前检查

1. 桌面版测试全部通过：`python tests/test_douyin_gui.py`
2. Android 单元测试通过：`gradle :app:testDebugUnitTest --no-daemon`
3. 不新增访问真实抖音服务的测试，一律使用本地假数据或 Fake Session
4. 改动涉及链接解析、下载、PDF、文件名清洗时，补对应用例
5. 在 `CHANGELOG.md` 里补一条变更记录

## 代码约定

- Python 用 4 空格缩进，函数与变量用 snake_case，异常统一抛 `ExtractError`，消息要能直接展示给用户
- Java 遵循 Android 默认风格，界面文案放 `strings.xml`
- 注释与界面文案用中文；日志里不打印 Cookie、Token 或完整签名参数

## 不要提交

- APK、AAB、签名文件（`.keystore` / `.jks`）
- 本机路径、`local.properties`、IDE 配置
- Cookie、Token、设备信息、真实下载结果
- 任何绕过登录、付费、验证码或访问控制的代码

## PR 流程

1. Fork 后从 `main` 切分支，分支名用 `feat/xxx`、`fix/xxx`
2. 一个 PR 只做一件事，说明里写清「改了什么、为什么、怎么验证」
3. 提交信息推荐 Conventional Commits：`feat: ...`、`fix: ...`、`docs: ...`
4. 等 Android CI 通过后再合并

## 安全问题

请勿在公开 Issue 中披露安全问题，按 [SECURITY.md](SECURITY.md) 的渠道报告。
