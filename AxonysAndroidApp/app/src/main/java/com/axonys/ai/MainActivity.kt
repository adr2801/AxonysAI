package com.axonys.ai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.blur
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- Modèles ---
// Redondances supprimées : ThemeMode, JarvisChatMessage, TaskItem définis dans Models.kt

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

        private lateinit var fusedLocationClient: FusedLocationProviderClient
        private var updateLocation: ((Double, Double) -> Unit)? = null

        private val requestPermissionLauncher =
                registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                        permissions ->
                        val allGranted = permissions.entries.all { it.value }
                        if (allGranted) {
                                requestLocation()
                        } else {
                                Log.w("AxonysAuth", "Certaines permissions ont été refusées")
                        }
                }

        private fun requestLocation() {
                if (ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                ) {
                        // LocationRequest pour des mises à jour périodiques fraîches
                        val locationRequest =
                                com.google.android.gms.location.LocationRequest.Builder(
                                                com.google.android.gms.location.Priority
                                                        .PRIORITY_HIGH_ACCURACY,
                                                30000L // toutes les 30 secondes
                                        )
                                        .setMinUpdateIntervalMillis(10000L)
                                        .build()

                        val locationCallback =
                                object : com.google.android.gms.location.LocationCallback() {
                                        override fun onLocationResult(
                                                result:
                                                        com.google.android.gms.location.LocationResult
                                        ) {
                                                val location = result.lastLocation ?: return
                                                Log.d(
                                                        "AxonysGPS",
                                                        "Position mise à jour: ${location.latitude}, ${location.longitude} (précision: ${location.accuracy}m)"
                                                )
                                                updateLocation?.invoke(
                                                        location.latitude,
                                                        location.longitude
                                                )
                                        }
                                }
                        fusedLocationClient.requestLocationUpdates(
                                locationRequest,
                                locationCallback,
                                android.os.Looper.getMainLooper()
                        )
                        // Aussi récupérer la dernière position connue immédiatement
                        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location?
                                ->
                                if (location != null) {
                                        Log.d(
                                                "AxonysGPS",
                                                "Dernière position connue: ${location.latitude}, ${location.longitude}"
                                        )
                                        updateLocation?.invoke(
                                                location.latitude,
                                                location.longitude
                                        )
                                }
                        }
                } else {
                        requestPermissionLauncher.launch(
                                arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                        )
                }
        }

        private val googleSignInLauncher =
                registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
                        ->
                        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                        try {
                                val account = task.getResult(ApiException::class.java)
                                handleSignInResult(account)
                        } catch (e: ApiException) {
                                Log.e("AxonysAuth", "Google Sign-In failed: ${e.statusCode}")
                                android.widget.Toast.makeText(
                                                this,
                                                "Erreur Google: ${e.statusCode}",
                                                android.widget.Toast.LENGTH_LONG
                                        )
                                        .show()
                        }
                }

        private var onAuthSuccess: ((String) -> Unit)? = null

        private val imagePickerLauncher =
                registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                        uri?.let { onImageSelected?.invoke(it) }
                }
        private var onImageSelected: ((Uri) -> Unit)? = null

        override fun onNewIntent(intent: Intent?) {
                super.onNewIntent(intent)
                setIntent(intent)
                // Si l'intent contient une notification, on peut déclencher un rafraîchissement
                intent?.getStringExtra("notif_title")?.let { title ->
                        intent.getStringExtra("notif_message")?.let { message ->
                                // On pourra passer ça à l'état Compose via une callback ou un
                                // LiveData si besoin
                                Log.d(
                                        "AxonysNotif",
                                        "Nouvelle notification reçue via Intent: $title"
                                )
        }
}

        override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)

                fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                val lastAccount = GoogleSignIn.getLastSignedInAccount(this)
                requestLocation()

                // Activation de l'anticipation proactive
                toggleAnticipationWorker(true)

                // Demande de permissions
                val permissionsToRequest = mutableListOf<String>()
                
                // Micro (Nécessaire pour la voix dès Android 6.0)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
                }
                
                // Notifications (Nécessaire à partir d'Android 13)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                
                if (permissionsToRequest.isNotEmpty()) {
                    requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
                }

                val iaPrioriseur = MlpPrioriseur()

                val prefs = getSharedPreferences("AxonysPrefs", Context.MODE_PRIVATE)
                val savedTheme =
                        prefs.getString("theme_mode", ThemeMode.SYSTEM.name)
                                ?: ThemeMode.SYSTEM.name
                val briefingEnabled = prefs.getBoolean("briefing_enabled", false)

                val initialUserId = lastAccount?.displayName?.lowercase()?.replace(" ", "_") ?: "antoine"
                val chatHistory =
                        prefs.getString("chat_history_${initialUserId}", null)
                val defaultGreeting =
                        "Bonjour ${lastAccount?.displayName?.split(" ")?.firstOrNull() ?: "Antoine"}. Les systèmes sont en ligne."
                val initialMessages =
                        if (chatHistory != null) {
                                try {
                                        Gson().fromJson<List<JarvisChatMessage>>(
                                                        chatHistory,
                                                        object :
                                                                        TypeToken<
                                                                                List<
                                                                                        JarvisChatMessage>>() {}
                                                                .type
                                                )
                                } catch (e: Exception) {
                                        listOf(JarvisChatMessage(defaultGreeting, false))
                                }
                        } else listOf(JarvisChatMessage(defaultGreeting, false))

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



                setContent {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        var themeMode by remember { mutableStateOf(ThemeMode.valueOf(savedTheme)) }
                        var isBriefingEnabled by remember { mutableStateOf(briefingEnabled) }
                        var JarvisChatMessages by remember { mutableStateOf(initialMessages) }
                        var prioritizedTasks by remember { mutableStateOf(initialTasks) }
                        var googleAccount by remember { mutableStateOf(lastAccount) }
                        val currentUserId =
                                remember(googleAccount) {
                                        googleAccount?.id ?: android.provider.Settings.Secure.getString(
                                                context.contentResolver,
                                                android.provider.Settings.Secure.ANDROID_ID
                                        ) ?: "default_device"
                                }
                        val currentUserName =
                                remember(googleAccount) {
                                        googleAccount?.displayName?.split(" ")?.firstOrNull()
                                                ?: "Antoine"
                                }
                        var activeNotification by remember {
                                mutableStateOf<JarvisNotification?>(null)
                        }
                        val coroutineScope = rememberCoroutineScope()
                        LaunchedEffect(currentUserId) {
                                prefs.edit().putString("user_id", currentUserId).apply()
                        }
                        val onPickImage: ((Uri) -> Unit) -> Unit = { callback ->
                                onImageSelected = callback
                                imagePickerLauncher.launch("image/*")
                        }

                        // Heure du briefing
                        val savedHour = prefs.getInt("briefing_hour", 8)
                        val savedMinute = prefs.getInt("briefing_minute", 0)
                        var briefingHour by remember { mutableStateOf(savedHour) }
                        var briefingMinute by remember { mutableStateOf(savedMinute) }
                        var isMemoryExplorerOpen by remember { mutableStateOf(false) }

                        // Surveillance des changements d'Intent pour afficher les notifications
                        // cliquées
                        LaunchedEffect(intent) {
                                intent?.getStringExtra("notif_title")?.let { title ->
                                        intent?.getStringExtra("notif_message")?.let { message ->
                                                activeNotification =
                                                        JarvisNotification(title, message, "")
                                                // Nettoyage de l'intent pour éviter de réafficher
                                                // la notif au pivotement
                                                intent.removeExtra("notif_title")
                                                intent.removeExtra("notif_message")
                                        }
                                }
                        }

                        var currentLatitude by remember { mutableStateOf<Double?>(null) }
                        var currentLongitude by remember { mutableStateOf<Double?>(null) }

                        // --- Synchronisation des Tâches avec Supabase ---
                        LaunchedEffect(googleAccount) {
                                try {
                                        val response =
                                                JarvisApiClient.apiService.getTasks(currentUserId)
                                        prioritizedTasks = response.tasks
                                } catch (e: Exception) {
                                        Log.e("JarvisTasks", "Erreur sync tâches: ${e.message}")
                                }
                        }

                        updateLocation = { lat, lng ->
                                currentLatitude = lat
                                currentLongitude = lng
                        }

                        var isAutoReadEnabled by remember {
                                mutableStateOf(prefs.getBoolean("auto_read_enabled", false))
                        }

                        // Polling des notifications proactives
                        LaunchedEffect(googleAccount) {
                                while (true) {
                                        try {
                                                val notifResponse =
                                                        JarvisApiClient.apiService.getNotifications(
                                                                currentUserId
                                                        )

                                                val notifs = notifResponse.notifications
                                                if (notifs.isNotEmpty()) {
                                                        notifs.forEach { notif ->
                                                                showNativeNotification(
                                                                        this@MainActivity,
                                                                        notif.title,
                                                                        notif.message
                                                                )
                                                                // Ajout automatique à la discussion
                                                                val chatNotif =
                                                                        JarvisChatMessage(
                                                                                text =
                                                                                        "🔔 **${notif.title}**\n${notif.message}",
                                                                                isUser = false,
                                                                                isError = false
                                                                        )
                                                                JarvisChatMessages =
                                                                        JarvisChatMessages +
                                                                                chatNotif
                                                        }
                                                        JarvisApiClient.apiService
                                                                .clearNotifications(currentUserId)
                                                }
                                        } catch (e: Exception) {
                                                Log.e(
                                                        "JarvisPolling",
                                                        "Erreur polling: ${e.message}"
                                                )
                                        }
                                        kotlinx.coroutines.delay(30000) // 30 secondes
                                }
                        }

                        // Rafraîchissement automatique au démarrage
                        LaunchedEffect(googleAccount) {
                                googleAccount?.let { account ->
                                        val token = getFreshAccessToken(account)
                                        if (token != null) {
                                                prefs.edit()
                                                        .putString("google_id_token", token)
                                                        .apply()
                                                Log.d(
                                                        "AxonysAuth",
                                                        "Token rafraîchi automatiquement au démarrage"
                                                )
                                        }
                                }
                        }

                        onAuthSuccess = {
                                googleAccount = GoogleSignIn.getLastSignedInAccount(this)
                        }

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
                                        primaryContainer =
                                                Color(0xFFEEF2FF), // Fond bulle utilisateur
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
                                                currentUserId = currentUserId,
                                                currentUserName = currentUserId,
                                                lat = currentLatitude,
                                                lng = currentLongitude,
                                                onThemeChange = {
                                                        themeMode = it
                                                        prefs.edit()
                                                                .putString("theme_mode", it.name)
                                                                .apply()
                                                },
                                                onBriefingToggle = {
                                                        isBriefingEnabled = it
                                                        prefs.edit()
                                                                .putBoolean("briefing_enabled", it)
                                                                .apply()
                                                        if (it) {
                                                                toggleBriefingWorker(
                                                                        true,
                                                                        briefingHour,
                                                                        briefingMinute
                                                                )
                                                        } else {
                                                                toggleBriefingWorker(false)
                                                        }
                                                },
                                                onMessagesChange = {
                                                        JarvisChatMessages = it
                                                        prefs.edit()
                                                                .putString(
                                                                        "chat_history_${currentUserId}",
                                                                        Gson().toJson(it)
                                                                )
                                                                .apply()
                                                },
                                                onTasksChange = { newList ->
                                                        val oldList = prioritizedTasks
                                                        prioritizedTasks = newList
                                                        prefs.edit()
                                                                .putString(
                                                                        "prioritized_tasks",
                                                                        Gson().toJson(newList)
                                                                )
                                                                .apply()

                                                        coroutineScope.launch(
                                                                kotlinx.coroutines.Dispatchers.IO
                                                        ) {
                                                                try {
                                                                        if (newList.size <
                                                                                        oldList.size
                                                                        ) {
                                                                                val deletedTask =
                                                                                        oldList
                                                                                                .find {
                                                                                                        old
                                                                                                        ->
                                                                                                        newList
                                                                                                                .none {
                                                                                                                        it.name ==
                                                                                                                                old.name
                                                                                                                }
                                                                                                }
                                                                                deletedTask?.let {
                                                                                        task ->
                                                                                        JarvisApiClient
                                                                                                .apiService
                                                                                                .deleteTask(
                                                                                                        currentUserId,
                                                                                                        mapOf(
                                                                                                                "id" to
                                                                                                                        task.id,
                                                                                                                "name" to
                                                                                                                        task.name
                                                                                                        )
                                                                                                )
                                                                                }
                                                                        } else if (newList.size >
                                                                                        oldList.size
                                                                        ) {
                                                                                newList.lastOrNull()
                                                                                        ?.let { task
                                                                                                ->
                                                                                                JarvisApiClient
                                                                                                        .apiService
                                                                                                        .addTask(
                                                                                                                currentUserId,
                                                                                                                TaskRequest(
                                                                                                                        name =
                                                                                                                                task.name
                                                                                                                                        ?: "Sans titre",
                                                                                                                        urgency =
                                                                                                                                5,
                                                                                                                        importance =
                                                                                                                                5,
                                                                                                                        duration =
                                                                                                                                5,
                                                                                                                        envy =
                                                                                                                                5,
                                                                                                                        energy =
                                                                                                                                5,
                                                                                                                        score =
                                                                                                                                task.score
                                                                                                                                        ?: 0.0,
                                                                                                                        status =
                                                                                                                                task.status
                                                                                                                                        ?: "pending"
                                                                                                                )
                                                                                                        )
                                                                                        }
                                                                        }
                                                                } catch (e: Exception) {
                                                                        Log.e(
                                                                                "JarvisSync",
                                                                                "Erreur sync task: ${e.message}"
                                                                        )
                                                                }
                                                        }
                                                },
                                                onGoogleSignIn = { startGoogleSignIn() },
                                                onGoogleSignOut = { signOutGoogle() },
                                                onRequestNotifPermission = {
                                                        checkAndRequestNotifPermission()
                                                },
                                                onRequestNotifAccess = {
                                                        openNotificationAccessSettings()
                                                },
                                                onRefreshToken = {
                                                        googleAccount?.let {
                                                                getFreshAccessToken(it)
                                                        }
                                                },
                                                briefingHour = briefingHour,
                                                briefingMinute = briefingMinute,
                                                onBriefingTimeChange = { h, m ->
                                                        prefs.edit()
                                                                .putInt("briefing_hour", h)
                                                                .putInt("briefing_minute", m)
                                                                .apply()
                                                        briefingHour = h
                                                        briefingMinute = m
                                                        if (isBriefingEnabled) {
                                                                toggleBriefingWorker(true, h, m)
                                                        }
                                                },
                                                onImpromptuBriefing = {
                                                        triggerImpromptuBriefing()
                                                },
                                                isMemoryExplorerOpen = isMemoryExplorerOpen,
                                                onMemoryExplorerToggle = {
                                                        isMemoryExplorerOpen = it
                                                },
                                                isAutoReadEnabled = isAutoReadEnabled,
                                                onAutoReadToggle = {
                                                        isAutoReadEnabled = it
                                                        prefs.edit()
                                                                .putBoolean("auto_read_enabled", it)
                                                                .apply()
                                                },
                                                onPickImage = onPickImage
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

                                        val prefs =
                                                getSharedPreferences(
                                                        "AxonysPrefs",
                                                        Context.MODE_PRIVATE
                                                )
                                        prefs.edit().putString("google_id_token", token).apply()
                                        prefs.edit()
                                                .putString(
                                                        "user_name",
                                                        account.displayName ?: "Antoine"
                                                )
                                                .apply()

                                        kotlinx.coroutines.withContext(
                                                kotlinx.coroutines.Dispatchers.Main
                                        ) {
                                                android.widget.Toast.makeText(
                                                                this@MainActivity,
                                                                "Accès Workspace activé !",
                                                                android.widget.Toast.LENGTH_SHORT
                                                        )
                                                        .show()
                                                onAuthSuccess?.invoke(account.displayName ?: "")
                                        }
                                } catch (e: Exception) {
                                        Log.e("AxonysAuth", "Erreur AccessToken: ${e.message}")
                                        if (e is
                                                        com.google.android.gms.auth.UserRecoverableAuthException
                                        ) {
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
                        val prefs = getSharedPreferences("AxonysPrefs", Context.MODE_PRIVATE)
                        prefs.edit().remove("google_id_token").apply()
                        recreate()
                }
        }

        private fun checkAndRequestNotifPermission() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                        ) {
                                requestPermissionLauncher.launch(
                                        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                                )
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
                                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                                briefingRequest
                        )
                        Log.d(
                                "JarvisBriefing",
                                "Briefing programmé à ${hour}h${minute} (Délai: ${delay/60000} min)"
                        )
                } else {
                        workManager.cancelUniqueWork("morning_briefing")
                }
        }

        private fun triggerImpromptuBriefing() {
                val workManager = WorkManager.getInstance(this)
                val immediateRequest =
                        OneTimeWorkRequestBuilder<BriefingWorker>()
                                .addTag("impromptu_briefing")
                                .build()
                workManager.enqueue(immediateRequest)
                android.widget.Toast.makeText(
                                this,
                                "⚡ Jarvis prépare ton briefing impromptu...",
                                android.widget.Toast.LENGTH_SHORT
                        )
                        .show()
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
                                val prefs =
                                        getSharedPreferences("AxonysPrefs", Context.MODE_PRIVATE)
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
                                Log.e("AxonysAuth", "Erreur refresh token: ${e.message}")
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
        onPickImage: ((Uri) -> Unit) -> Unit
) {

        val scope = rememberCoroutineScope()

        var selectedTab by remember { mutableStateOf(0) }

        var activeNotification by remember { mutableStateOf<JarvisNotification?>(null) }
        val context = androidx.compose.ui.platform.LocalContext.current

        // Polling pour les notifications proactives
        LaunchedEffect(googleAccount) {
                if (googleAccount != null) {
                        while (true) {
                                try {
                                        val response =
                                                JarvisApiClient.apiService.getNotifications(
                                                        currentUserId
                                                )
                                        if (response.notifications.isNotEmpty()) {
                                                response.notifications.forEach { notif ->
                                                        showNativeNotification(
                                                                context,
                                                                notif.title,
                                                                notif.message
                                                        )
                                                        // On pourrait aussi les afficher dans la
                                                        // liste de chat si besoin
                                                }
                                                // Une fois reçues, on demande au serveur de les
                                                // effacer
                                                JarvisApiClient.apiService.clearNotifications(
                                                        currentUserId
                                                )
                                        }
                                } catch (e: Exception) {
                                        Log.e("AxonysNotif", "Erreur polling: ${e.message}")
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
                        text = {
                                Text(
                                        "Une nouvelle version de Axonys AI est disponible sur GitHub !"
                                )
                        },
                        confirmButton = {
                                Button(
                                        onClick = {
                                                context.startActivity(
                                                        Intent(
                                                                Intent.ACTION_VIEW,
                                                                Uri.parse(updateUrl)
                                                        )
                                                )
                                                updateUrl = null
                                        }
                                ) { Text("Mettre à jour") }
                        },
                        dismissButton = {
                                TextButton(onClick = { updateUrl = null }) { Text("Plus tard") }
                        }
                )
        }

        Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                Box(
                        modifier =
                                Modifier.fillMaxSize()
                                        .background(
                                                Brush.verticalGradient(
                                                        colors =
                                                                if (isSystemInDarkTheme())
                                                                        listOf(
                                                                                Color(0xFF0F111A),
                                                                                Color(0xFF1A1D2E)
                                                                        )
                                                                else
                                                                        listOf(
                                                                                Color(0xFFF9FAFB),
                                                                                Color(0xFFF3F4F6)
                                                                        )
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
                                        0 ->
                                                PrioritizerScreen(
                                                        iaPrioriseur,
                                                        prioritizedTasks,
                                                        onTasksChange,
                                                        onImpromptuBriefing
                                                )
                                        1 ->
                                                JarvisScreen(
                                                        JarvisChatMessages,
                                                        googleAccount,
                                                        currentUserId,
                                                        currentUserName,
                                                        lat,
                                                        lng,
                                                        onMessagesChange,
                                                        onRefreshToken,
                                                        isAutoReadEnabled,
                                                        onAutoReadToggle,
                                                        onPickImage
                                                )
                                        2 ->
                                                SettingsScreen(
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
                                                        onBriefingTimeChange = onBriefingTimeChange,
                                                        onExploreMemory = {
                                                                onMemoryExplorerToggle(true)
                                                        }
                                                )
                                }
                        }

                        // Écran d'exploration de mémoire (Overlay)
                        if (isMemoryExplorerOpen) {
                                MemoryExplorerScreen(
                                        currentUserId = currentUserId,
                                        onDismiss = { onMemoryExplorerToggle(false) },
                                        onDeleteFact = { fact ->
                                                scope.launch {
                                                        try {
                                                                JarvisApiClient.apiService
                                                                        .deleteMemoryFact(
                                                                                DeleteMemoryRequest(
                                                                                        fact,
                                                                                        currentUserId
                                                                                )
                                                                        )
                                                        } catch (e: Exception) {
                                                                Log.e(
                                                                        "MemoryDelete",
                                                                        "Error: ${e.message}"
                                                                )
                                                        }
                                                }
                                        }
                                )
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
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .align(Alignment.BottomCenter)
                                                .padding(horizontal = 24.dp, vertical = 20.dp)
                                                .navigationBarsPadding()
                        ) {
                                Surface(
                                        modifier = Modifier.fillMaxWidth().height(72.dp),
                                        shape = RoundedCornerShape(36.dp),
                                        color =
                                                MaterialTheme.colorScheme.surface.copy(
                                                        alpha = 0.95f
                                                ),
                                        tonalElevation = 8.dp,
                                        shadowElevation = 16.dp,
                                        border =
                                                androidx.compose.foundation.BorderStroke(
                                                        0.5.dp,
                                                        Color.Gray.copy(alpha = 0.1f)
                                                )
                                ) {
                                        Row(
                                                modifier =
                                                        Modifier.fillMaxSize()
                                                                .padding(horizontal = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceEvenly,
                                                verticalAlignment = Alignment.CenterVertically
                                        ) {
                                                val navItems = listOf("Focus", "Jarvis", "Profil")
                                                val navIcons = listOf("🧠", "🤖", "⚙️")

                                                navItems.forEachIndexed { index, label ->
                                                        val isSelected = selectedTab == index
                                                        val color =
                                                                if (isSelected)
                                                                        MaterialTheme.colorScheme
                                                                                .primary
                                                                else Color.Gray

                                                        Column(
                                                                modifier =
                                                                        Modifier.weight(1f)
                                                                                .clickable(
                                                                                        interactionSource =
                                                                                                remember {
                                                                                                        androidx.compose
                                                                                                                .foundation
                                                                                                                .interaction
                                                                                                                .MutableInteractionSource()
                                                                                                },
                                                                                        indication =
                                                                                                null,
                                                                                        onClick = {
                                                                                                selectedTab =
                                                                                                        index
                                                                                        }
                                                                                ),
                                                                horizontalAlignment =
                                                                        Alignment
                                                                                .CenterHorizontally,
                                                                verticalArrangement =
                                                                        Arrangement.Center
                                                        ) {
                                                                // Petit point indicateur actif
                                                                androidx.compose.animation
                                                                        .AnimatedVisibility(
                                                                                visible =
                                                                                        isSelected,
                                                                                enter =
                                                                                        fadeIn() +
                                                                                                expandVertically(),
                                                                                exit =
                                                                                        fadeOut() +
                                                                                                shrinkVertically()
                                                                        ) {
                                                                                Box(
                                                                                        modifier =
                                                                                                Modifier.size(
                                                                                                                width =
                                                                                                                        20.dp,
                                                                                                                height =
                                                                                                                        3.dp
                                                                                                        )
                                                                                                        .background(
                                                                                                                color,
                                                                                                                RoundedCornerShape(
                                                                                                                        2.dp
                                                                                                                )
                                                                                                        )
                                                                                )
                                                                        }

                                                                Spacer(
                                                                        modifier =
                                                                                Modifier.height(
                                                                                        4.dp
                                                                                )
                                                                )

                                                                Text(
                                                                        navIcons[index],
                                                                        fontSize =
                                                                                if (isSelected)
                                                                                        22.sp
                                                                                else 20.sp
                                                                )

                                                                Text(
                                                                        label,
                                                                        fontSize = 11.sp,
                                                                        fontWeight =
                                                                                if (isSelected)
                                                                                        FontWeight
                                                                                                .Bold
                                                                                else
                                                                                        FontWeight
                                                                                                .Medium,
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
fun NotificationDetailScreen(notification: JarvisNotification, onDismiss: () -> Unit) {
        Box(
                modifier =
                        Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.9f))
                                .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
        ) {
                Surface(
                        modifier = Modifier.fillMaxWidth(0.85f).padding(24.dp),
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
                                ) { Text("Compris, merci Jarvis") }
                        }
                }
        }
}

private fun showNativeNotification(context: Context, title: String, message: String) {
        val channelId = "jarvis_notifications"
        val intent =
                Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("notif_title", title)
                        putExtra("notif_message", message)
                }

        val pendingIntent =
                PendingIntent.getActivity(
                        context,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

        val notification =
                androidx.core.app.NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setStyle(
                                androidx.core.app.NotificationCompat.BigTextStyle()
                                        .bigText(message)
                                        .setBigContentTitle(title)
                                        .setSummaryText("Alerte Jarvis")
                        )
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_EVENT)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .build()

        val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel =
                        NotificationChannel(
                                channelId,
                                "Alertes Jarvis",
                                NotificationManager.IMPORTANCE_HIGH
                        )
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
                                        "🧠 Axonys AI",
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                        "v${BuildConfig.VERSION_NAME}",
                                        fontSize = 14.sp,
                                        color = Color.Gray
                                )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                                onClick = { onImpromptuBriefing() },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                colors =
                                        ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.secondary
                                        ),
                                shape = RoundedCornerShape(20.dp),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                        ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("⚡", fontSize = 18.sp)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                                "Briefing Impromptu",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp
                                        )
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
                                                        (tasks +
                                                                        TaskItem(
                                                                                name = taskName,
                                                                                score = score * 100
                                                                        ))
                                                                .sortedByDescending {
                                                                        it.score ?: 0.0
                                                                }
                                                onTasksChange(newList)
                                                taskName = ""
                                        }
                                },
                                modifier =
                                        Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp),
                                shape = RoundedCornerShape(20.dp),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                        ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("🎯", fontSize = 18.sp)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                                "Analyser la priorité",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp
                                        )
                                }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                                "Ma Liste de Priorités",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                }
                items(
                        tasks,
                        key = { it.id ?: (it.name.hashCode() + (it.score ?: 0.0).hashCode()) }
                ) { task ->
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
                                        modifier =
                                                Modifier.padding(
                                                        horizontal = 8.dp,
                                                        vertical = 2.dp
                                                ),
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
                        colors =
                                SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor =
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
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
        currentUserId: String,
        currentUserName: String,
        lat: Double?,
        lng: Double?,
        onMessagesChange: (List<JarvisChatMessage>) -> Unit,
        onRefreshToken: suspend () -> String?,
        isAutoReadEnabled: Boolean,
        onAutoReadToggle: (Boolean) -> Unit,
        onPickImage: ((Uri) -> Unit) -> Unit
) {
        var input by remember { mutableStateOf("") }
        var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
        var selectedImageBase64 by remember { mutableStateOf<String?>(null) }
        var isLoading by remember { mutableStateOf(false) }
        var isModelLoading by remember { mutableStateOf(false) }
        var isModelLaunching by remember { mutableStateOf(false) }
        var isOptimizing by remember { mutableStateOf(false) }
        var isToolRunning by remember { mutableStateOf(false) }
        var runningToolName by remember { mutableStateOf<String?>(null) }
        var availableModes by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
        var currentMode by remember { mutableStateOf<String?>(null) }
        val coroutineScope = rememberCoroutineScope()
        val context = androidx.compose.ui.platform.LocalContext.current
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        val listState = rememberLazyListState()

        var isSpeaking by remember { mutableStateOf(false) }
        var isListening by remember { mutableStateOf(false) }

        val voiceAssistant = remember {
                VoiceAssistant(
                        context = context,
                        onSpeakStatusChanged = { isSpeaking = it },
                        onListeningStatusChanged = { isListening = it },
                        onResult = { recognizedText -> input = recognizedText }
                )
        }

        DisposableEffect(Unit) { onDispose { voiceAssistant.destroy() } }

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
        LaunchedEffect(currentUserId) {
                try {
                        val response = JarvisApiClient.apiService.getThreads(currentUserId)
                        threads = (response.threads + "briefing").distinct()
                } catch (e: Exception) {
                        Log.e("JarvisThreads", "Erreur chargement threads: ${e.message}")
                }
        }

        // Chargement de l'historique quand on change de thread
        LaunchedEffect(currentThreadId) {
                isLoading = true
                try {
                        val response =
                                JarvisApiClient.apiService.getHistory(
                                        currentThreadId,
                                        currentUserId
                                )

                        val updated = threadMessages.toMutableMap()
                        updated[currentThreadId] =
                                response.history.map {
                                        JarvisChatMessage(
                                                text = it.text,
                                                isUser = it.isUser,
                                                isError = false
                                        )
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
                if (currentMessages.isNotEmpty())
                        listState.animateScrollToItem(currentMessages.size - 1)
        }

        var currentSentiment by remember { mutableStateOf("CALM") }

        // Couleur d'accent dynamique : Gem > Sentiment > Défaut
        val gemColorHex = availableModes.find { it["name"] == currentMode }?.get("color")
        val gemColor =
                if (gemColorHex != null) Color(android.graphics.Color.parseColor(gemColorHex))
                else null

        val sentimentColor = when(currentSentiment) {
                "STRESS" -> Color(0xFFFF5252)    // Rouge vif
                "FATIGUE" -> Color(0xFFB39DDB)   // Lavande doux
                "ENTHUSIASM" -> Color(0xFF00E676) // Vert électrique
                else -> Color.Transparent
        }

        val threadColor = gemColor ?: (if (sentimentColor != Color.Transparent) sentimentColor else MaterialTheme.colorScheme.primary)

        // Dialogue de création de nouveau thread
        if (showNewThreadDialog) {
                AlertDialog(
                        onDismissRequest = { showNewThreadDialog = false },
                        shape = RoundedCornerShape(28.dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                        title = {
                                Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth()
                                ) {
                                        Text(
                                                "🚀 Nouveau Canal",
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 22.sp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                                "Créez un espace de discussion dédié",
                                                fontSize = 12.sp,
                                                color = Color.Gray
                                        )
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
                                        colors =
                                                TextFieldDefaults.outlinedTextFieldColors(
                                                        focusedBorderColor = threadColor,
                                                        unfocusedBorderColor = Color.LightGray
                                                )
                                )
                        },
                        confirmButton = {
                                Button(
                                        onClick = {
                                                if (newThreadName.isNotBlank()) {
                                                        val threadId =
                                                                newThreadName
                                                                        .lowercase()
                                                                        .replace(" ", "_")
                                                        threads = (threads + threadId).distinct()
                                                        threadMessages =
                                                                threadMessages.toMutableMap().also {
                                                                        it[threadId] = emptyList()
                                                                }
                                                        currentThreadId = threadId
                                                        newThreadName = ""
                                                        showNewThreadDialog = false
                                                        showSidebar = false
                                                }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors =
                                                ButtonDefaults.buttonColors(
                                                        containerColor = threadColor
                                                )
                                ) { Text("Créer l'espace", fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = {
                                TextButton(onClick = { showNewThreadDialog = false }) {
                                        Text("Plus tard", color = Color.Gray)
                                }
                        }
                )
        }

        // --- Animation d'arrière-plan dynamique ---
        val infiniteTransition = rememberInfiniteTransition()
        val backgroundOffset by
                infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1000f,
                        animationSpec =
                                infiniteRepeatable(
                                        animation = tween(40000, easing = LinearEasing),
                                        repeatMode = RepeatMode.Reverse
                                )
                )

        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                // Arrière-plan avec dégradé animé (Plus visible)
                Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(
                                brush =
                                        Brush.radialGradient(
                                                colors =
                                                        listOf(
                                                                threadColor.copy(alpha = 0.15f),
                                                                Color.Transparent
                                                        ),
                                                center =
                                                        androidx.compose.ui.geometry.Offset(
                                                                backgroundOffset % size.width,
                                                                (backgroundOffset * 0.7f) %
                                                                        size.height
                                                        ),
                                                radius = size.width * 1.5f
                                        )
                        )
                        drawRect(
                                brush =
                                        Brush.radialGradient(
                                                colors =
                                                        listOf(
                                                                threadColor.copy(alpha = 0.12f),
                                                                Color.Transparent
                                                        ),
                                                center =
                                                        androidx.compose.ui.geometry.Offset(
                                                                size.width -
                                                                        (backgroundOffset %
                                                                                size.width),
                                                                size.height -
                                                                        ((backgroundOffset * 0.5f) %
                                                                                size.height)
                                                        ),
                                                radius = size.width * 1.2f
                                        )
                        )
                }

                // --- CHAT PRINCIPAL ---
                Column(modifier = Modifier.fillMaxSize()) {

                        // En-tête avec le nom du thread actif
                        Box(
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .background(
                                                        brush =
                                                                Brush.horizontalGradient(
                                                                        listOf(
                                                                                threadColor.copy(
                                                                                        alpha =
                                                                                                0.15f
                                                                                ),
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .surface
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
                                                        Text(
                                                                if (showSidebar) "✕" else "☰",
                                                                fontSize = 18.sp,
                                                                color = threadColor
                                                        )
                                                }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        // Titre et Description
                                        Column(modifier = Modifier.padding(start = 4.dp)) {
                                                Text(
                                                        if (currentThreadId == "main") "JARVIS"
                                                        else
                                                                currentThreadId
                                                                        .replace("_", " ")
                                                                        .uppercase(),
                                                        fontSize = 22.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        letterSpacing = 4.sp,
                                                        color = threadColor
                                                )
                                                Text(
                                                        if (currentThreadId == "main")
                                                                "Assistant IA Personnel"
                                                        else "Canal spécialisé",
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
                                        val align =
                                                if (msg.isUser) Alignment.CenterEnd
                                                else Alignment.CenterStart

                                        // Dégradés premium pour les bulles
                                        val bubbleBrush =
                                                if (msg.isUser) {
                                                        Brush.linearGradient(
                                                                listOf(
                                                                        threadColor,
                                                                        threadColor.copy(
                                                                                alpha = 0.85f
                                                                        )
                                                                )
                                                        )
                                                } else {
                                                        // Effet Glassmorphism pour Jarvis (Plus
                                                        // marqué)
                                                        Brush.verticalGradient(
                                                                listOf(
                                                                        Color.White.copy(
                                                                                alpha = 0.18f
                                                                        ),
                                                                        Color.White.copy(
                                                                                alpha = 0.08f
                                                                        )
                                                                )
                                                        )
                                                }

                                        val textColor =
                                                if (msg.isUser) Color.White
                                                else MaterialTheme.colorScheme.onSurface

                                        Box(
                                                modifier = Modifier.fillMaxWidth(),
                                                contentAlignment = align
                                        ) {
                                                Box(
                                                        modifier =
                                                                Modifier.widthIn(max = 310.dp)
                                                                        .padding(vertical = 2.dp)
                                                                        .shadow(
                                                                                elevation =
                                                                                        if (msg.isUser
                                                                                        )
                                                                                                6.dp
                                                                                        else 0.dp,
                                                                                shape =
                                                                                        RoundedCornerShape(
                                                                                                topStart =
                                                                                                        24.dp,
                                                                                                topEnd =
                                                                                                        24.dp,
                                                                                                bottomStart =
                                                                                                        if (msg.isUser
                                                                                                        )
                                                                                                                24.dp
                                                                                                        else
                                                                                                                4.dp,
                                                                                                bottomEnd =
                                                                                                        if (msg.isUser
                                                                                                        )
                                                                                                                4.dp
                                                                                                        else
                                                                                                                24.dp
                                                                                        )
                                                                        )
                                                                        .background(
                                                                                brush = bubbleBrush,
                                                                                shape =
                                                                                        RoundedCornerShape(
                                                                                                topStart =
                                                                                                        24.dp,
                                                                                                topEnd =
                                                                                                        24.dp,
                                                                                                bottomStart =
                                                                                                        if (msg.isUser
                                                                                                        )
                                                                                                                24.dp
                                                                                                        else
                                                                                                                4.dp,
                                                                                                bottomEnd =
                                                                                                        if (msg.isUser
                                                                                                        )
                                                                                                                4.dp
                                                                                                        else
                                                                                                                24.dp
                                                                                        )
                                                                        )
                                                                        .border(
                                                                                width =
                                                                                        if (msg.isUser
                                                                                        )
                                                                                                0.dp
                                                                                        else 1.dp,
                                                                                brush =
                                                                                        Brush.linearGradient(
                                                                                                listOf(
                                                                                                        Color.White
                                                                                                                .copy(
                                                                                                                        alpha =
                                                                                                                                0.3f
                                                                                                                ),
                                                                                                        Color.White
                                                                                                                .copy(
                                                                                                                        alpha =
                                                                                                                                0.1f
                                                                                                                )
                                                                                                )
                                                                                        ),
                                                                                shape =
                                                                                        RoundedCornerShape(
                                                                                                topStart =
                                                                                                        24.dp,
                                                                                                topEnd =
                                                                                                        24.dp,
                                                                                                bottomStart =
                                                                                                        if (msg.isUser
                                                                                                        )
                                                                                                                24.dp
                                                                                                        else
                                                                                                                4.dp,
                                                                                                bottomEnd =
                                                                                                        if (msg.isUser
                                                                                                        )
                                                                                                                4.dp
                                                                                                        else
                                                                                                                24.dp
                                                                                        )
                                                                        )
                                                                        .combinedClickable(
                                                                                onLongClick = {
                                                                                        val msgToDelete =
                                                                                                msg
                                                                                        val updated =
                                                                                                threadMessages
                                                                                                        .toMutableMap()
                                                                                        updated[
                                                                                                currentThreadId] =
                                                                                                currentMessages
                                                                                                        .filter {
                                                                                                                it !=
                                                                                                                        msgToDelete
                                                                                                        }
                                                                                        threadMessages =
                                                                                                updated

                                                                                        // Suppression persistante sur le
                                                                                        // serveur
                                                                                        coroutineScope
                                                                                                .launch {
                                                                                                        try {
                                                                                                                JarvisApiClient
                                                                                                                        .apiService
                                                                                                                        .deleteMessage(
                                                                                                                                currentUserId,
                                                                                                                                mapOf(
                                                                                                                                        "thread_id" to
                                                                                                                                                currentThreadId,
                                                                                                                                        "content" to
                                                                                                                                                msgToDelete
                                                                                                                                                        .text
                                                                                                                                )
                                                                                                                        )
                                                                                                        } catch (
                                                                                                                e:
                                                                                                                        Exception) {
                                                                                                                Log.e(
                                                                                                                        "JarvisDelete",
                                                                                                                        "Erreur suppression msg: ${e.message}"
                                                                                                                )
                                                                                                        }
                                                                                                }
                                                                                },
                                                                                onClick = {}
                                                                        )
                                                ) {
                                                        if (msg.isUser || !msg.isNew) {
                                                                FormattedMessage(
                                                                        text = msg.text,
                                                                        isUser = msg.isUser,
                                                                        color = textColor,
                                                                        imageResult = msg.imageResult
                                                                )
                                                        } else {
                                                                // Effet de typing pour les nouveaux
                                                                // messages Jarvis
                                                                TypewriterText(
                                                                        text = msg.text,
                                                                        modifier =
                                                                                Modifier.padding(
                                                                                        horizontal =
                                                                                                18.dp,
                                                                                        vertical =
                                                                                                14.dp
                                                                                ),
                                                                        color = textColor,
                                                                        onComplete = {
                                                                                // On marque le
                                                                                // message comme
                                                                                // "non nouveau" une
                                                                                // fois
                                                                                // fini pour éviter
                                                                                // de rejouer l'anim
                                                                                // au scroll
                                                                                val updated =
                                                                                        threadMessages
                                                                                                .toMutableMap()
                                                                                val currentList =
                                                                                        updated[
                                                                                                        currentThreadId]
                                                                                                ?.toMutableList()
                                                                                                ?: mutableListOf()
                                                                                val idx =
                                                                                        currentList
                                                                                                .indexOf(
                                                                                                        msg
                                                                                                )
                                                                                if (idx != -1) {
                                                                                        currentList[
                                                                                                idx] =
                                                                                                msg.copy(
                                                                                                        isNew =
                                                                                                                false
                                                                                                )
                                                                                        updated[
                                                                                                currentThreadId] =
                                                                                                currentList
                                                                                        threadMessages =
                                                                                                updated
                                                                                }
                                                                        }
                                                                )
                                                        }
                                                }
                                        }
                                }

                                if (isLoading) {
                                        item {
                                                JarvisOrb(
                                                        isThinking = !isModelLoading && !isModelLaunching && !isOptimizing && !isToolRunning,
                                                        isToolRunning = isToolRunning,
                                                        toolName = runningToolName,
                                                        isModelLoading = isModelLoading || isOptimizing,
                                                        isModelLaunching = isModelLaunching,
                                                        isListening = isListening,
                                                        isSpeaking = isSpeaking,
                                                        baseColor = threadColor,
                                                        moodColor = sentimentColor
                                                )
                                        }
                                }

                                item { Spacer(modifier = Modifier.height(8.dp)) }
                        }

                        // Sélecteur de modes
                        var showCreateModeDialog by remember { mutableStateOf(false) }

                        fun refreshModes() {
                                coroutineScope.launch {
                                        try {
                                                val response =
                                                        JarvisApiClient.apiService.getModes(
                                                                currentUserId
                                                        )
                                                availableModes =
                                                        response.modes.map {
                                                                mapOf(
                                                                        "name" to it.name,
                                                                        "icon" to (it.icon ?: "💎"),
                                                                        "color" to
                                                                                (it.color
                                                                                        ?: "#4285F4")
                                                                )
                                                        }
                                        } catch (e: Exception) {
                                                Log.e(
                                                        "JarvisModes",
                                                        "Erreur chargement modes: ${e.message}"
                                                )
                                        }
                                }
                        }

                        LaunchedEffect(currentUserId) { refreshModes() }

                        if (showCreateModeDialog) {
                                CreateModeDialog(
                                        onDismiss = { showCreateModeDialog = false },
                                        onCreate = { n, i, ic, c ->
                                                coroutineScope.launch {
                                                        try {
                                                                JarvisApiClient.apiService
                                                                        .createMode(
                                                                                currentUserId,
                                                                                ModeRequest(
                                                                                        n,
                                                                                        i,
                                                                                        ic,
                                                                                        c
                                                                                )
                                                                        )

                                                                showCreateModeDialog = false
                                                                refreshModes()
                                                        } catch (e: Exception) {
                                                                Log.e(
                                                                        "JarvisModes",
                                                                        "Erreur création mode: ${e.message}"
                                                                )
                                                        }
                                                }
                                        }
                                )
                        }

                        JarvisModeSelector(
                                selectedMode = currentMode,
                                onModeSelected = {
                                        if (it != currentMode) {
                                                currentMode = it
                                                // Petit effet visuel de chargement de modèle lors
                                                // du changement de mode
                                                coroutineScope.launch {
                                                        isModelLoading = true
                                                        isLoading = true
                                                        kotlinx.coroutines.delay(1200)
                                                        isModelLoading = false
                                                        isLoading = false
                                                }
                                        }
                                },
                                modes = availableModes,
                                onAddMode = { showCreateModeDialog = true }
                        )

                        // Barre de saisie "Flottante"

                        Surface(
                                modifier =
                                        Modifier.padding(16.dp)
                                                .navigationBarsPadding()
                                                .fillMaxWidth(),
                                shape = RoundedCornerShape(32.dp),
                                tonalElevation = 8.dp,
                                shadowElevation = 12.dp,
                                color = MaterialTheme.colorScheme.surface
                        ) {
                                Column {
                                        // Aperçu de l'image sélectionnée
                                        androidx.compose.animation.AnimatedVisibility(
                                                visible = selectedImageUri != null,
                                                enter = expandVertically() + fadeIn(),
                                                exit = shrinkVertically() + fadeOut()
                                        ) {
                                                Box(
                                                        modifier = Modifier
                                                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                                                .size(100.dp)
                                                ) {
                                                        Surface(
                                                                shape = RoundedCornerShape(12.dp),
                                                                shadowElevation = 4.dp,
                                                                border = BorderStroke(2.dp, threadColor.copy(alpha = 0.5f))
                                                        ) {
                                                                AsyncImage(
                                                                        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                                                                .data(selectedImageUri)
                                                                                .crossfade(true)
                                                                                .build(),
                                                                        contentDescription = "Aperçu image",
                                                                        modifier = Modifier.fillMaxSize(),
                                                                        contentScale = ContentScale.Crop
                                                                )
                                                        }
                                                        
                                                        // Bouton supprimer l'image
                                                        IconButton(
                                                                onClick = { selectedImageUri = null },
                                                                modifier = Modifier
                                                                        .align(Alignment.TopEnd)
                                                                        .offset(x = 8.dp, y = (-8).dp)
                                                                        .size(24.dp)
                                                                        .background(Color.Red, CircleShape)
                                                        ) {
                                                                Icon(
                                                                        Icons.Default.Close,
                                                                        contentDescription = "Supprimer",
                                                                        tint = Color.White,
                                                                        modifier = Modifier.size(16.dp)
                                                                )
                                                        }
                                                }
                                        }
                                        
                                        Row(
                                                modifier =
                                                        Modifier.padding(
                                                                horizontal = 8.dp,
                                                                vertical = 6.dp
                                                        ),
                                                verticalAlignment = Alignment.CenterVertically
                                        ) {
                                        var showTools by remember { mutableStateOf(false) }

                                        // Bouton d'expansion des outils (+)
                                        IconButton(onClick = { showTools = !showTools }) {
                                                Icon(
                                                        imageVector =
                                                                if (showTools) Icons.Default.Close
                                                                else Icons.Default.Add,
                                                        contentDescription = "Outils",
                                                        tint =
                                                                if (showTools) Color.Gray
                                                                else threadColor,
                                                        modifier =
                                                                Modifier.graphicsLayer(
                                                                        rotationZ =
                                                                                if (showTools) 90f
                                                                                else 0f
                                                                )
                                                )
                                        }

                                        androidx.compose.animation.AnimatedVisibility(
                                                visible = showTools,
                                                enter = fadeIn() + expandHorizontally(),
                                                exit = fadeOut() + shrinkHorizontally()
                                        ) {
                                                Row(
                                                        verticalAlignment =
                                                                Alignment.CenterVertically
                                                ) {
                                                        // Analyse d'image
                                                        IconButton(
                                                                onClick = {
                                                                        showTools = false
                                                                        onPickImage { uri ->
                                                                                selectedImageUri =
                                                                                        uri
                                                                        }
                                                                }
                                                        ) {
                                                                Box {
                                                                        Icon(
                                                                                Icons.Default.Image,
                                                                                contentDescription =
                                                                                        "Image",
                                                                                tint = threadColor
                                                                        )
                                                                        if (selectedImageUri != null
                                                                        ) {
                                                                                Surface(
                                                                                        modifier =
                                                                                                Modifier.size(
                                                                                                                8.dp
                                                                                                        )
                                                                                                        .align(
                                                                                                                Alignment
                                                                                                                        .TopEnd
                                                                                                        ),
                                                                                        shape =
                                                                                                CircleShape,
                                                                                        color =
                                                                                                Color(
                                                                                                        0xFF4CAF50
                                                                                                ),
                                                                                        border =
                                                                                                androidx.compose
                                                                                                        .foundation
                                                                                                        .BorderStroke(
                                                                                                                1.dp,
                                                                                                                Color.White
                                                                                                        )
                                                                                ) {}
                                                                        }
                                                                }
                                                        }

                                                        // Dictée Vocale
                                                        IconButton(
                                                                onClick = {
                                                                        showTools = false
                                                                        voiceAssistant
                                                                                .startListening()
                                                                }
                                                        ) {
                                                                Icon(
                                                                        Icons.Default.Mic,
                                                                        contentDescription =
                                                                                "Vocal",
                                                                        tint = threadColor
                                                                )
                                                        }

                                                        // Lecture automatique
                                                        IconButton(
                                                                onClick = {
                                                                        val next =
                                                                                !isAutoReadEnabled
                                                                        onAutoReadToggle(next)
                                                                }
                                                        ) {
                                                                Icon(
                                                                        imageVector =
                                                                                if (isAutoReadEnabled
                                                                                )
                                                                                        Icons.Default
                                                                                                .VolumeUp
                                                                                else
                                                                                        Icons.Default
                                                                                                .VolumeOff,
                                                                        contentDescription =
                                                                                "Auto-read",
                                                                        tint =
                                                                                if (isAutoReadEnabled
                                                                                )
                                                                                        threadColor
                                                                                else Color.Gray
                                                                )
                                                        }

                                                        Box(
                                                                modifier =
                                                                        Modifier.width(1.dp)
                                                                                .height(24.dp)
                                                                                .background(
                                                                                        Color.LightGray
                                                                                                .copy(
                                                                                                        alpha =
                                                                                                                0.5f
                                                                                                )
                                                                                )
                                                        )
                                                }
                                        }

                                        OutlinedTextField(
                                                value = input,
                                                onValueChange = { input = it },
                                                modifier = Modifier.weight(1f),
                                                placeholder = {
                                                        Text(
                                                                if (currentThreadId == "main")
                                                                        "Demander quelque chose..."
                                                                else
                                                                        "Message dans ${currentThreadId.replace("_", " ")}...",
                                                                color = Color.Gray
                                                        )
                                                },
                                                shape = RoundedCornerShape(28.dp),
                                                colors =
                                                        TextFieldDefaults.outlinedTextFieldColors(
                                                                containerColor = Color.Transparent,
                                                                unfocusedBorderColor =
                                                                        threadColor.copy(
                                                                                alpha = 0.3f
                                                                        ),
                                                                focusedBorderColor = threadColor
                                                        ),
                                                maxLines = 6
                                        )

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Box(
                                                modifier = Modifier
                                                        .size(48.dp)
                                                        .clip(CircleShape)
                                                        .background(threadColor)
                                                        .clickable {
                                                            if (input.isNotBlank() && !isLoading) {
                                                                val userMsg = input
                                                                val updatedWithUser =
                                                                        currentMessages +
                                                                                JarvisChatMessage(
                                                                                        text = userMsg,
                                                                                        isUser = true,
                                                                                        isError = false
                                                                                )
                                                                val updated = threadMessages.toMutableMap()
                                                                updated[currentThreadId] = updatedWithUser
                                                                threadMessages = updated
                                                                if (currentThreadId == "main") onMessagesChange(updatedWithUser)
                                                                input = ""

                                                coroutineScope.launch {
                                                                    if (selectedImageUri != null) {
                                                                        try {
                                                                            val inputStream = context.contentResolver.openInputStream(selectedImageUri!!)
                                                                            val bytes = inputStream?.readBytes()
                                                                            if (bytes != null) {
                                                                                selectedImageBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                                                                            }
                                                                        } catch (e: Exception) {
                                                                            Log.e("JarvisVision", "Erreur encodage image: ${e.message}")
                                                                        }
                                                                        selectedImageUri = null
                                                                    }

                                                                    isLoading = true
                                                                    isModelLaunching = true
                                                                    isOptimizing = false
                                                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)

                                                                    launch {
                                                                        kotlinx.coroutines.delay(5000)
                                                                        if (isLoading) isOptimizing = true
                                                                    }

                                                                    try {
                                                                        val prefs = context.getSharedPreferences("AxonysPrefs", Context.MODE_PRIVATE)
                                                                        var token = prefs.getString("google_id_token", null)
                                                                        val responseBody = withContext(Dispatchers.IO) {
                                                                            val freshToken = onRefreshToken()
                                                                            if (freshToken != null) token = freshToken
                                                                            JarvisApiClient.apiService.streamMessage(
                                                                                ChatRequest(
                                                                                    prompt = userMsg,
                                                                                    google_token = token,
                                                                                    user_id = currentUserId,
                                                                                    user_name = currentUserName,
                                                                                    lat = lat,
                                                                                    lng = lng,
                                                                                    thread_id = currentThreadId,
                                                                                    mode = currentMode,
                                                                                    image_base64 = selectedImageBase64
                                                                                )
                                                                            )
                                                                        }
                                                                        selectedImageBase64 = null
                                                                        isModelLaunching = false
                                                                        
                                                                        val gson = Gson()
                                                                        val reader = withContext(Dispatchers.IO) {
                                                                            BufferedReader(InputStreamReader(responseBody.byteStream()))
                                                                        }
                                                                        var fullText = ""
                                                                        val initialJarvisMsg = JarvisChatMessage(text = "", isUser = false, isThinking = true)
                                                                        var streamWithJarvis = updatedWithUser + initialJarvisMsg
                                                                        
                                                                        withContext(Dispatchers.IO) {
                                                                            reader.use { br ->
                                                                                while (true) {
                                                                                    val line = br.readLine() ?: break
                                                                                    if (line.startsWith("data: ")) {
                                                                                        val json = line.substring(6)
                                                                                        val data = gson.fromJson(json, Map::class.java)
                                                                                        withContext(Dispatchers.Main) {
                                                                                            if (data["error"] != null) {
                                                                                                val backendError = data["error"] as String
                                                                                                val errMsg = JarvisChatMessage(
                                                                                                    "Jarvis a rencontré une erreur: $backendError",
                                                                                                    isUser = false,
                                                                                                    isError = true
                                                                                                )
                                                                                                val updatedWithErr = messages + errMsg
                                                                                                val updatedThreadMessages = threadMessages.toMutableMap()
                                                                                                updatedThreadMessages[currentThreadId] = updatedWithErr
                                                                                                threadMessages = updatedThreadMessages
                                                                                                if (currentThreadId == "main") {
                                                                                                    onMessagesChange(updatedWithErr)
                                                                                                }
                                                                                                Log.e("JarvisStream", "Backend Error: $backendError")
                                                                                                return@withContext
                                                                                            }
                                                                                            if (data["sentiment"] != null) {
                                                                                                currentSentiment = data["sentiment"] as String
                                                                                            }
                                                                                            if (data["chunk"] != null) {
                                                                                                isToolRunning = false
                                                                                                fullText += data["chunk"] as String
                                                                                                val updatedJarvisMsg = initialJarvisMsg.copy(text = fullText, isThinking = false)
                                                                                                streamWithJarvis = updatedWithUser + updatedJarvisMsg
                                                                                                val updatedStream = threadMessages.toMutableMap()
                                                                                                updatedStream[currentThreadId] = streamWithJarvis
                                                                                                threadMessages = updatedStream
                                                                                                if (currentThreadId == "main") onMessagesChange(streamWithJarvis)
                                                                                            }
                                                                                            if (data["tool_use"] != null) {
                                                                                                isToolRunning = true
                                                                                                runningToolName = data["tool_use"] as String
                                                                                                isOptimizing = false
                                                                                            }
                                                                                            if (data["done"] == true) {
                                                                                                isToolRunning = false
                                                                                                runningToolName = null
                                                                                                val finalSentiment = data["sentiment"] as? String ?: "CALM"
                                                                                                val finalImage = data["image_result"] as? String
                                                                                                currentSentiment = finalSentiment
                                                                                                val finalJarvisMsg = initialJarvisMsg.copy(
                                                                                                    text = fullText,
                                                                                                    isThinking = false,
                                                                                                    imageResult = finalImage,
                                                                                                    isNew = true
                                                                                                )
                                                                                                streamWithJarvis = updatedWithUser + finalJarvisMsg
                                                                                                val updatedFinal = threadMessages.toMutableMap()
                                                                                                updatedFinal[currentThreadId] = streamWithJarvis
                                                                                                threadMessages = updatedFinal
                                                                                                if (currentThreadId == "main") onMessagesChange(streamWithJarvis)
                                                                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                                                                if (isAutoReadEnabled) voiceAssistant?.speak(fullText)
                                                                                            }
                                                                                        }
                                                                                    }
                                                                                }
                                                                            }
                                                                        }
                                                                    } catch (e: Exception) {
                                                                        Log.e("JarvisStream", "Error processing stream: ${e.message}", e)
                                                                        val errMsg = JarvisChatMessage(
                                                                            "Erreur de connexion ou de traitement du flux. Veuillez réessayer.",
                                                                            isUser = false,
                                                                            isError = true
                                                                        )
                                                                        val updatedErr = threadMessages.toMutableMap()
                                                                        updatedErr[currentThreadId] = messages + errMsg
                                                                        threadMessages = updatedErr
                                                                        if (currentThreadId == "main") {
                                                                            onMessagesChange(messages + errMsg)
                                                                        }
                                                                    } finally {
                                                                        isLoading = false
                                                                        isModelLaunching = false
                                                                        isModelLoading = false
                                                                        isOptimizing = false
                                                                    }
                                                                }
                                                            }
                                                        }
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        if (isLoading) {
                                                            CircularProgressIndicator(
                                                                modifier = Modifier.size(22.dp),
                                                                color = Color.White,
                                                                strokeWidth = 2.dp
                                                            )
                                                        } else {
                                                            Text(
                                                                "↑",
                                                                fontSize = 24.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color.White
                                                            )
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
                                modifier =
                                        Modifier.fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.4f))
                                                .clickable { showSidebar = false }
                        )
                }

                // --- SIDEBAR (Overlay) ---
                androidx.compose.animation.AnimatedVisibility(
                        visible = showSidebar,
                        enter =
                                androidx.compose.animation.slideInHorizontally() +
                                        androidx.compose.animation.fadeIn(),
                        exit =
                                androidx.compose.animation.slideOutHorizontally() +
                                        androidx.compose.animation.fadeOut()
                ) {
                        Box(modifier = Modifier.width(260.dp).fillMaxHeight()) {
                                // Fond glassmorphism (blur uniquement sur ce layer)
                                if (android.os.Build.VERSION.SDK_INT >= 31) {
                                        Box(
                                                modifier =
                                                        Modifier.fillMaxSize()
                                                                .blur(20.dp)
                                                                .background(
                                                                        MaterialTheme.colorScheme
                                                                                .surface.copy(
                                                                                alpha = 0.85f
                                                                        )
                                                                )
                                        )
                                }
                                Surface(
                                        modifier = Modifier.fillMaxSize(),
                                        color =
                                                MaterialTheme.colorScheme.surface.copy(
                                                        alpha =
                                                                if (android.os.Build.VERSION
                                                                                .SDK_INT >= 31
                                                                )
                                                                        0.1f
                                                                else 0.97f
                                                ),
                                        shadowElevation = 0.dp,
                                        tonalElevation = 0.dp
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
                                                        val icon =
                                                                when {
                                                                        threadId == "main" -> "🏠"
                                                                        threadId.contains(
                                                                                "briefing"
                                                                        ) -> "⚡"
                                                                        threadId.contains("dev") ->
                                                                                "🛠️"
                                                                        threadId.contains("nsi") ->
                                                                                "💻"
                                                                        threadId.contains(
                                                                                "projet"
                                                                        ) -> "🚀"
                                                                        else -> "📌"
                                                                }
                                                        val displayName =
                                                                if (threadId == "main") "Général"
                                                                else
                                                                        threadId.replace("_", " ")
                                                                                .replaceFirstChar {
                                                                                        it.uppercase()
                                                                                }

                                                        Surface(
                                                                onClick = {
                                                                        currentThreadId = threadId
                                                                        showSidebar = false
                                                                },
                                                                shape = RoundedCornerShape(12.dp),
                                                                color =
                                                                        if (isSelected)
                                                                                threadColor.copy(
                                                                                        alpha = 0.2f
                                                                                )
                                                                        else Color.Transparent,
                                                                modifier =
                                                                        Modifier.fillMaxWidth()
                                                                                .padding(
                                                                                        vertical =
                                                                                                2.dp
                                                                                )
                                                        ) {
                                                                Row(
                                                                        modifier =
                                                                                Modifier.padding(
                                                                                        12.dp
                                                                                ),
                                                                        verticalAlignment =
                                                                                Alignment
                                                                                        .CenterVertically
                                                                ) {
                                                                        Text(icon, fontSize = 18.sp)
                                                                        Spacer(
                                                                                modifier =
                                                                                        Modifier.width(
                                                                                                12.dp
                                                                                        )
                                                                        )
                                                                        Text(
                                                                                displayName,
                                                                                fontWeight =
                                                                                        if (isSelected
                                                                                        )
                                                                                                FontWeight
                                                                                                        .Bold
                                                                                        else
                                                                                                FontWeight
                                                                                                        .Medium,
                                                                                color =
                                                                                        if (isSelected
                                                                                        )
                                                                                                threadColor
                                                                                        else
                                                                                                MaterialTheme
                                                                                                        .colorScheme
                                                                                                        .onSurface,
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
                                                        colors =
                                                                ButtonDefaults.buttonColors(
                                                                        containerColor = threadColor
                                                                )
                                                ) { Text("+ Nouveau canal") }
                                        }
                                }
                        } // close Surface
                } // close Box glassmorphism
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
        onBriefingTimeChange: (Int, Int) -> Unit,
        onExploreMemory: () -> Unit
) {
        val context = androidx.compose.ui.platform.LocalContext.current
        Column(
                modifier =
                        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
        ) {
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
                                ThemeOptionRow(
                                        "Thème Système",
                                        ThemeMode.SYSTEM,
                                        themeMode,
                                        onThemeChange
                                )
                                ThemeOptionRow(
                                        "Mode Clair",
                                        ThemeMode.LIGHT,
                                        themeMode,
                                        onThemeChange
                                )
                                ThemeOptionRow(
                                        "Mode Sombre",
                                        ThemeMode.DARK,
                                        themeMode,
                                        onThemeChange
                                )
                        }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text("Automatisation", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                        "Morning Briefing",
                                                        fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                        "Jarvis résume ta journée à ${briefingHour.toString().padStart(2, '0')}:${briefingMinute.toString().padStart(2, '0')}",
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
                                                        val timePicker =
                                                                android.app.TimePickerDialog(
                                                                        context,
                                                                        { _, h, m ->
                                                                                onBriefingTimeChange(
                                                                                        h,
                                                                                        m
                                                                                )
                                                                        },
                                                                        briefingHour,
                                                                        briefingMinute,
                                                                        true
                                                                )
                                                        timePicker.show()
                                                }
                                        ) {
                                                Text(
                                                        "Heure du briefing : ${String.format("%02d:%02d", briefingHour, briefingMinute)}",
                                                        color = MaterialTheme.colorScheme.primary
                                                )
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
                Text("Intelligence & Mémoire", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                                Button(
                                        onClick = onExploreMemory,
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors =
                                                ButtonDefaults.buttonColors(
                                                        containerColor =
                                                                MaterialTheme.colorScheme
                                                                        .primaryContainer
                                                )
                                ) {
                                        Text(
                                                "🧠 Explorer ce que Jarvis sait",
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                }
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
                                        Text(
                                                "Connecté en tant que :",
                                                fontSize = 12.sp,
                                                color = Color.Gray
                                        )
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
                                                                        MaterialTheme.colorScheme
                                                                                .errorContainer,
                                                                contentColor =
                                                                        MaterialTheme.colorScheme
                                                                                .onErrorContainer
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
        val taskScore = task.score ?: 0.0
        val accentColor =
                if (taskScore >= 70) Color(0xFFFF5252)
                else if (taskScore >= 40) Color(0xFFFFB300) else Color(0xFF00E676)

        Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(20.dp),
                colors =
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
                Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                        // Barre de priorité sur le côté
                        Box(modifier = Modifier.fillMaxHeight().width(6.dp).background(accentColor))

                        Row(
                                modifier = Modifier.padding(16.dp).weight(1f),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                                task.name ?: "Sans titre",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp
                                        )
                                        Text(
                                                "Priorité : ${(task.score ?: 0.0).toInt()}%",
                                                fontSize = 12.sp,
                                                color = Color.Gray
                                        )
                                }
                                IconButton(
                                        onClick = onDelete,
                                        modifier =
                                                Modifier.background(
                                                        accentColor.copy(alpha = 0.1f),
                                                        CircleShape
                                                )
                                ) { Text("✅", fontSize = 16.sp) }
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

@Composable
fun ThinkingWave(color: Color) {
        val infiniteTransition = rememberInfiniteTransition()
        val waveOffset by
                infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 2f * Math.PI.toFloat(),
                        animationSpec =
                                infiniteRepeatable(
                                        animation = tween(1200, easing = LinearEasing),
                                        repeatMode = RepeatMode.Restart
                                )
                )

        Canvas(modifier = Modifier.fillMaxWidth().height(40.dp).padding(vertical = 8.dp)) {
                val width = size.width
                val height = size.height
                val points = mutableListOf<androidx.compose.ui.geometry.Offset>()

                for (x in 0..width.toInt() step 5) {
                        val relativeX = x.toFloat() / width
                        val sine = Math.sin((relativeX * 3f * Math.PI) + waveOffset).toFloat()
                        val y = height / 2f + sine * 12f
                        points.add(androidx.compose.ui.geometry.Offset(x.toFloat(), y))
                }

                val path =
                        androidx.compose.ui.graphics.Path().apply {
                                moveTo(points[0].x, points[0].y)
                                for (i in 1 until points.size) {
                                        lineTo(points[i].x, points[i].y)
                                }
                        }

                drawPath(
                        path = path,
                        color = color,
                        style =
                                androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 3.dp.toPx(),
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                                )
                )

                // Deuxième onde décalée
                val path2 =
                        androidx.compose.ui.graphics.Path().apply {
                                moveTo(points[0].x, points[0].y + 4f)
                                for (i in 1 until points.size) {
                                        val relativeX = points[i].x / width
                                        val sine =
                                                Math.sin(
                                                                (relativeX * 3f * Math.PI) +
                                                                        waveOffset +
                                                                        1f
                                                        )
                                                        .toFloat()
                                        val y = height / 2f + sine * 8f
                                        lineTo(points[i].x, y)
                                }
                        }

                drawPath(
                        path = path2,
                        color = color.copy(alpha = 0.4f),
                        style =
                                androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 2.dp.toPx(),
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                                )
                )
        }
}

@Composable
fun MemoryExplorerScreen(
        currentUserId: String,
        onDismiss: () -> Unit,
        onDeleteFact: (String) -> Unit
) {
        var memories by remember { mutableStateOf<List<MemoryFact>>(emptyList()) }
        var preferences by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var isLoading by remember { mutableStateOf(true) }
        var selectedTab by remember { mutableStateOf(0) } // 0: Faits, 1: Préférences

        LaunchedEffect(currentUserId) {
                try {
                        val responseFacts = JarvisApiClient.apiService.getMemory(currentUserId)
                        memories = responseFacts.facts
                        val responsePrefs = JarvisApiClient.apiService.getPreferences(currentUserId)
                        preferences = responsePrefs.preferences
                } catch (e: Exception) {
                        Log.e("MemoryExplorer", "Error: ${e.message}")
                } finally {
                        isLoading = false
                }
        }

        Box(
                modifier =
                        Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.8f))
                                .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
        ) {
                Surface(
                        modifier =
                                Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.8f).clickable(
                                                enabled = false
                                        ) {}, // Prevent dismiss when clicking inside
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 8.dp
                ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                                Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                ) {
                                        Text(
                                                "🧠 Mémoire de Jarvis",
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                        )
                                        IconButton(onClick = onDismiss) {
                                                Text("❌", fontSize = 16.sp)
                                        }
                                }

                                Text(
                                        "Voici ce que Jarvis a retenu sur vous. Vous pouvez supprimer des faits si nécessaire.",
                                        fontSize = 13.sp,
                                        color = Color.Gray,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                if (isLoading) {
                                        Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                        ) {
                                                CircularProgressIndicator(
                                                        color = MaterialTheme.colorScheme.primary
                                                )
                                        }
                                } else {
                                        Row(
                                                modifier =
                                                        Modifier.fillMaxWidth()
                                                                .padding(bottom = 12.dp),
                                                horizontalArrangement = Arrangement.SpaceEvenly
                                        ) {
                                                TextButton(onClick = { selectedTab = 0 }) {
                                                        Text(
                                                                "Faits",
                                                                color =
                                                                        if (selectedTab == 0)
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .primary
                                                                        else Color.Gray,
                                                                fontWeight =
                                                                        if (selectedTab == 0)
                                                                                FontWeight.Bold
                                                                        else FontWeight.Normal
                                                        )
                                                }
                                                TextButton(onClick = { selectedTab = 1 }) {
                                                        Text(
                                                                "Préférences",
                                                                color =
                                                                        if (selectedTab == 1)
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .primary
                                                                        else Color.Gray,
                                                                fontWeight =
                                                                        if (selectedTab == 1)
                                                                                FontWeight.Bold
                                                                        else FontWeight.Normal
                                                        )
                                                }
                                        }

                                        if (selectedTab == 0) {
                                                if (memories.isEmpty()) {
                                                        Box(
                                                                modifier = Modifier.fillMaxSize(),
                                                                contentAlignment = Alignment.Center
                                                        ) {
                                                                Text(
                                                                        "Jarvis n'a pas encore mémorisé de faits.",
                                                                        textAlign =
                                                                                TextAlign.Center,
                                                                        color = Color.Gray
                                                                )
                                                        }
                                                } else {
                                                        LazyColumn(
                                                                verticalArrangement =
                                                                        Arrangement.spacedBy(12.dp)
                                                        ) {
                                                                items(memories) { item ->
                                                                        Surface(
                                                                                modifier =
                                                                                        Modifier.fillMaxWidth(),
                                                                                shape =
                                                                                        RoundedCornerShape(
                                                                                                16.dp
                                                                                        ),
                                                                                color =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .surfaceVariant
                                                                                                .copy(
                                                                                                        alpha =
                                                                                                                0.5f
                                                                                                ),
                                                                                border =
                                                                                        androidx.compose
                                                                                                .foundation
                                                                                                .BorderStroke(
                                                                                                        0.5.dp,
                                                                                                        Color.Gray
                                                                                                                .copy(
                                                                                                                        alpha =
                                                                                                                                0.1f
                                                                                                                )
                                                                                                )
                                                                        ) {
                                                                                Row(
                                                                                        modifier =
                                                                                                Modifier.padding(
                                                                                                        16.dp
                                                                                                ),
                                                                                        verticalAlignment =
                                                                                                Alignment
                                                                                                        .CenterVertically
                                                                                ) {
                                                                                        Column(
                                                                                                modifier =
                                                                                                        Modifier.weight(
                                                                                                                1f
                                                                                                        )
                                                                                        ) {
                                                                                                Text(
                                                                                                        item.fact,
                                                                                                        fontSize =
                                                                                                                15.sp,
                                                                                                        fontWeight =
                                                                                                                FontWeight
                                                                                                                        .Medium
                                                                                                )
                                                                                                Text(
                                                                                                        item.timestamp
                                                                                                                .split(
                                                                                                                        " "
                                                                                                                )[
                                                                                                                0],
                                                                                                        fontSize =
                                                                                                                11.sp,
                                                                                                        color =
                                                                                                                Color.Gray
                                                                                                )
                                                                                        }
                                                                                        IconButton(
                                                                                                onClick = {
                                                                                                        onDeleteFact(
                                                                                                                item.fact
                                                                                                        )
                                                                                                        memories =
                                                                                                                memories
                                                                                                                        .filter {
                                                                                                                                it.fact !=
                                                                                                                                        item.fact
                                                                                                                        }
                                                                                                },
                                                                                                modifier =
                                                                                                        Modifier.size(
                                                                                                                        32.dp
                                                                                                                )
                                                                                                                .background(
                                                                                                                        Color.Red
                                                                                                                                .copy(
                                                                                                                                        alpha =
                                                                                                                                                0.1f
                                                                                                                                ),
                                                                                                                        CircleShape
                                                                                                                )
                                                                                        ) {
                                                                                                Text(
                                                                                                        "🗑️",
                                                                                                        fontSize =
                                                                                                                14.sp
                                                                                                )
                                                                                        }
                                                                                }
                                                                        }
                                                                }
                                                        }
                                                }
                                        } else {
                                                if (preferences.isEmpty()) {
                                                        Box(
                                                                modifier = Modifier.fillMaxSize(),
                                                                contentAlignment = Alignment.Center
                                                        ) {
                                                                Text(
                                                                        "Aucune préférence définie.",
                                                                        textAlign =
                                                                                TextAlign.Center,
                                                                        color = Color.Gray
                                                                )
                                                        }
                                                } else {
                                                        LazyColumn(
                                                                verticalArrangement =
                                                                        Arrangement.spacedBy(12.dp)
                                                        ) {
                                                                items(preferences.toList()) { entry
                                                                        ->
                                                                        val (key, value) = entry
                                                                        Surface(
                                                                                modifier =
                                                                                        Modifier.fillMaxWidth(),
                                                                                shape =
                                                                                        RoundedCornerShape(
                                                                                                16.dp
                                                                                        ),
                                                                                color =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .surfaceVariant
                                                                                                .copy(
                                                                                                        alpha =
                                                                                                                0.5f
                                                                                                ),
                                                                                border =
                                                                                        androidx.compose
                                                                                                .foundation
                                                                                                .BorderStroke(
                                                                                                        0.5.dp,
                                                                                                        Color.Gray
                                                                                                                .copy(
                                                                                                                        alpha =
                                                                                                                                0.1f
                                                                                                                )
                                                                                                )
                                                                        ) {
                                                                                Row(
                                                                                        modifier =
                                                                                                Modifier.padding(
                                                                                                        16.dp
                                                                                                ),
                                                                                        verticalAlignment =
                                                                                                Alignment
                                                                                                        .CenterVertically
                                                                                ) {
                                                                                        Column(
                                                                                                modifier =
                                                                                                        Modifier.weight(
                                                                                                                1f
                                                                                                        )
                                                                                        ) {
                                                                                                Text(
                                                                                                        key,
                                                                                                        fontSize =
                                                                                                                12.sp,
                                                                                                        color =
                                                                                                                Color.Gray,
                                                                                                        fontWeight =
                                                                                                                FontWeight
                                                                                                                        .Medium
                                                                                                )
                                                                                                Text(
                                                                                                        value,
                                                                                                        fontSize =
                                                                                                                15.sp,
                                                                                                        fontWeight =
                                                                                                                FontWeight
                                                                                                                        .Bold
                                                                                                )
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
        }
}

@Composable
fun JarvisOrb(
        isThinking: Boolean = true,
        isToolRunning: Boolean = false,
        isModelLoading: Boolean = false,
        isModelLaunching: Boolean = false,
        isListening: Boolean = false,
        isSpeaking: Boolean = false,
        toolName: String? = null,
        baseColor: Color = MaterialTheme.colorScheme.primary,
        moodColor: Color = Color.Transparent
) {
        val infiniteTransition = rememberInfiniteTransition()

        // Animation de pulsation de base
        val pulseScale by
                infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.2f,
                        animationSpec =
                                infiniteRepeatable(
                                        animation = tween(1500, easing = FastOutSlowInEasing),
                                        repeatMode = RepeatMode.Reverse
                                )
                )

        // Animation de rotation pour les outils
        val rotation by
                infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec =
                                infiniteRepeatable(
                                        animation =
                                                tween(
                                                        if (isToolRunning) 2000 else 8000,
                                                        easing = LinearEasing
                                                ),
                                        repeatMode = RepeatMode.Restart
                                )
                )

        // Couleur dynamique
        val orbColor =
                when {
                        isListening -> Color.Red // Micro actif
                        isSpeaking -> Color(0xFFE91E63) // Jarvis parle
                        isModelLoading -> Color(0xFF00BCD4)
                        isModelLaunching -> Color(0xFF4CAF50)
                        isToolRunning -> Color(0xFFFF9800)
                        else -> baseColor
                }

        Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.Start
        ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(140.dp)) {
                        // Mood Glow (Arrière-plan)
                        if (moodColor != Color.Transparent) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                        drawCircle(
                                                brush = Brush.radialGradient(
                                                        colors = listOf(moodColor.copy(alpha = 0.35f), Color.Transparent),
                                                        center = center,
                                                        radius = size.width / 2
                                                )
                                        )
                                }
                        }
                        // 1. Halo extérieur profond (Atmosphère)
                        Canvas(modifier = Modifier.size(90.dp * pulseScale)) {
                                drawCircle(
                                        brush =
                                                Brush.radialGradient(
                                                        colors =
                                                                listOf(
                                                                        orbColor.copy(alpha = 0.3f),
                                                                        Color.Transparent
                                                                ),
                                                        center = center,
                                                        radius = size.width / 1.2f
                                                )
                                )
                        }

                        // 2. Anneau HUD rotatif (Sci-Fi)
                        Canvas(
                                modifier =
                                        Modifier.size(80.dp)
                                                .graphicsLayer(rotationZ = -rotation * 0.5f)
                        ) {
                                drawCircle(
                                        color = orbColor.copy(alpha = 0.2f),
                                        style =
                                                Stroke(
                                                        width = 1.dp.toPx(),
                                                        pathEffect =
                                                                androidx.compose.ui.graphics
                                                                        .PathEffect.dashPathEffect(
                                                                        floatArrayOf(10f, 10f)
                                                                )
                                                )
                                )
                        }

                        // 3. Deuxième anneau HUD (Sens inverse)
                        Canvas(
                                modifier =
                                        Modifier.size(72.dp)
                                                .graphicsLayer(rotationZ = rotation * 1.2f)
                        ) {
                                drawArc(
                                        color = orbColor.copy(alpha = 0.5f),
                                        startAngle = 0f,
                                        sweepAngle = 90f,
                                        useCenter = false,
                                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                                )
                                drawArc(
                                        color = orbColor.copy(alpha = 0.5f),
                                        startAngle = 180f,
                                        sweepAngle = 90f,
                                        useCenter = false,
                                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                                )
                        }

                        // 4. L'Orbe Central avec gradient complexe (Profondeur)
                        Canvas(
                                modifier =
                                        Modifier.size(45.dp)
                                                .graphicsLayer(rotationZ = rotation * 0.3f)
                        ) {
                                val path = android.graphics.Path()
                                val radius = size.width / 2

                                for (i in 0..360 step 30) {
                                        val angle = Math.toRadians(i.toDouble())
                                        val variation =
                                                if (isThinking || isModelLoading || isModelLaunching
                                                )
                                                        Math.sin(
                                                                angle * 4 +
                                                                        (rotation / 15).toDouble()
                                                        ) * 4
                                                else 0.0
                                        val r = radius + variation
                                        val x = center.x + (r * Math.cos(angle)).toFloat()
                                        val y = center.y + (r * Math.sin(angle)).toFloat()
                                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                path.close()

                                // Remplissage avec gradient radial interne pour la profondeur
                                drawContext.canvas.nativeCanvas.drawPath(
                                        path,
                                        android.graphics.Paint().apply {
                                                shader =
                                                        android.graphics.RadialGradient(
                                                                center.x,
                                                                center.y,
                                                                radius,
                                                                intArrayOf(
                                                                        Color.White.copy(
                                                                                        alpha = 0.9f
                                                                                )
                                                                                .toArgb(),
                                                                        orbColor.toArgb(),
                                                                        orbColor.copy(alpha = 0.8f)
                                                                                .toArgb()
                                                                ),
                                                                floatArrayOf(0f, 0.6f, 1f),
                                                                android.graphics.Shader.TileMode
                                                                        .CLAMP
                                                        )
                                                style = android.graphics.Paint.Style.FILL
                                                isAntiAlias = true
                                                setShadowLayer(30f, 0f, 0f, orbColor.toArgb())
                                        }
                                )
                        }

                        // 5. Système de Particules "Synaptiques" (Nouveau)
                        if (isThinking || isToolRunning || isModelLaunching) {
                                repeat(8) { i ->
                                        val particleRotation = (rotation * (1f + i * 0.1f)) % 360f
                                        val particleDistance = 50.dp + (i * 2).dp
                                        val particleSize = 2.dp + (i % 3).dp

                                        Box(
                                                modifier =
                                                        Modifier.graphicsLayer {
                                                                        rotationZ =
                                                                                particleRotation +
                                                                                        (i * 45)
                                                                        translationX =
                                                                                particleDistance
                                                                                        .toPx()
                                                                }
                                                                .size(particleSize)
                                                                .background(
                                                                        orbColor.copy(alpha = 0.6f),
                                                                        CircleShape
                                                                )
                                        )
                                }
                        }
                        
                        // Indicateur de micro actif
                        if (isListening) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .align(Alignment.BottomCenter)
                                    .offset(y = 20.dp)
                                    .background(Color.Red, CircleShape)
                                    .animateContentSize()
                            )
                        }

                        if (isToolRunning) {
                                CircularProgressIndicator(
                                        modifier = Modifier.size(65.dp),
                                        color = Color(0xFFFF9800),
                                        strokeWidth = 3.dp
                                )
                        }
                }

                if (isModelLoading) {
                        Text(
                                if (isModelLoading && !isThinking && !isModelLaunching)
                                        "Chargement du modèle..."
                                else "Optimisation du modèle...",
                                fontSize = 12.sp,
                                color = orbColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                        )
                } else if (isModelLaunching) {
                        Text(
                                "Lancement du modèle...",
                                fontSize = 12.sp,
                                color = orbColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                        )
                } else if (isToolRunning && toolName != null) {
                        Text(
                                "Jarvis utilise : $toolName",
                                fontSize = 12.sp,
                                color = orbColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                        )
                } else if (isThinking) {
                        Text(
                                "Jarvis réfléchit...",
                                fontSize = 12.sp,
                                color = baseColor.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 8.dp)
                        )
                }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisModeSelector(
        selectedMode: String?,
        onModeSelected: (String?) -> Unit,
        modes: List<Map<String, String>>,
        onAddMode: () -> Unit
) {
        LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
                item {
                        ModeChip(
                                name = "Standard",
                                icon = "🤖",
                                isSelected = selectedMode == null,
                                onClick = { onModeSelected(null) }
                        )
                }
                items(modes) { mode ->
                        val name = mode["name"] ?: ""
                        ModeChip(
                                name = name,
                                icon = mode["icon"] ?: "💎",
                                color =
                                        Color(
                                                android.graphics.Color.parseColor(
                                                        mode["color"] ?: "#4285F4"
                                                )
                                        ),
                                isSelected = selectedMode == name,
                                onClick = { onModeSelected(name) }
                        )
                }
                item {
                        Surface(
                                onClick = onAddMode,
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(36.dp)
                        ) {
                                Box(contentAlignment = Alignment.Center) {
                                        Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                }
                        }
                }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateModeDialog(onDismiss: () -> Unit, onCreate: (String, String, String, String) -> Unit) {
        var name by remember { mutableStateOf("") }
        var instruction by remember { mutableStateOf("") }
        var icon by remember { mutableStateOf("💎") }
        var color by remember { mutableStateOf("#4285F4") }
        val colors = listOf("Bleu", "Rouge", "Vert", "Jaune", "Violet", "Rose", "Cyan", "Orange")

        AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Créer une nouvelle Gem (Mode)") },
                text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                        value = name,
                                        onValueChange = { newName -> name = newName },
                                        label = { Text("Nom du mode") },
                                        modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                        value = instruction,
                                        onValueChange = { newInstr -> instruction = newInstr },
                                        label = { Text("Instructions système") },
                                        placeholder = { Text("Ex: Tu es un expert en cuisine...") },
                                        modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                ) {
                                        OutlinedTextField(
                                                value = icon,
                                                onValueChange = { newIcon -> icon = newIcon },
                                                label = { Text("Icône") },
                                                modifier = Modifier.width(80.dp)
                                        )

                                        Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                        "Couleur",
                                                        fontSize = 12.sp,
                                                        modifier = Modifier.padding(bottom = 4.dp)
                                                )
                                                LazyRow(
                                                        horizontalArrangement =
                                                                Arrangement.spacedBy(8.dp)
                                                ) {
                                                        items(colors) { cName ->
                                                                val hex =
                                                                        when (cName) {
                                                                                "Bleu" -> "#4285F4"
                                                                                "Rouge" -> "#EA4335"
                                                                                "Vert" -> "#34A853"
                                                                                "Jaune" -> "#FBBC04"
                                                                                "Violet" ->
                                                                                        "#A142F4"
                                                                                "Rose" -> "#FF69B4"
                                                                                "Cyan" -> "#00FFFF"
                                                                                "Orange" ->
                                                                                        "#FF8C00"
                                                                                else -> "#4285F4"
                                                                        }
                                                                val isColSelected = color == hex
                                                                Surface(
                                                                        onClick = { color = hex },
                                                                        modifier =
                                                                                Modifier.size(
                                                                                        32.dp
                                                                                ),
                                                                        shape = CircleShape,
                                                                        color =
                                                                                Color(
                                                                                        android.graphics
                                                                                                .Color
                                                                                                .parseColor(
                                                                                                        hex
                                                                                                )
                                                                                ),
                                                                        border =
                                                                                if (isColSelected)
                                                                                        BorderStroke(
                                                                                                2.dp,
                                                                                                Color.Black
                                                                                        )
                                                                                else null
                                                                ) {}
                                                        }
                                                }
                                        }
                                }
                        }
                },
                confirmButton = {
                        Button(
                                onClick = { onCreate(name, instruction, icon, color) },
                                enabled = name.isNotBlank() && instruction.isNotBlank()
                        ) { Text("Créer") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
        )
}

@Composable
fun CodeBlock(code: String, color: Color) {
    val clipboardManager = LocalClipboardManager.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1E1E1E),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Python",
                    fontSize = 10.sp,
                    color = color.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(code.trim())) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Text("📋", fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    text = code.trim(),
                    color = Color(0xFFCE9178),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
fun FormattedMessage(text: String, isUser: Boolean, color: Color, imageResult: String? = null) {
    val parts = text.split("```")
    Column {
        parts.forEachIndexed { index, part ->
            if (index % 2 == 0) {
                if (part.isNotBlank()) {
                    Text(
                        text = part.trim(),
                        modifier = Modifier.padding(16.dp),
                        color = color,
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    )
                }
            } else {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    CodeBlock(code = part.trim(), color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Affichage du graphique/image si présent
        imageResult?.let { base64 ->
            Box(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White) // Fond blanc pour les graphiques souvent sur fond blanc
            ) {
                val bitmap = remember(base64) {
                    try {
                        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (e: Exception) {
                        null
                    }
                }
                
                bitmap?.let {
                    androidx.compose.foundation.Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Graphique généré",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
}


@Composable
fun TypewriterText(
        text: String,
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        onComplete: () -> Unit = {}
) {
        var displayedText by remember { mutableStateOf("") }

        LaunchedEffect(text) {
                text.forEachIndexed { index, _ ->
                        displayedText = text.substring(0, index + 1)
                        kotlinx.coroutines.delay(15) // Vitesse de frappe (15ms par caractère)
                }
                onComplete()
        }

        Text(
                text = displayedText,
                modifier = modifier,
                color = color,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Normal
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeChip(
        name: String,
        icon: String,
        color: Color = MaterialTheme.colorScheme.primary,
        isSelected: Boolean,
        onClick: () -> Unit
) {
        val scale by animateFloatAsState(if (isSelected) 1.05f else 1f)

        Surface(
                onClick = onClick,
                modifier =
                        Modifier.padding(vertical = 4.dp)
                                .graphicsLayer(scaleX = scale, scaleY = scale),
                shape = RoundedCornerShape(16.dp),
                color =
                        if (isSelected) color.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
                Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                        Text(icon, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
                        Text(
                                name,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color =
                                        if (isSelected) color
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                }
        }
}
}
}
