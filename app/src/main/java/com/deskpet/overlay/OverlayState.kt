package com.deskpet.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 悬浮窗运行状态（进程内共享）。
 *
 * 之前 MainActivity 用一个 `remember` 的 Boolean 记录是否在运行，
 * 进程/Activity 重建后就会和真实服务状态失联（宠物还浮着，界面却显示"开启"）。
 * 现在由服务自己发布状态，UI 只负责消费。
 */
object OverlayState {
    private val _petId = MutableStateFlow<String?>(null)
    val petId: StateFlow<String?> = _petId.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun setRunning(id: String?) {
        _petId.value = id
        _running.value = id != null
    }
}
