package com.cortex.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val iaPrioriseur = MlpPrioriseur()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(iaPrioriseur)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(iaPrioriseur: MlpPrioriseur) {
    var selectedTab by remember { mutableStateOf(0) }
    var updateUrl by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        try {
            val release = JarvisApiClient.githubService.getLatestRelease()
            val latestVersion = release.tag_name.replace("v", "")
            if (latestVersion != BuildConfig.VERSION_NAME) {
                updateUrl = release.html_url
            }
        } catch (e: Exception) {
            // Pas d'internet ou erreur
        }
    }

    if (updateUrl != null) {
        AlertDialog(
            onDismissRequest = { updateUrl = null },
            title = { Text("Mise à jour disponible 🎉") },
            text = { Text("Une nouvelle version de Cortex IA est disponible sur GitHub !") },
            confirmButton = {
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))
                    context.startActivity(intent)
                    updateUrl = null
                }) { Text("Mettre à jour") }
            },
            dismissButton = {
                TextButton(onClick = { updateUrl = null }) { Text("Plus tard") }
            }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("📋") },
                    label = { Text("Prioriseur") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("🤖") },
                    label = { Text("Jarvis") }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            if (selectedTab == 0) {
                PrioritizerScreen(iaPrioriseur)
            } else {
                JarvisScreen()
            }
        }
    }
}

data class TaskItem(val name: String, val score: Double)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrioritizerScreen(iaPrioriseur: MlpPrioriseur) {
    var taskName by remember { mutableStateOf("") }
    var urgency by remember { mutableStateOf(5f) }
    var importance by remember { mutableStateOf(5f) }
    var duration by remember { mutableStateOf(5f) }
    var envy by remember { mutableStateOf(5f) }
    var energy by remember { mutableStateOf(5f) }

    var tasks by remember { mutableStateOf(listOf<TaskItem>()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("🧠 Cortex IA", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
            Text("v${BuildConfig.VERSION_NAME}", fontSize = 14.sp, color = Color.Gray)
        }
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = taskName,
            onValueChange = { taskName = it },
            label = { Text("Nom de la tâche") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        SliderRow("Urgence", urgency) { urgency = it }
        SliderRow("Importance", importance) { importance = it }
        SliderRow("Durée (0 court, 10 long)", duration) { duration = it }
        SliderRow("Envie", envy) { envy = it }
        SliderRow("Énergie requise", energy) { energy = it }

        Button(
            onClick = {
                if (taskName.isNotBlank()) {
                    val score = iaPrioriseur.forward(
                        urgency.toDouble(),
                        importance.toDouble(),
                        duration.toDouble(),
                        envy.toDouble(),
                        energy.toDouble()
                    )
                    val newTask = TaskItem(taskName, score * 100)
                    tasks = (tasks + newTask).sortedByDescending { it.score }
                    taskName = ""
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ) {
            Text("Calculer la priorité", color = Color.White)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Tâches Prioritaires :", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        
        LazyColumn {
            items(tasks) { task ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2E2E2E))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(task.name, color = Color.White)
                        Text(String.format("%.1f %%", task.score), color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SliderRow(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..10f,
            steps = 10,
            modifier = Modifier.weight(2f)
        )
        Text(String.format("%.1f", value), modifier = Modifier.width(30.dp), fontSize = 14.sp)
    }
}

data class ChatMessage(val text: String, val isUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisScreen() {
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf(ChatMessage("Bonjour Antoine ! Je suis Jarvis. Comment puis-je t'aider ?", false))) }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("🤖 Jarvis", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
            Text("v${BuildConfig.VERSION_NAME}", fontSize = 14.sp, color = Color.Gray)
        }
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(messages) { msg ->
                val align = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart
                val color = if (msg.isUser) Color(0xFF1976D2) else Color(0xFF388E3C)
                Box(contentAlignment = align, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = color,
                        modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth(0.8f)
                    ) {
                        Text(msg.text, modifier = Modifier.padding(12.dp), color = Color.White)
                    }
                }
            }
            if (isLoading) {
                item {
                    Text("Jarvis réfléchit...", color = Color.Gray, modifier = Modifier.padding(8.dp))
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message à Jarvis...") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        val userMsg = input
                        messages = messages + ChatMessage(userMsg, true)
                        input = ""
                        isLoading = true

                        coroutineScope.launch {
                            try {
                                val response = JarvisApiClient.apiService.sendMessage(ChatRequest(userMsg))
                                val textReply = response.response ?: response.text ?: "Erreur de réponse"
                                messages = messages + ChatMessage(textReply, false)
                            } catch (e: Exception) {
                                messages = messages + ChatMessage("Erreur de connexion : ${e.message}", false)
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                },
                modifier = Modifier.align(Alignment.CenterVertically),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text("Envoyer")
            }
        }
    }
}
