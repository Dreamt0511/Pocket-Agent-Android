package com.pocketagent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.pocketagent.app.core.AppBootstrapper
import com.pocketagent.app.ui.theme.PocketAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启动 Agent 守护进程（异步：同步代码 + 初始化 Python）
        AppBootstrapper.start()

        setContent {
            PocketAgentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppEntryPoint()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 显示悬浮窗
        // OverlayManager.showMini()
    }

    override fun onResume() {
        super.onResume()
        // 隐藏悬浮窗
        // OverlayManager.hide()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理资源
        // OverlayManager.cleanup()
    }
}

@Composable
fun AppEntryPoint() {
    val navController = rememberNavController()
    AppNavGraph(navController = navController)
}