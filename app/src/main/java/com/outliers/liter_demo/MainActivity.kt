package com.outliers.liter_demo

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.FileOutputStream

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var modelPathInput by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let {
                val path = getPathFromUri(context, it)
                if (path != null) {
                    modelPathInput = path
                }
            }
        }
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("Model Setup", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = modelPathInput,
                        onValueChange = { modelPathInput = it },
                        label = { Text("Model File Path") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                        Text("Pick")
                    }
                }
                Button(
                    onClick = { viewModel.initializeEngine(modelPathInput) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = modelPathInput.isNotEmpty() && !uiState.isThinking
                ) {
                    Text(if (uiState.isInitialized) "Re-initialize" else "Initialize Engine")
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Automatic Tool Calling")
            Switch(
                checked = uiState.isAutomaticToolCalling,
                onCheckedChange = { viewModel.setAutomaticToolCalling(it) }
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            reverseLayout = false
        ) {
            items(uiState.messages) { message ->
                ChatBubble(message)
            }
            if (uiState.isThinking) {
                item {
                    Text(
                        "Thinking...",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
            uiState.pendingToolCall?.let { toolCall ->
                item {
                    ToolCallCard(toolCall = toolCall, onExecute = { viewModel.executeManualTool() })
                }
            }
        }

        uiState.error?.let { error ->
            AlertDialog(
                onDismissRequest = { /* No-op */ },
                title = { Text("Error") },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = { /* Could clear error here */ }) {
                        Text("OK")
                    }
                }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                enabled = uiState.isInitialized && !uiState.isThinking
            )
            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    }
                },
                enabled = uiState.isInitialized && !uiState.isThinking && inputText.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
fun ChatBubble(message: Message) {
    val alignment = if (message.isUser) Alignment.End else Alignment.Start
    val color = if (message.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = color,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(8.dp),
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun ToolCallCard(toolCall: ToolCallState, onExecute: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Tool Call Requested", fontWeight = FontWeight.Bold)
            Text("Tool: ${toolCall.name}")
            Text("Arguments: ${toolCall.arguments}", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onExecute, modifier = Modifier.align(Alignment.End)) {
                Text("Execute")
            }
        }
    }
}

fun getPathFromUri(context: android.content.Context, uri: Uri): String? {
    if (uri.scheme == "file") return uri.path

    return try {
        val contentResolver = context.contentResolver
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val tempFile = File(context.cacheDir, "model.litertlm")
        val outputStream = FileOutputStream(tempFile)
        inputStream.use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }
        tempFile.absolutePath
    } catch (e: Exception) {
        null
    }
}
