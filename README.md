# Pocket Agent Android App

> 将 Pocket-Agent Python AI Agent 能力搬到 Android 手机上的原生客户端。

## 核心功能

- **AI 对话**：连接 Termux 中运行的 Python 后端（FastAPI），通过 SSE 流式输出 AI 回复
- **全局悬浮窗**：系统级悬浮球 + 终端卡片，退出 App 后仍可监控 Agent 执行。悬浮窗基于独立窗口层级，不会被 NeuralBridge 的 `android_get_ui_tree` 获取到，因此不会干扰子 Agent 的 UI 自动化操作——用户可以实时监控执行过程，同时 Agent 能"看不见"悬浮窗地正常操控屏幕
- **任务队列**：后台服务保活，单任务串行执行，支持排队和取消
- **语音输入**：内置语音识别
- **MCP 联动**：通过独立的 [NeuralBridge MCP](https://github.com/dondetir/NeuralBridge_mcp) 服务操控手机（无障碍/触控），本项目不内置无障碍实现
- **代码同步**：通知 Termux 执行 `git pull`，自动拉取最新 Python Agent 代码
- **配置管理**：支持 LLM 模型配置（base_url / api_key / model / temperature / max_tokens），云端 API + 本地 llama-server
- **技能管理**：扫描、查看、创建、编辑、删除 Agent 技能，支持导出和批量操作
- **版本管理**：获取当前代码版本（git commit SHA），支持版本历史查看和回退
- **会话持久化**：通过 Python 后端 SQLite 存储历史会话和消息

## 架构概览

```
┌─────────────────────────────────────────────┐
│           Android App (Kotlin/Compose)       │
│                                              │
│  HomeScreen → ChatScreen → TerminalScreen    │
│  ConfigScreen / HistoryScreen / SkillsScreen │
│                  │                           │
│          AppBootstrapper (启动协调)           │
│           ┌──────┴──────┐                    │
│     AgentDaemon    OverlayManager            │
│           │             │                    │
│  TermuxServiceClient  StreamBridge           │
│     (OkHttp/SSE)    (输出分发)                │
└──────────┬──────────────┬────────────────────┘
           │ HTTP/SSE     │ → 悬浮窗 / 终端
           ▼
┌──────────────────────────────────────┐
│     Termux (localhost:8000)          │
│                                      │
│  uvicorn app:app → FastAPI 后端       │
│  Python Agent 引擎 (Pocket-Agent/)   │
│  ├─ LangChain/LangGraph Agent        │
│  ├─ 分层记忆系统（向量+全文检索）      │
│  ├─ 技能系统（SKILL.md 自动发现）      │
│  └─ MCP 集成（NeuralBridge）          │
└──────────────────────────────────────┘
```

### 通信链路

1. `TermuxLauncher` 通过 Intent 向 Termux 发送 bash 脚本
2. 首次启动：`git clone` + `pip install`（创建 `~/.pocket-agent-ready` 标记文件）
3. 后续启动：直接运行 `uvicorn app:app --host 0.0.0.0 --port 8000`
4. `TermuxServiceClient`（OkHttp）与 FastAPI 通信：health check、SSE 流式对话、配置同步、代码同步、技能 CRUD、会话管理、版本管理
5. `StreamBridge` 将输出同步分发到悬浮窗和终端页面

### 数据层

- **SettingsRepository**：DataStore Preferences 存储用户配置
- **Python 后端 SQLite**：会话和消息持久化（通过 HTTP API 访问）

### 核心组件

| 组件 | 职责 |
|------|------|
| `AppBootstrapper` | 全局单例启动器，协调所有子系统初始化 |
| `AgentDaemon` | Agent 守护进程，状态机（Idle → Initializing → Ready → Executing）+ 任务执行 |
| `TermuxLauncher` | 通过 Intent 向 Termux 发送 bash 脚本（启动/停止 FastAPI） |
| `TermuxServiceClient` | OkHttp 客户端，与 localhost:8000 的 FastAPI 通信 |
| `StreamBridge` | 观察者模式桥接器，所有输出自动分发到已注册的 StreamTarget |
| `OverlayService` | 前台服务，维护全局 StateFlow（streamText、taskStatus、isAgentOperating） |
| `SkillManager` | 技能扫描、CRUD、导入导出 |
| `NeuralBridgeHelper` | 检测 NeuralBridge 安装状态和无障碍服务是否开启 |
| `TaskQueueManager` | 任务队列管理，单任务串行执行 |

## 项目结构

```
PocketAgent/
├── app/src/main/java/com/pocketagent/app/
│   ├── MainActivity.kt              # 入口 Activity
│   ├── NavGraph.kt                  # Compose 导航（Screen sealed class）
│   ├── PocketAgentApp.kt            # Application 类
│   ├── core/
│   │   ├── AgentDaemon.kt           # Agent 守护进程，状态机 + 任务执行
│   │   ├── AppBootstrapper.kt       # 启动协调器（单例）
│   │   ├── TermuxLauncher.kt        # Termux Intent 发送 bash 脚本
│   │   ├── TermuxServiceClient.kt   # OkHttp 客户端（HTTP/SSE）
│   │   ├── ConfigManager.kt         # Agent 配置管理
│   │   ├── SkillManager.kt          # 技能扫描和 CRUD
│   │   ├── NeuralBridgeHelper.kt    # MCP 服务辅助（安装检测 + 无障碍状态）
│   │   └── ScriptStatusReceiver.kt  # Termux 脚本状态广播接收器
│   ├── data/
│   │   └── SettingsRepository.kt    # DataStore 配置存储
│   ├── overlay/
│   │   ├── OverlayService.kt        # 前台服务，维护全局 StateFlow
│   │   ├── OverlayManager.kt        # 悬浮窗管理
│   │   ├── StreamBridge.kt          # 输出分发桥接器
│   │   ├── MiniOverlayView.kt       # 悬浮球视图
│   │   ├── ExpandedOverlayView.kt   # 终端卡片视图
│   │   └── OverlayPermissionHelper.kt # 悬浮窗权限辅助
│   ├── service/
│   │   ├── AgentService.kt          # 前台服务（保活 + 心跳）
│   │   ├── TaskQueueManager.kt      # 任务队列（单任务串行）
│   │   └── SessionManager.kt        # 会话管理
│   ├── update/
│   │   ├── UpdateChecker.kt         # 更新检查
│   │   └── TaskResult.kt            # 任务结果类型
│   └── ui/
│       ├── chat/ChatScreen.kt       # 对话页面（Markdown 渲染）
│       ├── home/HomeScreen.kt       # 主页
│       ├── config/ConfigScreen.kt   # 配置页面
│       ├── terminal/TerminalScreen.kt # 终端输出页面
│       ├── overlay/OverlayScreen.kt # 悬浮窗设置
│       ├── screens/history/         # 历史会话
│       ├── screens/skills/          # 技能列表（查看/创建/编辑/删除/导出）
│       ├── screens/onboarding/      # 引导页
│       ├── components/              # 通用组件（TaskInputBar, VoiceInputButton 等）
│       └── theme/                   # 主题 + GlassComponents 毛玻璃风格
├── scripts/
│   └── bootstrap.sh                 # Termux 环境初始化脚本
└── .github/workflows/build.yml      # CI：下载 Termux Python → Gradle assembleRelease
```

## 页面路由

| 路由 | 页面 | 说明 |
|------|------|------|
| `home` | HomeScreen | 主页，显示功能入口和状态 |
| `execute/{conversationId}` | ChatScreen | 对话页面，发送指令和查看 AI 回复 |
| `history` | HistoryScreen | 历史会话列表 |
| `skills` | SkillsScreen | 技能管理（查看/创建/编辑/删除/导出） |
| `config` | ConfigScreen | LLM 配置和系统设置 |
| `overlay` | OverlayScreen | 悬浮窗设置 |

## 编译环境要求

| 组件 | 版本要求 | 说明 |
|------|----------|------|
| JDK | 17 | OpenJDK 或 Oracle JDK |
| Android SDK | API 34 | compileSdk = 34, minSdk = 26 |
| Build Tools | 32.0.0 | |
| Gradle | 8.5 | 项目自带 wrapper，无需手动安装 |
| Kotlin | 1.9.22 | AGP 自动管理 |
| AGP | 8.2.2 | Android Gradle Plugin |

## 快速编译（命令行）

### 1. 设置环境变量

```bash
# Windows PowerShell
$env:JAVA_HOME = "你的 JDK 17 路径"
$env:ANDROID_HOME = "你的 Android SDK 路径"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
```

### 2. 配置 SDK 路径

编辑 `local.properties`，填入你的 SDK 路径：
```
sdk.dir=C\:\\YourAndroidSdkPath
```

### 3. 编译

```bash
# Debug APK
./gradlew assembleDebug

# Release APK（需要 keystore）
./gradlew assembleRelease
```

### 4. 产物位置

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## 使用 Android Studio 编译（推荐）

1. 打开 Android Studio → File → Open → 选择项目根目录
2. 等待 Gradle 同步完成（首次需下载依赖，约 5-10 分钟）
3. Build → Build Bundle(s) / APK(s) → Build APK(s)
4. APK 生成在 `app/build/outputs/apk/debug/`

## CI 自动编译

GitHub Actions 在 push to main 时自动构建 Release APK：

1. 从 Termux 官方仓库下载 Python 3.13 deb 包 + libandroid-support
2. 解压到 `app/src/main/assets/python/`，用 patchelf 修改 RPATH
3. `./gradlew assembleRelease`
4. APK 上传为 Artifact

签名密钥通过 GitHub Secrets 配置（KEYSTORE_BASE64、KEYSTORE_PASSWORD、KEY_ALIAS、KEY_PASSWORD）。

## 安装后首次启动

1. 安装 [Termux](https://f-droid.org/packages/com.termux/)、Termux:API、Termux:Boot
2. 授权「显示在其他应用上层」权限（悬浮窗）
3. 授权「录音」权限（语音输入）
4. 进入「配置」页面，填写 LLM API 地址和密钥
5. 主页点击对话，Agent 自动在 Termux 中初始化环境（首次需等待 git clone + pip install）

## 权限说明

| 权限 | 用途 |
|------|------|
| `INTERNET` | 与 Termux FastAPI 通信 |
| `RECORD_AUDIO` | 语音输入 |
| `FOREGROUND_SERVICE` | 后台服务保活 |
| `WAKE_LOCK` | 防止设备休眠时服务中断 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗权限 |
| `ACCESS_NETWORK_STATE` | 检测网络状态 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ 前台服务类型声明 |
| `com.termux.permission.RUN_COMMAND` | 向 Termux 发送命令 |

## 主要依赖库

| 库 | 版本 | 用途 |
|----|------|------|
| Jetpack Compose | BOM 2024.02.00 | UI 框架 |
| Navigation Compose | 2.7.7 | 页面导航 |
| OkHttp | 4.12.0 | HTTP/SSE 通信 |
| Gson | 2.10.1 | JSON 解析 |
| compose-markdown | 0.7.2 | Markdown 渲染 |
| DataStore | 1.0.0 | 配置存储 |
| Coroutines | 1.7.3 | 异步编程 |

## Python 后端（Pocket-Agent）

Android App 通过 Termux 启动的 Python 后端是整个系统的核心引擎。

### 核心特性

- **LangChain Agent**：使用官方 `create_agent` 创建，支持中间件链（模型调用限制、上下文压缩）
- **分层记忆系统**：用户画像 + 事实记忆 + 事件记忆 + 程序记忆，四层架构
- **混合检索**：FTS5 全文搜索（trigram 分词，支持中文）+ BGE-M3 向量搜索，RRF 融合排序
- **技能系统**：自动发现 SKILL.md，支持主 Agent、子 Agent 和自动沉淀技能
- **MCP 集成**：通过 NeuralBridge 控制安卓设备（无障碍/触控）
- **持久化对话**：AsyncSqliteSaver 保存 LangGraph 状态，重启后恢复会话上下文

### 记忆系统

| 层级 | 存储 | 用途 |
|------|------|------|
| 用户画像 | `memory/user_profile.md` | 永久性个人信息（姓名、职业、偏好） |
| 事实记忆 | 向量索引（跨会话） | 项目决定、技术选型、工作上下文 |
| 事件记忆 | 消息表 + 向量索引 | 重要事件结果（部署成功、问题解决） |
| 程序记忆 | `auto-skills/` SKILL.md | 操作流程（自动沉淀的技能） |

### 内置工具

| 工具 | 功能 |
|------|------|
| `file_read` | 读取文件 |
| `file_write` | 写入文件 |
| `file_search` | 文件内容搜索 |
| `directory_list` | 列出目录 |
| `system_info` | 获取系统信息 |
| `shell_exec` | 执行 shell 命令 |
| `update_user_profile` | 更新用户画像 |
| `save_memory` | 保存记忆（fact/episodic） |
| `search_memory` | 检索记忆（向量+全文混合） |
| `mcp_call` | 调用 MCP 服务（NeuralBridge） |
| `delegate_task` | 委托子 Agent 任务 |
| `tts_speak` | 语音播报 |

### HTTP API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/health` | GET | 健康检查 |
| `/uptime` | GET | 服务运行时长 |
| `/heartbeat` | POST | 心跳（App 保活） |
| `/version` | GET | 当前代码版本（git SHA） |
| `/version/history` | GET | 版本历史（最多 5 个） |
| `/version/rollback` | POST | 回退到指定版本 |
| `/setup` | POST | 安装 Python 依赖 |
| `/chat` | POST | SSE 流式聊天 |
| `/cancel` | POST | 取消当前执行 |
| `/config` | GET/POST | 配置管理 |
| `/sync` | POST | 代码同步（git pull） |
| `/skills` | GET/POST | 技能列表/创建 |
| `/skills/{path}` | PUT/DELETE | 更新/删除技能 |
| `/conversations` | GET | 会话列表 |
| `/conversations/{id}` | DELETE | 删除会话 |
| `/conversations/{id}/messages` | GET | 会话消息 |
| `/messages/{id}` | DELETE | 删除消息 |
| `/memory/save` | POST | 保存记忆 |
| `/messages/search` | GET | FTS5 全文搜索 |
| `/messages/vector_search` | GET | 向量语义搜索 |

### 技能目录结构

```
Pocket-Agent/agent/skills/
├── main-skills/       # 主 Agent 手动技能
│   └── dev/           # 开发相关技能
├── executor-skills/   # 子 Agent 技能
│   └── skill-creator/ # 技能沉淀规范
└── auto-skills/       # 自动沉淀的技能
    ├── main/          # 主 Agent 自动技能
    └── executor/      # 子 Agent 自动技能
```

### 配置说明

### LLM 配置

在「配置」页面填写：
- **API 地址**：LLM 服务的 base_url（支持 OpenAI 兼容 API）
- **API 密钥**：认证密钥
- **模型名称**：如 gpt-4o、deepseek-chat 等
- **Temperature**：生成温度（0.0-2.0）
- **最大 Token**：单次回复最大 token 数

### MCP 服务

- **NeuralBridge MCP**：手机操控（触控/无障碍）由独立服务完成，需单独安装配置
- 默认地址：`http://127.0.0.1:7474/mcp`

### PyPI 镜像

可配置国内 PyPI 镜像源，加速 Termux 中的 pip install。

## 注意事项

1. **首次启动较慢**：Termux 中 `pip install` 可能需要 2-5 分钟，App 会轮询等待服务就绪
2. **网络要求**：Google Maven 和 GitHub 需要可访问，建议开启代理/VPN
3. **ABI 限制**：仅支持 `arm64-v8a`，不支持 32 位设备
4. **NeuralBridge MCP**：手机操控（触控/无障碍）由独立服务完成，需单独安装配置
5. **悬浮窗无障碍协作**：Agent 操作手机时，悬浮窗自动缩小为迷你模式并移至角落，避免干扰 UI 树获取
6. **代码同步**：支持通过配置页面手动触发 `git pull`，自动拉取最新 Python Agent 代码

## 相关项目

| 项目 | 说明 |
|------|------|
| [Pocket-Agent](https://github.com/Dreamt0511/Pocket-Agent) | Python AI Agent 后端（LangChain/LangGraph） |
| [NeuralBridge MCP](https://github.com/dondetir/NeuralBridge_mcp) | 手机操控 MCP 服务（无障碍/触控） |

### Python 后端依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| Python | 3.10+ | 运行环境 |
| LangChain | >=0.2.0 | Agent 框架 |
| LangGraph | >=0.2.0 | 状态图编排 |
| FastAPI | >=0.115.0 | HTTP API 服务 |
| Uvicorn | >=0.34.0 | ASGI 服务器 |
| aiosqlite | >=0.19.0 | 异步 SQLite |
| numpy | >=1.24.0 | 向量计算 |
| rich | >=13.0.0 | 终端 UI |

## 许可证

本项目仅供学习研究使用。
