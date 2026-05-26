# HTTP 架构重构设计

## 目标

将 Pocket-Agent-Android 从内嵌 Python 运行时架构重构为 HTTP 调用 Termux FastAPI 服务的架构。

## 核心原则

- Android = UI + System Layer
- Termux = AI Runtime Layer（FastAPI 常驻服务）
- 通信 = HTTP REST + SSE 流式
- App 不直接执行 Python，不内嵌 Python

## 架构

```
Android App (Compose UI)
├── UI 层（保持不变）
├── ForegroundService（保活 + /health 每30s）
├── TermuxServiceClient（OkHttp HTTP 调用）
│
│  HTTP 127.0.0.1:8000
▼
Termux FastAPI (app.py)
├── GET  /health   — 健康检查
├── POST /setup    — pip install -r requirements.txt
├── POST /chat     — SSE 流式 Agent 执行
├── POST /sync     — git pull 更新代码
├── GET  /config   — 读取配置
├── POST /config   — 写入配置
```

## 代码同步

- App 通过 OkHttp 从 GitHub Archive API 下载主库 ZIP
- 解压到 /sdcard/Pocket-Agent/（共享存储）
- 排除：docs/、.github/、.gitattributes、.gitignore、README.md
- Termux 通过 termux-setup-storage 访问同目录

## 保活策略

1. App 启动时通过 RUN_COMMAND Intent 拉起 Termux uvicorn
2. ForegroundService 每 30s GET /health
3. 发现不可用 → 重新发送 Intent 拉起
4. 引导用户安装 Termux:Boot 实现开机自启

## 删除

- BundledPythonManager, PythonDependencyManager, PythonRuntime（两个）
- AgentBridge, AgentDaemon（旧版）, TermuxBridge, TermuxBootstrap
- assets/python/, assets/python-ext/, assets/agent-seed/
- agent_core_main.py

## 保留

- 全部 UI 层
- overlay/ 悬浮窗
- ConfigManager, SkillManager, SessionManager（适配 HTTP）
- CodeSyncManager（改造同步目标）

## 新增

- TermuxServiceClient.kt — HTTP 客户端
- TermuxLauncher.kt — Intent 拉起 Termux
- ServiceHealthMonitor.kt — 健康检查
- app.py — Termux 侧 FastAPI（放在 GitHub 主库）

## 需引导用户安装

- Termux
- Termux:API
- Termux:Boot
- NeuralBridge（本仓库 Release 分发）
