package com.genshin.gachahelper

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.genshin.gachahelper.core.CrashCatcher
import com.genshin.gachahelper.core.SessionEvent
import com.genshin.gachahelper.core.SessionEventBus
import com.genshin.gachahelper.signin.SignInRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class GachaApp : Application(), Configuration.Provider {

    companion object {
        init {
            // 类加载即挂载崩溃捕获（早于 Hilt 注入），保证初始化期崩溃也能落盘
            CrashCatcher.installHandler()
        }
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var sessionEventBus: SessionEventBus

    @Inject
    lateinit var signInRepository: SignInRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 尽可能早拿到 context（onCreate 之前），覆盖注入期崩溃的落盘需求
        CrashCatcher.captureContext(this)
    }

    override fun onCreate() {
        super.onCreate()
        // 崩溃自述器：任何未捕获异常先落盘，供下次启动展示堆栈（幂等补挂）
        CrashCatcher.install(this)
        // 进程启动即补偿一次：自动签到开启且今日未签到 → 立即补签（幂等，带防抖）
        appScope.launch { signInRepository.onLaunchOrBoot() }
        // 登录完成后恢复可能被暂停的自动签到（重新注册周期任务并补签）
        appScope.launch {
            sessionEventBus.events.collectLatest { event ->
                if (event == SessionEvent.LoginCompleted) {
                    signInRepository.onLaunchOrBoot()
                }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}

