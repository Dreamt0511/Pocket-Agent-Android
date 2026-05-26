package com.pocketagent.app

import android.app.Application
import com.pocketagent.app.core.AppBootstrapper
import com.pocketagent.app.core.ConfigManager

/**
 * Application 入口
 */
class PocketAgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化配置管理器
        ConfigManager.init(this)

        // 异步启动核心服务
        AppBootstrapper.init(this)
    }

    companion object {
        lateinit var instance: PocketAgentApp
            private set
    }
}
