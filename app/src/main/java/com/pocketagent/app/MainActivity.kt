package com.pocketagent.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.rememberNavController
import com.pocketagent.app.core.AgentDaemon
import com.pocketagent.app.core.AppBootstrapper
import com.pocketagent.app.core.NeuralBridgeHelper
import com.pocketagent.app.core.TermuxLauncher
import com.pocketagent.app.ui.theme.PocketAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

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
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 应用被销毁（清后台/退出）时自动关闭 uvicorn
        // 但 Agent 正在执行任务时不杀后端，让任务继续完成。
        // 注意：清后台后 uvicorn 会一直运行直到任务完成，没有自动清理机制，
        // 这是 intentional tradeoff —— 宁可多跑一会也不打断正在执行的 Agent 任务。
        if (isFinishing && !isChangingConfigurations) {
            val status = AppBootstrapper.daemonStatus.value
            if (status !is AgentDaemon.DaemonStatus.Executing) {
                TermuxLauncher.stopFastAPI(this)
            }
        }
    }
}

@Composable
fun AppEntryPoint() {
    val navController = rememberNavController()
    val context = LocalContext.current

    AppNavGraph(navController = navController)

    // ─── NeuralBridge 就绪提示弹窗（全局，覆盖所有页面）───
    val alert by NeuralBridgeHelper.alert.collectAsState()

    if (alert != null) {
        NeuralBridgeAlertDialog(
            alert = alert!!,
            onDismiss = { NeuralBridgeHelper.dismissAlert() },
            onGoToSettings = {
                context.startActivity(
                    NeuralBridgeHelper.getAccessibilitySettingsIntent()
                )
                NeuralBridgeHelper.dismissAlert()
            },
            onGoToInstall = {
                context.startActivity(
                    NeuralBridgeHelper.getInstallIntent()
                )
                NeuralBridgeHelper.dismissAlert()
            }
        )
    }
}

@Composable
private fun NeuralBridgeAlertDialog(
    alert: NeuralBridgeHelper.Alert,
    onDismiss: () -> Unit,
    onGoToSettings: () -> Unit,
    onGoToInstall: () -> Unit
) {
    when (alert.status) {
        is NeuralBridgeHelper.Status.NotInstalled -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text("NeuralBridge 未安装", fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        "手机操控需要 NeuralBridge 应用提供无障碍服务。\n\n" +
                        "请从 GitHub 下载安装：\n${NeuralBridgeHelper.installUrl}"
                    )
                },
                confirmButton = {
                    Button(onClick = onGoToInstall) {
                        Text("去安装")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("知道了")
                    }
                }
            )
        }
        is NeuralBridgeHelper.Status.AccessibilityDisabled -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text("NeuralBridge 无障碍未开启", fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        "手机操控需要 NeuralBridge 的无障碍服务权限。\n\n" +
                        "请前往系统设置 → 无障碍 → NeuralBridge，开启开关。\n\n" +
                        "Agent 正在尝试执行：${alert.actionDescription}"
                    )
                },
                confirmButton = {
                    Button(onClick = onGoToSettings) {
                        Text("去设置")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("知道了")
                    }
                }
            )
        }
        else -> {} // Ready 不弹窗
    }
}