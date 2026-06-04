package com.axonys.ai.desktop

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.axonys.ai.ThemeMode
import com.axonys.ai.JarvisChatMessage
import com.axonys.ai.TaskItem
import com.axonys.ai.JarvisNotification
import com.axonys.ai.JarvisApiClient
import com.axonys.ai.MlpPrioriseur
import com.axonys.ai.DeleteMemoryRequest
import com.axonys.ai.ModeRequest
import com.axonys.ai.MemoryFact
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Base64
import java.util.prefs.Preferences
import org.jetbrains.skia.Image
import org.jetbrains.skia.Bitmap

// Version desktop
const val APP_VERSION = "1.0.0"

fun main() = application {
    val windowState = rememberWindowState(
        size = DpSize(420.dp, 840.dp)
    )
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Axonys AI - Desktop"
    ) {
        DesktopApp()
    }
}

// --- Préférences Desktop (remplace SharedPreferences) ---
class DesktopPreferences {
    private val prefs = Preferences.userNodeForPackage(DesktopPreferences::class.java)
    fun getString(key: String, default: String? = null): String? = prefs.get(key, default)
    fun putString(key: String, value: String) { prefs.put(key, value) }
    fun getBoolean(key: String, default: Boolean = false): Boolean = prefs.getBoolean(key, default)
    fun putBoolean(key: String, value: Boolean) { prefs.putBoolean(key, value) }
    fun getInt(key: String, default: Int = 0): Int = prefs.getInt(key, default)
    fun putInt(key: String, value: Int) { prefs.putInt(key, value) }
    fun remove(key: String) { prefs.remove(key) }
}

// --- Desktop App Principal ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopApp() {
    val prefs = remember { DesktopPreferences() }
    val savedTheme = prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
    val briefingEnabled = prefs.getBoolean("briefing_enabled", false)
    val iaPrioriseur = remember { MlpPrioriseur() }

    val defaultGreeting = "Bonjour Antoine. Les systèmes sont en ligne."
    val chatHistory = prefs.getString("chat_history_antoine", null)
    val gson = remember { Gson() }
    val initialMessages = if (chatHistory != null) {
        try {
            gson.fromJson<List<JarvisChatMessage>>(chatHistory, object : TypeToken<List<JarvisChatMessage>>() {}.type)
        } catch (e: Exception) {
            listOf(JarvisChatMessage(defaultGreeting, false))
        }
    } else listOf(JarvisChatMessage(defaultGreeting, false))

    val savedTasks = prefs.getString("prioritized_tasks", null)
    val initialTasks = if (savedTasks != null) {
        try {
            gson.fromJson<List<TaskItem>>(savedTasks, object : TypeToken<List<TaskItem>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }
    } else emptyList()

    var themeMode by remember { mutableStateOf(ThemeMode.valueOf(savedTheme)) }
    var isBriefingEnabled by remember { mutableStateOf(briefingEnabled) }
    var jarvisChatMessages by remember { mutableStateOf(initialMessages) }
    var prioritizedTasks by remember { mutableStateOf(initialTasks) }
    var activeNotification by remember { mutableStateOf<com.axonys.ai.JarvisNotification?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var isMemoryExplorerOpen by remember { mutableStateOf(false) }
    var currentLatitude by remember { mutableStateOf<Double?>(null) }
    var currentLongitude by remember { mutableStateOf<Double?>(null) }
    var isAutoReadEnabled by remember { mutableStateOf(prefs.getBoolean("auto_read_enabled", false)) }

    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val darkColors = darkColorScheme(
        primary = Color(0xFF64B5F6), secondary = Color(0xFF9575CD),
        background = Color(0xFF0F111A), surface = Color(0xFF1A1D2E),
        onPrimary = Color.Black, onBackground = Color.White, onSurface = Color.White,
        primaryContainer = Color(0xFF22283D), surfaceVariant = Color(0xFF2C314D)
    )
    val lightColors = lightColorScheme(
        primary = Color(0xFF4F46E5), secondary = Color(0xFF7C3AED),
        background = Color(0xFFF9FAFB), surface = Color.White,
        onPrimary = Color.White, onBackground = Color(0xFF111827), onSurface = Color(0xFF1F2937),
        primaryContainer = Color(0xFFEEF2FF), surfaceVariant = Color(0xFFF3F4F6)
    )
    val colorScheme = if (isDark) darkColors else lightColors

    MaterialTheme(colorScheme = colorScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MainScreen(
                iaPrioriseur = iaPrioriseur,
                themeMode = themeMode,
                isBriefingEnabled = isBriefingEnabled,
                JarvisChatMessages = jarvisChatMessages,
                prioritizedTasks = prioritizedTasks,
                googleAccount = null,
                currentUserId = "antoine",
                currentUserName = "antoine",
                lat = currentLatitude,
                lng = currentLongitude,
                onThemeChange = { themeMode = it; prefs.putString("theme_mode", it.name) },
                onBriefingToggle = { isBriefingEnabled = it; prefs.putBoolean("briefing_enabled", it) },
                onMessagesChange = { jarvisChatMessages = it; prefs.putString("chat_history_antoine", gson.toJson(it)) },
                onTasksChange = { prioritizedTasks = it; prefs.putString("prioritized_tasks", gson.toJson(it)) },
                onGoogleSignIn = {},
                onGoogleSignOut = {},
                onRequestNotifPermission = {},
                onRequestNotifAccess = {},
                onRefreshToken = { null },
                briefingHour = prefs.getInt("briefing_hour", 8),
                briefingMinute = prefs.getInt("briefing_minute", 0),
                onBriefingTimeChange = { h, m -> prefs.putInt("briefing_hour", h); prefs.putInt("briefing_minute", m) },
                onImpromptuBriefing = { println("⚡ Briefing impromptu (simulé)") },
                isMemoryExplorerOpen = isMemoryExplorerOpen,
                onMemoryExplorerToggle = { isMemoryExplorerOpen = it },
                isAutoReadEnabled = isAutoReadEnabled,
                onAutoReadToggle = { isAutoReadEnabled = it; prefs.putBoolean("auto_read_enabled", it) },
                onPickImage = { _ -> }
            )
        }
    }
}

