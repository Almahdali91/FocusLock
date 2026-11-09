package com.focuslock.app

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.focuslock.app.data.FocusDatabase
import com.focuslock.app.data.FocusRepository
import com.focuslock.app.data.FocusSessionEntity
import com.focuslock.app.data.SettingsStore
import com.focuslock.app.domain.BreakPolicy
import com.focuslock.app.domain.DndFilterPolicy
import com.focuslock.app.services.FocusForegroundService
import com.focuslock.app.system.PermissionManager
import com.focuslock.app.system.AccessibilityDisclosureConsent
import com.focuslock.app.system.ProtectionCapabilityManager
import com.focuslock.app.system.ProtectionTier
import com.focuslock.app.system.StrictModeManager
import com.focuslock.app.system.UninstallProtectionManager
import com.focuslock.app.system.StrictSessionPolicy
import com.focuslock.app.system.SessionClockStore
import com.focuslock.app.presentation.OnboardingScreen
import com.focuslock.app.presentation.BreakInfoScreen
import com.focuslock.app.presentation.DashboardScreen
import com.focuslock.app.presentation.StatisticsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Navy = Color(0xFF07162D)
private val Panel = Color(0xFF102747)
private val Blue = Color(0xFF12B9F4)
private val Cyan = Color(0xFF25D7E8)

class MainActivity : ComponentActivity() {
    private val strictLockTaskPrefs by lazy {
        getSharedPreferences("strict_lock_task_state", MODE_PRIVATE)
    }
    private var strictLockTaskOwned = false

