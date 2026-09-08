package com.genshin.gachahelper.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局会话事件总线
 *
 * 用于跨 ViewModel 通知数据变化，解决登录/导入/同步后页面不刷新的问题。
 * 各 ViewModel observe [events]，收到事件后重新加载数据。
 */
@Singleton
class SessionEventBus @Inject constructor() {

    private val _events = MutableSharedFlow<SessionEvent>(
        replay = 0,
        extraBufferCapacity = 4
    )
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    /**
     * 挂起式发送，背压由 SharedFlow 处理，避免 buffer 溢出时静默丢弃事件
     */
    suspend fun emit(event: SessionEvent) {
        _events.emit(event)
    }
}

/**
 * 会话事件类型
 */
sealed class SessionEvent {
    /** 登录完成（AuthViewModel selectRole 成功） */
    data object LoginCompleted : SessionEvent()

    /** 退出登录 */
    data object LogoutCompleted : SessionEvent()

    /** 数据导入完成（UIGF 导入） */
    data object DataImported : SessionEvent()

    /** 数据同步完成（GachaSyncService Success） */
    data object DataSynced : SessionEvent()

    /** 数据清除 */
    data object DataCleared : SessionEvent()
}