// =====================
// TOUS LES COMPOSABLES REPRIS DE MainActivity.kt
// =====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    iaPrioriseur: MlpPrioriseur,
    themeMode: ThemeMode,
    isBriefingEnabled: Boolean,
    JarvisChatMessages: List<JarvisChatMessage>,
    prioritizedTasks: List<TaskItem>,
    googleAccount: Any?, // null sur desktop
    currentUserId: String,
    currentUserName: String,
    lat: Double?,
    lng: Double?,
    onThemeChange: (ThemeMode) -> Unit,
    onBriefingToggle: (Boolean) -> Unit,
    onMessagesChange: (List<JarvisChatMessage>) -> Unit,
    onTasksChange: (List<TaskItem>) -> Unit,
    onGoogleSignIn: () -> Unit,
    onGoogleSignOut: () -> Unit,
    onRequestNotifPermission: () -> Unit,
    onRequestNotifAccess: () -> Unit,
    onRefreshToken: suspend () -> String?,
    briefingHour: Int,
    briefingMinute: Int,
    onBriefingTimeChange: (Int, Int) -> Unit,
    onImpromptuBriefing: () -> Unit,
    isMemoryExplorerOpen: Boolean,
    onMemoryExplorerToggle: (Boolean) -> Unit,
    isAutoReadEnabled: Boolean,
    onAutoReadToggle: (Boolean) -> Unit,
    onPickImage: ((java.net.URI) -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    var activeNotification by remember { mutableStateOf<com.axonys.ai.JarvisNotification?>(null) }

    var updateUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            val release = JarvisApiClient.githubService.getLatestRelease()
            val latestVersion = release.tag_name.replace("v", "")
            if (latestVersion != APP_VERSION) updateUrl = release.html_url
        } catch (e: Exception) { println("Erreur check maj: ${e.message}") }
    }
    if (updateUrl != null) {
        AlertDialog(
            onDismissRequest = { updateUrl = null },
            title = { Text("Mise à jour disponible 🎉") },
            text = { Text("Une nouvelle version de Axonys AI est disponible sur GitHub !") },
            confirmButton = { Button(onClick = { try { java.awt.Desktop.getDesktop().browse(java.net.URI(updateUrl)) } catch(_: Exception) {}; updateUrl = null }) { Text("Mettre à jour") } },
            dismissButton = { TextButton(onClick = { updateUrl = null }) { Text("Plus tard") } }
        )
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                colors = if (isSystemInDarkTheme()) listOf(Color(0xFF0F111A), Color(0xFF1A1D2E))
                else listOf(Color(0xFFF9FAFB), Color(0xFFF3F4F6))
            )
        )) {
            Crossfade(targetState = selectedTab, animationSpec = tween(400), modifier = Modifier.padding(bottom = 88.dp)) { tab ->
                when (tab) {
                    0 -> PrioritizerScreen(iaPrioriseur, prioritizedTasks, onTasksChange, onImpromptuBriefing)
                    1 -> JarvisScreen(
                        JarvisChatMessages, googleAccount, currentUserId, currentUserName, lat, lng,
                        onMessagesChange, onRefreshToken, isAutoReadEnabled, onAutoReadToggle, onPickImage
                    )
                    2 -> SettingsScreen(
                        themeMode, isBriefingEnabled, googleAccount, onThemeChange, onBriefingToggle,
                        onGoogleSignIn, onGoogleSignOut, onRequestNotifPermission, onRequestNotifAccess,
                        briefingHour, briefingMinute, onBriefingTimeChange, { onMemoryExplorerToggle(true) }
                    )
                }
            }
            if (isMemoryExplorerOpen) {
                MemoryExplorerScreen(currentUserId, onDismiss = { onMemoryExplorerToggle(false) }, onDeleteFact = { fact ->
                    scope.launch {
                        try { JarvisApiClient.apiService.deleteMemoryFact(DeleteMemoryRequest(fact, currentUserId)) }
                        catch (e: Exception) { println("MemoryDelete Error: ${e.message}") }
                    }
                })
            }
            activeNotification?.let { notif ->
                NotificationDetailScreen(notification = notif, onDismiss = { activeNotification = null })
            }
            // Dock flottant
            Box(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 20.dp)) {
                Surface(modifier = Modifier.fillMaxWidth().height(72.dp), shape = RoundedCornerShape(36.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), tonalElevation = 8.dp, shadowElevation = 16.dp,
                    border = BorderStroke(0.5.dp, Color.Gray.copy(alpha = 0.1f))
                ) {
                    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        val navItems = listOf("Focus", "Jarvis", "Profil")
                        val navIcons = listOf("🧠", "🤖", "⚙️")
                        navItems.forEachIndexed { index, label ->
                            val isSelected = selectedTab == index
                            val color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                            Column(modifier = Modifier.weight(1f).clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null, onClick = { selectedTab = index }
                            ), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                AnimatedVisibility(visible = isSelected, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                                    Box(modifier = Modifier.size(width = 20.dp, height = 3.dp).background(color, RoundedCornerShape(2.dp)))
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(navIcons[index], fontSize = if (isSelected) 22.sp else 20.sp)
                                Text(label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = color)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationDetailScreen(notification: com.axonys.ai.JarvisNotification, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.fillMaxWidth(0.85f).padding(24.dp), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp), tonalElevation = 8.dp) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🤖 Jarvis vous informe", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Text(notification.title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Text(notification.message, fontSize = 16.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Compris, merci Jarvis") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrioritizerScreen(iaPrioriseur: MlpPrioriseur, tasks: List<TaskItem>, onTasksChange: (List<TaskItem>) -> Unit, onImpromptuBriefing: () -> Unit) {
    var taskName by remember { mutableStateOf("") }
    var urgency by remember { mutableStateOf(5f) }
    var importance by remember { mutableStateOf(5f) }
    var duration by remember { mutableStateOf(5f) }
    var envy by remember { mutableStateOf(5f) }
    var energy by remember { mutableStateOf(5f) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🧠 Axonys AI", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                Text("v${APP_VERSION}", fontSize = 14.sp, color = Color.Gray)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onImpromptuBriefing, modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary), shape = RoundedCornerShape(20.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("⚡", fontSize = 18.sp); Spacer(modifier = Modifier.width(12.dp)); Text("Briefing Impromptu", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(value = taskName, onValueChange = { taskName = it }, label = { Text("Nouvelle tâche...") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true)
            Spacer(modifier = Modifier.height(16.dp))
            SliderRow("Urgence", urgency) { urgency = it }
            SliderRow("Importance", importance) { importance = it }
            SliderRow("Durée", duration) { duration = it }
            SliderRow("Envie", envy) { envy = it }
            SliderRow("Énergie", energy) { energy = it }
            Button(onClick = {
                if (taskName.isNotBlank()) {
                    val score = iaPrioriseur.forward(urgency.toDouble(), importance.toDouble(), duration.toDouble(), envy.toDouble(), energy.toDouble())
                    val newList = (tasks + TaskItem(name = taskName, score = score * 100)).sortedByDescending { it.score ?: 0.0 }
                    onTasksChange(newList)
                    taskName = ""
                }
            }, modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp), shape = RoundedCornerShape(20.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) { Row(verticalAlignment = Alignment.CenterVertically) { Text("🎯", fontSize = 18.sp); Spacer(modifier = Modifier.width(12.dp)); Text("Analyser la priorité", fontWeight = FontWeight.Bold, fontSize = 16.sp) } }
            Spacer(modifier = Modifier.height(24.dp))
            Text("Ma Liste de Priorités", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(8.dp))
        }
        items(tasks, key = { it.id ?: (it.name.hashCode() + (it.score ?: 0.0).hashCode()) }) { task ->
            AnimatedVisibility(visible = true, enter = slideInVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                TaskCard(task) { onTasksChange(tasks.filter { it != task }) }
            }
        }
    }
}

@Composable
fun SliderRow(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp)) {
                Text(value.toInt().toString(), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = 0f..10f, steps = 9,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun JarvisScreen(
    messages: List<JarvisChatMessage>,
    googleAccount: Any?,
    currentUserId: String,
    currentUserName: String,
    lat: Double?,
    lng: Double?,
    onMessagesChange: (List<JarvisChatMessage>) -> Unit,
    onRefreshToken: suspend () -> String?,
    isAutoReadEnabled: Boolean,
    onAutoReadToggle: (Boolean) -> Unit,
    onPickImage: ((java.net.URI) -> Unit) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var selectedImageByteArray by remember { mutableStateOf<ByteArray?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isModelLoading by remember { mutableStateOf(false) }
    var isModelLaunching by remember { mutableStateOf(false) }
    var isOptimizing by remember { mutableStateOf(false) }
    var isToolRunning by remember { mutableStateOf(false) }
    var runningToolName by remember { mutableStateOf<String?>(null) }
    var availableModes by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var currentMode by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var isSpeaking by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var currentSentiment by remember { mutableStateOf("CALM") }

    // Threads
    var currentThreadId by remember { mutableStateOf("main") }
    var threads by remember { mutableStateOf(listOf("main")) }
    var showSidebar by remember { mutableStateOf(false) }
    var showNewThreadDialog by remember { mutableStateOf(false) }
    var newThreadName by remember { mutableStateOf("") }
    var threadMessages by remember { mutableStateOf(mapOf("main" to messages)) }
    val currentMessages = threadMessages[currentThreadId] ?: emptyList()

    LaunchedEffect(messages) { threadMessages = threadMessages.toMutableMap().also { it["main"] = messages } }
    LaunchedEffect(currentUserId) {
        try { val response = JarvisApiClient.apiService.getThreads(currentUserId); threads = (response.threads + "briefing").distinct() }
        catch (e: Exception) { println("Erreur chargement threads: ${e.message}") }
    }
    LaunchedEffect(currentThreadId) {
        isLoading = true
        try {
            val response = JarvisApiClient.apiService.getHistory(currentThreadId, currentUserId)
            val updated = threadMessages.toMutableMap()
            updated[currentThreadId] = response.history.map { JarvisChatMessage(text = it.text, isUser = it.isUser, isError = false) }
            threadMessages = updated
        } catch (e: Exception) { println("Erreur chargement historique: ${e.message}") }
        finally { isLoading = false }
    }
    LaunchedEffect(currentMessages.size) { if (currentMessages.isNotEmpty()) listState.animateScrollToItem(currentMessages.size - 1) }

    val gemColorHex = availableModes.find { it["name"] == currentMode }?.get("color")
    val gemColor = if (gemColorHex != null) Color(org.jetbrains.skia.Color.makeRGB(
        Integer.parseInt(gemColorHex.substring(1,3),16),
        Integer.parseInt(gemColorHex.substring(3,5),16),
        Integer.parseInt(gemColorHex.substring(5,7),16)
    ).toInt()) else null

    val sentimentColor = when(currentSentiment) {
        "STRESS" -> Color(0xFFFF5252); "FATIGUE" -> Color(0xFFB39DDB); "ENTHUSIASM" -> Color(0xFF00E676)
        else -> Color.Transparent
    }
    val threadColor = gemColor ?: (if (sentimentColor != Color.Transparent) sentimentColor else MaterialTheme.colorScheme.primary)

    fun refreshModes() {
        coroutineScope.launch {
            try {
                val response = JarvisApiClient.apiService.getModes(currentUserId)
                availableModes = response.modes.map { mapOf("name" to it.name, "icon" to (it.icon ?: "💎"), "color" to (it.color ?: "#4285F4")) }
            } catch (e: Exception) { println("Erreur chargement modes: ${e.message}") }
        }
    }
    LaunchedEffect(currentUserId) { refreshModes() }

    var showCreateModeDialog by remember { mutableStateOf(false) }

    // Dialogue création mode
    if (showCreateModeDialog) {
        CreateModeDialog(onDismiss = { showCreateModeDialog = false }, onCreate = { n, i, ic, c ->
            coroutineScope.launch {
                try { JarvisApiClient.apiService.createMode(currentUserId, com.axonys.ai.ModeRequest(n, i, ic, c)); showCreateModeDialog = false; refreshModes() }
                catch (e: Exception) { println("Erreur création mode: ${e.message}") }
            }
        })
    }

    if (showNewThreadDialog) {
        AlertDialog(
            onDismissRequest = { showNewThreadDialog = false }, shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("🚀 Nouveau Canal", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp); Spacer(modifier = Modifier.height(4.dp))
                Text("Créez un espace de discussion dédié", fontSize = 12.sp, color = Color.Gray)
            }},
            text = { OutlinedTextField(value = newThreadName, onValueChange = { newThreadName = it }, placeholder = { Text("Ex: Projet NSI, Sport...") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, shape = RoundedCornerShape(16.dp)) },
            confirmButton = { Button(onClick = {
                if (newThreadName.isNotBlank()) {
                    val threadId = newThreadName.lowercase().replace(" ", "_")
                    threads = (threads + threadId).distinct()
                    threadMessages = threadMessages.toMutableMap().also { it[threadId] = emptyList() }
                    currentThreadId = threadId; newThreadName = ""; showNewThreadDialog = false; showSidebar = false
                }
            }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = threadColor)) { Text("Créer l'espace", fontWeight = FontWeight.Bold) }},
            dismissButton = { TextButton(onClick = { showNewThreadDialog = false }) { Text("Plus tard", color = Color.Gray) } }
        )
    }

    // Animation d'arrière-plan
    val infiniteTransition = rememberInfiniteTransition()
    val backgroundOffset by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 1000f,
        animationSpec = infiniteRepeatable(animation = tween(40000, easing = LinearEasing), repeatMode = RepeatMode.Reverse)
    )

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(brush = Brush.radialGradient(colors = listOf(threadColor.copy(alpha = 0.15f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(backgroundOffset % size.width, (backgroundOffset * 0.7f) % size.height), radius = size.width * 1.5f))
            drawRect(brush = Brush.radialGradient(colors = listOf(threadColor.copy(alpha = 0.12f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(size.width - (backgroundOffset % size.width), size.height - ((backgroundOffset * 0.5f) % size.height)), radius = size.width * 1.2f))
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // En-tête
            Box(modifier = Modifier.fillMaxWidth().background(brush = Brush.horizontalGradient(listOf(threadColor.copy(alpha = 0.15f), MaterialTheme.colorScheme.surface))).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(onClick = { showSidebar = !showSidebar }, shape = RoundedCornerShape(10.dp), color = threadColor.copy(alpha = 0.15f), modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text(if (showSidebar) "✕" else "☰", fontSize = 18.sp, color = threadColor) }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text(if (currentThreadId == "main") "JARVIS" else currentThreadId.replace("_", " ").uppercase(), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp, color = threadColor)
                        Text(if (currentThreadId == "main") "Assistant IA Personnel" else "Canal spécialisé", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.Gray, letterSpacing = 1.sp)
                    }
                }
            }

            // Messages
            LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Spacer(modifier = Modifier.height(8.dp)) }
                items(currentMessages) { msg ->
                    val align = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart
                    val bubbleBrush = if (msg.isUser) Brush.linearGradient(listOf(threadColor, threadColor.copy(alpha = 0.85f)))
                    else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.08f)))
                    val textColor = if (msg.isUser) Color.White else MaterialTheme.colorScheme.onSurface

                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = align) {
                        Box(modifier = Modifier.widthIn(max = 310.dp).padding(vertical = 2.dp).shadow(elevation = if (msg.isUser) 6.dp else 0.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = if (msg.isUser) 24.dp else 4.dp, bottomEnd = if (msg.isUser) 4.dp else 24.dp))
                            .background(brush = bubbleBrush, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = if (msg.isUser) 24.dp else 4.dp, bottomEnd = if (msg.isUser) 4.dp else 24.dp))
                            .combinedClickable(onLongClick = {
                                val msgToDelete = msg; val updated = threadMessages.toMutableMap()
                                updated[currentThreadId] = currentMessages.filter { it != msgToDelete }
                                threadMessages = updated
                                coroutineScope.launch { try { JarvisApiClient.apiService.deleteMessage(currentUserId, mapOf("thread_id" to currentThreadId, "content" to msgToDelete.text)) } catch (e: Exception) { println("Erreur suppression msg: ${e.message}") } }
                            }, onClick = {})
                        ) {
                            if (msg.isUser || !msg.isNew) FormattedMessage(text = msg.text, isUser = msg.isUser, color = textColor, imageResult = msg.imageResult)
                            else TypewriterText(text = msg.text, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp), color = textColor, onComplete = {
                                val updated = threadMessages.toMutableMap(); val currentList = updated[currentThreadId]?.toMutableList() ?: mutableListOf()
                                val idx = currentList.indexOf(msg); if (idx != -1) { currentList[idx] = msg.copy(isNew = false); updated[currentThreadId] = currentList; threadMessages = updated }
                            })
                        }
                    }
                }
                if (isLoading) { item { JarvisOrb(isThinking = !isModelLoading && !isModelLaunching && !isOptimizing && !isToolRunning, isToolRunning = isToolRunning, toolName = runningToolName, isModelLoading = isModelLoading || isOptimizing, isModelLaunching = isModelLaunching, isListening = isListening, isSpeaking = isSpeaking, baseColor = threadColor, moodColor = sentimentColor) } }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }

            // Mode Selector
            JarvisModeSelector(selectedMode = currentMode, onModeSelected = {
                if (it != currentMode) { currentMode = it; coroutineScope.launch { isModelLoading = true; isLoading = true; delay(1200); isModelLoading = false; isLoading = false } }
            }, modes = availableModes, onAddMode = { showCreateModeDialog = true })

            // Barre de saisie
            Surface(modifier = Modifier.padding(16.dp).fillMaxWidth(), shape = RoundedCornerShape(32.dp), tonalElevation = 8.dp, shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surface) {
                Column {
                    // Aperçu image
                    AnimatedVisibility(visible = selectedImageByteArray != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).size(100.dp)) {
                            Surface(shape = RoundedCornerShape(12.dp), shadowElevation = 4.dp, border = BorderStroke(2.dp, threadColor.copy(alpha = 0.5f))) {
                                val previewBitmap = remember(selectedImageByteArray) {
                                    selectedImageByteArray?.let { bytes ->
                                        runCatching {
                                            val img = Image.makeFromEncoded(bytes)
                                            Bitmap.makeFromImage(img).asImageBitmap()
                                        }.getOrNull()
                                    }
                                }
                                if (previewBitmap != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = previewBitmap,
                                        contentDescription = "Aperçu image",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                            IconButton(onClick = { selectedImageByteArray = null }, modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp).size(24.dp).background(Color.Red, CircleShape)) {
                                Icon(Icons.Default.Close, contentDescription = "Supprimer", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                        var showTools by remember { mutableStateOf(false) }
                        IconButton(onClick = { showTools = !showTools }) {
                            Icon(imageVector = if (showTools) Icons.Default.Close else Icons.Default.Add, contentDescription = "Outils", tint = if (showTools) Color.Gray else threadColor,
                                modifier = Modifier.graphicsLayer(rotationZ = if (showTools) 90f else 0f))
                        }
                        AnimatedVisibility(visible = showTools, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showTools = false }) { Icon(Icons.Default.Image, contentDescription = "Image", tint = threadColor) }
                                IconButton(onClick = { showTools = false }) { Icon(Icons.Default.Mic, contentDescription = "Vocal", tint = threadColor) }
                                IconButton(onClick = { onAutoReadToggle(!isAutoReadEnabled) }) {
                                    Icon(imageVector = if (isAutoReadEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff, contentDescription = "Auto-read", tint = if (isAutoReadEnabled) threadColor else Color.Gray)
                                }
                                Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color.LightGray.copy(alpha = 0.5f)))
                            }
                        }
                        OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text(if (currentThreadId == "main") "Demander quelque chose..." else "Message dans ${currentThreadId.replace("_", " ")}...", color = Color.Gray) },
                            shape = RoundedCornerShape(28.dp), maxLines = 6
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier
                            .padding(bottom = 4.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (input.isNotBlank() && !isLoading) threadColor
                                else threadColor.copy(alpha = 0.4f)
                            )
                            .clickable(enabled = input.isNotBlank() && !isLoading) {
                            if (input.isNotBlank() && !isLoading) {
                                val userMsg = input
                                val updatedWithUser = currentMessages + JarvisChatMessage(text = userMsg, isUser = true, isError = false)
                                val updated = threadMessages.toMutableMap(); updated[currentThreadId] = updatedWithUser; threadMessages = updated
                                if (currentThreadId == "main") onMessagesChange(updatedWithUser)
                                input = ""

                                coroutineScope.launch {
                                    isLoading = true; isModelLaunching = true; isOptimizing = false
                                    launch { delay(5000); if (isLoading) isOptimizing = true }

                                    try {
                                        var selectedImageBase64: String? = null
                                        selectedImageByteArray?.let { bytes ->
                                            selectedImageBase64 = Base64.getEncoder().encodeToString(bytes)
                                            selectedImageByteArray = null
                                        }
                                        val gson = Gson()
                                        val responseBody = withContext(Dispatchers.IO) {
                                            val freshToken = null // onRefreshToken() - pas de token Google sur desktop
                                            JarvisApiClient.apiService.streamMessage(com.axonys.ai.ChatRequest(
                                                prompt = userMsg, google_token = null, user_id = currentUserId, user_name = currentUserName,
                                                lat = lat, lng = lng, thread_id = currentThreadId, mode = currentMode, image_base64 = selectedImageBase64
                                            ))
                                        }
                                        isModelLaunching = false
                                        val reader = withContext(Dispatchers.IO) { BufferedReader(InputStreamReader(responseBody.byteStream())) }
                                        var fullText = ""
                                        val initialJarvisMsg = JarvisChatMessage(text = "", isUser = false, isThinking = true)
                                        var streamWithJarvis = updatedWithUser + initialJarvisMsg

                                        withContext(Dispatchers.IO) {
                                            reader.use { br ->
                                                while (true) {
                                                    val line = br.readLine() ?: break
                                                    if (line.startsWith("data: ")) {
                                                        val json = line.substring(6)
                                                        try {
                                                            val data = gson.fromJson(json, Map::class.java)
                                                            withContext(Dispatchers.Main) {
                                                                if (data["error"] != null) {
                                                                    val errMsg = JarvisChatMessage("Jarvis a rencontré une erreur: ${data["error"]}", isUser = false, isError = true)
                                                                    val updatedErr = threadMessages.toMutableMap()
                                                                    updatedErr[currentThreadId] = messages + errMsg
                                                                    threadMessages = updatedErr
                                                                    if (currentThreadId == "main") onMessagesChange(messages + errMsg)
                                                                    return@withContext
                                                                }
                                                                if (data["sentiment"] != null) currentSentiment = data["sentiment"] as String
                                                                if (data["chunk"] != null) {
                                                                    isToolRunning = false; fullText += data["chunk"] as String
                                                                    val updatedJarvisMsg = initialJarvisMsg.copy(text = fullText, isThinking = false)
                                                                    streamWithJarvis = updatedWithUser + updatedJarvisMsg
                                                                    val updatedStream = threadMessages.toMutableMap()
                                                                    updatedStream[currentThreadId] = streamWithJarvis; threadMessages = updatedStream
                                                                    if (currentThreadId == "main") onMessagesChange(streamWithJarvis)
                                                                }
                                                                if (data["tool_use"] != null) { isToolRunning = true; runningToolName = data["tool_use"] as String; isOptimizing = false }
                                                                if (data["done"] == true) {
                                                                    isToolRunning = false; runningToolName = null
                                                                    val finalSentiment = data["sentiment"] as? String ?: "CALM"
                                                                    val finalImage = data["image_result"] as? String
                                                                    currentSentiment = finalSentiment
                                                                    val finalJarvisMsg = initialJarvisMsg.copy(text = fullText, isThinking = false, imageResult = finalImage, isNew = true)
                                                                    streamWithJarvis = updatedWithUser + finalJarvisMsg
                                                                    val updatedFinal = threadMessages.toMutableMap()
                                                                    updatedFinal[currentThreadId] = streamWithJarvis; threadMessages = updatedFinal
                                                                    if (currentThreadId == "main") onMessagesChange(streamWithJarvis)
                                                                }
                                                            }
                                                        } catch (_: Exception) {}
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        println("Erreur stream: ${e.message}")
                                        val errMsg = JarvisChatMessage("Erreur de connexion ou de traitement du flux. Veuillez réessayer.", isUser = false, isError = true)
                                        val updatedErr = threadMessages.toMutableMap()
                                        updatedErr[currentThreadId] = messages + errMsg; threadMessages = updatedErr
                                        if (currentThreadId == "main") onMessagesChange(messages + errMsg)
                                    } finally { isLoading = false; isModelLaunching = false; isModelLoading = false; isOptimizing = false }
                                }
                            }
                        }) {
                            Box(contentAlignment = Alignment.Center) {
                                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                                else Icon(imageVector = Icons.Default.Send, contentDescription = "Envoyer", tint = if (input.isNotBlank()) Color.White else Color.White.copy(alpha = 0.5f), modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
        }

        // Scrim
        AnimatedVisibility(visible = showSidebar, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { showSidebar = false })
        }

        // Sidebar
        AnimatedVisibility(visible = showSidebar, enter = fadeIn() + slideInHorizontally(), exit = fadeOut() + slideOutHorizontally()) {
            Box(modifier = Modifier.width(260.dp).fillMaxHeight()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), shadowElevation = 0.dp, tonalElevation = 0.dp) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text("🤖 Discussions", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 12.dp))
                        threads.forEach { threadId ->
                            val isSelected = threadId == currentThreadId
                            val icon = when { threadId == "main" -> "🏠"; threadId.contains("briefing") -> "⚡"; threadId.contains("dev") -> "🛠️"; threadId.contains("nsi") -> "💻"; threadId.contains("projet") -> "🚀"; else -> "📌" }
                            val displayName = if (threadId == "main") "Général" else threadId.replace("_", " ").replaceFirstChar { it.uppercase() }
                            Surface(onClick = { currentThreadId = threadId; showSidebar = false }, shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) threadColor.copy(alpha = 0.2f) else Color.Transparent, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(icon, fontSize = 18.sp); Spacer(modifier = Modifier.width(12.dp))
                                    Text(displayName, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) threadColor else MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Button(onClick = { showNewThreadDialog = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = threadColor)) { Text("+ Nouveau canal") }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    themeMode: ThemeMode, isBriefingEnabled: Boolean, googleAccount: Any?,
    onThemeChange: (ThemeMode) -> Unit, onBriefingToggle: (Boolean) -> Unit,
    onGoogleSignIn: () -> Unit, onGoogleSignOut: () -> Unit,
    onRequestNotifPermission: () -> Unit, onRequestNotifAccess: () -> Unit,
    briefingHour: Int, briefingMinute: Int, onBriefingTimeChange: (Int, Int) -> Unit,
    onExploreMemory: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("⚙️ Paramètres", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Apparence", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(8.dp)) {
                ThemeOptionRow("Thème Système", ThemeMode.SYSTEM, themeMode, onThemeChange)
                ThemeOptionRow("Mode Clair", ThemeMode.LIGHT, themeMode, onThemeChange)
                ThemeOptionRow("Mode Sombre", ThemeMode.DARK, themeMode, onThemeChange)
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("Automatisation", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Morning Briefing", fontWeight = FontWeight.Medium)
                        Text("Jarvis résume ta journée à ${briefingHour.toString().padStart(2, '0')}:${briefingMinute.toString().padStart(2, '0')}", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(checked = isBriefingEnabled, onCheckedChange = onBriefingToggle)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRequestNotifAccess, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Autoriser la lecture des notifications") }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("Intelligence & Mémoire", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Button(onClick = onExploreMemory, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) { Text("🧠 Explorer ce que Jarvis sait", color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("Compte", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Connectez-vous à Jarvis via l'application mobile.", fontSize = 14.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Desktop mode - Utilisation hors ligne", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun TaskCard(task: TaskItem, onDelete: () -> Unit) {
    val taskScore = task.score ?: 0.0
    val accentColor = if (taskScore >= 70) Color(0xFFFF5252) else if (taskScore >= 40) Color(0xFFFFB300) else Color(0xFF00E676)
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.fillMaxHeight().width(6.dp).background(accentColor))
            Row(modifier = Modifier.padding(16.dp).weight(1f), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.name ?: "Sans titre", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Priorité : ${(task.score ?: 0.0).toInt()}%", fontSize = 12.sp, color = Color.Gray)
                }
                IconButton(onClick = onDelete, modifier = Modifier.background(accentColor.copy(alpha = 0.1f), CircleShape)) { Text("✅", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
fun ThemeOptionRow(label: String, option: ThemeMode, current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = current == option, onClick = { onSelect(option) })
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun ThinkingWave(color: Color) {
    val infiniteTransition = rememberInfiniteTransition()
    val waveOffset by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing), repeatMode = RepeatMode.Restart)
    )
    Canvas(modifier = Modifier.fillMaxWidth().height(40.dp).padding(vertical = 8.dp)) {
        val width = size.width; val height = size.height
        val points = mutableListOf<androidx.compose.ui.geometry.Offset>()
        for (x in 0..width.toInt() step 5) {
            val relativeX = x.toFloat() / width
            val sine = Math.sin((relativeX * 3f * Math.PI) + waveOffset).toFloat()
            points.add(androidx.compose.ui.geometry.Offset(x.toFloat(), height / 2f + sine * 12f))
        }
        val path = androidx.compose.ui.graphics.Path().apply { moveTo(points[0].x, points[0].y); for (i in 1 until points.size) lineTo(points[i].x, points[i].y) }
        drawPath(path = path, color = color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))

        val path2 = androidx.compose.ui.graphics.Path().apply {
            moveTo(points[0].x, points[0].y + 4f)
            for (i in 1 until points.size) { val relativeX = points[i].x / width; val sine = Math.sin((relativeX * 3f * Math.PI) + waveOffset + 1f).toFloat(); lineTo(points[i].x, height / 2f + sine * 8f) }
        }
        drawPath(path = path2, color = color.copy(alpha = 0.4f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun MemoryExplorerScreen(currentUserId: String, onDismiss: () -> Unit, onDeleteFact: (String) -> Unit) {
    var memories by remember { mutableStateOf<List<com.axonys.ai.MemoryFact>>(emptyList()) }
    var preferences by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(0) }

    LaunchedEffect(currentUserId) {
        try { memories = JarvisApiClient.apiService.getMemory(currentUserId).facts; preferences = JarvisApiClient.apiService.getPreferences(currentUserId).preferences }
        catch (e: Exception) { println("MemoryExplorer Error: ${e.message}") }
        finally { isLoading = false }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.8f).clickable(enabled = false) {}, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("🧠 Mémoire de Jarvis", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = onDismiss) { Text("❌", fontSize = 16.sp) }
                }
                Text("Voici ce que Jarvis a retenu sur vous. Vous pouvez supprimer des faits si nécessaire.", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) } }
                else {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { selectedTab = 0 }) { Text("Faits", color = if (selectedTab == 0) MaterialTheme.colorScheme.primary else Color.Gray, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
                        TextButton(onClick = { selectedTab = 1 }) { Text("Préférences", color = if (selectedTab == 1) MaterialTheme.colorScheme.primary else Color.Gray, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
                    }

                    if (selectedTab == 0) {
                        if (memories.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Jarvis n'a pas encore mémorisé de faits.", textAlign = TextAlign.Center, color = Color.Gray) }
                        else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(memories) { item ->
                                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), border = BorderStroke(0.5.dp, Color.Gray.copy(alpha = 0.1f))) {
                                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) { Text(item.fact, fontSize = 15.sp, fontWeight = FontWeight.Medium); Text(item.timestamp.split(" ")[0], fontSize = 11.sp, color = Color.Gray) }
                                        IconButton(onClick = { onDeleteFact(item.fact); memories = memories.filter { it.fact != item.fact } }, modifier = Modifier.size(32.dp).background(Color.Red.copy(alpha = 0.1f), CircleShape)) { Text("🗑️", fontSize = 14.sp) }
                                    }
                                }
                            }
                        }
                    } else {
                        if (preferences.isEmpty()) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Aucune préférence définie.", textAlign = TextAlign.Center, color = Color.Gray) }
                        else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(preferences.toList()) { (key, value) ->
                                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), border = BorderStroke(0.5.dp, Color.Gray.copy(alpha = 0.1f))) {
                                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) { Text(key, fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Medium); Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun JarvisOrb(
    isThinking: Boolean = true, isToolRunning: Boolean = false, isModelLoading: Boolean = false,
    isModelLaunching: Boolean = false, isListening: Boolean = false, isSpeaking: Boolean = false,
    toolName: String? = null, baseColor: Color = MaterialTheme.colorScheme.primary, moodColor: Color = Color.Transparent
) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(initialValue = 1f, targetValue = 1.2f, animationSpec = infiniteRepeatable(animation = tween(1500, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse))
    val rotation by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 360f, animationSpec = infiniteRepeatable(animation = tween(if (isToolRunning) 2000 else 8000, easing = LinearEasing), repeatMode = RepeatMode.Restart))
    val orbColor = when { isListening -> Color.Red; isSpeaking -> Color(0xFFE91E63); isModelLoading -> Color(0xFF00BCD4); isModelLaunching -> Color(0xFF4CAF50); isToolRunning -> Color(0xFFFF9800); else -> baseColor }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.Start) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(140.dp)) {
            if (moodColor != Color.Transparent) {
                Canvas(modifier = Modifier.fillMaxSize()) { drawCircle(brush = Brush.radialGradient(colors = listOf(moodColor.copy(alpha = 0.35f), Color.Transparent), center = center, radius = size.width / 2)) }
            }
            Canvas(modifier = Modifier.size(90.dp * pulseScale)) { drawCircle(brush = Brush.radialGradient(colors = listOf(orbColor.copy(alpha = 0.3f), Color.Transparent), center = center, radius = size.width / 1.2f)) }
            Canvas(modifier = Modifier.size(80.dp).graphicsLayer(rotationZ = -rotation * 0.5f)) { drawCircle(color = orbColor.copy(alpha = 0.2f), style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))) }
            Canvas(modifier = Modifier.size(72.dp).graphicsLayer(rotationZ = rotation * 1.2f)) {
                drawArc(color = orbColor.copy(alpha = 0.5f), startAngle = 0f, sweepAngle = 90f, useCenter = false, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                drawArc(color = orbColor.copy(alpha = 0.5f), startAngle = 180f, sweepAngle = 90f, useCenter = false, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
            // Orbe central - version simplifiée sans android.graphics.Path
            Canvas(modifier = Modifier.size(45.dp).graphicsLayer(rotationZ = rotation * 0.3f)) {
                // Avoid Skia shader APIs that differ across Skiko versions.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.9f),
                            orbColor.copy(alpha = 0.9f),
                            orbColor.copy(alpha = 0.35f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.minDimension / 2
                    )
                )
            }
            // Particules
            if (isThinking || isToolRunning || isModelLaunching) {
                repeat(8) { i ->
                    val particleRotation = (rotation * (1f + i * 0.1f)) % 360f
                    val particleDistance = 50.dp + (i * 2).dp
                    val particleSize = 2.dp + (i % 3).dp
                    Box(modifier = Modifier.graphicsLayer { rotationZ = particleRotation + (i * 45); translationX = particleDistance.toPx() }.size(particleSize).background(orbColor.copy(alpha = 0.6f), CircleShape))
                }
            }
            if (isListening) { Box(modifier = Modifier.size(10.dp).align(Alignment.BottomCenter).offset(y = 20.dp).background(Color.Red, CircleShape)) }
            if (isToolRunning) { CircularProgressIndicator(modifier = Modifier.size(65.dp), color = Color(0xFFFF9800), strokeWidth = 3.dp) }
        }
        when { isModelLoading && !isThinking && !isModelLaunching -> Text("Chargement du modèle...", fontSize = 12.sp, color = orbColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            isModelLaunching -> Text("Lancement du modèle...", fontSize = 12.sp, color = orbColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            isToolRunning && toolName != null -> Text("Jarvis utilise : $toolName", fontSize = 12.sp, color = orbColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            isThinking -> Text("Jarvis réfléchit...", fontSize = 12.sp, color = baseColor.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisModeSelector(selectedMode: String?, onModeSelected: (String?) -> Unit, modes: List<Map<String, String>>, onAddMode: () -> Unit) {
    LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        item { ModeChip(name = "Standard", icon = "🤖", isSelected = selectedMode == null, onClick = { onModeSelected(null) }) }
        items(modes) { mode -> val name = mode["name"] ?: ""
            ModeChip(name = name, icon = mode["icon"] ?: "💎",
                color = try { val hex = mode["color"] ?: "#4285F4"; Color(org.jetbrains.skia.Color.makeRGB(Integer.parseInt(hex.substring(1,3),16), Integer.parseInt(hex.substring(3,5),16), Integer.parseInt(hex.substring(5,7),16)).toInt()) } catch (_: Exception) { MaterialTheme.colorScheme.primary },
                isSelected = selectedMode == name, onClick = { onModeSelected(name) })
        }
        item { Surface(onClick = onAddMode, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(36.dp)) { Box(contentAlignment = Alignment.Center) { Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold) } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateModeDialog(onDismiss: () -> Unit, onCreate: (String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("💎") }
    var color by remember { mutableStateOf("#4285F4") }

    AlertDialog(onDismissRequest = onDismiss, title = { Text("Créer une nouvelle Gem (Mode)") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nom du mode") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = instruction, onValueChange = { instruction = it }, label = { Text("Instructions système") }, placeholder = { Text("Ex: Tu es un expert en cuisine...") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = icon, onValueChange = { icon = it }, label = { Text("Icône") }, modifier = Modifier.width(80.dp))
                OutlinedTextField(value = color, onValueChange = { color = it }, label = { Text("Couleur hex") }, placeholder = { Text("#4285F4") }, modifier = Modifier.weight(1f))
            }
        }
    }, confirmButton = { Button(onClick = { onCreate(name, instruction, icon, color) }, enabled = name.isNotBlank() && instruction.isNotBlank()) { Text("Créer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}

@Composable
fun CodeBlock(code: String, color: Color) {
    val clipboardManager = LocalClipboardManager.current
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(12.dp), color = Color(0xFF1E1E1E), border = BorderStroke(0.5.dp, color.copy(alpha = 0.3f))) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Python", fontSize = 10.sp, color = color.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                IconButton(onClick = { clipboardManager.setText(AnnotatedString(code.trim())) }, modifier = Modifier.size(24.dp)) { Text("📋", fontSize = 12.sp) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) { Text(text = code.trim(), color = Color(0xFFCE9178), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp) }
        }
    }
}

@Composable
fun FormattedMessage(text: String, isUser: Boolean, color: Color, imageResult: String? = null) {
    val parts = text.split("```")
    Column {
        parts.forEachIndexed { index, part ->
            if (index % 2 == 0) { if (part.isNotBlank()) Text(text = part.trim(), modifier = Modifier.padding(16.dp), color = color, fontSize = 16.sp, lineHeight = 24.sp) }
            else { Box(modifier = Modifier.padding(horizontal = 16.dp)) { CodeBlock(code = part.trim(), color = MaterialTheme.colorScheme.primary) } }
        }
        imageResult?.let { base64 ->
            Box(modifier = Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                try {
                    val bytes = Base64.getDecoder().decode(base64)
                    val img = Image.makeFromEncoded(bytes)
                    val bitmap = Bitmap.makeFromImage(img)
                    val imageBitmap = org.jetbrains.skiko.toComposeImage(bitmap)
                    androidx.compose.foundation.Image(bitmap = imageBitmap, contentDescription = "Graphique généré", modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp), contentScale = ContentScale.Fit)
                } catch (_: Exception) {}
            }
        }
    }
}

@Composable
fun TypewriterText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, onComplete: () -> Unit = {}) {
    var displayedText by remember { mutableStateOf("") }
    LaunchedEffect(text) {
        text.forEachIndexed { index, _ -> displayedText = text.substring(0, index + 1); delay(15) }
        onComplete()
    }
    Text(text = displayedText, modifier = modifier, color = color, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeChip(name: String, icon: String, color: Color = MaterialTheme.colorScheme.primary, isSelected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (isSelected) 1.05f else 1f)
    Surface(onClick = onClick, modifier = Modifier.padding(vertical = 4.dp).graphicsLayer(scaleX = scale, scaleY = scale),
        shape = RoundedCornerShape(16.dp), color = if (isSelected) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
            Text(name, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        }
    }
}