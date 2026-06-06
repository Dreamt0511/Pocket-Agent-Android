package com.pocketagent.app

import android.app.Application
import com.pocketagent.app.backscreen.BackScreenManager
import com.pocketagent.app.core.AppBootstrapper

/**
 * Application 入口
 */
class PocketAgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 异步启动核心服务
        AppBootstrapper.init(this)
        // 初始化背屏管理器（不启用，仅探测硬件）
        BackScreenManager.init(this)
    }

    companion object {
        lateinit var instance: PocketAgentApp
            private set
    }
}
