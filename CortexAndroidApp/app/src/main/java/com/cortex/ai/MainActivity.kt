package com.cortex.ai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.location.Location
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- Modèles ---
// Redondances supprimées : ThemeMode, JarvisChatMessage, TaskItem définis dans Models.kt

class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var updateLocation: ((Double, Double) -> Unit)? = null

    private val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    requestLocation()
                } else {
                    Log.w("CortexAuth", "Permission refusée")
                }
            }

    private fun requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    updateLocation?.invoke(it.latitude, it.longitude)
                }
            }
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private val googleSignInLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(ApiException::class.java)
                    handleSignInResult(account)
                } catch (e: ApiException) {
                    Log.e("CortexAuth", "Google Sign-In failed: ${e.statusCode}")
                    android.widget.Toast.makeText(
                                    this,
                                    "Erreur Google: ${e.statusCode}",
                                    android.widget.Toast.LENGTH_LONG
                            )
                            .show()
                }
            }

    private var onAuthSuccess: ((String) -> Unit)? = null

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) 
        // Si l'intent contient une notification, on peut déclencher un rafraîchissement
        intent?.getStringExtra("notif_title")?.let { title ->
            intent.getStringExtra("notif_message")?.let { message ->
                // On pourra passer ça à l'état Compose via une callback ou un LiveData si besoin
                Log.d("CortexNotif", "Nouvelle notification reçue via Intent: $title")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        requestLocation()
        
        // Activation de l'anticipation proactive
        toggleAnticipationWorker(true)

        // Demande de permission notification pour Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        val iaPrioriseur = MlpPrioriseur()

        val prefs = getSharedPreferences("CortexPrefs", Context.MODE_PRIVATE)
        val savedTheme =
                prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        val briefingEnabled = prefs.getBoolean("briefing_enabled", false)

        val chatHistory = prefs.getString("chat_history", null)
        val initialMessages =
                if (chatHistory != null) {
                    try {
                        Gson().fromJson<List<JarvisChatMessage>>(
                                        chatHistory,
                                        object : TypeToken<List<JarvisChatMessage>>() {}.type
                                )
                    } catch (e: Exception) {
                        listOf(JarvisChatMessage("Bonjour Antoine. Les systèmes sont en ligne.", false))
                    }
                } else listOf(JarvisChatMessage("Bonjour Antoine. Les systèmes sont en ligne.", false))

        val savedTasks = prefs.getString("prioritized_tasks", null)
        val initialTasks =
                if (savedTasks != null) {
                    try {
                        Gson().fromJson<List<TaskItem>>(
                                        savedTasks,
                                        object : TypeToken<List<TaskItem>>() {}.type
                                )
                    } catch (e: Exception) {
                        listOf<TaskItem>()
                    }
                } else listOf<TaskItem>()

        val lastAccount = GoogleSignIn.getLastSignedInAccount(this)

        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.valueOf(savedTheme)) }
            var isBriefingEnabled by remember { mutableStateOf(briefingEnabled) }
            var JarvisChatMessages by remember { mutableStateOf(initialMessages) }
            var prioritizedTasks by remember { mutableStateOf(initialTasks) }
            var googleAccount by remember { mutableStateOf(lastAccount) }
            var activeNotification by remember {
                mutableStateOf<JarvisNotification?>(null)
            }
            
            // Heure du briefing
            val savedHour = prefs.getInt("briefing_hour", 8)
            val savedMinute = prefs.getInt("briefing_minute", 0)
            var briefingHour by remember { mutableStateOf(savedHour) }
            var briefingMinute by remember { mutableStateOf(savedMinute) }


            // Surveillance des changements d'Intent pour afficher les notifications cliquées
            LaunchedEffect(intent) {
                intent?.getStringExtra("notif_title")?.let { title ->
                    intent?.getStringExtra("notif_message")?.let { message ->
                        activeNotification = JarvisNotification(title, message, "")
                        // Nettoyage de l'intent pour éviter de réafficher la notif au pivotement
                        intent.removeExtra("notif_title")
                        intent.removeExtra("notif_message")
                    }
                }
            }
            
            var currentLatitude by remember { mutableStateOf<Double?>(null) }
            var currentLongitude by remember { mutableStateOf<Double?>(null) }
            
            updateLocation = { lat, lng ->
                currentLatitude = lat
                currentLongitude = lng
            }

            // Polling des notifications proactives
            LaunchedEffect(googleAccount) {
                while (true) {
                    try {
                        val notifResponse = JarvisApiClient.apiService.getNotifications()
                        val notifs = notifResponse.notifications
                        if (notifs.isNotEmpty()) {
                            notifs.forEach { notif ->
                                showNativeNotification(this@MainActivity, notif.title, notif.message)
                                // Ajout automatique à la discussion
                                val chatNotif = JarvisChatMessage(text = "🔔 **${notif.title}**\n${notif.message}", isUser = false, isError = false)
                                JarvisChatMessages = JarvisChatMessages + chatNotif
                            }
                            JarvisApiClient.apiService.clearNotifications()
                        }
                    } catch (e: Exception) {
                        Log.e("JarvisPolling", "Erreur polling: ${e.message}")
                    }
                    kotlinx.coroutines.delay(30000) // 30 secondes
                }
            }

            // Rafraîchissement automatique au démarrage
            LaunchedEffect(googleAccount) {
                googleAccount?.let { account ->
                    val token = getFreshAccessToken(account)
                    if (token != null) {
                        prefs.edit().putString("google_id_token", token).apply()
                        Log.d("CortexAuth", "Token rafraîchi automatiquement au démarrage")
                    }
                }
            }

            onAuthSuccess = { googleAccount = GoogleSignIn.getLastSignedInAccount(this) }

            val isDark =
                    when (themeMode) {
                        ThemeMode.LIGHT -> false
                        ThemeMode.DARK -> true
                        ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    }

            val darkColors =
                    darkColorScheme(
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

            val lightColors =
                    lightColorScheme(
                            primary = Color(0xFF4F46E5), // Indigo Moderne
                            secondary = Color(0xFF7C3AED), // Violet
                            background = Color(0xFFF9FAFB), // Blanc cassé
                            surface = Color.White,
                            onPrimary = Color.White,
                            onBackground = Color(0xFF111827),
                            onSurface = Color(0xFF1F2937),
                            primaryContainer = Color(0xFFEEF2FF), // Fond bulle utilisateur
                            surfaceVariant = Color(0xFFF3F4F6) // Fond bulle Jarvis
                    )

            val colorScheme = if (isDark) darkColors else lightColors

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                            iaPrioriseur = iaPrioriseur,
                            themeMode = themeMode,
                            isBriefingEnabled = isBriefingEnabled,
                            JarvisChatMessages = JarvisChatMessages,
                            prioritizedTasks = prioritizedTasks,
                            googleAccount = googleAccount,
                            lat = currentLatitude,
                            lng = currentLongitude,
                            onThemeChange = {
                                themeMode = it
                                prefs.edit().putString("theme_mode", it.name).apply()
                            },
                            onBriefingToggle = {
                                isBriefingEnabled = it
                                prefs.edit().putBoolean("briefing_enabled", it).apply()
                                if (it) {
                                    toggleBriefingWorker(true, briefingHour, briefingMinute)
                                } else {
                                    toggleBriefingWorker(false)
                                }
                            },

                            onMessagesChange = {
                                JarvisChatMessages = it
                                prefs.edit().putString("chat_history", Gson().toJson(it)).apply()
                            },
                            onTasksChange = {
                                prioritizedTasks = it
                                prefs.edit()
                                        .putString("prioritized_tasks", Gson().toJson(it))
                                        .apply()
                            },
                            onGoogleSignIn = { startGoogleSignIn() },
                            onGoogleSignOut = { signOutGoogle() },
                            onRequestNotifPermission = { checkAndRequestNotifPermission() },
                            onRequestNotifAccess = { openNotificationAccessSettings() },
                            onRefreshToken = { googleAccount?.let { getFreshAccessToken(it) } },
                            briefingHour = briefingHour,
                            briefingMinute = briefingMinute,
                            onBriefingTimeChange = { h, m ->
                                prefs.edit().putInt("briefing_hour", h).putInt("briefing_minute", m).apply()
                                briefingHour = h
                                briefingMinute = m
                                if (isBriefingEnabled) {
                                    toggleBriefingWorker(true, h, m)
                                }
                            },
                            onImpromptuBriefing = { triggerImpromptuBriefing() }
                    )

                }
            }
        }
    }

    private fun startGoogleSignIn() {
        val gso =
                GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestEmail()
                        .requestProfile()
                        .requestScopes(
                                Scope("https://www.googleapis.com/auth/gmail.readonly"),
                                Scope("https://www.googleapis.com/auth/calendar.readonly"),
                                Scope("https://www.googleapis.com/auth/gmail.modify"),
                                Scope("https://www.googleapis.com/auth/documents"),
                                Scope("https://www.googleapis.com/auth/drive.file")
                        )
                        .requestIdToken(
                                "449010615326-bn7o6ek8jau2kphiaof04nptkbvntokl.apps.googleusercontent.com"
                        )
                        .build()
        val client = GoogleSignIn.getClient(this, gso)
        googleSignInLauncher.launch(client.signInIntent)
    }

    private fun handleSignInResult(account: GoogleSignInAccount?) {
        if (account != null) {
            val scope =
                    "oauth2:https://www.googleapis.com/auth/gmail.modify https://www.googleapis.com/auth/calendar"

            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val token =
                            com.google.android.gms.auth.GoogleAuthUtil.getToken(
                                    this@MainActivity,
                                    account.account!!,
                                    scope
                            )

                    val prefs = getSharedPreferences("CortexPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("google_id_token", token).apply()
                    prefs.edit().putString("user_name", account.displayName ?: "Antoine").apply()

                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(
                                        this@MainActivity,
                                        "Accès Workspace activé !",
                                        android.widget.Toast.LENGTH_SHORT
                                )
                                .show()
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun toggleBriefingWorker(enabled: Boolean, hour: Int = 8, minute: Int = 0) {
        val workManager = WorkManager.getInstance(this)
        if (enabled) {
            val calendar = java.util.Calendar.getInstance()
            val now = calendar.timeInMillis
            
            calendar.set(java.util.Calendar.HOUR_OF_DAY, hour)
            calendar.set(java.util.Calendar.MINUTE, minute)
            calendar.set(java.util.Calendar.SECOND, 0)
            
            if (calendar.timeInMillis <= now) {
                calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            
            val delay = calendar.timeInMillis - now
            
            val briefingRequest =
                    PeriodicWorkRequestBuilder<BriefingWorker>(24, TimeUnit.HOURS)
                            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                            .addTag("morning_briefing")
                            .build()
            workManager.enqueueUniquePeriodicWork(
                    "morning_briefing",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    briefingRequest
            )
            Log.d("JarvisBriefing", "Briefing programmé à ${hour}h${minute} (Délai: ${delay/60000} min)")
        } else {
            workManager.cancelUniqueWork("morning_briefing")
        }
    }

    private fun triggerImpromptuBriefing() {
        val workManager = WorkManager.getInstance(this)
        val immediateRequest = OneTimeWorkRequestBuilder<BriefingWorker>()
            .addTag("impromptu_briefing")
            .build()
        workManager.enqueue(immediateRequest)
        android.widget.Toast.makeText(this, "⚡ Jarvis prépare ton briefing impromptu...", android.widget.Toast.LENGTH_SHORT).show()
    }


    private fun toggleAnticipationWorker(enabled: Boolean) {
        val workManager = WorkManager.getInstance(this)
        if (enabled) {
            val anticipateRequest =
                    PeriodicWorkRequestBuilder<AnticipationWorker>(1, TimeUnit.HOURS)
                            .setInitialDelay(5, TimeUnit.MINUTES)
                            .addTag("jarvis_anticipation")
                            .build()
            workManager.enqueueUniquePeriodicWork(
                    "jarvis_anticipation",
                    ExistingPeriodicWorkPolicy.KEEP, // Garder si déjà existant
                    anticipateRequest
            )
            Log.d("JarvisAnticipate", "Moteur d'anticipation programmé (1h)")
        } else {
            workManager.cancelUniqueWork("jarvis_anticipation")
        }
    }

    private suspend fun getFreshAccessToken(account: GoogleSignInAccount): String? =
            withContext(Dispatchers.IO) {
                try {
                    val scope =
                            "oauth2:https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/calendar.readonly https://www.googleapis.com/auth/gmail.modify https://www.googleapis.com/auth/documents https://www.googleapis.com/auth/drive.file"
                    // Clear current token to force refresh if needed
                    val prefs = getSharedPreferences("CortexPrefs", Context.MODE_PRIVATE)
                    val currentToken = prefs.getString("google_id_token", null)
                    if (currentToken != null) {
                        com.google.android.gms.auth.GoogleAuthUtil.clearToken(
                                this@MainActivity,
                                currentToken
                        )
                    }
                    val newToken =
                            com.google.android.gms.auth.GoogleAuthUtil.getToken(
                                    this@MainActivity,
                                    account.account!!,
                                    scope
                            )
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
        iaPrioriseur: MlpPrioriseur,
        themeMode: ThemeMode,
        isBriefingEnabled: Boolean,
        JarvisChatMessages: List<JarvisChatMessage>,
        prioritizedTasks: List<TaskItem>,
        googleAccount: GoogleSignInAccount?,
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
        onImpromptuBriefing: () -> Unit
) {

    var selectedTab by remember { mutableStateOf(0) }

    var activeNotification by remember { mutableStateOf<JarvisNotification?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Polling pour les notifications proactives
    LaunchedEffect(googleAccount) {
        if (googleAccount != null) {
            while (true) {
                try {
                    val response = JarvisApiClient.apiService.getNotifications()
                    if (response.notifications.isNotEmpty()) {
                        response.notifications.forEach { notif ->
                            showNativeNotification(context, notif.title, notif.message)
                            // On pourrait aussi les afficher dans la liste de chat si besoin
                        }
                        // Une fois reçues, on demande au serveur de les effacer
                        JarvisApiClient.apiService.clearNotifications()
                    }
                } catch (e: Exception) {
                    Log.e("CortexNotif", "Erreur polling: ${e.message}")
                }
                kotlinx.coroutines.delay(30000) // Toutes les 30 secondes
            }
        }
    }

    var updateUrl by remember { mutableStateOf<String?>(null) }
    
    // Vérification des mises à jour au démarrage
    LaunchedEffect(Unit) {
        try {
            val release = JarvisApiClient.githubService.getLatestRelease()
            val latestVersion = release.tag_name.replace("v", "")
            val currentVersion = BuildConfig.VERSION_NAME
            
            if (latestVersion != currentVersion) {
                updateUrl = release.html_url
            }
        } catch (e: Exception) {
            Log.e("JarvisUpdate", "Erreur check maj: ${e.message}")
        }
    }

    if (updateUrl != null) {
        AlertDialog(
            onDismissRequest = { updateUrl = null },
            title = { Text("Mise à jour disponible 🎉") },
            text = { Text("Une nouvelle version de Cortex AI est disponible sur GitHub !") },

            confirmButton = {
                Button(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)))
                        updateUrl = null
                    }
                ) { Text("Mettre à jour") }
            },
            dismissButton = {
                TextButton(onClick = { updateUrl = null }) { Text("Plus tard") }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = if (isSystemInDarkTheme())
                            listOf(Color(0xFF0F111A), Color(0xFF1A1D2E))
                        else
                            listOf(Color(0xFFF9FAFB), Color(0xFFF3F4F6))
                    )
                )
        ) {
            // Contenu Principal avec Crossfade
            Crossfade(
                targetState = selectedTab, 
                animationSpec = tween(400),
                modifier = Modifier.padding(bottom = 88.dp) // Espace pour le dock
            ) { tab ->
                when (tab) {
                    0 -> PrioritizerScreen(iaPrioriseur, prioritizedTasks, onTasksChange, onImpromptuBriefing)
                    1 -> JarvisScreen(JarvisChatMessages, googleAccount, lat, lng, onMessagesChange, onRefreshToken)
                    2 -> SettingsScreen(
                        themeMode,
                        isBriefingEnabled,
                        googleAccount,
                        onThemeChange,
                        onBriefingToggle,
                        onGoogleSignIn,
                        onGoogleSignOut,
                        onRequestNotifPermission,
                        onRequestNotifAccess,
                        briefingHour,
                        briefingMinute,
                        onBriefingTimeChange
                    )
                }
            }

            // Écran de détail de notification (Overlay)
            activeNotification?.let { notif ->
                NotificationDetailScreen(
                    notification = notif,
                    onDismiss = { activeNotification = null }
                )
            }

            // --- DOCK FLOTTANT PREMIUM ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 20.dp)
                    .navigationBarsPadding()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    shape = RoundedCornerShape(36.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    tonalElevation = 8.dp,
                    shadowElevation = 16.dp,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.Gray.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val navItems = listOf("Focus", "Jarvis", "Profil")
                        val navIcons = listOf("🧠", "🤖", "⚙️")
                        
                        navItems.forEachIndexed { index, label ->
                            val isSelected = selectedTab == index
                            val color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                            
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null,
                                        onClick = { selectedTab = index }
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                // Petit point indicateur actif
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = isSelected,
                                    enter = fadeIn() + expandVertically(),
                                    exit = fadeOut() + shrinkVertically()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 20.dp, height = 3.dp)
                                            .background(color, RoundedCornerShape(2.dp))
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(4.dp))
                                
                                Text(
                                    navIcons[index], 
                                    fontSize = if (isSelected) 22.sp else 20.sp
                                )
                                
                                Text(
                                    label, 
                                    fontSize = 11.sp, 
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = color
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}



@Composable
fun NotificationDetailScreen(
    notification: JarvisNotification,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "🤖 Jarvis vous informe",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    notification.title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    notification.message,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Compris, merci Jarvis")
                }
            }
        }
    }
}

