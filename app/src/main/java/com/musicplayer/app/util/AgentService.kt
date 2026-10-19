package com.musicplayer.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容的对话补全接口封装（/chat/completions）。
 * 仅负责 HTTP 通信，API Key / Base URL / 模型均由 [AgentConfig] 在调用时提供，
 * 用户可使用任意 OpenAI 格式的官方、中转或自建服务。
 */
object AgentService {

    /** 模型返回的工具调用请求 */
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )

    /** 一次对话补全的响应 */
    data class Response(
        val content: String?,
        val toolCalls: List<ToolCall>
    )

    /**
     * 调用对话补全接口。
     *
     * @param apiKey 用户配置的 API Key
     * @param baseUrl 用户配置的 Base URL（可为 .../v1 或完整 .../chat/completions）
     * @param model 模型名称
     * @param messagesJson 完整对话历史（含 system/user/assistant/tool 消息）
     * @param toolsJson 工具定义列表（function calling）
     */
    suspend fun chatCompletion(
        apiKey: String,
        baseUrl: String,
        model: String,
        messagesJson: JSONArray,
        toolsJson: JSONArray
    ): Response = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messagesJson)
            put("tools", toolsJson)
            put("temperature", 0.1)
        }

        val conn = URL(buildEndpoint(baseUrl)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText() ?: ""

            if (code !in 200..299) {
                throw IOException("API请求失败($code): ${text.take(300)}")
            }

            parseResponse(text)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 将用户填写的 Base URL 归一化为完整的 chat/completions 端点。
     * 兼容写法：
     * - https://api.openai.com/v1
     * - https://api.openai.com/v1/
     * - https://host/v1/chat/completions（已带端点，原样使用）
     */
    private fun buildEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "Base URL 不能为空" }
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "Base URL 需以 http:// 或 https:// 开头"
        }
        return if (trimmed.endsWith("/chat/completions")) {
            trimmed
        } else {
            "$trimmed/chat/completions"
        }
    }

    private fun parseResponse(text: String): Response {
        val root = JSONObject(text)
        val message = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message")

        val content = message.optString("content", "").trim().ifEmpty { null }

        val toolCalls = mutableListOf<ToolCall>()
        if (message.has("tool_calls") && !message.isNull("tool_calls")) {
            val arr = message.getJSONArray("tool_calls")
            for (i in 0 until arr.length()) {
                val tc = arr.getJSONObject(i)
                val function = tc.getJSONObject("function")
                toolCalls.add(
                    ToolCall(
                        id = tc.getString("id"),
                        name = function.getString("name"),
                        arguments = function.optString("arguments", "{}").ifEmpty { "{}" }
                    )
                )
            }
        }
        return Response(content, toolCalls)
    }
}