    /**
     * Called only from the Room-backed session-state observer. The durable marker
     * distinguishes a Lock Task started by FocusLock from a pre-existing kiosk.
     */
    fun syncStrictLockTask(shouldLock: Boolean) {
        val activityManager = getSystemService(ActivityManager::class.java)
        if (shouldLock) {
            if (activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED) {
                // Do not claim ownership of a kiosk state started by another controller.
                strictLockTaskOwned = strictLockTaskPrefs.getBoolean("owned", false)
                return
            }
            runCatching {
                startLockTask()
                val entered = activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
                strictLockTaskOwned = entered
                strictLockTaskPrefs.edit { putBoolean("owned", entered) }
            }.onFailure {
                // startLockTask can fail while the Activity is backgrounded.
                // Keep the ownership marker intact; ON_RESUME increments permissionRefresh,
                // which reruns the policy effect and retries from the foreground.
            }
        } else {
            // Release only a Lock Task state previously started by FocusLock.
            // Never stop an unrelated managed kiosk merely because this app is D.O.
            val owned = strictLockTaskOwned || strictLockTaskPrefs.getBoolean("owned", false)
            if (owned && activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED) {
                runCatching { stopLockTask() }
            }
            strictLockTaskOwned = false
            strictLockTaskPrefs.edit { putBoolean("owned", false) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: com.focuslock.app.presentation.FocusLockAppViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            FocusLockApp(viewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        val activityManager = getSystemService(ActivityManager::class.java)
        if (activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_LOCKED) {
            strictLockTaskOwned = false
            strictLockTaskPrefs.edit { putBoolean("owned", false) }
        } else {
            strictLockTaskOwned = strictLockTaskPrefs.getBoolean("owned", false)
        }
    }
}

private fun startFocusService(context: Context, session: FocusSessionEntity) {
    val intent = Intent(context, FocusForegroundService::class.java).apply {
        action = FocusForegroundService.ACTION_START
        putExtra(FocusForegroundService.EXTRA_SESSION_ID, session.id)
    }
    context.startForegroundService(intent)
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusLockApp(viewModel: com.focuslock.app.presentation.FocusLockAppViewModel) {
    val context = LocalContext.current
    val currentConfiguration = LocalConfiguration.current
    val repository = viewModel.repository
    val settings = viewModel.settings
    val scope = rememberCoroutineScope()
    val prefs by settings.preferences.collectAsState(initial = com.focuslock.app.data.UserPreferences())
    val sessions by repository.recentSessions.collectAsState(initial = emptyList())
    val active by viewModel.active.collectAsState()
    val screen by viewModel.screen.collectAsState()
    val durationMinutes by viewModel.durationMinutes.collectAsState()
    val breaksEnabled by viewModel.breaksEnabled.collectAsState()
    val selectedLockMode by viewModel.selectedLockMode.collectAsState()
    val remaining by viewModel.remaining.collectAsState()
    val loaded by viewModel.loaded.collectAsState()
    val showOnboarding by viewModel.showOnboarding.collectAsState()
    val showPermissionChecklist by viewModel.showPermissionChecklist.collectAsState()
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val strictCapable = remember(permissionRefresh) {
        ProtectionCapabilityManager.detect(context).let { it.deviceOwner && it.lockTaskPermitted }
    }
    val effectiveLockMode = if (selectedLockMode == "STRICT" && strictCapable) "STRICT" else "STANDARD"
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permissionRefresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationsReady = remember(permissionRefresh) {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        runtimeGranted && context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }
    val accessibilityReady = remember(permissionRefresh) { PermissionManager.isStandardAccessibilityEnabled(context) }
    val notificationListenerReady = remember(permissionRefresh) {
        android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            .split(":").mapNotNull { android.content.ComponentName.unflattenFromString(it) }
            .any { it.packageName == context.packageName }
    }
    val dndAccessReady = remember(permissionRefresh) {
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    }
    val usageAccessReady = remember(permissionRefresh) {
        val appOps = context.getSystemService(android.app.AppOpsManager::class.java)
        appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }
    val callScreeningReady = remember(permissionRefresh) {
        context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)
    }
    val accessibilityRequired = effectiveLockMode != "STRICT" && prefs.blockedPackages.isNotEmpty()
    val protectionAccessReady = PermissionManager.isProtectionReady(
        effectiveLockMode,
        prefs.blockedPackages,
        accessibilityReady
    )
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRefresh++ }
    val ar = prefs.language == "ar"
    val dark = prefs.darkTheme
    val parentScreen = when (screen) {
        "safety" -> if (active != null) "home" else "settings"
        "permissions", "protection", "strict_mode", "notifications", "break" -> "settings"
        "permission_required" -> "session_review"
        "session_review" -> "session"
        "session" -> "home"
        else -> null
    }
    val navigateBack: () -> Unit = {
        parentScreen?.let { viewModel.setScreen(it) }
    }
    BackHandler(enabled = parentScreen != null && (active == null || screen == "safety")) {
        navigateBack()
    }
    val bg = if (dark) Navy else Color(0xFFF2F6FC)
    val card = if (dark) Panel else Color.White
    val fg = if (dark) Color(0xFFF4F8FF) else Color(0xFF10213A)
    val muted = if (dark) Color(0xFF9BB1CC) else Color(0xFF63748B)

    LaunchedEffect(permissionRefresh, active?.id, protectionAccessReady) {
        viewModel.initialize(context, protectionAccessReady)
        // Losing notification permission must not terminate an active focus session.
        // Only loss of access required by the selected-app protection feature does.
        viewModel.refreshRequiredAccess(context, protectionAccessReady)
    }
    LaunchedEffect(permissionRefresh, active?.id, active?.sessionStatus, active?.selectedLockMode) {
        val hostActivity = context as? MainActivity
        val capabilities = ProtectionCapabilityManager.detect(context)
        val shouldLock = StrictSessionPolicy.shouldEnterLockTask(
            active, capabilities.deviceOwner, capabilities.lockTaskPermitted
        )
        hostActivity?.syncStrictLockTask(shouldLock)
    }

     MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Blue, secondary = Cyan, background = Navy, surface = Panel, onPrimary = Navy, onBackground = fg, onSurface = fg)
        else lightColorScheme(primary = Color(0xFF087EBD), secondary = Color(0xFF008C9E), background = bg, surface = card, onBackground = fg, onSurface = fg)
    ) {
        val localizedContext = remember(context, ar) {
            val configuration = Configuration(currentConfiguration)
            configuration.setLocale(if (ar) Locale.forLanguageTag("ar") else Locale.ENGLISH)
            context.createConfigurationContext(configuration)
        }
        CompositionLocalProvider(
            LocalResources provides localizedContext.resources,
            LocalConfiguration provides localizedContext.resources.configuration,
            LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr
        ) {
            if (loaded && !showOnboarding && active == null && (showPermissionChecklist || !protectionAccessReady)) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text(if (ar) "إعداد الحماية" else "Protection setup") },
                    text = {
                        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (ar) "تحقق من متطلبات مستوى الحماية المختار. إشعارات الجلسة اختيارية، وإمكانية الوصول مطلوبة فقط للوضع القياسي عند تحديد تطبيقات للحجب." else "Review the requirements for the selected protection mode. Session notifications are optional; Accessibility is required only for Standard mode when apps are selected for blocking.")
                            Text((if (notificationsReady) "✓ " else "• ") + (if (ar) "إشعارات الجلسة" else "Session notifications") + if (notificationsReady) (if (ar) " — مفعّلة" else " — Enabled") else (if (ar) " — اختيارية، غير مفعّلة" else " — Optional, not enabled"))
                            Text((if (accessibilityReady || !accessibilityRequired) "✓ " else "• ") + (if (ar) "خدمة إمكانية الوصول" else "Accessibility protection service") + if (accessibilityReady) (if (ar) " — مفعّلة" else " — Enabled") else if (accessibilityRequired) (if (ar) " — مطلوبة للوضع القياسي" else " — Required for Standard mode") else (if (ar) " — غير مطلوبة للوضع المختار" else " — Not required for selected mode"))
                            Text((if (notificationListenerReady) "✓ " else "• ") + (if (ar) "وصول الإشعارات للقراءة المحلية" else "Notification Listener (optional local capture)") + if (notificationListenerReady) (if (ar) " — مفعّل" else " — Enabled") else (if (ar) " — اختياري" else " — Optional"))
                            Text((if (dndAccessReady) "✓ " else "• ") + (if (ar) "وصول عدم الإزعاج" else "Do Not Disturb access (optional)") + if (dndAccessReady) (if (ar) " — مفعّل" else " — Enabled") else (if (ar) " — اختياري" else " — Optional"))
                            Text((if (usageAccessReady) "✓ " else "• ") + (if (ar) "وصول الاستخدام" else "Usage Access (supplemental)") + if (usageAccessReady) (if (ar) " — مفعّل" else " — Enabled") else (if (ar) " — اختياري" else " — Optional"))
                            Text((if (callScreeningReady) "✓ " else "• ") + (if (ar) "دور فلترة المكالمات" else "Call screening role (optional)") + if (callScreeningReady) (if (ar) " — مفعّل" else " — Enabled") else (if (ar) " — اختياري" else " — Optional"))
                            Text(if (ar) "إذن Device Owner ليس ضروريًا للبدء ويبقى خيارًا منفصلًا في الإعدادات." else "Device Owner is not required here; it remains a separate test option in Settings.", color = muted, fontSize = 12.sp)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (protectionAccessReady) {
                                context.getSharedPreferences("focus_permission_checklist", Context.MODE_PRIVATE)
                                    .edit { putBoolean("seen", true) }
                                viewModel.setShowPermissionChecklist(false)
                            } else {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) {
                            Text(if (protectionAccessReady) (if (ar) "متابعة" else "Continue") else (if (ar) "تفعيل إمكانية الوصول" else "Enable Accessibility"))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text(if (ar) "إعدادات الإشعارات" else "Notification settings") }
                    }
                )
            }
            Scaffold(
                containerColor = bg,
                topBar = {
                    if (!showOnboarding) TopAppBar(
                        navigationIcon = {
                            if (parentScreen != null && (active == null || screen == "safety")) {
                                IconButton(onClick = navigateBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (ar) "رجوع" else "Back", tint = fg)
                                }
                            }
                        },
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Image(painterResource(com.focuslock.app.R.drawable.focuslock_logo), null, Modifier.size(38.dp).clip(CircleShape))
                                Column {
                                    Text("FocusLock", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                                    Text(if (ar) "مساحة هادئة لإنجازك" else "Your space to do more", fontSize = 10.sp, color = muted)
                                }
                            }
                        },
                        actions = { IconButton(onClick = { scope.launch { settings.setLanguage(if (ar) "en" else "ar") } }) { Text(if (ar) "EN" else "ع", fontWeight = FontWeight.Bold, color = Blue) } },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, titleContentColor = fg)
                    )
                },
                bottomBar = {
                    if (!showOnboarding && active == null) NavigationBar(containerColor = card) {
                        val items = listOf(
                            Triple("home", if (ar) "الرئيسية" else "Home", Icons.Default.Home),
                            Triple("session", if (ar) "جلسة" else "Focus", Icons.Default.Timer),
                            Triple("stats", if (ar) "الإحصاءات" else "Stats", Icons.Default.BarChart),
                            Triple("settings", if (ar) "الإعدادات" else "Settings", Icons.Default.Settings)
                        )
                        items.forEach { (key, label, icon) ->
                            NavigationBarItem(selected = screen == key, onClick = { viewModel.setScreen(key) }, icon = { Icon(icon, contentDescription = label) }, label = { Text(label, fontSize = 11.sp) })
                        }
                    }
                }
            ) { padding ->
                when {
                    !loaded -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    showOnboarding -> OnboardingScreen(ar, fg, muted, card, onLanguage = { language -> scope.launch { settings.setLanguage(language) } }, onComplete = { scope.launch { settings.setOnboardingCompleted(); viewModel.setShowOnboarding(false); viewModel.setScreen("permissions") } }, modifier = Modifier.padding(padding))
                    active != null && screen == "safety" -> SafetyEmergencyScreen(context, repository, ar, fg, muted, card, Modifier.padding(padding))
                    active != null -> ActiveSessionScreen(
                        active!!,
                        remaining,
                        active!!.sessionStatus == "BREAK_ACTIVE",
                        ar,
                        fg,
                        muted,
                        card,
                        onSafety = { viewModel.setScreen("safety") },
                        modifier = Modifier.padding(padding)
                    )
                    screen == "session" -> SessionSetupScreen(durationMinutes, { viewModel.setDurationMinutes(it) }, breaksEnabled, { viewModel.setBreaksEnabled(it) }, selectedLockMode, { viewModel.setSelectedLockMode(it) }, ProtectionCapabilityManager.detect(context).let { it.deviceOwner && it.lockTaskPermitted }, ar, fg, muted, card, onReview = { viewModel.setScreen("session_review") }, modifier = Modifier.padding(padding))
                    screen == "session_review" -> SessionReviewScreen(durationMinutes, breaksEnabled, effectiveLockMode, ar, fg, muted, card, onBack = { viewModel.setScreen("session") }, onStart = {
                        viewModel.startSession(context, effectiveLockMode) {
                            viewModel.setScreen("permission_required")
                        }
                    }, modifier = Modifier.padding(padding))
                    screen == "permissions" -> PermissionsGuideScreen(context, ar, fg, muted, card, Modifier.padding(padding))
                    screen == "permission_required" -> PermissionRequiredScreen(
                        context, ar, fg, muted, card,
                        onRetry = { viewModel.setScreen("session_review") },
                        modifier = Modifier.padding(padding)
                    )
                    screen == "strict_mode" -> StrictModeSettingsScreen(context, ar, fg, muted, card, Modifier.padding(padding))
                    screen == "protection" -> ProtectionSettingsScreen(context, prefs.blockedPackages, ar, fg, muted, card,
                        onToggle = { packageName, enabled ->
                            scope.launch { settings.setBlockedPackage(packageName, enabled) }
                        }, modifier = Modifier.padding(padding))
                    screen == "break" -> BreakInfoScreen(ar, fg, muted, card, onBack = { viewModel.setScreen("settings") }, Modifier.padding(padding))
                    screen == "notifications" -> NotificationCenterScreen(context, repository, ar, fg, muted, card, Modifier.padding(padding))
                    screen == "safety" -> SafetyEmergencyScreen(context, repository, ar, fg, muted, card, Modifier.padding(padding))
                    screen == "stats" -> StatisticsScreen(sessions, ar, fg, muted, card, Modifier.padding(padding))
                    screen == "settings" -> SettingsScreen(ar, dark, fg, muted, card,
                        onLanguage = { scope.launch { settings.setLanguage(if (ar) "en" else "ar") } },
                        onTheme = { scope.launch { settings.setDarkTheme(!dark) } },
                        onDelete = { scope.launch { repository.deleteAllSessions() } },
                        onWipeAll = {
                            scope.launch {
                                // A full wipe must never destroy the recovery snapshot
                                // while a focus session or an owned Lock Task may still run.
                                if (repository.activeSession() != null) {
                                    Toast.makeText(
                                        context,
                                        "Cannot erase data while a focus session is active.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    return@launch
                                }

                                // Restore the exact pre-session DPM allowlist first.
                                // If restoration fails, keep every recovery record and all
                                // user data intact so the operation can be retried safely.
                                val policyRecovery = runCatching {
                                    StrictModeManager.releaseSessionAllowlist(context).getOrThrow()
                                    UninstallProtectionManager.release(context).getOrThrow()
                                }
                                if (policyRecovery.isFailure) {
                                    Toast.makeText(
                                        context,
                                        "Data was not erased because device policy recovery failed. Retry after resolving the device policy issue.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    return@launch
                                }

                                repository.clearUserData()
                                settings.clear()
                                listOf(
                                    "focus_service_state",
                                    "session_clock_anchors",
                                    "notification_capture",
                                    "call_screening_preferences",
                                    "focus_permission_checklist",
                                    "strict_lock_task_policy",
                                    "strict_lock_task_state",
                                    "focus_uninstall_protection",
                                    "accessibility_disclosure"
                                ).forEach { name ->
                                    context.getSharedPreferences(name, Context.MODE_PRIVATE)
                                        .edit(commit = true) { clear() }
                                }
                                viewModel.setShowOnboarding(true)
                                viewModel.setScreen("home")
                            }
                        },
                        onPermissions = { viewModel.setScreen("permissions") },
                        onBreakInfo = { viewModel.setScreen("break") },
                        onNotifications = { viewModel.setScreen("notifications") },
                        onSafety = { viewModel.setScreen("safety") },
                        onProtection = { viewModel.setScreen("protection") },
                        onStrictMode = { viewModel.setScreen("strict_mode") },
                        modifier = Modifier.padding(padding))
                    else -> DashboardScreen(sessions, ar, fg, muted, card, onStart = { viewModel.setScreen("session") }, onStats = { viewModel.setScreen("stats") }, modifier = Modifier.padding(padding))
                }
            }
        }
    }
}




