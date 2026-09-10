package com.musicplayer.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 智谱 AI API 调用封装（OpenAI 兼容格式，模型 GLM-4-Flash）。
 * 仅负责 HTTP 通信，不包含业务逻辑。
 */
object AgentService {

    private const val API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    private const val API_KEY = "b8a65ce92121403ea3aebea418449122.zalDKiU3X7TC3OSe"
    private const val MODEL = "glm-4-flash"

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
     * @param messagesJson 完整对话历史（含 system/user/assistant/tool 消息）
     * @param toolsJson 工具定义列表（function calling）
     */
    suspend fun chatCompletion(
        messagesJson: JSONArray,
        toolsJson: JSONArray
    ): Response = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", MODEL)
            put("messages", messagesJson)
            put("tools", toolsJson)
            put("temperature", 0.1)
        }

        val conn = URL(API_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $API_KEY")
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
