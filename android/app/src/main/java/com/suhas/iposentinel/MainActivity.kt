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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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

private enum class AppScreen { DASHBOARD, RESEARCH, STRATEGIES, SETTINGS }

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

    LaunchedEffect(Unit) {
        repository.initialize()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30L * 60L * 1000L)
            repository.refreshResearch(force = true)
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
                    NavigationBarItem(
                        selected = screen == AppScreen.DASHBOARD,
                        onClick = { screen = AppScreen.DASHBOARD },
                        icon = { Text("●") },
                        label = { Text("Dashboard") }
                    )
                    NavigationBarItem(
                        selected = screen == AppScreen.RESEARCH,
                        onClick = { screen = AppScreen.RESEARCH },
                        icon = { Text("◆") },
                        label = { Text("Research") }
                    )
                    NavigationBarItem(
                        selected = screen == AppScreen.STRATEGIES,
                        onClick = { screen = AppScreen.STRATEGIES },
                        icon = { Text("▲") },
                        label = { Text("Strategies") }
                    )
                    NavigationBarItem(
                        selected = screen == AppScreen.SETTINGS,
                        onClick = { screen = AppScreen.SETTINGS },
                        icon = { Text("⚙") },
                        label = { Text("Settings") }
                    )
                }
            }
        ) { padding ->
            when (screen) {
                AppScreen.DASHBOARD -> DashboardScreen(
                    modifier = Modifier.padding(padding),
                    state = state
                )
                AppScreen.RESEARCH -> ResearchScreen(
                    modifier = Modifier.padding(padding),
                    state = state,
                    onRefresh = { repository.refreshResearch(force = true) }
                )
                AppScreen.STRATEGIES -> StrategiesScreen(
                    modifier = Modifier.padding(padding),
                    state = state
                )
                AppScreen.SETTINGS -> SettingsScreen(
                    modifier = Modifier.padding(padding),
                    state = state,
                    repository = repository
                )
            }
        }
    }
}