private fun showNativeNotification(context: Context, title: String, message: String) {
    val channelId = "jarvis_notifications"
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("notif_title", title)
        putExtra("notif_message", message)
    }

    val pendingIntent = PendingIntent.getActivity(
        context, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title)
        .setContentText(message)
        .setStyle(androidx.core.app.NotificationCompat.BigTextStyle()
            .bigText(message)
            .setBigContentTitle(title)
            .setSummaryText("Alerte Jarvis"))
        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_EVENT)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()


    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        val channel = NotificationChannel(channelId, "Alertes Jarvis", NotificationManager.IMPORTANCE_HIGH)
        notificationManager.createNotificationChannel(channel)
    }
    
    notificationManager.notify(System.currentTimeMillis().toInt(), notification)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrioritizerScreen(
        iaPrioriseur: MlpPrioriseur,
        tasks: List<TaskItem>,
        onTasksChange: (List<TaskItem>) -> Unit,
        onImpromptuBriefing: () -> Unit
) {

    var taskName by remember { mutableStateOf("") }
    var urgency by remember { mutableStateOf(5f) }
    var importance by remember { mutableStateOf(5f) }
    var duration by remember { mutableStateOf(5f) }
    var envy by remember { mutableStateOf(5f) }
    var energy by remember { mutableStateOf(5f) }

    LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                        "🧠 Cortex IA",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                )
                Text("v${BuildConfig.VERSION_NAME}", fontSize = 14.sp, color = Color.Gray)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onImpromptuBriefing() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                shape = RoundedCornerShape(20.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚡", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Briefing Impromptu", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
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
            SliderRow("Durée", duration) { duration = it }
            SliderRow("Envie", envy) { envy = it }
            SliderRow("Énergie", energy) { energy = it }
            Button(
                    onClick = {
                        if (taskName.isNotBlank()) {
                            val score =
                                    iaPrioriseur.forward(
                                            urgency.toDouble(),
                                            importance.toDouble(),
                                            duration.toDouble(),
                                            envy.toDouble(),
                                            energy.toDouble()
                                    )
                            val newList =
                                    (tasks + TaskItem(taskName, score * 100)).sortedByDescending {
                                        it.score
                                    }
                            onTasksChange(newList)
                            taskName = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎯", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Analyser la priorité", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("Ma Liste de Priorités", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(8.dp))
        }
        items(tasks, key = { it.name + it.score }) { task ->
            AnimatedVisibility(
                    visible = true,
                    enter = slideInVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
            ) { TaskCard(task) { onTasksChange(tasks.filter { it != task }) } }
        }
    }
}

@Composable
fun SliderRow(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    value.toInt().toString(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
        Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = 0f..10f,
                steps = 9,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )
        )
    }
}