@Composable
private fun PermissionsGuideScreen(context: Context, ar: Boolean, fg: Color, muted: Color, card: Color, modifier: Modifier = Modifier) {
    val accessibilityEnabled = PermissionManager.isStandardAccessibilityEnabled(context)
    val rows = if (ar) listOf(
        "الإشعارات" to "لعرض حالة جلسة التركيز والتنبيهات. يمكن رفضها، وقد يقل وضوح حالة الجلسة.",
        "إمكانية الوصول" to "تستخدمها الحماية القياسية لرصد فتح التطبيقات التي اخترتها وعرض شاشة حجب أثناء الجلسة النشطة. يمكنك إيقافها في أي وقت من إعدادات Android.",
        "شاشة الحجب Overlay" to "تعرضها خدمة إمكانية الوصول فوق التطبيق المحدد دون طلب صلاحية الظهور فوق التطبيقات المنفصلة.",
        "عدم الإزعاج" to "لإدارة المقاطعات وفق اختيارك. لا يتم تفعيله دون موافقة النظام."
    ) else listOf(
        "Notifications" to "Show focus-session status and alerts. You may deny this; session visibility may be reduced.",
        "Accessibility" to "Standard Protection uses this service to detect selected apps and display a blocking screen during an active focus session. You can disable it in Android settings.",
        "Blocking overlay" to "The Accessibility service displays the blocking screen above a selected app; no separate draw-over-apps permission is requested.",
        "Do Not Disturb" to "Manage interruptions only with explicit system approval."
    )
    val secondaryRows = rows.drop(1)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(if (ar) "دليل الصلاحيات" else "Permissions guide", color = fg, fontSize = 25.sp, fontWeight = FontWeight.Bold) }
        item { Text(if (ar) "لا يطلب FocusLock صلاحية دون توضيح الغرض. الصلاحيات الحساسة يفعّلها المستخدم بنفسه من إعدادات Android." else "FocusLock explains why access is needed. Sensitive access is enabled by you in Android settings.", color = muted) }
        item { NotificationPermissionCard(context, ar, fg, card) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (ar) "إمكانية الوصول — مطلوبة عند اختيار تطبيقات للحجب" else "Accessibility — required when apps are selected for blocking", color = fg, fontWeight = FontWeight.Bold)
                    Text(if (accessibilityEnabled) (if (ar) "مفعّلة" else "Enabled") else (if (ar) "غير مفعّلة" else "Not enabled"), color = if (accessibilityEnabled) Color(0xFF27AE78) else Color(0xFFE09A35))
                    Text(if (ar) "تستخدم لرصد انتقالك إلى التطبيقات التي اخترتها وعرض شاشة الحجب. لا تقرأ FocusLock محتوى النوافذ." else "Detects transitions to apps you selected and displays the blocking screen. FocusLock does not retrieve window contents.", color = muted, fontSize = 12.sp)
                    Button(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (ar) "فتح إعدادات إمكانية الوصول" else "Open Accessibility settings")
                    }
                }
            }
        }
        items(secondaryRows.size) { i -> Card(colors = CardDefaults.cardColors(containerColor = card)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(secondaryRows[i].first, color = fg, fontWeight = FontWeight.Bold); Text(secondaryRows[i].second, color = muted, fontSize = 13.sp) } } }
    }
}

@Composable
private fun NotificationPermissionCard(
    context: Context,
    ar: Boolean,
    fg: Color,
    card: Color
) {
    val notificationManager = remember(context) {
        context.getSystemService(NotificationManager::class.java)
    }
    fun focusNotificationsEnabled(): Boolean {
        val channel = notificationManager.getNotificationChannel("focus_session")
        return notificationManager.areNotificationsEnabled() &&
            (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    }
    var permissionGranted by remember(context) {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var notificationsEnabled by remember(context) {
        mutableStateOf(focusNotificationsEnabled())
    }
    var requestAttempted by remember(context) { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        notificationsEnabled = focusNotificationsEnabled()
        requestAttempted = true
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                notificationsEnabled = focusNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val ready = permissionGranted && notificationsEnabled
    val buttonLabel = when {
        ready -> if (ar) "إدارة إعدادات الإشعارات" else "Manage notification settings"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !permissionGranted && !requestAttempted ->
            if (ar) "السماح بالإشعارات" else "Allow notifications"
        else -> if (ar) "فتح إعدادات الإشعارات" else "Open notification settings"
    }

    Card(colors = CardDefaults.cardColors(containerColor = card)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(if (ar) "الإشعارات" else "Notifications", color = fg, fontWeight = FontWeight.Bold)
            Text(
                if (ready) {
                    if (ar) "مفعّلة — سيظهر إشعار حالة جلسة التركيز." else "Enabled — focus-session status notifications can be shown."
                } else {
                    if (ar) "غير مفعّلة. قد تستمر الجلسة، لكن قد يخفي Android إشعارها من درج الإشعارات." else "Not enabled. A focus session can still run, but Android may hide its notification from the notification drawer."
                },
                color = if (ready) Color(0xFF27AE78) else Color(0xFFE09A35),
                fontSize = 13.sp
            )
            Button(
                onClick = {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !permissionGranted && !requestAttempted
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun PermissionRequiredScreen(context: Context, ar: Boolean, fg: Color, muted: Color, card: Color, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val enabled = PermissionManager.isStandardAccessibilityEnabled(context)
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.GppBad, null, tint = Color(0xFFE09A35), modifier = Modifier.size(54.dp))
        Text(if (ar) "لا يمكن بدء جلسة الحماية القياسية" else "Standard Protection cannot start", color = fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            if (ar) "اخترت تطبيقات لحجبها، لكن خدمة إمكانية الوصول غير مفعّلة. بدء الجلسة الآن سيعني أن الحماية المطلوبة لن تعمل. فعّل الخدمة أولاً من إعدادات Android." else "You selected apps to block, but the Accessibility service is not enabled. Starting now would leave the requested protection inactive. Enable the service in Android Settings first.",
            color = muted
        )
        Card(colors = CardDefaults.cardColors(containerColor = card)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (ar) "الحالة الحالية" else "Current status", color = fg, fontWeight = FontWeight.Bold)
                Text(if (enabled) (if (ar) "مفعّلة" else "Enabled") else (if (ar) "غير مفعّلة" else "Not enabled"), color = if (enabled) Color(0xFF27AE78) else Color(0xFFE09A35))
                Text(if (ar) "هذه الصلاحية تتحكم في اكتشاف التطبيقات المحددة وإظهار شاشة الحجب أثناء الجلسة." else "This access detects selected apps and displays the blocking screen during the session.", color = muted, fontSize = 12.sp)
            }
        }
        Button(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (ar) "تفعيل إمكانية الوصول" else "Enable Accessibility")
        }
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(if (ar) "إعادة التحقق والمتابعة" else "Recheck and continue")
        }
    }
}





@Composable
private fun SessionSetupScreen(minutes: Int, onMinutes: (Int) -> Unit, breaks: Boolean, onBreaks: (Boolean) -> Unit, lockMode: String, onLockMode: (String) -> Unit, strictAvailable: Boolean, ar: Boolean, fg: Color, muted: Color, card: Color, onReview: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text(if (ar) "إعداد الجلسة" else "Session setup", color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (ar) "مدة التركيز" else "Focus duration", color = muted)
                    Text("${minutes / 60} ${if (ar) "س" else "h"} ${minutes % 60} ${if (ar) "د" else "min"}", color = fg, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
                    Slider(value = minutes.toFloat(), onValueChange = { onMinutes(it.toInt().coerceIn(5, 480)) }, valueRange = 5f..480f)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf(5, 15, 30, 60, 90, 120, 180, 240).forEach { value ->
                            TextButton(onClick = { onMinutes(value) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text(if (ar) {
                                    if (value >= 60) "${value / 60}\u0633${if (value % 60 > 0) " ${value % 60}\u062f" else ""}" else "${value}\u062f"
                                } else {
                                    if (value >= 60) "${value / 60}h${if (value % 60 > 0) " ${value % 60}" else ""}" else "${value}m"
                                }, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (ar) "مستوى الحماية" else "Protection level", color = fg, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = lockMode == "STANDARD", onClick = { onLockMode("STANDARD") }, label = { Text(if (ar) "قياسي" else "Standard") })
                        FilterChip(selected = lockMode == "STRICT", onClick = { if (strictAvailable) onLockMode("STRICT") }, enabled = strictAvailable, label = { Text(if (ar) "صارم (مؤسسي)" else "Strict (managed)") })
                    }
                    Text(
                        if (strictAvailable) (if (ar) "الوضع الصارم متاح لأن الجهاز مُدار ويسمح بـ Lock Task." else "Strict mode is available because this device is managed and permits Lock Task.")
                        else (if (ar) "الوضع الصارم غير متاح على هذا الجهاز. يتطلب إعداد Device Owner مسبقًا؛ سيظل الوضع القياسي متاحًا." else "Strict mode is unavailable on this device. It requires prior Device Owner provisioning; Standard mode remains available."),
                        color = muted, fontSize = 12.sp
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (ar) "الاستراحات الذكية" else "Smart breaks", color = fg, fontWeight = FontWeight.Bold)
                        Text(if (ar) "استراحة 10 دقائق بعد أول ساعتين، ثم بعد كل ساعة تركيز مكتملة مع بقاء وقت." else "10-minute break after the first two hours, then each additional completed focus hour while time remains.", color = muted, fontSize = 12.sp)
                        Text("${BreakPolicy.breakCount(minutes * 60_000L, breaks)} ${if (ar) "استراحة متوقعة" else "planned breaks"}", color = Blue, fontSize = 12.sp)
                    }
                    Switch(checked = breaks, onCheckedChange = onBreaks)
                }
            }
        }
        item {
            Button(onClick = onReview, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (ar) "مراجعة وبدء الجلسة" else "Review & start", fontWeight = FontWeight.Bold)
            }
        }
        item { Text(if (ar) "ملاحظة: جلسات التركيز تحفظ محلياً. أوضاع الحماية المتقدمة تتطلب إعداد صلاحيات النظام." else "Sessions are stored locally. Advanced blocking modes require explicit Android system access.", color = muted, fontSize = 12.sp) }
    }
}