@Composable
private fun DashboardScreen(modifier: Modifier, state: AppState) {
    val plan = state.researchPlan
    val active30 = plan?.allKnownCandidates.orEmpty()
        .count { (it.tradingDayNumber ?: 0) in 1..30 }
    val failedSources = state.researchSources.filter { it.status == "FAILED" }
    val cachedSources = state.researchSources.filter { it.usingCachedData }

    ScreenColumn(modifier) {
        Text("IPO Sentinel", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Unified direct research architecture • NSE + Groww", color = Muted)

        if (state.isRefreshing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
                Text("  Refreshing shared state…", color = Muted)
            }
        }

        StatusCard(
            title = "Groww Connection",
            primary = when {
                state.growwConnectionReady -> "READY"
                state.connectionStatus?.growwConfigured == true -> "NEEDS VALIDATION"
                else -> "NOT CONFIGURED"
            },
            secondary = when {
                state.growwConnectionReady -> "Authentication + configured static-IP checks passed."
                state.connectionStatus?.growwConfigured == true -> "Credentials saved. Validation is required."
                else -> "Save Groww TOTP credentials and static IP in Settings."
            },
            primaryColor = if (state.growwConnectionReady) Teal else Amber
        )

        StatusCard(
            title = "Static IP",
            primary = when {
                state.validation?.staticIpMatches == true -> "MATCHED"
                state.validation?.detectedEgressIp != null -> "MISMATCH / NOT READY"
                else -> "NOT CHECKED"
            },
            secondary = "Detected: " + (state.validation?.detectedEgressIp ?: "—") +
                "\nExpected: " + (state.validation?.expectedStaticIp ?: state.connectionStatus?.expectedStaticIp ?: "—"),
            primaryColor = if (state.validation?.staticIpMatches == true) Teal else Amber
        )

        StatusCard(
            title = "NSE Research Sources",
            primary = plan?.researchHealth ?: "NOT SYNCED",
            secondary = when {
                plan == null -> "Research state has not been hydrated yet."
                failedSources.isNotEmpty() -> failedSources.joinToString("\n") {
                    it.name + ": unavailable" + (it.error?.let { e -> " (" + e + ")" } ?: "")
                }
                cachedSources.isNotEmpty() -> cachedSources.joinToString("\n") {
                    it.name + ": cached from " + (it.lastSuccessAt ?: "previous success")
                }
                else -> "Official discovery, listing identity and calendar sources are available."
            },
            primaryColor = if (plan?.researchHealth == "OK") Teal else Amber
        )

        StatusCard(
            title = "Next Trading Day",
            primary = plan?.nextTradingDay ?: "UNKNOWN",
            secondary = if (plan?.calendarReady == true) {
                "Calculated with the official NSE cash-market calendar."
            } else {
                "Calendar source unavailable; weekday fallback is informational only."
            },
            primaryColor = if (plan?.calendarReady == true) Teal else Amber
        )

        val nextCount = plan?.nextTradingDayCandidates?.size ?: 0
        StatusCard(
            title = "Next IPO Candidates",
            primary = nextCount.toString(),
            secondary = when {
                plan == null -> "Not synced."
                !plan.sourceReady && nextCount == 0 -> "Source unavailable/degraded. This is not interpreted as “no IPOs”."
                nextCount == 0 -> "Available sources returned zero candidates for the next trading day."
                else -> "Candidates flow through official identity and exact Groww symbol verification."
            },
            primaryColor = if (plan?.sourceReady == true) Teal else Amber
        )

        StatusCard(
            title = "D1–D30 Active Universe",
            primary = active30.toString(),
            secondary = "Mainboard and SME listings remain research-only until live confirmation exists.",
            primaryColor = Teal
        )

        StatusCard(
            title = "Groww Symbol Resolution",
            primary = (plan?.growwResolvedCount ?: 0).toString() + " resolved",
            secondary = (plan?.growwPendingCount ?: 0).toString() + " official identities still pending exact NSE/CASH match.",
            primaryColor = if ((plan?.growwPendingCount ?: 0) == 0) Teal else Amber
        )

        StatusCard(
            title = "Live Execution",
            primary = "LOCKED OFF",
            secondary = "No automatic real-money orders. Direct order placement, reconciliation, fill tracking, ownership isolation and complete risk controls are not implemented.",
            primaryColor = Danger
        )

        state.lastValidatedAtMillis?.let {
            Text("Last Groww validation: " + Instant.ofEpochMilli(it).toString(), color = Muted, fontSize = 12.sp)
        }
        state.lastError?.let { Text(it, color = Danger) }
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
    val active30 = plan?.allKnownCandidates.orEmpty()
        .filter { (it.tradingDayNumber ?: 0) in 1..30 }

    ScreenColumn(modifier) {
        Text("Research", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Official discovery → identity → Groww instrument → research", color = Muted)

        Button(
            onClick = { scope.launch { onRefresh() } },
            enabled = !state.isRefreshing
        ) {
            Text(if (state.isRefreshing) "Refreshing…" else "Refresh Direct Research")
        }

        Text("Source health", fontWeight = FontWeight.Bold)
        if (state.researchSources.isEmpty()) {
            Text("No source status recorded yet.", color = Muted)
        } else {
            state.researchSources.forEach { source ->
                SourceHealthCard(source)
            }
        }

        Text("Next trading day: " + (plan?.nextTradingDay ?: "unknown"), fontWeight = FontWeight.Bold)
        CandidateSection(
            title = "Next listing candidates",
            candidates = plan?.nextTradingDayCandidates.orEmpty(),
            sourceReady = plan?.sourceReady == true
        )

        CandidateSection(
            title = "D1–D30 active universe",
            candidates = active30,
            sourceReady = plan?.sourceReady == true
        )

        if (plan?.errors?.isNotEmpty() == true) {
            Text("Research diagnostics", fontWeight = FontWeight.Bold)
            plan.errors.forEach { Text("• " + it, color = Amber, fontSize = 12.sp) }
        }

        Text(
            "All candidates are WATCH / RESEARCH / WAIT LIVE CONFIRMATION. No entry, stop, target, win rate or live confidence is manufactured.",
            color = Muted,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun CandidateSection(
    title: String,
    candidates: List<ResearchCandidate>,
    sourceReady: Boolean
) {
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    if (candidates.isEmpty()) {
        Text(
            if (sourceReady) "No candidates returned by available sources." else "Candidate availability unknown because one or more required sources are unavailable.",
            color = if (sourceReady) Muted else Amber
        )
    } else {
        candidates.forEach { CandidateCard(it) }
    }
}

@Composable
private fun CandidateCard(candidate: ResearchCandidate) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(candidate.companyName, fontWeight = FontWeight.Bold)
            Text(
                (candidate.symbol ?: "NSE symbol pending") + " • " +
                    (if (candidate.isSme) "SME" else "MAINBOARD") + " • " +
                    (candidate.listingDate ?: "listing date pending"),
                color = Muted
            )
            Text(candidate.lifecycleState, color = Teal, fontWeight = FontWeight.Bold)
            Text("Groww: " + candidate.growwResolutionStatus, color = Muted, fontSize = 12.sp)
            candidate.isin?.let { Text("ISIN: " + it, color = Muted, fontSize = 12.sp) }
            candidate.tradingDayNumber?.let { Text("Trading day D" + it, color = Muted, fontSize = 12.sp) }
            Text("WATCH • RESEARCH • WAIT LIVE CONFIRMATION", color = Amber, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SourceHealthCard(source: ResearchSourceStatus) {
    val color = when (source.status) {
        "FRESH" -> Teal
        "CACHED" -> Amber
        else -> Danger
    }
    Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(source.name, fontWeight = FontWeight.Bold)
            Text(source.status, color = color)
            if (source.usingCachedData) {
                Text("Using cached data from " + (source.lastSuccessAt ?: "last successful refresh"), color = Muted, fontSize = 12.sp)
            }
            source.error?.let { Text(it, color = Muted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun StrategiesScreen(modifier: Modifier, state: AppState) {
    val summary = state.strategySummary
    ScreenColumn(modifier) {
        Text("Strategies", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Catalog availability is separate from evidence.", color = Muted)

        StatusCard(
            title = "Strategy Catalog",
            primary = summary.totalStrategyFamilies.toString() + " families",
            secondary = "Tested: " + summary.testedFamilies +
                " • Champions: " + summary.champions +
                " • Untested: " + summary.untestedFamilies,
            primaryColor = Teal
        )

        Text(summary.rankingNote, color = Muted)
        summary.families.forEach { family ->
            Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(family.name, fontWeight = FontWeight.Bold)
                    Text(family.phase + " • " + family.status, color = Amber)
                    Text(family.description, color = Muted, fontSize = 12.sp)
                    Text("Evidence: " + family.trades + " replay/live observations", color = Muted, fontSize = 12.sp)
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
    var staticIp by rememberSaveable { mutableStateOf("") }
    var whitelistConfirmed by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.connectionStatus?.expectedStaticIp, state.connectionStatus?.staticIpConfirmed) {
        if (staticIp.isBlank()) {
            staticIp = state.connectionStatus?.expectedStaticIp.orEmpty()
        }
        whitelistConfirmed = state.connectionStatus?.staticIpConfirmed ?: whitelistConfirmed
    }

    ScreenColumn(modifier) {
        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Secrets are encrypted on-device and never repopulated into visible fields.", color = Muted)

        StatusCard(
            title = "Order Notification Permission",
            primary = if (NotificationHelper.notificationsAllowed(context)) "ALLOWED" else "NOT ALLOWED",
            secondary = "IPO Sentinel only sends its own notifications. It does not request notification-listener access.",
            primaryColor = if (NotificationHelper.notificationsAllowed(context)) Teal else Amber
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
            }) { Text("Notification Settings") }

            Button(onClick = {
                NotificationHelper.showOrderEvent(
                    context,
                    OrderLifecycleEvent(
                        id = System.currentTimeMillis(),
                        timestamp = Instant.now().toString(),
                        eventType = "STRATEGY_REVIEW",
                        message = "IPO Sentinel test notification"
                    )
                )
            }) { Text("Send Test Notification") }
        }

        Text(
            if (state.connectionStatus?.growwConfigured == true) "Groww credentials: SAVED" else "Groww credentials: NOT SAVED",
            color = if (state.connectionStatus?.growwConfigured == true) Teal else Amber,
            fontWeight = FontWeight.Bold
        )

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Groww TOTP token / API key") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text("Groww TOTP secret") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = staticIp,
            onValueChange = { staticIp = it.trim() },
            label = { Text("Static whitelisted public IP") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = whitelistConfirmed,
                onCheckedChange = { whitelistConfirmed = it }
            )
            Text("I have whitelisted this IP in Groww")
        }

        Text(
            "Stored token/secret are never shown. To replace credentials, enter both fields and save.",
            color = Muted,
            fontSize = 12.sp
        )

        Button(
            onClick = {
                scope.launch {
                    val result = repository.saveGrowwSettings(token, secret, staticIp, whitelistConfirmed)
                    if (result.ok) {
                        token = ""
                        secret = ""
                        message = "Groww settings saved securely."
                    } else {
                        message = result.error ?: "Unable to save settings."
                    }
                }
            },
            enabled = token.isNotBlank() && secret.isNotBlank() && staticIp.isNotBlank() && !state.isRefreshing
        ) { Text("Save Groww Settings") }

        Button(
            onClick = {
                scope.launch {
                    repository.validateGrowwAndStaticIp()
                    message = "Validation refreshed."
                }
            },
            enabled = state.connectionStatus?.growwConfigured == true && !state.isRefreshing
        ) { Text("Validate Groww + Static IP") }

        Button(
            onClick = {
                scope.launch {
                    repository.refreshResearch(force = true)
                    repository.validateGrowwAndStaticIp()
                    message = "Shared status refreshed."
                }
            },
            enabled = !state.isRefreshing
        ) { Text("Refresh Status") }

        ValidationLine("Groww TOTP authentication", state.validation?.growwAuthOk)
        ValidationLine("Static public IP matches", state.validation?.staticIpMatches)
        ValidationLine("Groww whitelist confirmed", state.validation?.staticIpConfirmed)
        ValidationLine("Android Keystore ready", state.validation?.secretStoreReady ?: state.connectionStatus?.secretStoreReady)
        ValidationLine("Official NSE calendar ready", state.researchPlan?.calendarReady)
        ValidationLine(
            "NSE listing identity source ready",
            state.researchSources
                .filter { it.name == "NSE_FORTHCOMING_LISTING" || it.name == "NSE_RECENT_LISTING" }
                .any { it.status == "FRESH" || it.status == "CACHED" }
        )

        StatusCard(
            title = "Automatic Live Trading",
            primary = "OFF",
            secondary = "Authentication success does not enable trading. Execution remains fail-closed in v1.3.2.",
            primaryColor = Danger
        )

        Button(onClick = {
            scope.launch {
                runCatching {
                    val file = AppAudit.exportWeekly(context)
                    AppAudit.shareExport(context, file)
                    message = "Weekly audit export created."
                }.onFailure {
                    message = "Audit export failed: " + (it.message ?: it.javaClass.simpleName)
                }
            }
        }) { Text("Export Weekly Audit Log") }

        message?.let { Text(it, color = if (it.contains("failed", true)) Danger else Muted) }
    }
}

@Composable
private fun ValidationLine(label: String, value: Boolean?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(
            when (value) {
                true -> "PASS"
                false -> "FAIL"
                null -> "UNKNOWN"
            },
            color = when (value) {
                true -> Teal
                false -> Danger
                null -> Muted
            },
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StatusCard(
    title: String,
    primary: String,
    secondary: String,
    primaryColor: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Muted)
            Text(primary, color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(secondary, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ScreenColumn(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}
