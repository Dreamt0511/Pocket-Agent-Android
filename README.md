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
- **会话持久化**：Room 数据库存储历史会话和消息

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
│  Python Agent 引擎 (agent-seed/)     │
└──────────────────────────────────────┘
```

### 通信链路

1. `TermuxLauncher` 通过 Intent 向 Termux 发送 bash 脚本
2. 首次启动：`git clone` + `pip install`（创建 `~/.pocket-agent-ready` 标记文件）
3. 后续启动：直接运行 `uvicorn app:app --host 0.0.0.0 --port 8000`
4. `TermuxServiceClient`（OkHttp）与 FastAPI 通信：health check、SSE 流式对话、配置同步、代码同步
5. `StreamBridge` 将输出同步分发到悬浮窗和终端页面

### 数据层

- **SettingsRepository**：DataStore Preferences 存储用户配置
- **AppDatabase**（Room）：会话（Conversation）+ 消息（Message）持久化
- **ChatRepository**：会话和消息的 CRUD

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
│   │   ├── SkillManager.kt          # 技能扫描
│   │   └── NeuralBridgeHelper.kt    # MCP 服务辅助
│   ├── data/
│   │   ├── SettingsRepository.kt    # DataStore 配置存储
│   │   ├── AppDatabase.kt           # Room 数据库
│   │   ├── ChatRepository.kt        # 聊天数据仓库
│   │   ├── entity/                  # Conversation, Message
│   │   └── dao/                     # ConversationDao, MessageDao
│   ├── overlay/
│   │   ├── OverlayService.kt        # 前台服务，维护全局 StateFlow
│   │   ├── OverlayManager.kt        # 悬浮窗管理
│   │   ├── StreamBridge.kt          # 输出分发桥接器
│   │   ├── MiniOverlayView.kt       # 悬浮球视图
│   │   └── ExpandedOverlayView.kt   # 终端卡片视图
│   ├── service/
│   │   ├── AgentService.kt          # 前台服务
│   │   ├── TaskQueueManager.kt      # 任务队列（单任务串行）
│   │   └── SessionManager.kt        # 会话管理
│   ├── update/
│   │   ├── CodeSyncManager.kt       # 代码同步
│   │   └── UpdateChecker.kt         # 更新检查
│   └── ui/
│       ├── chat/ChatScreen.kt       # 对话页面（Markdown 渲染）
│       ├── home/HomeScreen.kt       # 主页
│       ├── config/ConfigScreen.kt   # 配置页面
│       ├── terminal/TerminalScreen.kt # 终端输出页面
│       ├── overlay/OverlayScreen.kt # 悬浮窗设置
│       ├── screens/history/         # 历史会话
│       ├── screens/skills/          # 技能列表
│       ├── screens/onboarding/      # 引导页
│       ├── components/              # 通用组件（TaskInputBar, VoiceInputButton 等）
│       └── theme/                   # 主题 + GlassComponents 毛玻璃风格
├── agent-seed/                      # Python Agent 种子代码
│   ├── stable_entry.py              # 不可变接口（task/ready/config/get_config 模式）
│   ├── config.json                  # Agent 配置
│   ├── requirements.txt             # Python 依赖
│   └── agent/                       # Agent 核心（config.py, core.py, prompts/, tools/）
├── scripts/
│   └── bootstrap.sh                 # Termux 环境初始化脚本
└── .github/workflows/build.yml      # CI：下载 Termux Python → Gradle assembleRelease
```

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

## 注意事项

1. **首次启动较慢**：Termux 中 `pip install` 可能需要 2-5 分钟，App 会轮询等待服务就绪
2. **网络要求**：Google Maven 和 GitHub 需要可访问，建议开启代理/VPN
3. **ABI 限制**：仅支持 `arm64-v8a`，不支持 32 位设备
4. **NeuralBridge MCP**：手机操控（触控/无障碍）由独立服务完成，需单独安装配置
