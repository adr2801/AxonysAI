package com.cortex.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

// --- Modèles ---
enum class ThemeMode { SYSTEM, LIGHT, DARK }
data class ChatMessage(val text: String, val isUser: Boolean, val isError: Boolean = false)
data class TaskItem(val name: String, val score: Double)

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            handleSignInResult(account)
        } catch (e: ApiException) {
            Log.e("CortexAuth", "Google Sign-In failed: ${e.statusCode}")
            android.widget.Toast.makeText(this, "Erreur Google: ${e.statusCode}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private var onAuthSuccess: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val iaPrioriseur = MlpPrioriseur()
        
        val prefs = getSharedPreferences("CortexPrefs", Context.MODE_PRIVATE)
        val savedTheme = prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        val briefingEnabled = prefs.getBoolean("briefing_enabled", false)
        
        val chatHistory = prefs.getString("chat_history", null)
        val initialMessages = if (chatHistory != null) {
            try { Gson().fromJson<List<ChatMessage>>(chatHistory, object : TypeToken<List<ChatMessage>>() {}.type) }
            catch (e: Exception) { listOf(ChatMessage("Bonjour Antoine. Les systèmes sont en ligne.", false)) }
        } else listOf(ChatMessage("Bonjour Antoine. Les systèmes sont en ligne.", false))

        val savedTasks = prefs.getString("prioritized_tasks", null)
        val initialTasks = if (savedTasks != null) {
            try { Gson().fromJson<List<TaskItem>>(savedTasks, object : TypeToken<List<TaskItem>>() {}.type) }
            catch (e: Exception) { listOf<TaskItem>() }
        } else listOf<TaskItem>()

        val lastAccount = GoogleSignIn.getLastSignedInAccount(this)

        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.valueOf(savedTheme)) }
            var isBriefingEnabled by remember { mutableStateOf(briefingEnabled) }
            var chatMessages by remember { mutableStateOf(initialMessages) }
            var prioritizedTasks by remember { mutableStateOf(initialTasks) }
            var googleAccount by remember { mutableStateOf(lastAccount) }
            
            onAuthSuccess = { googleAccount = GoogleSignIn.getLastSignedInAccount(this) }

            val isDark = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            
            val darkColors = darkColorScheme(
                primary = Color(0xFF64B5F6),
                secondary = Color(0xFF9575CD),
                background = Color(0xFF0F111A),
                surface = Color(0xFF1A1D2E),
                onPrimary = Color.Black,
                onBackground = Color.White,
                onSurface = Color.White,
                primaryContainer = Color(0xFF22283D),
                surfaceVariant = Color(0xFF2C314D)
            )

            val lightColors = lightColorScheme(
                primary = Color(0xFF1976D2),
                secondary = Color(0xFF673AB7),
                background = Color(0xFFF5F7FA),
                surface = Color.White,
                onPrimary = Color.White,
                onBackground = Color(0xFF1A1C1E),
                onSurface = Color(0xFF1A1C1E)
            )
            
            val colorScheme = if (isDark) darkColors else lightColors

            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        iaPrioriseur = iaPrioriseur,
                        themeMode = themeMode,
                        isBriefingEnabled = isBriefingEnabled,
                        chatMessages = chatMessages,
                        prioritizedTasks = prioritizedTasks,
                        googleAccount = googleAccount,
                        onThemeChange = { themeMode = it; prefs.edit().putString("theme_mode", it.name).apply() },
                        onBriefingToggle = { isBriefingEnabled = it; prefs.edit().putBoolean("briefing_enabled", it).apply(); toggleBriefingWorker(it) },
                        onMessagesChange = { chatMessages = it; prefs.edit().putString("chat_history", Gson().toJson(it)).apply() },
                        onTasksChange = { prioritizedTasks = it; prefs.edit().putString("prioritized_tasks", Gson().toJson(it)).apply() },
                        onGoogleSignIn = { startGoogleSignIn() },
                        onGoogleSignOut = { signOutGoogle() },
                        onRequestNotifPermission = { checkAndRequestNotifPermission() },
                        onRequestNotifAccess = { openNotificationAccessSettings() }
                    )
                }
            }
        }
    }

    private fun startGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestScopes(Scope("https://www.googleapis.com/auth/gmail.modify"))
            .requestScopes(Scope("https://www.googleapis.com/auth/calendar"))
            .requestIdToken("449010615326-bn7o6ek8jau2kphiaof04nptkbvntokl.apps.googleusercontent.com")
            .build()
        val client = GoogleSignIn.getClient(this, gso)
        googleSignInLauncher.launch(client.signInIntent)
    }

    private fun handleSignInResult(account: GoogleSignInAccount?) {
        if (account != null) {
            val scope = "oauth2:https://www.googleapis.com/auth/gmail.modify https://www.googleapis.com/auth/calendar"
            
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val token = com.google.android.gms.auth.GoogleAuthUtil.getToken(
                        this@MainActivity, 
                        account.account!!, 
                        scope
                    )
                    
                    val prefs = getSharedPreferences("CortexPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("google_id_token", token).apply()
                    prefs.edit().putString("user_name", account.displayName ?: "Antoine").apply()
                    
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(this@MainActivity, "Accès Workspace activé !", android.widget.Toast.LENGTH_SHORT).show()
                        onAuthSuccess?.invoke(account.displayName ?: "")
                    }
                } catch (e: Exception) {
                    Log.e("CortexAuth", "Erreur AccessToken: ${e.message}")
                    if (e is com.google.android.gms.auth.UserRecoverableAuthException) {
                        googleSignInLauncher.launch(e.intent)
                    }
                }
            }
        }
    }

    private fun signOutGoogle() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        val client = GoogleSignIn.getClient(this, gso)
        client.signOut().addOnCompleteListener {
            val prefs = getSharedPreferences("CortexPrefs", Context.MODE_PRIVATE)
            prefs.edit().remove("google_id_token").apply()
            recreate() 
        }
    }

    private fun checkAndRequestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun toggleBriefingWorker(enabled: Boolean) {
        val workManager = WorkManager.getInstance(this)
        if (enabled) {
            val briefingRequest = PeriodicWorkRequestBuilder<BriefingWorker>(24, TimeUnit.HOURS).setInitialDelay(1, TimeUnit.MINUTES).addTag("morning_briefing").build()
            workManager.enqueueUniquePeriodicWork("morning_briefing", ExistingPeriodicWorkPolicy.UPDATE, briefingRequest)
        } else { workManager.cancelUniqueWork("morning_briefing") }
    }

    private suspend fun getFreshAccessToken(account: GoogleSignInAccount): String? = withContext(Dispatchers.IO) {
        try {
            val scope = "oauth2:https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/calendar.readonly https://www.googleapis.com/auth/gmail.modify"
            // Clear current token to force refresh if needed
            val prefs = getSharedPreferences("CortexPrefs", Context.MODE_PRIVATE)
            val currentToken = prefs.getString("google_id_token", null)
            if (currentToken != null) {
                com.google.android.gms.auth.GoogleAuthUtil.clearToken(this@MainActivity, currentToken)
            }
            val newToken = com.google.android.gms.auth.GoogleAuthUtil.getToken(this@MainActivity, account.account!!, scope)
            prefs.edit().putString("google_id_token", newToken).apply()
            newToken
        } catch (e: Exception) {
            Log.e("CortexAuth", "Erreur refresh token: ${e.message}")
            null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    iaPrioriseur: MlpPrioriseur, themeMode: ThemeMode, isBriefingEnabled: Boolean, 
    chatMessages: List<ChatMessage>, prioritizedTasks: List<TaskItem>,
    googleAccount: GoogleSignInAccount?, onThemeChange: (ThemeMode) -> Unit, 
    onBriefingToggle: (Boolean) -> Unit, onMessagesChange: (List<ChatMessage>) -> Unit, 
    onTasksChange: (List<TaskItem>) -> Unit, onGoogleSignIn: () -> Unit, 
    onGoogleSignOut: () -> Unit, onRequestNotifPermission: () -> Unit, onRequestNotifAccess: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    var updateUrl by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        try {
            val release = JarvisApiClient.githubService.getLatestRelease()
            if (release.tag_name.replace("v", "") != BuildConfig.VERSION_NAME) {
                updateUrl = release.html_url
            }
        } catch (e: Exception) {}
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
            dismissButton = { TextButton(onClick = { updateUrl = null }) { Text("Plus tard") } }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Text("📋") }, label = { Text("Focus") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Text("🤖") }, label = { Text("Jarvis") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Text("⚙️") }, label = { Text("Paramètres") })
            }
        }
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F111A), Color(0xFF1A1D2E))
                )
            )) {
            Crossfade(targetState = selectedTab, animationSpec = tween(400)) { tab ->
                when (tab) {
                    0 -> PrioritizerScreen(iaPrioriseur, prioritizedTasks, onTasksChange)
                    1 -> JarvisScreen(chatMessages, googleAccount, onMessagesChange, onRefreshToken)
                    2 -> SettingsScreen(themeMode, isBriefingEnabled, googleAccount, onThemeChange, onBriefingToggle, onGoogleSignIn, onGoogleSignOut, onRequestNotifPermission, onRequestNotifAccess)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrioritizerScreen(iaPrioriseur: MlpPrioriseur, tasks: List<TaskItem>, onTasksChange: (List<TaskItem>) -> Unit) {
    var taskName by remember { mutableStateOf("") }
    var urgency by remember { mutableStateOf(5f) }
    var importance by remember { mutableStateOf(5f) }
    var duration by remember { mutableStateOf(5f) }
    var envy by remember { mutableStateOf(5f) }
    var energy by remember { mutableStateOf(5f) }
    
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("🧠 Cortex IA", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                Text("v${BuildConfig.VERSION_NAME}", fontSize = 14.sp, color = Color.Gray)
            }
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedTextField(value = taskName, onValueChange = { taskName = it }, label = { Text("Nouvelle tâche...") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true)
            Spacer(modifier = Modifier.height(16.dp))
            SliderRow("Urgence", urgency) { urgency = it }
            SliderRow("Importance", importance) { importance = it }
            SliderRow("Durée", duration) { duration = it }
            SliderRow("Envie", envy) { envy = it }
            SliderRow("Énergie", energy) { energy = it }
            Button(onClick = { if (taskName.isNotBlank()) { 
                val score = iaPrioriseur.forward(urgency.toDouble(), importance.toDouble(), duration.toDouble(), envy.toDouble(), energy.toDouble())
                val newList = (tasks + TaskItem(taskName, score * 100)).sortedByDescending { it.score }
                onTasksChange(newList)
                taskName = "" 
            } }, modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp), shape = RoundedCornerShape(16.dp)) { Text("Analyser la priorité") }
            Spacer(modifier = Modifier.height(24.dp))
            Text("Ma Liste de Priorités", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(8.dp))
        }
        items(tasks, key = { it.name + it.score }) { task ->
            AnimatedVisibility(visible = true, enter = slideInVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                TaskCard(task) { onTasksChange(tasks.filter { it != task }) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun JarvisScreen(
    messages: List<ChatMessage>, 
    googleAccount: GoogleSignInAccount?, 
    onMessagesChange: (List<ChatMessage>) -> Unit,
    onRefreshToken: suspend () -> String?
) {
    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp)) {
            Text("JARVIS", fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp, color = MaterialTheme.colorScheme.primary)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(messages) { msg ->
                val align = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart
                val bubbleColor = if (msg.isError) Color(0xFFD32F2F) 
                                 else if (msg.isUser) MaterialTheme.colorScheme.primary 
                                 else MaterialTheme.colorScheme.surfaceVariant
                val textColor = if (msg.isUser) Color.Black else MaterialTheme.colorScheme.onSurface
                
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = align) {
                    Surface(
                        color = bubbleColor,
                        shape = RoundedCornerShape(
                            topStart = 20.dp, 
                            topEnd = 20.dp, 
                            bottomStart = if (msg.isUser) 20.dp else 4.dp, 
                            bottomEnd = if (msg.isUser) 4.dp else 20.dp
                        ),
                        tonalElevation = 4.dp,
                        shadowElevation = 2.dp,
                        modifier = Modifier.widthIn(max = 300.dp).combinedClickable(
                            onLongClick = { onMessagesChange(messages.filter { it != msg }) },
                            onClick = {}
                        )
                    ) {
                        Text(msg.text, modifier = Modifier.padding(14.dp), color = textColor, fontSize = 16.sp, lineHeight = 22.sp)
                    }
                }
            }
            if (isLoading) { item { Text("Jarvis analyse...", color = Color.Gray, modifier = Modifier.padding(16.dp)) } }
        }
        Row(modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
            .navigationBarsPadding(), 
            verticalAlignment = Alignment.CenterVertically) {
            
            OutlinedTextField(
                value = input, 
                onValueChange = { input = it }, 
                modifier = Modifier.weight(1f), 
                placeholder = { Text("Parler à Jarvis...", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }, 
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                ),
                maxLines = 4
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Surface(
                onClick = {
                    if (input.isNotBlank() && !isLoading) {
                        val userMsg = input
                        val newMessages = messages + ChatMessage(userMsg, true)
                        onMessagesChange(newMessages)
                        input = ""
                        isLoading = true
                        coroutineScope.launch {
                            try {
                                val prefs = context.getSharedPreferences("CortexPrefs", Context.MODE_PRIVATE)
                                var token = prefs.getString("google_id_token", null)
                                
                                // Rafraîchissement automatique du token
                                val freshToken = onRefreshToken()
                                if (freshToken != null) token = freshToken

                                val name = googleAccount?.displayName ?: "Antoine"
                                val response = JarvisApiClient.apiService.sendMessage(ChatRequest(userMsg, token, name))
                                onMessagesChange(newMessages + ChatMessage(response.response ?: response.text ?: "Aucune réponse.", false))
                            } catch (e: Exception) {
                                val errorMsg = "Erreur de connexion au serveur."
                                onMessagesChange(newMessages + ChatMessage(errorMsg, false, isError = true))
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp),
                enabled = input.isNotBlank() && !isLoading
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Text("↑", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(themeMode: ThemeMode, isBriefingEnabled: Boolean, googleAccount: GoogleSignInAccount?, onThemeChange: (ThemeMode) -> Unit, onBriefingToggle: (Boolean) -> Unit, onGoogleSignIn: () -> Unit, onGoogleSignOut: () -> Unit, onRequestNotifPermission: () -> Unit, onRequestNotifAccess: () -> Unit) {
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
                        Text("Jarvis résume ta journée à 8h00", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(checked = isBriefingEnabled, onCheckedChange = { onBriefingToggle(it); if (it) onRequestNotifPermission() })
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRequestNotifAccess, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Autoriser la lecture des notifications") }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("Compte Google Workspace", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (googleAccount != null) {
                    Text("Connecté en tant que :", fontSize = 12.sp, color = Color.Gray)
                    Text(googleAccount.displayName ?: "Utilisateur Google", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(googleAccount.email ?: "", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onGoogleSignOut, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text("Se déconnecter") }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Jarvis a accès à votre Workspace.", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("Liez votre compte pour Gmail et Calendrier.", fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
                    Button(onClick = onGoogleSignIn, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Se connecter avec Google") }
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun TaskCard(task: TaskItem, onDelete: () -> Unit) {
    val colors = if (task.score >= 70) listOf(Color(0xFFFF5252), Color(0xFFFF1744)) else if (task.score >= 40) listOf(Color(0xFFFFB300), Color(0xFFFF8F00)) else listOf(Color(0xFF00E676), Color(0xFF00C853))
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(4.dp)) {
        Row(modifier = Modifier.background(Brush.horizontalGradient(colors)).padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(task.name, color = Color.White, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${task.score.toInt()}%", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) { Text("✅") }
            }
        }
    }
}

@Composable
fun SliderRow(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(40.dp)) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Slider(value = value, onValueChange = onValueChange, valueRange = 0f..10f, steps = 10, modifier = Modifier.weight(2f))
    }
}

@Composable
fun ThemeOptionRow(label: String, option: ThemeMode, current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = current == option, onClick = { onSelect(option) })
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
