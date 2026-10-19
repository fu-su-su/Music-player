package com.musicplayer.app.util

import android.content.Context

/**
 * AI 助手的用户自定义 API 配置（OpenAI 兼容接口）。
 *
 * 用户可在 Agent 页的配置弹窗中自行填写：
 * - API Key
 * - Base URL（如 https://api.openai.com/v1、
 *   https://open.bigmodel.cn/api/paas/v4，支持任意 OpenAI 格式的中转/自建服务）
 * - 模型名称（如 gpt-4o-mini、glm-4-flash、deepseek-chat）
 */
object AgentConfig {

    private const val PREFS_NAME = "agent_config"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"

    /** 开箱即用的默认值（智谱 GLM-4-Flash），用户可自行覆盖 */
    const val DEFAULT_API_KEY = "b8a65ce92121403ea3aebea418449122.zalDKiU3X7TC3OSe"
    const val DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
    const val DEFAULT_MODEL = "glm-4-flash"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, DEFAULT_API_KEY)?.trim().orEmpty()

    fun getBaseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL)?.trim().orEmpty()

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, DEFAULT_MODEL)?.trim().orEmpty()

    /** 三项是否都已填写（API Key 与模型为必填，Base URL 有默认值） */
    fun isConfigured(context: Context): Boolean =
        getApiKey(context).isNotEmpty() && getModel(context).isNotEmpty()

    fun save(context: Context, apiKey: String, baseUrl: String, model: String) {
        prefs(context).edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_BASE_URL, baseUrl.trim().ifEmpty { DEFAULT_BASE_URL })
            .putString(KEY_MODEL, model.trim())
            .apply()
    }
}