@Composable
private fun SessionReviewScreen(minutes: Int, breaks: Boolean, lockMode: String, ar: Boolean, fg: Color, muted: Color, card: Color, onBack: () -> Unit, onStart: () -> Unit, modifier: Modifier = Modifier) {
    val count = BreakPolicy.breakCount(minutes * 60_000L, breaks)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(if (ar) "مراجعة الجلسة" else "Review your session", color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (ar) "مدة التركيز" else "Focus duration", color = muted)
                    Text(if (ar) "$minutes دقيقة" else "$minutes minutes", color = fg, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    HorizontalDivider()
                    Text(if (ar) "مستوى الحماية" else "Protection level", color = muted)
                    Text(if (lockMode == "STRICT") (if (ar) "صارم — إدارة مؤسسية / Lock Task" else "Strict — managed Lock Task") else (if (ar) "قياسي — إمكانية الوصول" else "Standard — Accessibility"), color = fg, fontWeight = FontWeight.Bold)
                    HorizontalDivider()
                    Text(if (ar) "الاستراحات" else "Breaks", color = muted)
                    Text(if (!breaks) (if (ar) "معطلة" else "Disabled") else "$count ${if (ar) "استراحة × 10 دقائق" else "breaks × 10 minutes"}", color = fg, fontWeight = FontWeight.Bold)
                    Text(if (ar) "تبدأ أول استراحة بعد ساعتين من التركيز، ثم استراحة بعد كل ساعة إضافية مكتملة." else "The first break starts after two hours of focus, then after each additional completed focus hour.", color = muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(if (ar) "تعديل" else "Edit") }
                Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text(if (ar) "ابدأ الآن" else "Start now") }
            }
        }
    }
}

@Composable
private fun ActiveSessionScreen(
    session: FocusSessionEntity,
    remaining: Long,
    onBreak: Boolean,
    ar: Boolean,
    fg: Color,
    muted: Color,
    card: Color,
    onSafety: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (onBreak) {
        (1f - remaining.toFloat() / BreakPolicy.DEFAULT_BREAK_MILLIS).coerceIn(0f, 1f)
    } else {
        (1f - remaining.toFloat() / session.durationMillis.coerceAtLeast(1L)).coerceIn(0f, 1f)
    }
    Column(modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(if (onBreak) (if (ar) "حان وقت الاستراحة" else "It's break time") else (if (ar) "أنت في منطقة التركيز" else "You're in your focus zone"), color = muted, fontSize = 15.sp)
        Spacer(Modifier.height(28.dp))
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(236.dp), strokeWidth = 12.dp, color = Blue, trackColor = muted.copy(alpha = .2f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (onBreak) Icons.Default.Coffee else Icons.Default.Lock, null, tint = Cyan, modifier = Modifier.size(26.dp))
                Text(formatClock(remaining), color = fg, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
                Text(if (onBreak) (if (ar) "الاستراحة المتبقية" else "BREAK REMAINING") else (if (ar) "وقت التركيز المتبقي" else "FOCUS REMAINING"), color = muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
            }
        }
        Spacer(Modifier.height(26.dp))
        Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text(if (ar) "مدة الجلسة" else "Session length", color = muted, fontSize = 12.sp); Text(formatDuration(session.durationMillis, ar), color = fg, fontWeight = FontWeight.Bold) }
                Column(horizontalAlignment = Alignment.End) { Text(if (ar) "الاستراحات" else "Breaks", color = muted, fontSize = 12.sp); Text(BreakPolicy.breakCount(session.durationMillis, session.breakEnabled).toString(), color = fg, fontWeight = FontWeight.Bold) }
            }
        }
        Text(
            if (session.selectedLockMode == "STRICT") {
                if (ar) "الحماية الصارمة تعمل عبر Lock Task المُدار من Android. تظل وظائف الطوارئ متاحة وفق نظام Android."
                else "Strict protection is enforced by Android managed Lock Task. Emergency functions remain available according to Android."
            } else {
                if (ar) "يُحجب ما اخترته عند تفعيل خدمة إمكانية الوصول. يظل زر Home والتطبيقات الحديثة متاحين في الوضع القياسي."
                else "Selected apps are blocked when Accessibility is enabled. Home and Recents remain available in Standard Mode."
            },
            color = muted,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(18.dp))
        OutlinedButton(onClick = onSafety, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.HealthAndSafety, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (ar) "السلامة والطوارئ" else "Safety & emergency")
        }
    }
}




private data class LaunchableApp(val packageName: String, val label: String)

