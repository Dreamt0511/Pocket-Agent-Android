#!/usr/bin/env python3
"""
Pocket-Agent 公共配置文件
存放非隐私的通用配置，隐私配置（API密钥、URL等）请在.env文件中设置
"""
import os
import json
from pathlib import Path

# 项目根目录
PROJECT_ROOT = Path(__file__).parent.parent.absolute()

# ==============================
# Agent 运行配置
# ==============================
# Agent最大迭代次数：最多支持多少轮工具调用
MAX_ITERATIONS = 300

# LangGraph底层递归限制（兜底防护，建议设置为MAX_ITERATIONS的2倍）
RECURSION_LIMIT = 600

# ==============================
# 上下文窗口配置
# ==============================
# 云端API模型的上下文窗口大小（如DeepSeek、Qwen等均为128K）
MAX_CONTEXT_TOKENS = 128000

# ==============================
# 技能系统配置
# ==============================
SKILLS_DIR = str(PROJECT_ROOT / "agent" / "skills" / "main-skills")

# 技能文件名称
SKILL_FILE_NAMES = ["SKILL.md", "skill.md"]

# 子Agent技能目录
EXECUTOR_SKILLS_DIR = str(PROJECT_ROOT / "agent" / "skills" / "executor-skills")

# 自动沉淀技能目录
AUTO_SKILLS_DIR = str(PROJECT_ROOT / "agent" / "skills" / "auto-skills")

# ==============================
# 任务文件存储目录
# ==============================
TASKS_DIR = str(PROJECT_ROOT / "tasks")

# ==============================
# 日志配置
# ==============================
LOGS_DIR = str(PROJECT_ROOT / "logs")

# ==============================
# 提示词配置
# ==============================
PROMPTS_DIR = str(PROJECT_ROOT / "agent" / "prompts")


def load_env_config(env_file: str = None) -> dict:
    """
    加载环境变量配置，优先从 config.json 读取，其次 .env 文件
    返回 dict 供 Android 端读取
    """
    if env_file is None:
        env_file = str(PROJECT_ROOT / "config.json")

    config = {}

    # 从 config.json 读取
    if os.path.exists(env_file):
        try:
            with open(env_file, "r") as f:
                config = json.load(f)
        except json.JSONDecodeError:
            pass

    return config