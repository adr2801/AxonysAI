package com.axonys.ai.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

fun main() = application {
    val windowState = rememberWindowState(width = 800.dp, height = 600.dp)
    
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Jarvis - Coding Agent Desktop"
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                CodingAgentScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodingAgentScreen() {
    var prompt by remember { mutableStateOf("") }
    var responseLog by remember { mutableStateOf("En attente d'instructions...") }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "🤖 Antigravity Coding Agent",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Card(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            LazyColumn(modifier = Modifier.padding(12.dp)) {
                item {
                    Text(text = responseLog, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Que doit coder l'agent ?") },
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            )

            Button(
                onClick = {
                    if (prompt.isNotBlank()) {
                        isLoading = true
                        responseLog = "L'agent Antigravity réfléchit..."
                        coroutineScope.launch {
                            responseLog = callCodingAgentBackend(prompt)
                            isLoading = false
                            prompt = ""
                        }
                    }
                },
                modifier = Modifier.align(javax.compose.ui.Alignment.CenterVertically),
                enabled = !isLoading
            ) {
                if (isLoading) CircularProgressIndicator(size = 20.dp) else Text("Envoyer")
            }
        }
    }
}

private fun callCodingAgentBackend(promptText: String): String {
    return try {
        val url = URL("http://127.0.0.1:8000/coding-agent/chat")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val jsonInputString = "{\"prompt\": \"${promptText.replace("\"", "\\\"")}\", \"user_id\": \"antoine\"}"
        
        conn.outputStream.use { os ->
            val input = jsonInputString.toByteArray(charset("utf-8"))
            os.write(input, 0, input.size)
        }

        if (conn.responseCode == 200) {
            BufferedReader(InputStreamReader(conn.inputStream, "utf-8")).use { br ->
                val response = StringBuilder()
                var responseLine: String?
                while (br.readLine().also { responseLine = it } != null) {
                    response.append(responseLine?.trim())
                }
                response.toString()
            }
        } else {
            "Erreur Serveur: Code ${conn.responseCode}"
        }
    } catch (e: Exception) {
        "Erreur de connexion au backend Python : ${e.message}"
    }
}