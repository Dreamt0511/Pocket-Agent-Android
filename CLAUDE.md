# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
