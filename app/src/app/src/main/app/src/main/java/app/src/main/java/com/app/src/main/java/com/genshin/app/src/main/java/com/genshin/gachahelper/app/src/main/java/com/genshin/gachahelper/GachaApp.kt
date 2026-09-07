package com.genshin.gachahelper

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var sessionEventBus: SessionEventBus

    @Inject
    lateinit var signInRepository: SignInRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
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

