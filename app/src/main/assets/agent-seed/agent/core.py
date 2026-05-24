#!/usr/bin/env python3
"""
Pocket-Agent 核心引擎
包装 LangChainPocketAgent，提供统一接口供 Android 端调用
"""
import json
import os
import sys
import asyncio
from pathlib import Path
from typing import Callable, Optional

# 确保 agent 目录在路径中
AGENT_ROOT = Path(__file__).parent.parent
sys.path.insert(0, str(AGENT_ROOT))
sys.path.insert(0, str(AGENT_ROOT / "agent"))

# 加载环境变量
from dotenv import load_dotenv
load_dotenv(dotenv_path=str(AGENT_ROOT / ".env"), override=True)

from agent.config import MAX_ITERATIONS
from agent.tools.basic_tools import ALL_TOOLS


class AgentCore:
    """
    Agent 核心引擎
    封装 LangChain Agent，对外暴露 execute() 方法
    """

    def __init__(self, config: dict = None):
        self.config = config or {}
        self._llm_config = self._build_llm_config()
        self._agent = None

    def _build_llm_config(self) -> dict:
        """构建 LLM 配置，优先 env 变量，其次 config.json，最后默认值"""
        return {
            "base_url": os.getenv("DEFAULT_LLM_BASE_URL", self.config.get("llm", {}).get("base_url", "http://127.0.0.1:8080/v1")),
            "api_key": os.getenv("LLM_API_KEY", self.config.get("llm", {}).get("api_key", "dummy")),
            "model": os.getenv("LLM_MODEL", self.config.get("llm", {}).get("model", "gelab-zero-4b-preview")),
            "temperature": float(os.getenv("LLM_TEMPERATURE", self.config.get("llm", {}).get("temperature", 0.7))),
            "max_tokens": int(os.getenv("LLM_MAX_TOKENS", self.config.get("llm", {}).get("max_tokens", 8000))),
        }

    async def execute(self, task: str, callback: Callable = None) -> str:
        """
        执行任务，每步通过 callback 报告进度

        Args:
            task: 用户任务描述
            callback: 进度回调 callback(status: str, message: str)

        Returns:
            任务执行结果文本
        """
        def emit(status: str, message: str):
            if callback:
                callback(status, message)

        emit("planning", f"收到任务: {task}")

        try:
            # 动态导入 LangChain Agent（避免启动时依赖问题）
            emit("loading", "正在加载 Agent 引擎...")
            from agent.agent_langchain import LangChainPocketAgent

            if self._agent is None:
                # 读取系统提示词
                system_prompt = self._load_system_prompt()

                self._agent = LangChainPocketAgent(
                    system_prompt=system_prompt,
                    max_iterations=MAX_ITERATIONS,
                    llm_config=self._llm_config,
                )

            emit("executing", "Agent 已启动，开始执行任务...")
            response, _, _ = await self._agent.run_conversation(task)
            emit("done", "任务执行完毕")
            return response

        except ImportError as e:
            error_msg = f"Agent 核心模块加载失败: {e}"
            emit("error", error_msg)
            return f"错误: {error_msg}"
        except Exception as e:
            error_msg = f"任务执行失败: {e}"
            emit("error", error_msg)
            return f"错误: {error_msg}"

    def _load_system_prompt(self) -> str:
        """加载系统提示词"""
        try:
            from agent.prompts.system_base import prompt
            return prompt
        except ImportError:
            return "你是一个手机 AI 助手，能够通过工具操控手机完成任务。"

    def get_available_tools(self) -> list:
        """获取可用工具列表"""
        return [t.name for t in ALL_TOOLS]


# 全局单例
_agent_instance: Optional[AgentCore] = None


def get_agent(config: dict = None) -> AgentCore:
    """获取 Agent 单例"""
    global _agent_instance
    if _agent_instance is None:
        _agent_instance = AgentCore(config)
    return _agent_instance