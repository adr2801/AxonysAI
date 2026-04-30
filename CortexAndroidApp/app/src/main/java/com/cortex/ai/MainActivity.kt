package com.cortex.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import retrofit2.HttpException

// Enum pour le Thème
enum class ThemeMode { SYSTEM, LIGHT, DARK }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val iaPrioriseur = MlpPrioriseur()
        
        // Lire la préférence de thème
        val prefs = getSharedPreferences("CortexPrefs", Context.MODE_PRIVATE)
        val savedTheme = prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name

        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.valueOf(savedTheme)) }
            
            val isDark = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            
            // Couleurs Premium Cortex
            val colorScheme = if (isDark) {
                darkColorScheme(
                    primary = Color(0xFF00E676),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    onPrimary = Color.Black,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF00C853),
                    background = Color(0xFFF5F5F5),
                    surface = Color.White,
                    onPrimary = Color.White,
                    onBackground = Color.Black,
                    onSurface = Color.Black
                )
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        iaPrioriseur = iaPrioriseur,
                        themeMode = themeMode,
                        onThemeChange = { newTheme ->
                            themeMode = newTheme
                            prefs.edit().putString("theme_mode", newTheme.name).apply()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(iaPrioriseur: MlpPrioriseur, themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }
    var updateUrl by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        try {
            val release = JarvisApiClient.githubService.getLatestRelease()
            val latestVersion = release.tag_name.replace("v", "")
            if (latestVersion != BuildConfig.VERSION_NAME) {
                updateUrl = release.html_url
            }
        } catch (e: Exception) {
            // Silencieux
        }
    }

    if (updateUrl != null) {
        AlertDialog(
            onDismissRequest = { updateUrl = null },
            title = { Text("Mise à jour disponible 🎉") },
            text = { Text("Une nouvelle version de Cortex IA est disponible sur GitHub !") },
            confirmButton = {
                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)))
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
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("📋", fontSize = 20.sp) },
                    label = { Text("Priorités") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("🤖", fontSize = 20.sp) },
                    label = { Text("Jarvis") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("⚙️", fontSize = 20.sp) },
                    label = { Text("Paramètres") }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            Crossfade(targetState = selectedTab, animationSpec = tween(300)) { tab ->
                when (tab) {
                    0 -> PrioritizerScreen(iaPrioriseur)
                    1 -> JarvisScreen()
                    2 -> SettingsScreen(themeMode, onThemeChange)
                }
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
            Text("🧠 Cortex IA", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Text("v${BuildConfig.VERSION_NAME}", fontSize = 14.sp, color = Color.Gray)
        }
        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = taskName,
            onValueChange = { taskName = it },
            label = { Text("Nouvelle tâche...") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        SliderRow("Urgence", urgency) { urgency = it }
        SliderRow("Importance", importance) { importance = it }
        SliderRow("Durée (Longue = 10)", duration) { duration = it }
        SliderRow("Envie", envy) { envy = it }
        SliderRow("Énergie requise", energy) { energy = it }

        Button(
            onClick = {
                if (taskName.isNotBlank()) {
                    val score = iaPrioriseur.forward(
                        urgency.toDouble(), importance.toDouble(), duration.toDouble(), envy.toDouble(), energy.toDouble()
                    )
                    val newTask = TaskItem(taskName, score * 100)
                    tasks = (tasks + newTask).sortedByDescending { it.score }
                    taskName = ""
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Analyser la priorité", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("🎯 Plan d'action :", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks, key = { it.name + it.score }) { task ->
                AnimatedVisibility(
                    visible = true,
                    enter = slideInVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    TaskCard(task) {
                        tasks = tasks.filter { it != task }
                    }
                }
            }
        }
    }
}

@Composable
fun TaskCard(task: TaskItem, onDelete: () -> Unit) {
    // Dégradé selon le score
    val gradientColors = when {
        task.score >= 70 -> listOf(Color(0xFFFF5252), Color(0xFFFF1744))
        task.score >= 40 -> listOf(Color(0xFFFFB300), Color(0xFFFF8F00))
        else -> listOf(Color(0xFF00E676), Color(0xFF00C853))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .background(Brush.horizontalGradient(gradientColors))
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(task.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${task.score.toInt()}%", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Text("✅", fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
fun SliderRow(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(40.dp)) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..10f,
            steps = 10,
            modifier = Modifier.weight(2f)
        )
    }
}

data class ChatMessage(val text: String, val isUser: Boolean, val isError: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisScreen() {
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf(ChatMessage("Bonjour Antoine. Les systèmes sont en ligne. Que puis-je faire pour toi ?", false))) }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp)
        ) {
            Text("🤖 Jarvis", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }

        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp), reverseLayout = false) {
            items(messages) { msg ->
                val align = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart
                val bubbleColor = if (msg.isError) Color(0xFFD32F2F) else if (msg.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                val textColor = if (msg.isError) Color.White else if (msg.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

                Box(contentAlignment = align, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = bubbleColor,
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        Text(msg.text, modifier = Modifier.padding(14.dp), color = textColor, fontSize = 15.sp)
                    }
                }
            }
            if (isLoading) {
                item {
                    Text("Jarvis analyse la requête...", color = Color.Gray, modifier = Modifier.padding(16.dp), fontSize = 13.sp)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Tapez votre message...") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 3
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
                                val textReply = response.response ?: response.text ?: "Aucune réponse reçue."
                                messages = messages + ChatMessage(textReply, false)
                            } catch (e: HttpException) {
                                // Capture précise de l'erreur 500 pour le diagnostic
                                val errorBody = e.response()?.errorBody()?.string() ?: "Erreur serveur inconnue"
                                messages = messages + ChatMessage("Erreur ${e.code()} du serveur HuggingFace.\nDétail : $errorBody\n\n(Vérifiez vos clés API sur HF)", false, isError = true)
                            } catch (e: Exception) {
                                messages = messages + ChatMessage("Impossible de joindre Jarvis : ${e.localizedMessage}", false, isError = true)
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                Text("➤", fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun SettingsScreen(themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("⚙️ Paramètres", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(24.dp))

        Text("Apparence", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(8.dp))
        
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(8.dp)) {
                ThemeOptionRow("Thème Système", ThemeMode.SYSTEM, themeMode, onThemeChange)
                ThemeOptionRow("Mode Clair", ThemeMode.LIGHT, themeMode, onThemeChange)
                ThemeOptionRow("Mode Sombre", ThemeMode.DARK, themeMode, onThemeChange)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text("Compte & Synchronisation", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(8.dp))
        
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Sauvegarde sur le Cloud", fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { /* TODO */ }, enabled = false, shape = RoundedCornerShape(12.dp)) {
                    Text("Se connecter avec Google (Bientôt)")
                }
                Text("La synchronisation Google Auth arrivera dans une prochaine mise à jour.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
fun ThemeOptionRow(label: String, option: ThemeMode, current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = current == option, onClick = { onSelect(option) })
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
