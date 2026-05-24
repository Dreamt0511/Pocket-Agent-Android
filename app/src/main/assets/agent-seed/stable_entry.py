#!/usr/bin/env python3
"""
Pocket-Agent Stable Entry Point (v2)

Android APK 与 Python Agent 之间的不可变接口。
无论 agent 内部如何变化，此文件保持不变。

接口协议:
- 调用方式: python3 stable_entry.py --mode=<task|ready>
- task模式: 从stdin读取任务JSON → 执行 → 通过stdout输出JSON lines
- ready模式: 输出agent状态JSON → 退出

输出格式 (每行一个JSON):
{"type": "step", "status": "planning|executing|done|error", "message": "...", "timestamp": 1234567890}
"""

import json
import os
import sys
import time
import traceback
from pathlib import Path

# Agent 代码位于本文件同目录
AGENT_ROOT = Path(__file__).parent
AGENT_DIR = AGENT_ROOT / "agent"
TASKS_DIR = AGENT_ROOT / "tasks"
ENV_FILE = AGENT_ROOT / ".env"
CONFIG_FILE = AGENT_ROOT / "config.json"

# 确保 agent 目录在 import 路径中
sys.path.insert(0, str(AGENT_ROOT))
sys.path.insert(0, str(AGENT_DIR))
sys.path.insert(0, str(TASKS_DIR))


def log_step(status: str, message: str):
    """输出一行 JSON 状态更新到 stdout"""
    step = {
        "type": "step",
        "status": status,
        "message": message,
        "timestamp": int(time.time())
    }
    print(json.dumps(step, ensure_ascii=False), flush=True)


def load_config() -> dict:
    """加载 agent 配置（config.json）"""
    if CONFIG_FILE.exists():
        try:
            with open(CONFIG_FILE, "r") as f:
                return json.load(f)
        except json.JSONDecodeError:
            log_step("warning", "config.json 解析失败，使用默认配置")
    return {}


def ensure_env_file():
    """确保 .env 文件存在，不存在时用默认配置创建"""
    if not ENV_FILE.exists():
        default_env = """# Pocket-Agent 环境变量配置
# 复制此文件为配置或在App设置页面修改

# LLM 配置
LLM_BASE_URL=http://127.0.0.1:8080/v1
LLM_API_KEY=dummy
LLM_MODEL=gelab-zero-4b-preview
LLM_TEMPERATURE=0.7
LLM_MAX_TOKENS=8000

# MCP 服务
MCP_SERVER_URL=http://127.0.0.1:7474/mcp
"""
        with open(ENV_FILE, "w") as f:
            f.write(default_env)
        log_step("info", "已创建默认 .env 配置文件")


def write_env_config(updates: dict):
    """更新 .env 配置"""
    ensure_env_file()

    if not ENV_FILE.exists():
        return False

    with open(ENV_FILE, "r") as f:
        lines = f.readlines()

    new_lines = []
    updated_keys = set()
    for line in lines:
        stripped = line.strip()
        if stripped and not stripped.startswith("#") and "=" in stripped:
            key = stripped.split("=", 1)[0].strip()
            if key in updates:
                new_lines.append(f"{key}={updates[key]}\n")
                updated_keys.add(key)
                continue
        new_lines.append(line)

    # 添加未更新过的新配置项
    for key, value in updates.items():
        if key not in updated_keys:
            new_lines.append(f"{key}={value}\n")

    with open(ENV_FILE, "w") as f:
        f.writelines(new_lines)

    return True


def run_task_mode(task: str):
    """执行单个任务并报告步骤进度"""
    log_step("planning", f"收到任务: {task}")

    # 确保 .env 存在
    ensure_env_file()

    try:
        # 加载 dotenv
        from dotenv import load_dotenv
        load_dotenv(dotenv_path=str(ENV_FILE), override=True)

        # 导入 agent 核心
        from agent.core import AgentCore

        config = load_config()
        agent = AgentCore(config=config)

        log_step("executing", "Agent 引擎已启动")

        # 执行任务
        import asyncio
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)

        result = loop.run_until_complete(
            agent.execute(task, callback=lambda s, m: log_step(s, m))
        )
        loop.close()

        log_step("done", "任务执行完毕")
        # 输出最终结果
        if result:
            print(json.dumps({"type": "result", "content": str(result)}, ensure_ascii=False), flush=True)

    except ImportError as e:
        log_step("error", f"Agent 核心模块加载失败: {e}")
        log_step("error", "请确保 agent/ 目录已从 GitHub 更新")
        sys.exit(1)
    except Exception as e:
        log_step("error", f"任务执行失败: {e}")
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)


def run_ready_mode():
    """报告 Agent 状态"""
    ensure_env_file()

    status = {
        "type": "ready",
        "agent_root": str(AGENT_ROOT),
        "agent_available": (AGENT_DIR / "core.py").exists(),
        "tasks_available": TASKS_DIR.exists(),
        "config_available": CONFIG_FILE.exists(),
        "env_available": ENV_FILE.exists(),
        "timestamp": int(time.time())
    }
    print(json.dumps(status, ensure_ascii=False), flush=True)


def run_config_mode(config_data: str):
    """更新配置"""
    try:
        updates = json.loads(config_data)
        success = write_env_config(updates)
        result = {
            "type": "config_result",
            "success": success,
            "message": "配置已更新" if success else "配置更新失败"
        }
        print(json.dumps(result, ensure_ascii=False), flush=True)
    except json.JSONDecodeError as e:
        print(json.dumps({
            "type": "config_result",
            "success": False,
            "message": f"JSON解析错误: {e}"
        }, ensure_ascii=False), flush=True)


def run_get_config_mode():
    """读取当前配置"""
    ensure_env_file()
    config = {}
    if ENV_FILE.exists():
        with open(ENV_FILE, "r") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    key = key.strip()
                    value = value.strip()
                    config[key] = value

    result = {
        "type": "config",
        "config": config
    }
    print(json.dumps(result, ensure_ascii=False), flush=True)


def main():
    args = sys.argv

    mode = None
    for arg in args:
        if arg.startswith("--mode="):
            mode = arg.split("=", 1)[1]
            break

    if mode == "task":
        task_input = sys.stdin.read().strip()
        if not task_input:
            log_step("error", "未收到任务描述")
            sys.exit(1)
        run_task_mode(task_input)

    elif mode == "config":
        config_input = sys.stdin.read().strip()
        if not config_input:
            log_step("error", "未收到配置数据")
            sys.exit(1)
        run_config_mode(config_input)

    elif mode == "get_config":
        run_get_config_mode()

    else:
        run_ready_mode()


if __name__ == "__main__":
    main()