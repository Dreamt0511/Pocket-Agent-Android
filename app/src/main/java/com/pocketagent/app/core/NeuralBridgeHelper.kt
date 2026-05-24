package com.pocketagent.app.core

import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * NeuralBridge 手机操控能力检测
 *
 * 当 Agent 需要操控手机时，检查 NeuralBridge 是否就绪：
 *   1. 是否已安装
 *   2. 无障碍服务是否已开启
 *
 * 如果未就绪，弹出引导对话框引导用户去安装/开启。
 */
object NeuralBridgeHelper {
    private const val TAG = "NeuralBridge"

    /** NeuralBridge 包名（后续可配置） */
    var packageName: String = "com.neuralbridge.app"

    /** NeuralBridge 无障碍服务组件全名 */
    var accessibilityService: String = "com.neuralbridge.app/.accessibility.NeuralBridgeService"

    /** 安装地址（后续会在主库 Release 中发布 APK） */
    var installUrl: String = "https://github.com/Dreamt0511/Pocket-Agent/releases"

    sealed class Status {
        /** 已安装且无障碍已开启 */
        object Ready : Status()
        /** 未安装 */
        object NotInstalled : Status()
        /** 已安装但无障碍服务未开启 */
        object AccessibilityDisabled : Status()
    }

    data class Alert(
        val status: Status,
        /** 触发此检查的操控动作描述 */
        val actionDescription: String
    )

    private val _alert = MutableStateFlow<Alert?>(null)
    val alert: StateFlow<Alert?> = _alert

    /** 由 UI 层消费后调用，清除当前 Alert */
    fun dismissAlert() {
        _alert.value = null
    }

    /**
     * 检查 NeuralBridge 状态并设置 alert（如未就绪）
     * @return true = 就绪，false = 需要用户干预
     */
    fun checkAndAlert(context: Context, actionDescription: String): Boolean {
        val status = checkStatus(context)
        return when (status) {
            is Status.Ready -> true
            else -> {
                _alert.value = Alert(status, actionDescription)
                false
            }
        }
    }

    fun checkStatus(context: Context): Status {
        return if (!isInstalled(context)) {
            Status.NotInstalled
        } else if (!isAccessibilityEnabled(context)) {
            Status.AccessibilityDisabled
        } else {
            Status.Ready
        }
    }

    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        // enabledServices 格式: "pkg1/svc1:pkg2/svc2:..."
        // 按包名匹配（不依赖具体 service 类名）
        return enabledServices.split(":").any { it.startsWith("$packageName/") }
    }

    /** 无障碍设置页 Intent */
    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 安装页 Intent */
    fun getInstallIntent(): Intent {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = android.net.Uri.parse(installUrl)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }
}