@Composable
private fun ProtectionSettingsScreen(
    context: Context,
    selectedPackages: Set<String>,
    ar: Boolean,
    fg: Color,
    muted: Color,
    card: Color,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val appLoadState by produceState(
        initialValue = false to emptyList<LaunchableApp>(),
        key1 = context.applicationContext
    ) {
        val appContext = context.applicationContext
        val loadedApps = withContext(Dispatchers.IO) {
            val packageManager = appContext.packageManager
            val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(launchIntent, 0)
                .mapNotNull { result ->
                    val activity = result.activityInfo ?: return@mapNotNull null
                    val packageName = activity.packageName
                    val protectedSystemPackages = setOf(
                        "com.android.phone",
                        "com.google.android.dialer",
                        "com.samsung.android.dialer",
                        "com.android.emergency",
                        "com.samsung.android.app.telephonyui"
                    )
                    if (packageName == appContext.packageName || packageName in protectedSystemPackages) null
                    else LaunchableApp(packageName, result.loadLabel(packageManager).toString())
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase(Locale.ROOT) }
        }
        value = true to loadedApps
    }
    val appsLoaded = appLoadState.first
    val apps = appLoadState.second
    var searchQuery by remember { mutableStateOf("") }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    LaunchedEffect(selectedPackages) {
        if (selectedPackages.isNotEmpty() && !AccessibilityDisclosureConsent.hasAccepted(context)) {
            showAccessibilityDisclosure = true
        }
    }
    val filteredApps = remember(apps, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) apps else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(if (ar) "الحماية القياسية" else "Standard protection", color = fg, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text(if (ar) "حدد التطبيقات التي تريد حجبها أثناء جلسات التركيز. لا تحجب وظائف النظام أو تطبيق الهاتف والطوارئ." else "Select apps to block during focus sessions. System functions, Phone and emergency access are not blocked.", color = muted, fontSize = 13.sp)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (ar) "تفعيل خدمة إمكانية الوصول" else "Enable Accessibility service", color = fg, fontWeight = FontWeight.Bold)
                    Text(if (ar) "قبل فتح إعدادات Android، اقرأ الإفصاح التالي ووافق عليه. لن تعمل الحماية القياسية دون موافقتك وتفعيل الخدمة يدويًا." else "Before opening Android Settings, review and accept the disclosure. Standard Protection will not run without your consent and your manual activation of the service.", color = muted, fontSize = 12.sp)
                    Button(onClick = { showAccessibilityDisclosure = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (ar) "مراجعة الإفصاح والمتابعة" else "Review disclosure and continue")
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (ar) "\u0627\u0644\u062a\u0637\u0628\u064a\u0642\u0627\u062a \u0627\u0644\u0642\u0627\u0628\u0644\u0629 \u0644\u0644\u062a\u0634\u063a\u064a\u0644 (${apps.size})" else "Launchable apps (${apps.size})", color = fg, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { apps.forEach { app -> onToggle(app.packageName, true) } },
                        enabled = appsLoaded && apps.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (ar) "تحديد الكل" else "Select all")
                    }
                    TextButton(
                        onClick = { selectedPackages.forEach { packageName -> onToggle(packageName, false) } },
                        enabled = selectedPackages.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (ar) "إلغاء تحديد الكل" else "Clear all")
                    }
                }
                Text(
                    if (ar) "يمكنك تحديد جميع التطبيقات أو إلغاء تحديدها دفعة واحدة."
                    else "Select or clear all apps at once.",
                    color = muted,
                    fontSize = 11.sp
                )
            }
        }
        if (!appsLoaded) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(if (ar) "جارٍ تحميل التطبيقات…" else "Loading apps…", color = muted, fontSize = 13.sp)
                }
            }
        }
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(if (ar) "\u0628\u062d\u062b \u0639\u0646 \u062a\u0637\u0628\u064a\u0642" else "Search apps") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = if (ar) "\u0645\u0633\u062d \u0627\u0644\u0628\u062d\u062b" else "Clear search")
                        }
                    }
                }
            )
        }
        items(filteredApps, key = { it.packageName }) { app ->
            Card(colors = CardDefaults.cardColors(containerColor = card)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label, color = fg, fontWeight = FontWeight.Medium)
                        Text(app.packageName, color = muted, fontSize = 10.sp)
                    }
                    Switch(checked = app.packageName in selectedPackages, onCheckedChange = { onToggle(app.packageName, it) })
                }
            }
        }
        if (appsLoaded && filteredApps.isEmpty()) {
            item {
                Text(if (ar) "\u0644\u0627 \u062a\u0648\u062c\u062f \u062a\u0637\u0628\u064a\u0642\u0627\u062a \u0645\u0637\u0627\u0628\u0642\u0629" else "No matching apps", color = muted)
            }
        }
        item {
            Text(if (ar) "ملاحظة: الوضع القياسي يعتمد على مراقبة إمكانية الوصول وشاشة Overlay. يمكن للمستخدم استخدام Home وRecents، ولا يضمن هذا الوضع منع إيقاف FocusLock." else "Note: Standard Mode uses Accessibility monitoring and an overlay. Home and Recents remain available, and this mode cannot guarantee that FocusLock will not be stopped.", color = muted, fontSize = 12.sp)
        }
    }
    if (showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosure = false },
            title = { Text(stringResource(R.string.accessibility_disclosure_title)) },
            text = { Text(stringResource(R.string.accessibility_disclosure_body)) },
            confirmButton = {
                TextButton(onClick = {
                    AccessibilityDisclosureConsent.accept(context)
                    showAccessibilityDisclosure = false
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure {
                        Toast.makeText(context, if (ar) "تعذر فتح إعدادات إمكانية الوصول" else "Could not open Accessibility settings", Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.accessibility_disclosure_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDisclosure = false }) {
                    Text(stringResource(R.string.accessibility_disclosure_cancel))
                }
            }
        )
    }
}


