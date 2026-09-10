package com.musicplayer.app.ui.agent

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.app.R
import com.musicplayer.app.databinding.ActivityAgentBinding
import com.musicplayer.app.util.AgentService
import com.musicplayer.app.util.AgentTools
import com.musicplayer.app.util.ThemeManager
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * AI 助手对话页：纯文字交互，通过云端 GLM-4-Flash + Function Calling
 * 执行音乐播放器白名单内的操作。无关请求由模型按固定话术拒绝。
 */
class AgentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAgentBinding
    private lateinit var tools: AgentTools
    private lateinit var chatAdapter: ChatAdapter

    /** 完整对话历史（含 system/tool 消息，仅用于API请求） */
    private val historyJson = JSONArray()

    /** UI消息列表 */
    private val uiMessages = mutableListOf<ChatMessage>()

    private var isBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyActivityTheme(this)
        binding = ActivityAgentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setBackgroundResource(ThemeManager.getGradientDrawableRes(this))
        ThemeManager.applySystemBars(this, immersive = false)

        tools = AgentTools(application)

        setupToolbar()
        setupChat()
        setupInput()
        applyEdgeInsets()

        appendAgentMessage(getString(R.string.agent_welcome))
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupChat() {
        chatAdapter = ChatAdapter(uiMessages)
        binding.chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@AgentActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
    }

    private fun setupInput() {
        binding.sendButton.setOnClickListener { sendCurrentInput() }
        binding.inputEditText.setOnEditorActionListener { _, _, _ ->
            sendCurrentInput()
            true
        }
    }

    /**
     * 非沉浸式模式下系统自动处理状态栏/导航栏/键盘的 insets，
     * 只需给 RecyclerView 留足输入容器高度的底部 padding。
     */
    private fun applyEdgeInsets() {
        try {
            val spacing = resources.getDimensionPixelSize(R.dimen.spacing_sm)
            val inputContainer = binding.root.getChildAt(2) as? View
            binding.chatRecyclerView.post {
                if (inputContainer != null) {
                    val inputHeight = inputContainer.height.takeIf { it > 0 }
                        ?: (84 * resources.displayMetrics.density).toInt()
                    binding.chatRecyclerView.setPadding(
                        binding.chatRecyclerView.paddingStart,
                        binding.chatRecyclerView.paddingTop,
                        binding.chatRecyclerView.paddingEnd,
                        inputHeight + spacing
                    )
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun sendCurrentInput() {
        val text = binding.inputEditText.text?.toString()?.trim() ?: ""
        if (text.isEmpty() || isBusy) return
        binding.inputEditText.setText("")
        sendMessage(text)
    }

    private fun sendMessage(text: String) {
        appendUserMessage(text)

        isBusy = true
        binding.sendButton.isEnabled = false
        val thinkingPos = appendAgentMessage(getString(R.string.agent_thinking))

        lifecycleScope.launch {
            val reply = try {
                runAgentTurn(text)
            } catch (e: IOException) {
                "网络异常，请检查网络后重试"
            } catch (e: Exception) {
                "操作失败：${e.message ?: "未知错误"}"
            }

            // 更新"思考中"占位为最终回复
            uiMessages[thinkingPos] = uiMessages[thinkingPos].copy(text = reply)
            chatAdapter.notifyItemChanged(thinkingPos)
            scrollToBottom()

            isBusy = false
            binding.sendButton.isEnabled = true
        }
    }

    /**
     * 执行一轮 Agent 对话：最多连续3次工具调用后强制返回文字。
     */
    private suspend fun runAgentTurn(userInput: String): String {
        // 控制历史长度
        trimHistory()

        historyJson.put(
            JSONObject().put("role", "user").put("content", userInput)
        )

        repeat(3) {
            val response = AgentService.chatCompletion(historyJson, tools.buildToolsJson())

            if (response.toolCalls.isEmpty()) {
                val content = response.content ?: AgentTools.REJECT_MESSAGE
                historyJson.put(
                    JSONObject().put("role", "assistant").put("content", content)
                )
                return content
            }

            // 记录 assistant 的 tool_calls 消息
            val assistantMsg = JSONObject().put("role", "assistant")
            if (response.content != null) {
                assistantMsg.put("content", response.content)
            }
            val toolCallsJson = JSONArray()
            for (tc in response.toolCalls) {
                toolCallsJson.put(
                    JSONObject()
                        .put("id", tc.id)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject().put("name", tc.name).put("arguments", tc.arguments)
                        )
                )
            }
            assistantMsg.put("tool_calls", toolCallsJson)
            historyJson.put(assistantMsg)

            // 逐个执行工具并回填结果
            for (tc in response.toolCalls) {
                val result = try {
                    val args = JSONObject(tc.arguments)
                    tools.execute(tc.name, args)
                } catch (e: Exception) {
                    "工具执行失败：${e.message ?: "未知错误"}"
                }
                historyJson.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", tc.id)
                        .put("content", result)
                )
            }
        }

        return "操作步骤过多，请分步告诉我～"
    }

    /** 保留 system 消息 + 最近 MAX_HISTORY 条 */
    private fun trimHistory() {
        val maxHistory = 24
        while (historyJson.length() > maxHistory + 1) {
            historyJson.remove(1)  // 跳过索引0的system消息
        }
    }

    private fun appendUserMessage(text: String) {
        uiMessages.add(ChatMessage(isUser = true, text = text))
        chatAdapter.notifyItemInserted(uiMessages.size - 1)
        scrollToBottom()
    }

    private fun appendAgentMessage(text: String): Int {
        uiMessages.add(ChatMessage(isUser = false, text = text))
        val pos = uiMessages.size - 1
        chatAdapter.notifyItemInserted(pos)
        scrollToBottom()
        return pos
    }

    private fun scrollToBottom() {
        binding.chatRecyclerView.post {
            binding.chatRecyclerView.scrollToPosition((uiMessages.size - 1).coerceAtLeast(0))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

/** UI层聊天消息 */
data class ChatMessage(
    val isUser: Boolean,
    val text: String
)

/** 聊天消息列表适配器 */
class ChatAdapter(private val messages: List<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(val binding: com.musicplayer.app.databinding.ItemChatMessageBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ChatViewHolder {
        val binding = com.musicplayer.app.databinding.ItemChatMessageBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = messages[position]
        if (msg.isUser) {
            holder.binding.userTextView.text = msg.text
            holder.binding.userTextView.visibility = View.VISIBLE
            holder.binding.agentTextView.visibility = View.GONE
        } else {
            holder.binding.agentTextView.text = msg.text
            holder.binding.agentTextView.visibility = View.VISIBLE
            holder.binding.userTextView.visibility = View.GONE
        }
    }

    override fun getItemCount() = messages.size
}
