package com.musicplayer.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Agent 与页面之间的轻量事件总线。
 * Agent 在独立页面运行，但需要影响主页列表（如切换排序），通过此总线传递。
 */
object AgentBus {

    private val _sortRequest = MutableStateFlow<String?>(null)
    val sortRequest: StateFlow<String?> = _sortRequest.asStateFlow()

    /** 请求切换排序：NAME / ARTIST / ADD_TIME */
    fun requestSort(type: String) {
        _sortRequest.value = type
    }

    /** 消费排序请求（页面处理后调用，避免重复响应） */
    fun consumeSort() {
        _sortRequest.value = null
    }
}
