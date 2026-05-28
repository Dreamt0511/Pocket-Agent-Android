# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## ⚠️ 最高优先级规则（必须遵守）

### 1. Android 仓库推送规则（强制执行）
**Android 仓库（Pocket-Agent-Android）推送前必须得到用户明确同意。**

- 本地 commit 可以自行完成
- **但推送远程必须等用户说"推""push""推送"等确认词，且必须先问"准备推送 xxx，确认吗？"，用户说"确认"才执行**
- 用户没说之前，commit 后告知用户已就绪等待其指令即可
- **违反此规则已发生十几次，必须严格执行**

**Why:** 本地没有 Android SDK，无法编译验证。之前私自推送导致 CI 编译报错，浪费 CI 资源。

### 2. 不要动 Termux 目录
只能编辑以下两个目录的文件：
- `/storage/emulated/0/手机agent开发/Pocket-Agent/`（主库）
- `/mnt/sdcard/本地项目开发/Pocket-Agent-Android/`（Android 仓库）

除此之外的所有目录（包括 Termux 目录 `/data/data/com.termux/files/home/Pocket-Agent/`）绝对不要碰。

### 3. 不要修改 bootstrap 逻辑
bootstrap() 的原始逻辑是正确的，不要改动：
1. healthCheck 成功 → 直接连接（不重启）
2. healthCheck 失败 → launchFastAPI 启动新服务

**Why:** 两次修改 bootstrap 都导致服务连接失败。

### 4. 排查问题时两库一起查
排查连接/功能问题时，必须同时考虑两个仓库：
- Pocket-Agent-Android（App 端）
- Pocket-Agent（Python 后端）

**Why:** 曾因只查 Android 代码改来改去，实际问题在 Python 后端。

### 5. pgrep -f 自匹配问题
Termux 启动脚本中 `pgrep -f` 会匹配执行脚本的 `bash -c` 进程自身，必须排除 $$：
`[ "$pid" != "$$" ] && kill $pid`

### 6. 子 Agent 模型选择
| 主模型 | 子 Agent 模型 |
|---|---|
| deepseek 系列 | `model: "haiku"` |
| 小米 mimo-v2.5-pro | `model: "mimo-v2.5-pro"` |

### 7. mimo 模型下不用 Explore Agent
Explore subagent_type 在 mimo-v2.5-pro 模型下会报 API 400 错误，改用 general-purpose agent 或直接读文件。

### 8. 推送后不检查 CI
推送代码后不需要等待或检查 CI 结果，用户自己会看。

## 项目概述

Pocket Agent Android — 将 Pocket-Agent Python AI Agent 能力搬到 Android 手机上的原生客户端。Android 前端通过 Termux Intent 启动 Python 后端（FastAPI + uvicorn），再通过 HTTP/SSE 流式通信执行 AI Agent 任务。

## 构建命令

```bash
# Debug APK
./gradlew assembleDebug

# Release APK（需要 keystore）
./gradlew assembleRelease

# 产物路径
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release.apk
```

环境要求：JDK 17、Android SDK API 34、Build Tools 32.0.0。项目自带 Gradle Wrapper（8.5），无需手动安装。

## 架构

### 通信链路

```
Android App (Kotlin/Compose)
  ↓ Termux Intent (RUN_COMMAND)
Termux Shell (bash)
  ↓ git clone + pip install (首次) → uvicorn app:app --port 8000
FastAPI 后端 (Python)
  ↓ HTTP/SSE
TermuxServiceClient (OkHttp)
```

- `TermuxLauncher` — 通过 Intent 向 Termux 发送 bash 脚本，首次启动会 clone 仓库 + pip install，之后直接启动 uvicorn
- `TermuxServiceClient` — OkHttp 客户端，与 localhost:8000 的 FastAPI 通信，核心接口：`healthCheck()`、`chatStream()`（SSE）、`syncConfig()`、`fetchSkills()`
- `AgentDaemon` — 协调启动流程和任务执行，管理 DaemonStatus 状态机（Idle → Initializing → Ready → Executing）
- `AppBootstrapper` — 全局单例启动器，初始化顺序：OverlayManager → AgentDaemon → SkillManager → bootstrap()

### 数据流（任务执行）

1. 用户在 ChatScreen 输入指令
2. `AppBootstrapper.executeCommand()` → `AgentDaemon.execute()`
3. `TermuxServiceClient.chatStream()` 发起 SSE 请求，返回 `Flow<String>`
4. 流式数据通过 `StreamBridge` 同步到：悬浮窗（OverlayService）、终端（TerminalScreen）
5. `[TOOL]` 前缀的事件仅更新状态栏，不写入聊天内容
6. 消息持久化到 Room 数据库（ChatRepository）

### UI 层（Jetpack Compose）

- 导航：`NavGraph.kt` 定义 `Screen` sealed class 路由
- 主要页面：HomeScreen、ChatScreen、ConfigScreen、TerminalScreen、OverlayScreen、HistoryScreen、SkillsScreen
- 主题：`ui/theme/` 下的 GlassComponents 提供毛玻璃风格组件
- AI 消息使用 `compose-markdown` 库渲染 Markdown

### 数据层

- `SettingsRepository` — DataStore Preferences 存储 LLM 配置（base_url、api_key、model、temperature、max_tokens）和 MCP 服务地址
- `AppDatabase` (Room) — 持久化会话和消息，实体：`Conversation`、`Message`
- `ChatRepository` — 会话和消息的 CRUD 操作

### 悬浮窗系统

- `OverlayService` — 前台服务，维护全局 StateFlow（streamText、taskStatus、isAgentOperating）
- `StreamBridge` — 观察者模式桥接器，所有输出自动分发到已注册的 StreamTarget
- 双模式：MiniOverlayView（悬浮球）↔ ExpandedOverlayView（终端卡片）

### Python Agent 种子代码（agent-seed/）

- `stable_entry.py` — 不可变接口，Android 与 Python Agent 之间的协议层
- 支持模式：`--mode=task`（stdin 读任务 JSON → stdout 输出结果）、`--mode=ready`（报告状态）
- 输出格式：每行一个 JSON，type 为 step/result/config_result

### CI（GitHub Actions）

- 触发：push to main 或 workflow_dispatch
- 构建流程：下载 Termux Python 3.13 deb 包 → 解压到 assets/python/ → patchelf 修改 RPATH → Gradle assembleRelease
- Python 运行时内置到 APK 的 assets 目录，无需设备额外安装

## 关键配置

- `local.properties` — SDK 路径（不提交 Git）
- `gradle.properties` — JVM 内存 `-Xmx2048m`
- 签名配置在 `app/build.gradle.kts` 的 `signingConfigs`，密码可通过环境变量覆盖
- ABI 过滤：仅 `arm64-v8a`