@OptIn(
        ExperimentalMaterial3Api::class,
        androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
fun JarvisScreen(
        messages: List<JarvisChatMessage>,
        googleAccount: GoogleSignInAccount?,
        lat: Double?,
        lng: Double?,
        onMessagesChange: (List<JarvisChatMessage>) -> Unit,
        onRefreshToken: suspend () -> String?
) {
    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()

    // --- Gestion des Threads ---
    var currentThreadId by remember { mutableStateOf("main") }
    var threads by remember { mutableStateOf(listOf("main")) }
    var showSidebar by remember { mutableStateOf(false) }
    var showNewThreadDialog by remember { mutableStateOf(false) }
    var newThreadName by remember { mutableStateOf("") }
    // Messages par thread (stockés localement pour éviter de tout recharger)
    var threadMessages by remember { mutableStateOf(mapOf("main" to messages)) }
    val currentMessages = threadMessages[currentThreadId] ?: emptyList()

    // Synchronisation du thread "main" avec les messages partagés
    LaunchedEffect(messages) {
        threadMessages = threadMessages.toMutableMap().also { it["main"] = messages }
    }

    // Chargement des threads disponibles depuis l'API
    LaunchedEffect(Unit) {
        try {
            val response = JarvisApiClient.apiService.getThreads()
            threads = (response.threads + "briefing").distinct()

        } catch (e: Exception) {
            Log.e("JarvisThreads", "Erreur chargement threads: ${e.message}")
        }
    }

    // Chargement de l'historique quand on change de thread
    LaunchedEffect(currentThreadId) {
        isLoading = true
        try {
            val userName = googleAccount?.displayName ?: "Antoine"
            val response = JarvisApiClient.apiService.getHistory(currentThreadId, userName)
            val updated = threadMessages.toMutableMap()
            updated[currentThreadId] = response.history.map { 
                JarvisChatMessage(text = it.text, isUser = it.isUser, isError = false)
            }
            threadMessages = updated
        } catch (e: Exception) {
            Log.e("JarvisHistory", "Erreur chargement historique: ${e.message}")
        } finally {
            isLoading = false
        }
    }


    // Auto-scroll au dernier message
    LaunchedEffect(currentMessages.size) {
        if (currentMessages.isNotEmpty()) listState.animateScrollToItem(currentMessages.size - 1)
    }


    // Couleur d'accent selon le thread (main = bleu primaire, autres = violet/orange)
    val threadColor = if (currentThreadId == "main")
        MaterialTheme.colorScheme.primary
    else Color(0xFF9575CD)

    // Dialogue de création de nouveau thread
    if (showNewThreadDialog) {
        AlertDialog(
            onDismissRequest = { showNewThreadDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { 
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("🚀 Nouveau Canal", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Créez un espace de discussion dédié", fontSize = 12.sp, color = Color.Gray)
                }
            },
            text = {
                OutlinedTextField(
                    value = newThreadName,
                    onValueChange = { newThreadName = it },
                    placeholder = { Text("Ex: Projet NSI, Sport...") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = threadColor,
                        unfocusedBorderColor = Color.LightGray
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newThreadName.isNotBlank()) {
                            val threadId = newThreadName.lowercase().replace(" ", "_")
                            threads = (threads + threadId).distinct()
                            threadMessages = threadMessages.toMutableMap().also {
                                it[threadId] = emptyList()
                            }
                            currentThreadId = threadId
                            newThreadName = ""
                            showNewThreadDialog = false
                            showSidebar = false
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = threadColor)
                ) { Text("Créer l'espace", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showNewThreadDialog = false }) { Text("Plus tard", color = Color.Gray) }
            }
        )
    }


    Box(modifier = Modifier.fillMaxSize()) {
        // --- CHAT PRINCIPAL ---
        Column(modifier = Modifier.fillMaxSize()) {

            // En-tête avec le nom du thread actif
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(
                                threadColor.copy(alpha = 0.15f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Bouton sidebar
                    Surface(
                        onClick = { showSidebar = !showSidebar },
                        shape = RoundedCornerShape(10.dp),
                        color = threadColor.copy(alpha = 0.15f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(if (showSidebar) "✕" else "☰", fontSize = 18.sp, color = threadColor)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    // Titre et Description
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text(
                            if (currentThreadId == "main") "JARVIS" else currentThreadId.replace("_", " ").uppercase(),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 4.sp,
                            color = threadColor
                        )
                        Text(
                            if (currentThreadId == "main") "Assistant IA Personnel" else "Canal spécialisé",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.Gray,
                            letterSpacing = 1.sp
                        )
                    }

                }
            }

            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }
                items(currentMessages) { msg ->
                    val align = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart
                    
                    // Dégradés premium pour les bulles
                    val bubbleBrush = if (msg.isUser) {
                        Brush.linearGradient(listOf(threadColor, threadColor.copy(alpha = 0.85f)))
                    } else {
                        Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)))
                    }
                    
                    val textColor = if (msg.isUser) Color.White else MaterialTheme.colorScheme.onSurface

                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = align) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 310.dp)
                                .padding(vertical = 2.dp)
                                .shadow(elevation = 4.dp, shape = RoundedCornerShape(
                                    topStart = 24.dp, topEnd = 24.dp,
                                    bottomStart = if (msg.isUser) 24.dp else 4.dp,
                                    bottomEnd = if (msg.isUser) 4.dp else 24.dp
                                ))
                                .background(
                                    brush = bubbleBrush,
                                    shape = RoundedCornerShape(
                                        topStart = 24.dp, topEnd = 24.dp,
                                        bottomStart = if (msg.isUser) 24.dp else 4.dp,
                                        bottomEnd = if (msg.isUser) 4.dp else 24.dp
                                    )
                                )
                                .border(
                                    width = 0.5.dp,
                                    brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.2f), Color.Gray.copy(alpha = 0.1f))),
                                    shape = RoundedCornerShape(
                                        topStart = 24.dp, topEnd = 24.dp,
                                        bottomStart = if (msg.isUser) 24.dp else 4.dp,
                                        bottomEnd = if (msg.isUser) 4.dp else 24.dp
                                    )
                                )
                                .combinedClickable(
                                    onLongClick = {
                                        val updated = threadMessages.toMutableMap()
                                        updated[currentThreadId] = currentMessages.filter { it != msg }
                                        threadMessages = updated
                                    },
                                    onClick = {}
                                )
                        ) {
                            Text(
                                msg.text,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                                color = textColor,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }


                if (isLoading) {
                    item {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = threadColor)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Jarvis réfléchit...", color = Color.Gray, fontSize = 14.sp)
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }

            // Barre de saisie "Flottante"
            Surface(
                modifier = Modifier
                    .padding(16.dp)
                    .navigationBarsPadding()
                    .fillMaxWidth(),
                shape = RoundedCornerShape(32.dp),
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                if (currentThreadId == "main") "Demander quelque chose..."
                                else "Message dans ${currentThreadId.replace("_", " ")}...",
                                color = Color.Gray
                            )
                        },
                        shape = RoundedCornerShape(28.dp),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            containerColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent
                        ),
                        maxLines = 6
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Surface(
                        onClick = {
                            if (input.isNotBlank() && !isLoading) {
                                val userMsg = input
                                val updatedWithUser = currentMessages + JarvisChatMessage(text = userMsg, isUser = true, isError = false)
                                val updated = threadMessages.toMutableMap()
                                updated[currentThreadId] = updatedWithUser
                                threadMessages = updated
                                if (currentThreadId == "main") onMessagesChange(updatedWithUser)
                                input = ""
                                isLoading = true
                                coroutineScope.launch {
                                    try {
                                        val prefs = context.getSharedPreferences("CortexPrefs", Context.MODE_PRIVATE)
                                        var token = prefs.getString("google_id_token", null)
                                        val freshToken = onRefreshToken()
                                        if (freshToken != null) token = freshToken
                                        val name = googleAccount?.displayName ?: "Antoine"
                                        val response = JarvisApiClient.apiService.sendMessage(
                                            ChatRequest(userMsg, token, name, lat, lng, currentThreadId)
                                        )
                                        val rawText = response.response ?: response.text ?: "Aucune réponse."
                                        val cleanText = rawText.replace(Regex("\\[CONTEXTE.*?\\]\\n?", RegexOption.DOT_MATCHES_ALL), "").trim()
                                        
                                        val jarvisMsg = JarvisChatMessage(text = cleanText, isUser = false, isError = false)
                                        val withResponse = updatedWithUser + jarvisMsg
                                        val updatedFinal = threadMessages.toMutableMap()
                                        updatedFinal[currentThreadId] = withResponse
                                        threadMessages = updatedFinal
                                        if (currentThreadId == "main") onMessagesChange(withResponse)
                                    } catch (e: Exception) {
                                        val errMsg = JarvisChatMessage("Erreur de connexion.", isUser = false, isError = true)
                                        val updatedErr = threadMessages.toMutableMap()
                                        updatedErr[currentThreadId] = updatedWithUser + errMsg
                                        threadMessages = updatedErr
                                        if (currentThreadId == "main") onMessagesChange(updatedWithUser + errMsg)
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }
                        },
                        shape = CircleShape,
                        color = threadColor,
                        modifier = Modifier.size(48.dp),
                        enabled = input.isNotBlank() && !isLoading
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Text("↑", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }


        // --- VOILE DE FOND (Scrim) ---
        androidx.compose.animation.AnimatedVisibility(
            visible = showSidebar,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { showSidebar = false }
            )
        }

        // --- SIDEBAR (Overlay) ---
        androidx.compose.animation.AnimatedVisibility(
            visible = showSidebar,
            enter = androidx.compose.animation.slideInHorizontally() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally() + androidx.compose.animation.fadeOut()
        ) {
            Surface(
                modifier = Modifier.width(260.dp).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 16.dp,
                tonalElevation = 8.dp
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text(
                        "🤖 Discussions",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    threads.forEach { threadId ->
                        val isSelected = threadId == currentThreadId
                        val icon = when {
                            threadId == "main" -> "🏠"
                            threadId.contains("briefing") -> "⚡"
                            threadId.contains("dev") -> "🛠️"
                            threadId.contains("nsi") -> "💻"
                            threadId.contains("projet") -> "🚀"
                            else -> "📌"
                        }
                        val displayName = if (threadId == "main") "Général" 
                                          else threadId.replace("_", " ").replaceFirstChar { it.uppercase() }

                        Surface(
                            onClick = {
                                currentThreadId = threadId
                                showSidebar = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) threadColor.copy(alpha = 0.2f)
                                    else Color.Transparent,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(icon, fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    displayName,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) threadColor else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }


                    Spacer(modifier = Modifier.weight(1f))

                    // Bouton "+" Créer un canal
                    Button(
                        onClick = { showNewThreadDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = threadColor)
                    ) {
                        Text("+ Nouveau canal")
                    }
                }
            }
        }
    }
}


@Composable
fun SettingsScreen(
        themeMode: ThemeMode,
        isBriefingEnabled: Boolean,
        googleAccount: GoogleSignInAccount?,
        onThemeChange: (ThemeMode) -> Unit,
        onBriefingToggle: (Boolean) -> Unit,
        onGoogleSignIn: () -> Unit,
        onGoogleSignOut: () -> Unit,
        onRequestNotifPermission: () -> Unit,
        onRequestNotifAccess: () -> Unit,
        briefingHour: Int,
        briefingMinute: Int,
        onBriefingTimeChange: (Int, Int) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {

        Text(
                "⚙️ Paramètres",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
        )
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
                        Text(
                                "Jarvis résume ta journée à 8h00",
                                fontSize = 12.sp,
                                color = Color.Gray
                        )
                    }
                    Switch(
                            checked = isBriefingEnabled,
                            onCheckedChange = {
                                onBriefingToggle(it)
                                if (it) {
                                    onRequestNotifPermission()
                                }
                            }
                    )

                }
                if (isBriefingEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            val timePicker = android.app.TimePickerDialog(context, { _, h, m ->
                                onBriefingTimeChange(h, m)
                            }, briefingHour, briefingMinute, true)
                            timePicker.show()
                        }
                    ) {
                        Text("Heure du briefing : ${String.format("%02d:%02d", briefingHour, briefingMinute)}", color = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                        onClick = onRequestNotifAccess,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                ) { Text("Autoriser la lecture des notifications") }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("Compte Google Workspace", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (googleAccount != null) {
                    Text("Connecté en tant que :", fontSize = 12.sp, color = Color.Gray)
                    Text(
                            googleAccount.displayName ?: "Utilisateur Google",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                    )
                    Text(googleAccount.email ?: "", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                            onClick = onGoogleSignOut,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors =
                                    ButtonDefaults.buttonColors(
                                            containerColor =
                                                    MaterialTheme.colorScheme.errorContainer,
                                            contentColor =
                                                    MaterialTheme.colorScheme.onErrorContainer
                                    )
                    ) { Text("Se déconnecter") }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                            "Jarvis a accès à votre Workspace.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                            "Liez votre compte pour Gmail et Calendrier.",
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Button(
                            onClick = onGoogleSignIn,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                    ) { Text("Se connecter avec Google") }
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun TaskCard(task: TaskItem, onDelete: () -> Unit) {
    val accentColor =
            if (task.score >= 70) Color(0xFFFF5252)
            else if (task.score >= 40) Color(0xFFFFB300)
            else Color(0xFF00E676)
            
    Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
        ) {
            // Barre de priorité sur le côté
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(6.dp)
                    .background(accentColor)
            )
            
            Row(
                modifier = Modifier.padding(16.dp).weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Priorité : ${task.score.toInt()}%", fontSize = 12.sp, color = Color.Gray)
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.background(accentColor.copy(alpha = 0.1f), CircleShape)
                ) { 
                    Text("✅", fontSize = 16.sp) 
                }
            }
        }
    }
}




@Composable
fun ThemeOptionRow(
        label: String,
        option: ThemeMode,
        current: ThemeMode,
        onSelect: (ThemeMode) -> Unit
) {
    Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = current == option, onClick = { onSelect(option) })
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
