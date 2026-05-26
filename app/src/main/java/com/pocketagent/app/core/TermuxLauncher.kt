package com.pocketagent.app.core

import android.content.Context
import android.content.Intent
import android.util.Log

object TermuxLauncher {
    private const val TAG = "TermuxLauncher"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val POCKET_AGENT_DIR = "Pocket-Agent"
    private const val GIT_REPO = "https://github.com/Dreamt0511/Pocket-Agent.git"

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 启动 FastAPI 服务。代码存储在 Termux 私有目录 ~/Pocket-Agent，
     * 避免 sdcardfs 跨应用权限限制。app.py 在首次 clone 后由脚本自动生成。
     */
    fun launchFastAPI(context: Context, mirrorUrl: String = ""): Boolean {
        if (!isTermuxInstalled(context)) {
            Log.w(TAG, "Termux not installed")
            return false
        }

        val pipEnv = if (mirrorUrl.isNotBlank()) "PIP_INDEX_URL=$mirrorUrl " else ""
        val progressFile = "~/$POCKET_AGENT_DIR/.startup_progress"

        // app.py（FastAPI 入口，主库不含此文件，由脚本自动写入）
        val appPyScript = buildString {
            append("import os,sys,json,subprocess,asyncio,logging\n")
            append("from fastapi import FastAPI,Request\n")
            append("from fastapi.responses import StreamingResponse\n")
            append("logging.basicConfig(level=logging.INFO)\n")
            append("logger=logging.getLogger('pocket-agent-api')\n")
            append("PROJECT_ROOT=os.path.dirname(os.path.abspath(__file__))\n")
            append("sys.path.insert(0,PROJECT_ROOT)\n")
            append("app=FastAPI(title='Pocket-Agent API')\n")
            append("@app.get('/health')\n")
            append("async def health():\n")
            append("  return {'status':'ok','python':sys.version}\n")
            append("@app.post('/chat')\n")
            append("async def chat(request:Request):\n")
            append("  data=await request.json();message=data.get('message','')\n")
            append("  async def gen():\n")
            append("    try:\n")
            append("      from agent.agent_langchain import LangChainPocketAgent\n")
            append("      agent=LangChainPocketAgent()\n")
            append("      result,success,iterations=await agent.run_conversation(message)\n")
            append("      for chunk in result.split('\\n'):\n")
            append("        if chunk.strip():yield f'data:{chunk.strip()}\\n\\n'\n")
            append("    except Exception as e:\n")
            append("      yield f'data:[ERROR]{e}\\n\\n'\n")
            append("    yield 'data:[DONE]\\n\\n'\n")
            append("  return StreamingResponse(gen(),media_type='text/event-stream')\n")
            append("@app.post('/sync')\n")
            append("async def sync():\n")
            append("  r=subprocess.run(['git','pull','origin','main'],capture_output=True,text=True,cwd=PROJECT_ROOT)\n")
            append("  return {'status':'ok' if r.returncode==0 else 'error','output':r.stdout,'error':r.stderr}\n")
            append("@app.get('/config')\n")
            append("async def get_config():\n")
            append("  cfg={};env_file=os.path.join(PROJECT_ROOT,'.env')\n")
            append("  if os.path.exists(env_file):\n")
            append("    with open(env_file)as f:\n")
            append("      for line in f:\n")
            append("        line=line.strip()\n")
            append("        if line and not line.startswith('#')and'='in line:\n")
            append("          k,v=line.split('=',1);cfg[k.strip()]=v.strip().strip('\\\"\\'')\n")
            append("  return cfg\n")
            append("@app.post('/config')\n")
            append("async def set_config(request:Request):\n")
            append("  data=await request.json();env_file=os.path.join(PROJECT_ROOT,'.env');existing={}\n")
            append("  if os.path.exists(env_file):\n")
            append("    with open(env_file)as f:\n")
            append("      for line in f:\n")
            append("        line=line.strip()\n")
            append("        if line and not line.startswith('#')and'='in line:\n")
            append("          k,v=line.split('=',1);existing[k.strip()]=v.strip()\n")
            append("  existing.update(data)\n")
            append("  with open(env_file,'w')as f:\n")
            append("    for k,v in existing.items():f.write(f'{k}={v}\\n')\n")
            append("  return {'status':'ok'}\n")
            append("if __name__=='__main__':import uvicorn;uvicorn.run(app,host='0.0.0.0',port=8000)\n")
        }

        // Termux 脚本：git clone + 写入 app.py + pip install + uvicorn
        val script = buildString {
            append("cd && ")
            append("mkdir -p ~/$POCKET_AGENT_DIR && ")
            append("echo 'STEP=STARTED' > $progressFile 2>/dev/null; ")
            append("if [ ! -d ~/$POCKET_AGENT_DIR/.git ]; then ")
            append("echo 'STEP=GIT_CLONE' >> $progressFile 2>/dev/null && ")
            append("git clone $GIT_REPO ~/$POCKET_AGENT_DIR && ")
            append("echo 'STEP=GIT_DONE' >> $progressFile 2>/dev/null; ")
            append("else cd ~/$POCKET_AGENT_DIR && git pull origin main && ")
            append("echo 'STEP=GIT_DONE' >> $progressFile 2>/dev/null; ")
            append("fi && ")
            append("echo 'STEP=PIP_INSTALL' >> $progressFile 2>/dev/null && ")
            append("cd ~/$POCKET_AGENT_DIR && ")
            append("${pipEnv}pip install fastapi uvicorn -q && ")
            append("${pipEnv}pip install -r requirements.txt -q && ")
            append("echo 'STEP=APP_PY' >> $progressFile 2>/dev/null && ")
            append("cat > app.py << 'PYEOF'")
            append("\n")
            append(appPyScript)
            append("PYEOF && ")
            append("echo 'STEP=SERVICE_START' >> $progressFile 2>/dev/null && ")
            append("exec uvicorn app:app --host 0.0.0.0 --port 8000")
        }

        val intent = Intent(TERMUX_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, "$TERMUX_PACKAGE.RunCommandService")
            action = TERMUX_RUN_COMMAND
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_PATH", script)
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_BACKGROUND", true)
        }

        return try {
            context.startService(intent)
            Log.i(TAG, "Termux FastAPI launch intent sent")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Termux", e)
            false
        }
    }
}