@Composable
private fun StrictModeSettingsScreen(context: Context, ar: Boolean, fg: Color, muted: Color, card: Color, modifier: Modifier = Modifier) {
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val owner = remember(refresh) { StrictModeManager.isDeviceOwner(context) }
    val permitted = remember(refresh) { StrictModeManager.isLockTaskPermitted(context) }
    val capabilities = remember(refresh) { ProtectionCapabilityManager.detect(context) }
    val policyOwned = remember(refresh) { StrictModeManager.isSessionAllowlistOwned(context) }
    val recoveryScope = rememberCoroutineScope()

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(if (ar) "الحماية الصارمة — Strict Mode" else "Strict Protection — Strict Mode", color = fg, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (ar) "قدرات جهازك ومستوى الحماية المتاح" else "Device capabilities and available protection", color = fg, fontWeight = FontWeight.Bold)
                    val tierLabel = when (capabilities.selectedTier) {
                        ProtectionTier.ENTERPRISE_LOCK_TASK -> if (ar) "إدارة مؤسسية — Lock Task متاح" else "Enterprise-managed — Lock Task available"
                        ProtectionTier.STANDARD_ACCESSIBILITY -> if (ar) "حماية Android القياسية — إمكانية الوصول مفعّلة" else "Standard Android protection — Accessibility enabled"
                        ProtectionTier.MANUAL_ONLY -> if (ar) "حماية يدوية فقط — فعّل إمكانية الوصول للحجب" else "Manual guidance only — enable Accessibility for app blocking"
                    }
                    Text(tierLabel, color = Blue, fontWeight = FontWeight.SemiBold)
                    Text(if (ar) "إمكانية الوصول: ${if (capabilities.accessibilityEnabled) "مفعّلة" else "غير مفعّلة"}" else "Accessibility: ${if (capabilities.accessibilityEnabled) "Enabled" else "Not enabled"}", color = fg, fontSize = 13.sp)
                    Text(if (ar) "Usage Access (مساندة): ${if (capabilities.usageAccessGranted) "ممنوحة" else "غير ممنوحة"}" else "Usage Access (supplemental): ${if (capabilities.usageAccessGranted) "Granted" else "Not granted"}", color = muted, fontSize = 13.sp)
                    Text(if (ar) "إدارة الجهاز: ${if (capabilities.deviceOwner) "مالك الجهاز" else "غير مُدار بواسطة FocusLock"} • Lock Task: ${if (capabilities.lockTaskPermitted) "متاح" else "غير متاح"}" else "Device management: ${if (capabilities.deviceOwner) "Device Owner" else "Not managed by FocusLock"} • Lock Task: ${if (capabilities.lockTaskPermitted) "Available" else "Unavailable"}", color = muted, fontSize = 12.sp)
                    Text("${capabilities.manufacturer} ${capabilities.model} • Android API ${capabilities.apiLevel}", color = muted, fontSize = 11.sp)
                    Text(if (ar) "Usage Access معلومة مساندة فقط ولا تحل محل إمكانية الوصول. اكتشاف القدرات للقراءة فقط ولا يغيّر إعدادات النظام." else "Usage Access is supplemental and does not replace Accessibility. Capability detection is read-only and does not change system settings.", color = muted, fontSize = 11.sp)
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure { message = it.message ?: if (ar) "تعذر فتح إعدادات Usage Access" else "Could not open Usage Access settings" }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (ar) "إدارة Usage Access (اختياري)" else "Manage Usage Access (optional)") }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0x22F04444)),
                border = BorderStroke(1.dp, Color(0x99EF5350))
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF6B6B))
                        Text(if (ar) "خيار متقدم وخطير — Device Owner" else "Advanced high-risk option — Device Owner", color = Color(0xFFFF7777), fontWeight = FontWeight.ExtraBold)
                    }
                    Text(
                        if (ar) "هذا الخيار اختياري تمامًا وليس صلاحية مطلوبة للتطبيق. لا تحتاج إليه لاستخدام FocusLock على هواتف Android العادية. تعيين التطبيق كمالك للجهاز يمنحه صلاحيات إدارة واسعة وقد يتطلب تهيئة مؤسسية خاصة، وفي كثير من الحالات إعادة ضبط المصنع؛ لذلك لا تفعّله على هاتفك الأساسي أو جهاز يحتوي بيانات مهمة قبل فهم العواقب." else
                            "This is entirely optional and is not an app permission. FocusLock works on ordinary Android phones without it. Device Owner grants broad device-management authority and may require special enterprise provisioning or, in many cases, a factory reset. Do not enable it on your primary phone or a device with important data unless you fully understand the consequences.",
                        color = if (ar) Color(0xFFFFB4B4) else Color(0xFFFFC1C1), fontSize = 13.sp
                    )
                    Text(
                        if (ar) "لا ينفذ FocusLock التفعيل أو إعادة الضبط تلقائيًا. هذا القسم للمعلومات والفحص فقط؛ وأي إعداد مستقبلي سيكون إجراءً منفصلًا يبدأه المستخدم بموافقته الصريحة." else
                            "FocusLock will never activate Device Owner or reset the device automatically. This section is informational and read-only; any future provisioning flow must be a separate action initiated with your explicit consent.",
                        color = muted, fontSize = 12.sp
                    )
                    Text(
                        if (owner) (if (ar) "الحالة الحالية: FocusLock مالك الجهاز بالفعل." else "Current status: FocusLock is already Device Owner.") else (if (ar) "الحالة الحالية: غير مفعّل — وهذا طبيعي ولا يؤثر في استخدام الوضع القياسي." else "Current status: Not enabled — this is normal and does not affect Standard Mode."),
                        color = if (owner) Color(0xFF27AE78) else Color(0xFFFF7777), fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (ar) "حالة إدارة الجهاز" else "Device management status", color = fg, fontWeight = FontWeight.Bold)
                    Text(if (owner) (if (ar) "FocusLock هو مالك الجهاز" else "FocusLock is Device Owner") else (if (ar) "FocusLock ليس مالك الجهاز" else "FocusLock is not Device Owner"), color = if (owner) Color(0xFF27AE78) else Color(0xFFE09A35))
                    Text(if (ar) "لا يمكن تفعيل Lock Task الكامل إلا بعد إعداد الجهاز إدارياً وتعيين FocusLock كمالك للجهاز. هذه عملية إعداد خاصة قد تتطلب جهازاً جديداً أو إعادة ضبط المصنع، لذلك لن ينفذها التطبيق تلقائياً." else "Full Lock Task requires managed-device provisioning with FocusLock assigned as Device Owner. This is a special setup that may require a new or factory-reset device; the app will never perform it automatically.", color = muted, fontSize = 13.sp)
                    Text(if (ar) "حالة السماح بـ Lock Task: " + if (permitted) "مسموح" else "غير مسموح" else "Lock Task allowed: " + if (permitted) "Yes" else "No", color = fg)
                    OutlinedButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) { Text(if (ar) "إعادة فحص الحالة" else "Refresh status") }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (ar) "تجهيز وضع الحماية الصارمة" else "Prepare Strict Mode", color = fg, fontWeight = FontWeight.Bold)
                    Text(if (ar) "يتحقق التطبيق من ملكية الجهاز ويضيف FocusLock فقط إلى قائمة Lock Task. لا يبدأ القفل من شاشة الإعدادات؛ يجب أن يحدث ذلك ضمن جلسة نشطة بعد استكمال تكامل وضع الحماية." else "The app verifies Device Owner status and adds only FocusLock to the Lock Task allowlist. Locking is not started from Settings; it must be tied to an active session after the protection flow is fully integrated.", color = muted, fontSize = 13.sp)
                    Button(
                        enabled = owner,
                        onClick = {
                            val result = StrictModeManager.prepareAllowlist(context)
                            message = result.exceptionOrNull()?.message ?: if (ar) "تم تجهيز قائمة Lock Task لهذا التطبيق فقط." else "Lock Task allowlist prepared for this app only."
                            refresh++
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (ar) "تجهيز قائمة Lock Task" else "Prepare Lock Task allowlist") }
                    if (policyOwned) {
                        OutlinedButton(
                            enabled = owner,
                            onClick = {
                                recoveryScope.launch {
                                    val activeSession = FocusDatabase.get(context).sessions().activeSession()
                                    if (activeSession != null) {
                                        message = if (ar) "لا يمكن استعادة القائمة أثناء وجود جلسة نشطة." else "Cannot restore the allowlist while a focus session is active."
                                    } else {
                                        val result = StrictModeManager.releaseSessionAllowlist(context)
                                        message = result.exceptionOrNull()?.message ?: if (ar) "تمت استعادة قائمة Lock Task السابقة." else "The previous Lock Task allowlist was restored."
                                        refresh++
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (ar) "استعادة قائمة Lock Task السابقة" else "Restore previous Lock Task allowlist") }
                    }
                    message?.let { Text(it, color = muted, fontSize = 12.sp) }
                }
            }
        }
        item {
            Text(if (ar) "السلامة: لا يحاول FocusLock منع مكالمات الطوارئ أو يفعّل ملكية الجهاز أو يفرض وضع Kiosk دون إعداد وموافقة صريحين من مالك الجهاز/المسؤول. لا تعتبر الحماية الصارمة نشطة لمجرد ظهور هذه الشاشة." else "Safety: FocusLock does not block emergency calls, provision Device Owner, or force Kiosk mode without explicit owner/admin provisioning. This screen alone does not mean Strict Mode is active.", color = muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsScreen(ar: Boolean, dark: Boolean, fg: Color, muted: Color, card: Color, onLanguage: () -> Unit, onTheme: () -> Unit, onDelete: () -> Unit, onWipeAll: () -> Unit, onPermissions: () -> Unit, onBreakInfo: () -> Unit, onNotifications: () -> Unit, onSafety: () -> Unit, onProtection: () -> Unit, onStrictMode: () -> Unit, modifier: Modifier = Modifier) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var wipeConfirmationStep by remember { mutableIntStateOf(0) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(if (ar) "الإعدادات" else "Settings", color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card)) {
                Column {
                    ListItem(headlineContent = { Text(if (ar) "اللغة" else "Language") }, supportingContent = { Text(if (ar) "العربية" else "English") }, leadingContent = { Icon(Icons.Default.Language, null) }, trailingContent = { TextButton(onClick = onLanguage) { Text(if (ar) "English" else "العربية") } }, colors = ListItemDefaults.colors(containerColor = card))
                    HorizontalDivider()
                    ListItem(headlineContent = { Text(if (ar) "الصلاحيات" else "Permissions") }, supportingContent = { Text(if (ar) "شرح الوصول المطلوب لميزات الحماية" else "Learn about system access used by protection features") }, leadingContent = { Icon(Icons.Default.Security, null) }, trailingContent = { TextButton(onClick = onPermissions) { Text(if (ar) "عرض" else "Review") } }, colors = ListItemDefaults.colors(containerColor = card))
                    HorizontalDivider()
                    ListItem(headlineContent = { Text(if (ar) "الحماية القياسية" else "Standard protection") }, supportingContent = { Text(if (ar) "اختيار التطبيقات التي تُحجب أثناء جلسة التركيز" else "Choose apps to block during a focus session") }, leadingContent = { Icon(Icons.Default.Block, null) }, trailingContent = { TextButton(onClick = onProtection) { Text(if (ar) "إعداد" else "Configure") } }, colors = ListItemDefaults.colors(containerColor = card))
                    HorizontalDivider()
                    ListItem(headlineContent = { Text(if (ar) "الحماية الصارمة" else "Strict protection") }, supportingContent = { Text(if (ar) "حالة Device Owner وإعداد Lock Task" else "Device Owner status and Lock Task setup") }, leadingContent = { Icon(Icons.Default.AdminPanelSettings, null) }, trailingContent = { TextButton(onClick = onStrictMode) { Text(if (ar) "فحص" else "Check") } }, colors = ListItemDefaults.colors(containerColor = card))
                    HorizontalDivider()
                    ListItem(headlineContent = { Text(stringResource(R.string.notifications_title)) }, supportingContent = { Text(if (ar) "معلومات حول تنبيهات الجلسات" else "About focus-session alerts") }, leadingContent = { Icon(Icons.Default.Notifications, null) }, trailingContent = { TextButton(onClick = onNotifications) { Text(if (ar) "عرض" else "Open") } }, colors = ListItemDefaults.colors(containerColor = card))
                    HorizontalDivider()
                    ListItem(headlineContent = { Text(if (ar) "الأمان والطوارئ" else "Safety & emergency") }, supportingContent = { Text(if (ar) "الوصول الآمن وخيارات الطوارئ" else "Safe access and emergency options") }, leadingContent = { Icon(Icons.Default.HealthAndSafety, null) }, trailingContent = { TextButton(onClick = onSafety) { Text(if (ar) "عرض" else "Open") } }, colors = ListItemDefaults.colors(containerColor = card))
                    HorizontalDivider()
                    ListItem(headlineContent = { Text(if (ar) "المظهر" else "Appearance") }, supportingContent = { Text(if (dark) (if (ar) "داكن" else "Dark") else (if (ar) "فاتح" else "Light")) }, leadingContent = { Icon(Icons.Default.Contrast, null) }, trailingContent = { Switch(checked = dark, onCheckedChange = { onTheme() }) }, colors = ListItemDefaults.colors(containerColor = card))
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (ar) "الخصوصية والبيانات" else "Privacy & data", color = fg, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onBreakInfo) { Text(if (ar) "كيف تعمل الاستراحات؟" else "How breaks work") }
                    Text(if (ar) "تبقى الجلسات والإعدادات على هذا الجهاز. لا يتطلب التطبيق اتصالاً بالإنترنت." else "Sessions and preferences stay on this device. The app does not require internet access.", color = muted, fontSize = 13.sp)
                    OutlinedButton(onClick = { showDeleteConfirmation = true }) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text(if (ar) "حذف سجل الجلسات" else "Delete session history") }
                    OutlinedButton(onClick = { wipeConfirmationStep = 1 }) {
                        Icon(Icons.Default.DeleteForever, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (ar) "مسح جميع البيانات المحلية" else "Erase all local data")
                    }
                }
            }
        }
        item { Text("FocusLock • 1.0.0", color = muted, fontSize = 12.sp) }
    }

    if (wipeConfirmationStep == 1) {
        AlertDialog(
            onDismissRequest = { wipeConfirmationStep = 0 },
            title = { Text(if (ar) "مراجعة مسح البيانات" else "Review local data erasure") },
            text = {
                Text(if (ar)
                    "سيؤدي ذلك إلى حذف الجلسات والاستراحات والأحداث وجهات الاتصال المسموحة والإشعارات المحفوظة وجميع إعدادات التطبيق وبيانات الخدمات المحلية. لا يمكن التراجع عن هذا الإجراء."
                    else
                    "This will erase sessions, breaks, events, allowed contacts, saved notifications, app settings, and local service state. This action cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = { wipeConfirmationStep = 2 }) {
                    Text(if (ar) "متابعة" else "Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { wipeConfirmationStep = 0 }) {
                    Text(if (ar) "إلغاء" else "Cancel")
                }
            }
        )
    } else if (wipeConfirmationStep == 2) {
        AlertDialog(
            onDismissRequest = { wipeConfirmationStep = 0 },
            title = { Text(if (ar) "تأكيد نهائي" else "Final confirmation") },
            text = { Text(if (ar) "هل أنت متأكد تمامًا؟ سيتم مسح جميع البيانات المحلية الآن." else "Are you absolutely sure? All local data will now be erased.") },
            confirmButton = {
                TextButton(onClick = { wipeConfirmationStep = 0; onWipeAll() }) {
                    Text(if (ar) "امسح كل البيانات" else "Erase everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { wipeConfirmationStep = 0 }) {
                    Text(if (ar) "احتفاظ بالبيانات" else "Keep my data")
                }
            }
        )
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(if (ar) "تأكيد حذف السجل" else "Delete session history?") },
            text = { Text(if (ar) "سيتم حذف سجل الجلسات نهائيًا. لا يمكن التراجع عن هذا الإجراء." else "All saved session history on this device will be permanently deleted. This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirmation = false; onDelete() }) {
                    Text(if (ar) "حذف السجل" else "Delete history")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(if (ar) "إلغاء" else "Cancel")
                }
            }
        )
    }
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
}
private fun formatDateTime(timestamp: Long, ar: Boolean): String {
    val locale = if (ar) Locale.forLanguageTag("ar") else Locale.US
    return java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.DEFAULT,
        java.text.DateFormat.DEFAULT,
        locale
    ).format(java.util.Date(timestamp))
}

