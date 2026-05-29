#!/bin/bash
INPUT=$(cat)
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')

# 阻止在 Termux 目录下执行 git 操作
if [[ "$COMMAND" == *"/data/data/com.termux/"* ]] && [[ "$COMMAND" == *"git "* ]]; then
  echo "已阻止: 禁止在 Termux 目录下执行 git 操作。请在主库（/storage/emulated/0/手机agent开发/Pocket-Agent/）中操作。" >&2
  exit 2
fi

# 阻止 Android 仓库推送（需要用户确认）
if [[ "$COMMAND" == *"git push"* ]] && [[ "$COMMAND" == *"Pocket-Agent-Android"* ]]; then
  echo "已阻止: Android 仓库推送前必须征得用户同意。请让用户确认。" >&2
  exit 2
fi

exit 0
