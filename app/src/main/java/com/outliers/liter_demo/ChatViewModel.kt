package com.outliers.liter_demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class Message(
    val text: String,
    val isUser: Boolean
)

data class ToolCallState(
    val name: String,
    val arguments: String,
    val id: String? = null
)

data class ChatState(
    val messages: List<Message> = emptyList(),
    val isThinking: Boolean = false,
    val isInitialized: Boolean = false,
    val error: String? = null,
    val isAutomaticToolCalling: Boolean = true,
    val pendingToolCall: ToolCallState? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(ChatState())
    val uiState: StateFlow<ChatState> = _uiState.asStateFlow()

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    fun setAutomaticToolCalling(enabled: Boolean) {
        _uiState.update { it.copy(isAutomaticToolCalling = enabled) }
        // Re-create conversation if engine is already initialized to apply new config
        if (engine != null) {
            createConversation()
        }
    }

    fun initializeEngine(modelPath: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isThinking = true, error = null) }
            try {
                withContext(Dispatchers.IO) {
                    engine?.close()
                    val config = EngineConfig(modelPath = modelPath)
                    engine = Engine(config)
                    engine?.initialize()
                }
                createConversation()
                _uiState.update { it.copy(isInitialized = true, isThinking = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to initialize engine: ${e.message}", isThinking = false) }
            }
        }
    }

    private fun createConversation() {
        conversation?.close()
        val config = ConversationConfig(
            tools = listOf(StockPriceTool()),
            automaticToolCalling = _uiState.value.isAutomaticToolCalling
        )
        conversation = engine?.createConversation(config)
    }

    fun sendMessage(text: String) {
        val userMessage = Message(text = text, isUser = true)
        _uiState.update { it.copy(messages = it.messages + userMessage, isThinking = true) }

        viewModelScope.launch {
            try {
                val litertMessage = com.google.ai.edge.litertlm.Message.of(text)
                var fullResponse = ""

                conversation?.sendMessageAsync(litertMessage)?.collect { response ->
                    // Handle text response
                    val responseText = response.text
                    if (!responseText.isNullOrEmpty()) {
                        fullResponse += responseText
                        updateLastModelMessage(fullResponse)
                    }

                    // Handle manual tool calls
                    if (!_uiState.value.isAutomaticToolCalling && response.toolCalls.isNotEmpty()) {
                        val call = response.toolCalls.first()
                        _uiState.update { it.copy(
                            pendingToolCall = ToolCallState(call.name, call.arguments),
                            isThinking = false
                        ) }
                    }
                }
                _uiState.update { it.copy(isThinking = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Error: ${e.message}", isThinking = false) }
            }
        }
    }

    fun executeManualTool() {
        val pending = _uiState.value.pendingToolCall ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isThinking = true, pendingToolCall = null) }
            try {
                // Execute mock tool
                val result = StockPriceTool().execute(pending.arguments)

                // Send result back to model.
                // Using Message.of with role TOOL if supported, or structured content.
                // Based on standard LLM patterns, we send a message with the tool result.
                val resultMessage = com.google.ai.edge.litertlm.Message.of(
                    result,
                    // role = com.google.ai.edge.litertlm.Role.TOOL
                )

                var fullResponse = ""
                conversation?.sendMessageAsync(resultMessage)?.collect { response ->
                    val responseText = response.text
                    if (!responseText.isNullOrEmpty()) {
                        fullResponse += responseText
                        updateLastModelMessage(fullResponse)
                    }
                }
                _uiState.update { it.copy(isThinking = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Tool execution error: ${e.message}", isThinking = false) }
            }
        }
    }

    private fun updateLastModelMessage(text: String) {
        _uiState.update { state ->
            val lastMessage = state.messages.lastOrNull()
            if (lastMessage != null && !lastMessage.isUser) {
                val newMessages = state.messages.toMutableList()
                newMessages[newMessages.size - 1] = lastMessage.copy(text = text)
                state.copy(messages = newMessages)
            } else {
                state.copy(messages = state.messages + Message(text = text, isUser = false))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine?.close()
    }
}