private fun formatDuration(ms: Long, ar: Boolean): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
    val mins = totalSeconds / 60L
    return if (mins >= 60) {
        if (ar) "${mins / 60} \u0633 ${mins % 60} \u062f" else "${mins / 60}h ${mins % 60}m"
    } else if (mins > 0) {
        if (ar) "${mins} \u062f" else "${mins}m"
    } else if (totalSeconds > 0) {
        if (ar) "${totalSeconds} \u062b" else "${totalSeconds}s"
    } else {
        if (ar) "0 \u062f" else "0m"
    }
}

@Composable
private fun NotificationCenterScreen(
    context: Context,
    repository: FocusRepository,
    ar: Boolean,
    fg: Color,
    muted: Color,
    card: Color,
    modifier: Modifier = Modifier
) {
    val records by repository.notifications.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(com.focuslock.app.services.NotificationCaptureService.PREFS, Context.MODE_PRIVATE) }
    var captureEnabled by remember { mutableStateOf(prefs.getBoolean(com.focuslock.app.services.NotificationCaptureService.KEY_CAPTURE_ENABLED, false)) }
    var listenerGranted by remember { mutableStateOf(false) }
    var dndGranted by remember { mutableStateOf(false) }
    var dndEnabled by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    val errorOpenNotificationAccess = stringResource(R.string.notifications_error_open_access)
    val errorOpenDndAccess = stringResource(R.string.notifications_error_open_dnd)
    val errorEnableDnd = stringResource(R.string.notifications_error_enable_dnd)
    val errorDisableDnd = stringResource(R.string.notifications_error_disable_dnd)
    val notificationManager = remember { context.getSystemService(android.app.NotificationManager::class.java) }
    fun refreshPermission() {
        listenerGranted = runCatching {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ).orEmpty()
            enabled.split(":").any { it.contains(context.packageName) }
        }.getOrDefault(false)
        dndGranted = runCatching { notificationManager.isNotificationPolicyAccessGranted }.getOrDefault(false)
        dndEnabled = runCatching {
            when (notificationManager.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                NotificationManager.INTERRUPTION_FILTER_ALARMS,
                NotificationManager.INTERRUPTION_FILTER_NONE -> true
                else -> false
            }
        }.getOrDefault(false)
    }
            LaunchedEffect(Unit) { refreshPermission() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.notifications_title), color = fg, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.notifications_capture_title), color = fg, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.notifications_capture_description),
                        color = muted, fontSize = 13.sp
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.notifications_capture_toggle), color = fg, modifier = Modifier.weight(1f))
                        Switch(checked = captureEnabled, onCheckedChange = {
                            captureEnabled = it
                            val nextCaptureEpoch = prefs.getLong(
                                com.focuslock.app.services.NotificationCaptureService.KEY_CAPTURE_EPOCH, 0L
                            ) + 1L
                            prefs.edit {
                                putBoolean(com.focuslock.app.services.NotificationCaptureService.KEY_CAPTURE_ENABLED, it)
                                putLong(com.focuslock.app.services.NotificationCaptureService.KEY_CAPTURE_EPOCH, nextCaptureEpoch)
                            }
                        })
                    }
                    Text(
                        stringResource(R.string.notifications_access_status, stringResource(if (listenerGranted) R.string.status_granted else R.string.status_not_granted)),
                        color = if (listenerGranted) Color(0xFF27AE78) else muted
                    )
                    Button(onClick = {
                        runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { actionMessage = it.message ?: errorOpenNotificationAccess }
                        refreshPermission()
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.notifications_open_access_settings)) }
                    OutlinedButton(onClick = { refreshPermission() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.notifications_refresh_permission)) }
                    actionMessage?.let { Text(it, color = muted, fontSize = 12.sp) }
                    if (!captureEnabled) Text(stringResource(R.string.notifications_capture_off), color = muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.dnd_title), color = fg, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.dnd_description), color = muted, fontSize = 13.sp)
                    Text(stringResource(R.string.dnd_access_state, stringResource(if (dndGranted) R.string.status_granted else R.string.status_not_granted), stringResource(if (dndEnabled) R.string.status_on else R.string.status_off)), color = fg)
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { actionMessage = it.message ?: errorOpenDndAccess }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dnd_open_access_settings)) }
                    Button(enabled = dndGranted && !dndEnabled, onClick = {
                        runCatching { notificationManager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_NONE); refreshPermission() }.onFailure { actionMessage = it.message ?: errorEnableDnd }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dnd_enable_now)) }
                    OutlinedButton(enabled = dndGranted && dndEnabled, onClick = {
                        runCatching { notificationManager.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL); refreshPermission() }.onFailure { actionMessage = it.message ?: errorDisableDnd }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dnd_disable)) }
                    actionMessage?.let { Text(it, color = muted, fontSize = 12.sp) }
                }
            }
        }
        item { Text(stringResource(R.string.notifications_saved_count, records.size), color = fg, fontWeight = FontWeight.Bold) }
        if (records.isEmpty()) {
            item { Text(stringResource(R.string.notifications_empty), color = muted) }
        } else {
            items(records, key = { it.id }) { record ->
                Card(colors = CardDefaults.cardColors(containerColor = card)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(record.title ?: stringResource(R.string.notification_item), color = fg, fontWeight = FontWeight.SemiBold)
                        record.content?.let { Text(it, color = muted, fontSize = 13.sp) }
                        Text("${record.sourcePackage} • ${formatDateTime(record.receivedAt, ar)}", color = muted, fontSize = 10.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { scope.launch { repository.deleteNotification(record.id) } }) { Text(stringResource(R.string.action_delete)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafetyEmergencyScreen(
    context: Context,
    repository: FocusRepository,
    ar: Boolean,
    fg: Color,
    muted: Color,
    card: Color,
    modifier: Modifier = Modifier
) {
    val contacts by repository.allowedContacts.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val contactNameFallback = stringResource(R.string.safety_contact_name_fallback)
    val contactAddError = stringResource(R.string.safety_contact_add_error)
    val contactNoNumber = stringResource(R.string.safety_contact_no_number)
    var message by remember { mutableStateOf<String?>(null) }
    var callRoleRefresh by remember { mutableIntStateOf(0) }
    val roleManager = remember { context.getSystemService(android.app.role.RoleManager::class.java) }
    val callRoleAvailable = remember(callRoleRefresh) {
        runCatching { roleManager?.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_SCREENING) == true }.getOrDefault(false)
    }
    val callRoleHeld = remember(callRoleRefresh) {
        runCatching { roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING) == true }.getOrDefault(false)
    }
    val callPrefs = remember { context.getSharedPreferences(com.focuslock.app.services.FocusCallScreeningService.PREFS, Context.MODE_PRIVATE) }
    var callScreeningEnabled by remember(callRoleRefresh) {
        mutableStateOf(callPrefs.getBoolean(com.focuslock.app.services.FocusCallScreeningService.KEY_ENABLED, false))
    }
    var callFilterMode by remember(callRoleRefresh) {
        mutableStateOf(callPrefs.getString(
            com.focuslock.app.services.FocusCallScreeningService.KEY_MODE,
            com.focuslock.app.services.FocusCallScreeningService.MODE_FOCUS_ONLY
        ) ?: com.focuslock.app.services.FocusCallScreeningService.MODE_FOCUS_ONLY)
    }
    var filterModeMenuExpanded by remember { mutableStateOf(false) }
    var exemptAllowedContacts by remember(callRoleRefresh) {
        mutableStateOf(callPrefs.getBoolean(com.focuslock.app.services.FocusCallScreeningService.KEY_EXEMPT_ALLOWED, true))
    }
    val callRoleLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { callRoleRefresh++ }
    val callRoleLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(callRoleLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) callRoleRefresh++
        }
        callRoleLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { callRoleLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(callRoleHeld) {
        if (!callRoleHeld) {
            callScreeningEnabled = false
            callPrefs.edit(commit = true) {
                putBoolean(com.focuslock.app.services.FocusCallScreeningService.KEY_ENABLED, false)
            }
        }
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                ), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getString(0) ?: ""
                        val name = cursor.getString(1) ?: contactNameFallback
                        val number = cursor.getString(2) ?: ""
                        val normalized = com.focuslock.app.domain.PhoneNumberNormalizer.normalize(number)
                        if (normalized != null) {
                            scope.launch {
                                runCatching {
                                    repository.addAllowedContact(
                                        com.focuslock.app.data.AllowedContactEntity(
                                            contactId = id,
                                            displayName = name.take(200),
                                            normalizedPhoneNumber = normalized.take(40)
                                        )
                                    )
                                }.onFailure { message = contactAddError }
                            }
                        } else message = contactNoNumber
                    }
                }
            }.onFailure { message = it.message }
        }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.safety_title), color = fg, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0x22F04444)), border = BorderStroke(1.dp, Color(0x99EF5350))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.safety_emergency_heading), color = Color(0xFFFF7777), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.safety_emergency_description), color = fg, fontSize = 13.sp)
                    Button(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { message = it.message }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Call, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.safety_open_dialer))
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = card),
                border = BorderStroke(1.dp, muted.copy(alpha = 0.28f))
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(stringResource(R.string.safety_call_screening_heading), color = fg, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.safety_call_screening_description), color = muted, fontSize = 12.sp)
                    Text(
                        if (ar) "تعمل هذه الميزة على المكالمات الواردة فقط، ولا تتحكم في المكالمات الصادرة أو سجل المكالمات."
                        else "This feature handles incoming calls only; it does not control outgoing calls or call history.",
                        color = muted, fontSize = 11.sp
                    )
                    Text(stringResource(R.string.safety_system_role, stringResource(if (callRoleHeld) R.string.status_granted else R.string.status_not_granted)), color = if (callRoleHeld) Color(0xFF27AE78) else muted)
                    if (!callRoleHeld) {
                        Button(
                            enabled = callRoleAvailable,
                            onClick = {
                                runCatching {
                                    callRoleLauncher.launch(roleManager!!.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING))
                                }.onFailure { message = it.message ?: if (ar) "تعذر فتح طلب الدور" else "Could not open role request" }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.safety_request_call_role)) }
                    } else {
                        Text(if (ar) "نمط الفلترة" else "Screening mode", color = fg, fontWeight = FontWeight.SemiBold)
                        Box {
                            OutlinedButton(onClick = { filterModeMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (callFilterMode == com.focuslock.app.services.FocusCallScreeningService.MODE_ALWAYS) (if (ar) "طوال الوقت" else "At all times") else (if (ar) "أثناء جلسة التركيز فقط" else "During focus sessions only"))
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = filterModeMenuExpanded, onDismissRequest = { filterModeMenuExpanded = false }) {
                                DropdownMenuItem(text = { Text(if (ar) "أثناء جلسة التركيز فقط" else "During focus sessions only") }, onClick = {
                                    callFilterMode = com.focuslock.app.services.FocusCallScreeningService.MODE_FOCUS_ONLY
                                    callPrefs.edit(commit = true) {
                                        putString(com.focuslock.app.services.FocusCallScreeningService.KEY_MODE, callFilterMode)
                                        putBoolean(com.focuslock.app.services.FocusCallScreeningService.KEY_ENABLED, true)
                                    }
                                    filterModeMenuExpanded = false
                                })
                                DropdownMenuItem(text = { Text(if (ar) "طوال الوقت" else "At all times") }, onClick = {
                                    callFilterMode = com.focuslock.app.services.FocusCallScreeningService.MODE_ALWAYS
                                    callPrefs.edit(commit = true) {
                                        putString(com.focuslock.app.services.FocusCallScreeningService.KEY_MODE, callFilterMode)
                                        putBoolean(com.focuslock.app.services.FocusCallScreeningService.KEY_ENABLED, true)
                                    }
                                    filterModeMenuExpanded = false
                                })
                            }
                        }
                        Text(if (ar) "تُستثنى جهات الاتصال المسموحة فقط عند تفعيل خيار الاستثناء أدناه. الفلترة للمكالمات الواردة فقط." else "Allowed contacts are exempt only when the option below is enabled. Screening applies to incoming calls only.", color = muted, fontSize = 11.sp)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (ar) "استبعاد الأرقام المستثناة من الفلترة" else "Exclude allowed numbers from screening", color = fg, modifier = Modifier.weight(1f))
                            Switch(
                                checked = exemptAllowedContacts,
                                onCheckedChange = { checked ->
                                    exemptAllowedContacts = checked
                                    callPrefs.edit(commit = true) {
                                        putBoolean(com.focuslock.app.services.FocusCallScreeningService.KEY_EXEMPT_ALLOWED, checked)
                                    }
                                }
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                callScreeningEnabled = false
                                val saved = callPrefs.edit()
                                    .putBoolean(com.focuslock.app.services.FocusCallScreeningService.KEY_ENABLED, false)
                                    .commit()
                                message = if (saved) {
                                    if (ar) "تم إيقاف فلترة المكالمات فورًا. سيظل دور Android مسموحًا حتى تزيله من إعدادات التطبيقات الافتراضية."
                                    else "Call screening is now off. The Android role remains granted until you remove FocusLock from Default apps."
                                } else {
                                    if (ar) "تعذر حفظ إيقاف الفلترة. حاول مرة أخرى."
                                    else "Could not save the disabled state. Please try again."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (ar) "إيقاف الفلترة الآن" else "Disable call screening now") }
                        OutlinedButton(
                            onClick = {
                                val opened = runCatching {
                                    context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }.isSuccess
                                if (!opened) {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }.onFailure {
                                        message = if (ar) "تعذر فتح إعدادات Android. افتح الإعدادات يدويًا ثم التطبيقات > التطبيقات الافتراضية > تطبيق فلترة المكالمات."
                                            else "Could not open Android Settings. Open Settings > Apps > Default apps > Call screening app."
                                    }
                                } else {
                                    message = if (ar) "افتح التطبيقات الافتراضية ثم تطبيق فلترة المكالمات، واختر التطبيق السابق لإزالة FocusLock."
                                        else "In Default apps, open the call-screening app and select the previous app to remove FocusLock."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (ar) "إدارة الدور وإزالته من إعدادات Android" else "Manage or remove role in Android Settings") }
                        Text(
                            if (ar) "لإلغاء صلاحية الدور نفسها، أزل FocusLock من تطبيق فلترة المكالمات الافتراضي في إعدادات Android. زر الإيقاف أعلاه يعطل الفلترة فورًا دون انتظار."
                            else "To revoke the system role itself, remove FocusLock as the default call-screening app in Android Settings. The disable button above stops screening immediately.",
                            color = muted, fontSize = 11.sp
                        )
                    }
                    if (!callRoleAvailable) Text(stringResource(R.string.safety_call_role_unavailable), color = muted, fontSize = 11.sp)
                    message?.let { Text(it, color = muted, fontSize = 12.sp) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.safety_sms_deferred_heading), color = fg, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.safety_sms_deferred_description), color = muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.safety_allowed_contacts_heading), color = fg, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.safety_allowed_contacts_description), color = muted, fontSize = 13.sp)
                    OutlinedButton(onClick = {
                        picker.launch(Intent(Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.safety_choose_contact))
                    }
                    message?.let { Text(it, color = muted, fontSize = 12.sp) }
                }
            }
        }
        if (contacts.isEmpty()) {
            item { Text(stringResource(R.string.safety_no_contacts), color = muted) }
        } else {
            items(contacts, key = { it.id }) { contact ->
                Card(colors = CardDefaults.cardColors(containerColor = card)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(contact.displayName, color = fg, fontWeight = FontWeight.SemiBold)
                            Text(contact.normalizedPhoneNumber, color = muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { scope.launch { repository.deleteAllowedContact(contact.id) } }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = muted)
                        }
                    }
                }
            }
        }
    }
}

