package com.suhas.iposentinel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant

private val Ink = Color(0xFF0F1318)
private val Surface = Color(0xFF171D23)
private val Teal = Color(0xFF21D4B4)
private val Danger = Color(0xFFFF6B6B)
private val Muted = Color(0xFF9AA7B3)
private val Amber = Color(0xFFFFC857)

private enum class AppScreen { DASHBOARD, CALLS, RESEARCH, STRATEGIES, SETTINGS }

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppAudit.log(
                this,
                "NOTIFICATION_PERMISSION_RESULT",
                JSONObject().put("granted", granted)
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.createChannels(this)
        RecoveryScheduler.ensureScheduled(this)
        AppAudit.log(this, "APP_STARTED", JSONObject().put("version", BuildConfig.VERSION_NAME))

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent { IpoSentinelApp() }
    }
}

@Composable
private fun IpoSentinelApp() {
    val context = LocalContext.current
    val repository = remember(context) { AppStateRepository.get(context) }
    val state by repository.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf(AppScreen.DASHBOARD) }

    LaunchedEffect(Unit) { repository.initialize() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30L * 60L * 1000L)
            repository.refreshResearch(force = true)
            repository.refreshBrokerTruth(force = false)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Teal,
            secondary = Teal,
            background = Ink,
            surface = Surface,
            error = Danger
        )
    ) {
        Scaffold(
            containerColor = Ink,
            bottomBar = {
                NavigationBar(containerColor = Surface) {
                    NavItem(screen, AppScreen.DASHBOARD, "●", "Home") { screen = AppScreen.DASHBOARD }
                    NavItem(screen, AppScreen.CALLS, "☎", "Calls") { screen = AppScreen.CALLS }
                    NavItem(screen, AppScreen.RESEARCH, "◆", "Research") { screen = AppScreen.RESEARCH }
                    NavItem(screen, AppScreen.STRATEGIES, "▲", "Strategy") { screen = AppScreen.STRATEGIES }
                    NavItem(screen, AppScreen.SETTINGS, "⚙", "Settings") { screen = AppScreen.SETTINGS }
                }
            }
        ) { padding ->
            when (screen) {
                AppScreen.DASHBOARD -> DashboardScreen(Modifier.padding(padding), state)
                AppScreen.CALLS -> CallsScreen(
                    Modifier.padding(padding),
                    state,
                    onRefresh = { repository.refreshBrokerTruth(force = true) }
                )
                AppScreen.RESEARCH -> ResearchScreen(
                    Modifier.padding(padding),
                    state,
                    onRefresh = { repository.refreshResearch(force = true) }
                )
                AppScreen.STRATEGIES -> StrategiesScreen(Modifier.padding(padding), state)
                AppScreen.SETTINGS -> SettingsScreen(
                    Modifier.padding(padding),
                    state,
                    repository
                )
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(
    current: AppScreen,
    target: AppScreen,
    icon: String,
    label: String,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = current == target,
        onClick = onClick,
        icon = { Text(icon) },
        label = { Text(label, fontSize = 10.sp) }
    )
}

@Composable
private fun DashboardScreen(modifier: Modifier, state: AppState) {
    val plan = state.researchPlan
    val active30 = plan?.allKnownCandidates.orEmpty().count { (it.tradingDayNumber ?: 0) in 1..30 }
    val cachedSources = state.researchSources.count { it.usingCachedData }

    ScreenColumn(modifier) {
        Text("IPO Sentinel", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Durable calls • broker-truth recovery • NSE + Groww", color = Muted)

        if (state.isRefreshing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
                Text("  Refreshing shared state…", color = Muted)
            }
        }

        StatusCard(
            "Calls",
            state.liveCalls.size.toString() + " LIVE • " + state.closedCalls.size + " CLOSED",
            "Every recommendation is persisted with its first recommendation timestamp and update/close timestamps.",
            if (state.liveCalls.isNotEmpty()) Teal else Muted
        )

        StatusCard(
            "Groww Connection",
            when {
                state.growwConnectionReady -> "READY"
                state.connectionStatus?.growwConfigured == true -> "NEEDS VALIDATION"
                else -> "NOT CONFIGURED"
            },
            "Execution remains locked. Groww is used for authentication and read-only broker reconciliation.",
            if (state.growwConnectionReady) Teal else Amber
        )

        StatusCard(
            "Broker Truth Recovery",
            when {
                state.brokerTruth == null -> "NOT SYNCED"
                state.brokerTruth.error == null -> "SYNCED"
                else -> "ATTENTION"
            },
            buildString {
                append("Last: ")
                append(state.brokerTruth?.fetchedAt ?: "—")
                append("\nTrigger: ")
                append(state.brokerTruth?.trigger ?: "—")
                append("\nOrders: ")
                append(state.brokerTruth?.orders?.size ?: 0)
                append(" • Positions: ")
                append(state.brokerTruth?.positions?.size ?: 0)
                state.brokerTruth?.error?.let { append("\n").append(it) }
            },
            if (state.brokerTruth?.error == null && state.brokerTruth != null) Teal else Amber
        )

        StatusCard(
            "NSE Research",
            plan?.researchHealth ?: "NOT SYNCED",
            "Known " + (plan?.candidateCount ?: 0) +
                " • Groww resolved " + (plan?.growwResolvedCount ?: 0) +
                " • Cached sources " + cachedSources,
            if (plan?.researchHealth == "OK") Teal else Amber
        )

        StatusCard(
            "D1–D30 Research Universe",
            active30.toString(),
            "Mainboard and SME. A research candidate becomes a live call only after official identity + exact Groww NSE/CASH resolution.",
            Teal
        )

        StatusCard(
            "Live Execution",
            "LOCKED OFF",
            "No automatic real-money orders. Recovery observes broker truth but does not place, modify or cancel orders.",
            Danger
        )

        state.lastValidatedAtMillis?.let {
            Text("Last Groww validation: " + Instant.ofEpochMilli(it), color = Muted, fontSize = 12.sp)
        }
        state.lastError?.let { Text(it, color = Danger, fontSize = 12.sp) }
    }
}

@Composable
private fun CallsScreen(
    modifier: Modifier,
    state: AppState,
    onRefresh: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()
    ScreenColumn(modifier) {
        Text("Calls", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("One durable ledger for live and closed recommendations.", color = Muted)

        Button(
            onClick = { scope.launch { onRefresh() } },
            enabled = !state.isRefreshing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.isRefreshing) "Reconciling…" else "Reconcile From Groww Now")
        }

        Text("Live calls (" + state.liveCalls.size + ")", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (state.liveCalls.isEmpty()) {
            Text(
                "No live calls yet. The app will not manufacture a trade call from stale or incomplete market evidence.",
                color = Muted
            )
        } else {
            state.liveCalls.forEach { CallCard(it) }
        }

        HorizontalDivider()
        Text("Closed calls (" + state.closedCalls.size + ")", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (state.closedCalls.isEmpty()) {
            Text("No closed calls recorded yet.", color = Muted)
        } else {
            state.closedCalls.forEach { CallCard(it) }
        }

        Text(
            "Recovery rule: call state is stored before process death. Listener reconnect, boot/package restart, app open, periodic work, and manual refresh trigger broker reconciliation. Notifications are wake hints; Groww orders/positions are authoritative.",
            color = Muted,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun CallCard(call: RecommendationCall) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(call.symbol ?: call.companyName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(call.companyName + " • " + call.board, color = Muted, fontSize = 12.sp)
                }
                Text(
                    call.state,
                    color = if (call.state == "LIVE") Teal else Muted,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(call.action, color = Amber, fontWeight = FontWeight.SemiBold)
            Text("Recommended: " + call.recommendedAt, color = Muted, fontSize = 11.sp)
            Text("Updated: " + call.lastUpdatedAt, color = Muted, fontSize = 11.sp)
            call.sourceGeneratedAt?.let { Text("Research snapshot: " + it, color = Muted, fontSize = 11.sp) }
            if (call.brokerOrderId != null || call.brokerPositionQuantity != null) {
                Text(
                    "Broker: " + (call.brokerOrderStatus ?: "no order state") +
                        " • Position " + (call.brokerPositionQuantity ?: 0) +
                        (call.brokerAveragePrice?.let { " @ ₹" + String.format("%.2f", it) } ?: ""),
                    color = Teal,
                    fontSize = 12.sp
                )
                call.lastBrokerReconciledAt?.let {
                    Text("Broker checked: " + it, color = Muted, fontSize = 11.sp)
                }
            }
            call.closedAt?.let { Text("Closed: " + it, color = Muted, fontSize = 11.sp) }
            call.closeReason?.let { Text("Close reason: " + it.replace("_", " "), color = Muted, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun ResearchScreen(
    modifier: Modifier,
    state: AppState,
    onRefresh: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()
    val plan = state.researchPlan
    val active30 = plan?.allKnownCandidates.orEmpty().filter { (it.tradingDayNumber ?: 0) in 1..30 }

    ScreenColumn(modifier) {
        Text("Research", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Official discovery → identity → Groww instrument → timestamped call ledger", color = Muted)

        Button(
            onClick = { scope.launch { onRefresh() } },
            enabled = !state.isRefreshing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.isRefreshing) "Refreshing…" else "Refresh Direct Research")
        }

        Text("Research timestamp: " + (plan?.generatedAt ?: "not available"), color = Muted, fontSize = 12.sp)

        Text("Source health", fontWeight = FontWeight.Bold)
        if (state.researchSources.isEmpty()) {
            Text("No source status recorded yet.", color = Muted)
        } else {
            state.researchSources.forEach { source ->
                StatusCard(
                    source.name,
                    source.status,
                    "Last success: " + (source.lastSuccessAt ?: "—") +
                        (source.error?.let { "\n" + it } ?: ""),
                    if (source.status == "FRESH") Teal else Amber
                )
            }
        }

        CandidateSection("Next listing candidates", plan?.nextTradingDayCandidates.orEmpty(), plan?.generatedAt)
        CandidateSection("D1–D30 active universe", active30, plan?.generatedAt)

        plan?.errors?.forEach { Text("• " + it, color = Amber, fontSize = 12.sp) }

        Text(
            "Research cards do not assert live entry, stop, target, confidence or win rate. Calls remain WAIT LIVE CONFIRMATION until real live evidence exists.",
            color = Muted,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun CandidateSection(
    title: String,
    candidates: List<ResearchCandidate>,
    generatedAt: String?
) {
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    if (candidates.isEmpty()) {
        Text("No candidates currently available from the research snapshot.", color = Muted)
    } else {
        candidates.forEach { candidate ->
            Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(candidate.companyName, fontWeight = FontWeight.Bold)
                    Text(
                        (candidate.growwSymbol ?: candidate.symbol ?: "symbol pending") + " • " +
                            (if (candidate.isSme) "SME" else "MAINBOARD") + " • " +
                            (candidate.listingDate ?: "listing pending"),
                        color = Muted
                    )
                    Text(candidate.lifecycleState, color = Teal, fontWeight = FontWeight.Bold)
                    Text(candidate.resolutionStatus.replace("_", " "), color = Amber, fontSize = 12.sp)
                    Text("Research timestamp: " + (generatedAt ?: "—"), color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun StrategiesScreen(modifier: Modifier, state: AppState) {
    ScreenColumn(modifier) {
        Text("Strategies", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            state.strategySummary.totalStrategyFamilies.toString() + " families • " +
                state.strategySummary.testedFamilies + " tested • " +
                state.strategySummary.champions + " champions",
            color = Muted
        )
        state.strategySummary.families.forEach { family ->
            Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(family.name, fontWeight = FontWeight.Bold)
                    Text(family.phase.replace("_", " ") + " • " + family.status, color = Muted)
                    Text(family.description, color = Muted, fontSize = 12.sp)
                    Text(
                        "Evidence trades: " + family.trades,
                        color = if (family.trades > 0) Teal else Amber,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    state: AppState,
    repository: AppStateRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var staticIp by rememberSaveable { mutableStateOf(state.connectionStatus?.expectedStaticIp.orEmpty()) }
    var whitelist by rememberSaveable { mutableStateOf(state.connectionStatus?.staticIpConfirmed == true) }
    var message by remember { mutableStateOf<String?>(null) }
    var listenerEnabled by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }
    var exportBusy by remember { mutableStateOf(false) }

    LaunchedEffect(state.connectionStatus?.expectedStaticIp, state.connectionStatus?.staticIpConfirmed) {
        state.connectionStatus?.expectedStaticIp?.let { if (staticIp.isBlank()) staticIp = it }
        if (state.connectionStatus?.staticIpConfirmed == true) whitelist = true
    }

    ScreenColumn(modifier) {
        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        SettingsSection("Recovery & Notifications") {
            CheckRow("Normal notification permission", NotificationHelper.notificationsAllowed(context))
            CheckRow("Notification listener access", listenerEnabled)
            Text(
                "The listener is a wake-up hint only. If Vivo/Funtouch kills it or the process, the durable call ledger survives and the next reconnect/boot/app-open/periodic recovery resumes from Groww order + position truth.",
                color = Muted,
                fontSize = 12.sp
            )
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Notification Access") }
            TextButton(
                onClick = {
                    listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                    RecoveryScheduler.ensureScheduled(context)
                    RecoveryCoordinator.request(context, "SETTINGS_RECOVERY_CHECK", force = true)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Refresh Recovery Status") }
            OutlinedButton(
                onClick = {
                    NotificationHelper.showOrderEvent(
                        context,
                        OrderLifecycleEvent(
                            id = System.currentTimeMillis(),
                            timestamp = Instant.now().toString(),
                            eventType = "STRATEGY_REVIEW",
                            symbol = "TEST",
                            message = "Notification test only — no order was placed"
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Send Test Notification") }
        }

        SettingsSection("Groww TOTP") {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Groww TOTP token / API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("Groww TOTP secret") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (state.connectionStatus?.growwConfigured == true) {
                    "Credentials are already encrypted in Android Keystore. Enter new values only to replace them."
                } else {
                    "Credentials are encrypted locally with Android Keystore after Save."
                },
                color = Muted,
                fontSize = 12.sp
            )
        }

        SettingsSection("Static IP") {
            OutlinedTextField(
                value = staticIp,
                onValueChange = { staticIp = it.trim() },
                label = { Text("Whitelisted static public IP") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = whitelist, onCheckedChange = { whitelist = it })
                Text("I have whitelisted this IP in Groww")
            }
        }

        Button(
            onClick = {
                if (token.isBlank() || secret.isBlank() || staticIp.isBlank()) {
                    message = "Enter token, TOTP secret and static IP to replace/save Groww settings."
                } else {
                    scope.launch {
                        val result = repository.saveGrowwSettings(token, secret, staticIp, whitelist)
                        if (result.ok) {
                            token = ""
                            secret = ""
                            repository.validateGrowwAndStaticIp()
                            repository.refreshBrokerTruth(force = true)
                            message = "Groww settings saved, validated and broker recovery refreshed."
                        } else {
                            message = result.error ?: "Unable to save Groww settings."
                        }
                    }
                }
            },
            enabled = !state.isRefreshing,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save Groww Settings") }

        OutlinedButton(
            onClick = { scope.launch { repository.validateGrowwAndStaticIp() } },
            enabled = !state.isRefreshing,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Validate Groww + Static IP") }

        state.validation?.let {
            SettingsSection("Validation") {
                CheckRow("Groww TOTP authentication", it.growwAuthOk)
                CheckRow("Static public IP matches", it.staticIpMatches)
                CheckRow("Whitelist confirmed", it.staticIpConfirmed)
                CheckRow("Android Keystore", it.secretStoreReady)
                Text("Live execution: LOCKED OFF", color = Danger, fontWeight = FontWeight.Bold)
            }
        }

        SettingsSection("Weekly Verification Logs") {
            Text(
                "Export now includes the durable live/closed call ledger and broker-recovery status. Credentials and access tokens remain excluded.",
                color = Muted,
                fontSize = 12.sp
            )
            Button(
                onClick = {
                    exportBusy = true
                    scope.launch {
                        runCatching {
                            AppAudit.shareExport(context, AppAudit.exportWeekly(context))
                        }.onFailure { message = "Export failed: " + (it.message ?: "unknown") }
                        exportBusy = false
                    }
                },
                enabled = !exportBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (exportBusy) "Preparing…" else "Export Weekly Logs") }
        }

        message?.let { Text(it, color = if (it.contains("saved", true)) Teal else Amber) }
        state.lastError?.let { Text(it, color = Danger) }
    }
}

@Composable
private fun ScreenColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
private fun CheckRow(label: String, passed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (passed) "✓" else "✕", color = if (passed) Teal else Danger, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(label, color = Muted)
    }
}

@Composable
private fun StatusCard(
    title: String,
    primary: String,
    secondary: String,
    primaryColor: Color
) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Muted, fontSize = 12.sp)
            Text(primary, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = primaryColor)
            Text(secondary, color = Muted, fontSize = 12.sp)
        }
    }
}
