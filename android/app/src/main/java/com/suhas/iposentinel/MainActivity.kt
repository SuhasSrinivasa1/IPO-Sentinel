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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import java.text.NumberFormat
import java.util.Locale

private val Ink = Color(0xFF0F1318)
private val Card = Color(0xFF171D23)
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
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(AppScreen.DASHBOARD) }
    var liveEnabled by rememberSaveable { mutableStateOf(false) }
    var liveBusy by remember { mutableStateOf(false) }
    var liveMessage by remember { mutableStateOf<String?>(null) }
    var liveMessageColor by remember { mutableStateOf(Muted) }
    var budget by rememberSaveable { mutableFloatStateOf(100_000f) }
    var lastValidation by remember { mutableStateOf<ValidationStatus?>(null) }
    var growwConfigured by remember { mutableStateOf(false) }
    var researchPlan by remember { mutableStateOf<ResearchPlan?>(null) }

    val settingsPrefs = remember {
        context.getSharedPreferences("ipo_sentinel_settings_draft", android.content.Context.MODE_PRIVATE)
    }
    var growwTokenDraft by remember { mutableStateOf("") }
    var growwSecretDraft by remember { mutableStateOf("") }
    var staticIpDraft by rememberSaveable {
        mutableStateOf(settingsPrefs.getString("static_ip", "").orEmpty())
    }
    var whitelistDraft by rememberSaveable {
        mutableStateOf(settingsPrefs.getBoolean("whitelist_confirmed", false))
    }

    LaunchedEffect(Unit) {
        val directGroww = DirectGrowwClient(context)
        val (_, savedStatus) = directGroww.fetchStatus()
        if (savedStatus != null) {
            growwConfigured = savedStatus.growwConfigured
            if (!savedStatus.expectedStaticIp.isNullOrBlank()) {
                staticIpDraft = savedStatus.expectedStaticIp
                settingsPrefs.edit().putString("static_ip", staticIpDraft).apply()
            }
            whitelistDraft = savedStatus.staticIpConfirmed
            settingsPrefs.edit().putBoolean("whitelist_confirmed", whitelistDraft).apply()
        }

        val api = BackendApi()
        val (_, plan) = api.fetchResearchPlan()
        if (plan != null) {
            researchPlan = plan
        }

        val (_, liveState) = api.fetchLiveState()
        if (liveState != null) {
            liveEnabled = liveState.enabled
            budget = liveState.budgetRupees.toFloat()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            val (result, plan) = BackendApi().fetchResearchPlan()
            if (result.ok && plan != null) {
                researchPlan = plan
                AppAudit.log(
                    context,
                    "RESEARCH_PLAN_UI_SYNC",
                    JSONObject()
                        .put("next_trading_day", plan.nextTradingDay ?: "")
                        .put("candidate_count", plan.nextTradingDayCandidates.size)
                )
            }
        }
    }

    fun requestLiveState(enabled: Boolean) {
        if (liveBusy) return

        if (enabled && !NotificationHelper.notificationsAllowed(context)) {
            liveMessage = "Allow IPO Sentinel notifications before enabling live trading."
            liveMessageColor = Danger
            AppAudit.log(context, "LIVE_ENABLE_BLOCKED_NOTIFICATIONS")
            return
        }

        if (enabled && lastValidation?.liveExecutionReady != true) {
            liveMessage = "Validate Groww + Static IP in Settings before enabling live trading."
            liveMessageColor = Danger
            AppAudit.log(context, "LIVE_ENABLE_BLOCKED_VALIDATION")
            return
        }

        liveBusy = true
        scope.launch {
            AppAudit.log(
                context,
                "LIVE_STATE_REQUEST",
                JSONObject()
                    .put("enabled", enabled)
                    .put("budget_rupees", budget.toInt())
            )
            val (result, state) = BackendApi().setLiveState(enabled, budget.toInt())
            liveBusy = false

            if (result.ok && state != null) {
                liveEnabled = state.enabled
                if (state.enabled) {
                    context.getSharedPreferences("ipo_sentinel_live_events", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putLong("last_order_event_id", state.eventId)
                        .apply()
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, LiveNotificationService::class.java)
                    )
                    liveMessage = "Live trading enabled. Order notifications are active."
                    liveMessageColor = Teal
                } else {
                    liveMessage = "Auto trading disabled. Signal and managed-position monitoring remain active."
                    liveMessageColor = Muted
                }
                AppAudit.log(
                    context,
                    "LIVE_STATE_ACK",
                    JSONObject()
                        .put("enabled", state.enabled)
                        .put("budget_rupees", state.budgetRupees)
                )
            } else {
                liveMessage = result.error ?: "Live trading state could not be changed."
                liveMessageColor = Danger
                AppAudit.log(
                    context,
                    "LIVE_STATE_FAILED",
                    JSONObject()
                        .put("requested_enabled", enabled)
                        .put("error", result.error ?: "unknown")
                )
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Teal,
            secondary = Teal,
            background = Ink,
            surface = Card,
            error = Danger
        )
    ) {
        Scaffold(
            containerColor = Ink,
            bottomBar = {
                NavigationBar(containerColor = Card) {
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
                    liveEnabled = liveEnabled,
                    liveBusy = liveBusy,
                    liveMessage = liveMessage,
                    liveMessageColor = liveMessageColor,
                    onLiveEnabledChange = { requested -> requestLiveState(requested) },
                    budget = budget,
                    onBudgetChange = { budget = it },
                    growwConfigured = growwConfigured,
                    validation = lastValidation,
                    researchPlan = researchPlan
                )

                AppScreen.RESEARCH -> ResearchScreen(
                    modifier = Modifier.padding(padding),
                    budgetRupees = budget.toInt()
                )

                AppScreen.STRATEGIES -> StrategiesScreen(
                    modifier = Modifier.padding(padding)
                )

                AppScreen.SETTINGS -> GrowwSettingsScreen(
                    modifier = Modifier.padding(padding),
                    totpToken = growwTokenDraft,
                    onTotpTokenChange = { growwTokenDraft = it },
                    totpSecret = growwSecretDraft,
                    onTotpSecretChange = { growwSecretDraft = it },
                    staticIp = staticIpDraft,
                    onStaticIpChange = {
                        staticIpDraft = it.trim()
                        settingsPrefs.edit().putString("static_ip", staticIpDraft).apply()
                    },
                    whitelistConfirmed = whitelistDraft,
                    onWhitelistConfirmedChange = {
                        whitelistDraft = it
                        settingsPrefs.edit().putBoolean("whitelist_confirmed", whitelistDraft).apply()
                    },
                    onConfigurationSaved = {
                        growwConfigured = true
                        growwTokenDraft = ""
                        growwSecretDraft = ""
                    },
                    onValidated = {
                        lastValidation = it
                        scope.launch {
                            val (_, refreshedPlan) = BackendApi().fetchResearchPlan()
                            if (refreshedPlan != null) researchPlan = refreshedPlan
                        }
                        if (!it.liveExecutionReady && liveEnabled) {
                            requestLiveState(false)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    modifier: Modifier,
    liveEnabled: Boolean,
    liveBusy: Boolean,
    liveMessage: String?,
    liveMessageColor: Color,
    onLiveEnabledChange: (Boolean) -> Unit,
    budget: Float,
    onBudgetChange: (Float) -> Unit,
    growwConfigured: Boolean,
    validation: ValidationStatus?,
    researchPlan: ResearchPlan?
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("IPO Sentinel", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Listing-day + 30 trading-day intelligence • NSE", color = Muted)

        val readiness = when {
            validation?.liveExecutionReady == true -> "READY"
            growwConfigured -> "NEEDS VALIDATION"
            else -> "NOT CONFIGURED"
        }

        StatusCard(
            title = "Groww API + Static IP",
            primary = readiness,
            secondary = when {
                validation?.liveExecutionReady == true ->
                    "Groww authentication and static-IP checks passed"
                growwConfigured ->
                    "Credentials saved. Validate them from Settings."
                else ->
                    "Add Groww TOTP token, secret and whitelisted static IP in Settings"
            },
            primaryColor = if (validation?.liveExecutionReady == true) Teal else Amber
        )

        val nextDay = researchPlan?.nextTradingDay
        val nextCandidates = researchPlan?.nextTradingDayCandidates.orEmpty()
        val weekCandidates = researchPlan?.weekCandidates.orEmpty()
        val knownCandidates = researchPlan?.allKnownCandidates.orEmpty()
        val researchFailed = researchPlan != null && (
            researchPlan.researchHealth == "FAILED" || !researchPlan.sourceReady
        )

        StatusCard(
            title = "Daily research plan",
            primary = when {
                researchPlan == null -> "NOT SYNCED"
                researchFailed -> "RESEARCH SERVICE FAILED"
                researchPlan.researchHealth == "DEGRADED" -> "DEGRADED"
                else -> "HEALTHY"
            },
            secondary = when {
                researchPlan == null -> "Waiting for the backend research snapshot"
                researchFailed -> researchPlan.errors.joinToString(" • ").ifBlank {
                    "Official IPO source is unavailable; live execution remains blocked."
                }
                else -> "Generated " + (researchPlan.generatedAt ?: "timestamp unavailable") +
                    " • Known " + researchPlan.candidateCount +
                    " • NSE confirmed " + researchPlan.nseIdentityConfirmedCount +
                    " • Groww resolved " + researchPlan.growwResolvedCount +
                    " • Groww pending " + researchPlan.growwPendingCount
            },
            primaryColor = when {
                researchFailed -> Danger
                researchPlan?.researchHealth == "DEGRADED" -> Amber
                researchPlan?.sourceReady == true -> Teal
                else -> Amber
            }
        )

        StatusCard(
            title = "Next trading day",
            primary = when {
                nextDay == null -> "Official calendar not ready"
                researchFailed -> nextDay + " • Research unavailable"
                nextCandidates.isEmpty() -> nextDay + " • No confirmed listing"
                else -> nextDay + " • " + nextCandidates.size + " candidate" +
                    if (nextCandidates.size == 1) "" else "s"
            },
            secondary = when {
                researchFailed -> "Do not interpret this as no listings; the research source failed."
                nextCandidates.isEmpty() && nextDay != null ->
                    "No currently confirmed NSE IPO listings for the selected period"
                else ->
                    nextCandidates.joinToString(" • ") { candidate ->
                        (candidate.symbol ?: "Symbol Pending") + " [" + candidate.lifecycleState + "]"
                    }
            },
            primaryColor = when {
                researchFailed -> Danger
                researchPlan?.calendarReady == true -> Teal
                else -> Amber
            }
        )

        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = Card)) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Next-week IPO research", color = Muted, fontSize = 12.sp)
                val displayCandidates = if (weekCandidates.isNotEmpty()) weekCandidates else knownCandidates
                if (researchFailed) {
                    Text(
                        "Research service failed — candidate list is not authoritative.",
                        color = Danger,
                        fontSize = 12.sp
                    )
                } else if (displayCandidates.isEmpty()) {
                    Text(
                        "No currently confirmed NSE IPO listings for the selected period",
                        color = Muted,
                        fontSize = 12.sp
                    )
                } else {
                    displayCandidates.take(10).forEach { candidate ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    candidate.symbol ?: "Symbol Pending",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    (candidate.listingDate ?: "Listing date pending") +
                                        " • " + candidate.companyName +
                                        " • " + if (candidate.isSme) "SME" else "Mainboard",
                                    color = Muted,
                                    fontSize = 11.sp
                                )
                                Text(
                                    candidate.lifecycleState +
                                        (candidate.isin?.let { " • ISIN " + it } ?: ""),
                                    color = Muted,
                                    fontSize = 10.sp
                                )
                            }
                            Text(
                                when {
                                    candidate.symbolResolved -> "RESOLVED"
                                    candidate.nseListingConfirmed -> "GROWW PENDING"
                                    candidate.symbol == null -> "RESEARCHING"
                                    else -> "NSE PENDING"
                                },
                                color = if (candidate.symbolResolved) Teal else Amber,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    "Live trade gate: final NSE identity + exact Groww NSE/CASH instrument + valid listing-session quote/depth/liquidity/risk checks.",
                    color = Muted,
                    fontSize = 11.sp
                )
            }
        }

        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = Card)) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Live auto-trading", fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                liveBusy -> "Changing live state…"
                                liveEnabled -> "ARMED — order notifications active"
                                validation?.liveExecutionReady == true -> "READY — switch on when you want live execution"
                                else -> "LOCKED — Groww + static IP validation required"
                            },
                            color = if (liveEnabled) Danger else Muted,
                            fontSize = 13.sp
                        )
                    }
                    Switch(
                        checked = liveEnabled,
                        enabled = !liveBusy,
                        onCheckedChange = onLiveEnabledChange
                    )
                }

                liveMessage?.let {
                    Text(it, color = liveMessageColor, fontSize = 12.sp)
                }

                HorizontalDivider(color = Color(0xFF27313A))

                Text(
                    "Live budget  ₹" + NumberFormat.getNumberInstance(Locale("en", "IN"))
                        .format(budget.toInt()),
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = budget,
                    enabled = !liveEnabled && !liveBusy,
                    onValueChange = { onBudgetChange((it / 5_000f).toInt() * 5_000f) },
                    valueRange = 10_000f..100_000f
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("₹10k", color = Muted, fontSize = 12.sp)
                    Text("₹1L", color = Muted, fontSize = 12.sp)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Shadow P&L", "₹0", Modifier.weight(1f))
            MetricCard("Live P&L", if (liveEnabled) "₹0" else "OFF", Modifier.weight(1f))
        }

        StatusCard(
            title = "30-day IPO monitor",
            primary = "0 active listings",
            secondary = "Each new IPO remains under opportunity scan through trading day D30"
        )
        StatusCard(
            title = "Decision engine",
            primary = "WAIT",
            secondary = "No live listing-session evidence yet"
        )
        StatusCard(
            title = "Owned positions",
            primary = "0",
            secondary = "Unrelated Groww portfolio positions are read-only"
        )

        Button(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Danger),
            enabled = !liveBusy,
            onClick = { onLiveEnabledChange(false) }
        ) {
            Text("Emergency Disable", fontWeight = FontWeight.Bold)
        }
    }
}


@Composable
private fun ResearchScreen(
    modifier: Modifier,
    budgetRupees: Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { BackendApi() }
    var dashboard by remember { mutableStateOf<ResearchDashboard?>(null) }
    var busy by remember { mutableStateOf(false) }
    var actionBusy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageColor by remember { mutableStateOf(Muted) }

    fun refresh(showMessage: Boolean = false) {
        if (busy) return
        busy = true
        scope.launch {
            val (result, value) = client.fetchResearchDashboard()
            busy = false
            if (result.ok && value != null) {
                dashboard = value
                if (showMessage) {
                    message = "Research intelligence refreshed"
                    messageColor = Teal
                }
            } else {
                message = result.error ?: "Research dashboard is unavailable"
                messageColor = Danger
            }
        }
    }

    fun execute(symbol: String, action: String, fraction: Double = 1.0) {
        if (actionBusy != null) return
        actionBusy = symbol + ":" + action
        message = "Submitting " + action.replace("_", " ") + " for " + symbol + "…"
        messageColor = Amber
        scope.launch {
            val result = client.manualCardOrder(symbol, action, fraction)
            actionBusy = null
            if (result.ok) {
                message = action.replace("_", " ") + " accepted by Groww for " + symbol +
                    ". IPO Sentinel will reconcile and monitor it."
                messageColor = Teal
                AppAudit.log(
                    context,
                    "MANUAL_RESEARCH_CARD_ORDER",
                    JSONObject()
                        .put("symbol", symbol)
                        .put("action", action)
                        .put("budget_rupees", budgetRupees)
                )
                refresh()
            } else {
                message = result.error ?: "Order was not accepted"
                messageColor = Danger
                AppAudit.log(
                    context,
                    "MANUAL_RESEARCH_CARD_ORDER_REJECTED",
                    JSONObject()
                        .put("symbol", symbol)
                        .put("action", action)
                        .put("error", result.error ?: "unknown")
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(30_000L)
            if (!busy && actionBusy == null) refresh()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Research", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            "Tomorrow + D1-D30 IPO intelligence, signals, positions, outcomes and learning",
            color = Muted,
            fontSize = 13.sp
        )

        if (busy && dashboard == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        message?.let {
            Text(it, color = messageColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        val data = dashboard
        if (data == null) {
            StatusCard(
                title = "Research intelligence",
                primary = "NOT SYNCED",
                secondary = "Live research cards are unavailable until the direct on-device research engine has current market data.",
                primaryColor = Amber
            )
        } else {
            val goal = data.dailyGoal
            SettingsSection("IPO Sentinel daily objective") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("IPO-only realized P&L", color = Muted, fontSize = 11.sp)
                        Text(
                            String.format(Locale("en", "IN"), "₹%,.2f", goal.realizedNetPnl),
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (goal.realizedNetPnl >= 0) Teal else Danger
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Objective", color = Muted, fontSize = 11.sp)
                        Text("₹5,000", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                }
                LinearProgressIndicator(
                    progress = { (goal.realizedNetPnl.coerceAtLeast(0.0) / goal.targetRupees.coerceAtLeast(1.0)).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (goal.targetAchieved)
                        "Objective reached. Automatic new entries pause; existing positions continue to be managed."
                    else
                        "Remaining ₹" + String.format(Locale("en", "IN"), "%,.2f", goal.remainingRupees) +
                            " • " + goal.wins + " wins / " + goal.losses + " losses",
                    color = if (goal.targetAchieved) Teal else Muted,
                    fontSize = 12.sp
                )
                Text(
                    "Calculated only from IPO Sentinel reconciled buy/sell fills. Groww account-level P&L and your other trades are excluded.",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }

            SettingsSection("Top 3 watch for next session") {
                if (data.topThree.isEmpty()) {
                    Text("No ranked IPO candidates yet.", color = Muted, fontSize = 12.sp)
                } else {
                    data.topThree.take(3).forEachIndexed { index, pick ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("#" + (index + 1), color = Teal, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
                                Column(Modifier.weight(1f)) {
                                    Text((pick.symbol ?: "SYMBOL PENDING") + " • " + pick.companyName, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        listOfNotNull(
                                            pick.direction ?: pick.preMarketBias,
                                            pick.board,
                                            pick.tradingDayNumber?.let { "D" + it }
                                        ).joinToString(" • "),
                                        color = Muted,
                                        fontSize = 10.sp
                                    )
                                }
                                Text(
                                    String.format(Locale.US, "%.0f", pick.confidence ?: pick.researchScore),
                                    color = Teal,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (pick.entryPrice != null) {
                                Text(
                                    "Entry ₹" + String.format(Locale.US, "%.2f", pick.entryPrice) +
                                        " • T1 ₹" + String.format(Locale.US, "%.2f", pick.target1 ?: 0.0) +
                                        " • T2 ₹" + String.format(Locale.US, "%.2f", pick.target2 ?: 0.0) +
                                        " • SL ₹" + String.format(Locale.US, "%.2f", pick.stopLoss ?: 0.0),
                                    color = Muted,
                                    fontSize = 11.sp
                                )
                            }
                            if (pick.reasons.isNotEmpty()) {
                                Text(pick.reasons.take(4).joinToString(" • "), color = Muted, fontSize = 10.sp)
                            }
                        }
                        if (index < data.topThree.take(3).lastIndex) HorizontalDivider(color = Color(0xFF27313A))
                    }
                }
            }

            SettingsSection("Live opportunity cards") {
                if (data.activeSignals.isEmpty()) {
                    Text(
                        "No signal currently passes the price, volume, order-book and net-edge gates.",
                        color = Muted,
                        fontSize = 12.sp
                    )
                } else {
                    data.activeSignals.forEachIndexed { index, signal ->
                        val signalColor = if (signal.direction == "LONG") Teal else Danger
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(signal.companyName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(
                                        signal.symbol + " • " + signal.direction + " • D" + signal.tradingDayNumber +
                                            " • " + if (signal.isSme) "SME" else "Mainboard",
                                        color = signalColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    String.format(Locale.US, "%.0f%%", signal.confidence),
                                    color = signalColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                StrategyStat("Entry", "₹" + String.format(Locale.US, "%.2f", signal.entryPrice))
                                StrategyStat("T1", "₹" + String.format(Locale.US, "%.2f", signal.target1))
                                StrategyStat("T2", "₹" + String.format(Locale.US, "%.2f", signal.target2))
                                StrategyStat("Stop", "₹" + String.format(Locale.US, "%.2f", signal.stopLoss))
                            }
                            Text(
                                "Qty " + signal.quantity +
                                    " • RVOL " + String.format(Locale.US, "%.2fx", signal.relativeVolume) +
                                    " • Spread " + String.format(Locale.US, "%.0f bps", signal.spreadBps) +
                                    " • Est. net @ T2 ₹" + String.format(Locale("en", "IN"), "%,.0f", signal.expectedNetAtTarget2),
                                color = Muted,
                                fontSize = 11.sp
                            )
                            if (signal.reasonCodes.isNotEmpty()) {
                                Text(signal.reasonCodes.take(5).joinToString(" • "), color = Muted, fontSize = 10.sp)
                            }
                            Button(
                                onClick = {
                                    execute(
                                        signal.symbol,
                                        if (signal.direction == "LONG") "BUY" else "SHORT"
                                    )
                                },
                                enabled = actionBusy == null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (actionBusy == signal.symbol + ":" + if (signal.direction == "LONG") "BUY" else "SHORT")
                                        "Submitting…"
                                    else if (signal.direction == "LONG")
                                        "BUY " + signal.quantity + " CNC"
                                    else
                                        "SHORT " + signal.quantity + " MIS"
                                )
                            }
                        }
                        if (index < data.activeSignals.lastIndex) HorizontalDivider(color = Color(0xFF27313A))
                    }
                }
            }

            SettingsSection("Managed IPO Sentinel positions") {
                if (data.openPositions.isEmpty()) {
                    Text("No open IPO Sentinel positions.", color = Muted, fontSize = 12.sp)
                } else {
                    data.openPositions.forEachIndexed { index, position ->
                        val isLong = position.quantity > 0
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(position.symbol, fontWeight = FontWeight.Bold)
                                    Text(
                                        (if (isLong) "LONG" else "SHORT") + " • " + kotlin.math.abs(position.quantity) + " units",
                                        color = Muted,
                                        fontSize = 11.sp
                                    )
                                }
                                Text(
                                    String.format(Locale("en", "IN"), "%+,.2f", position.unrealizedPnl),
                                    color = if (position.unrealizedPnl >= 0) Teal else Danger,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                "Avg ₹" + String.format(Locale.US, "%.2f", position.avgPrice) +
                                    " • LTP ₹" + String.format(Locale.US, "%.2f", position.mark),
                                color = Muted,
                                fontSize = 11.sp
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { execute(position.symbol, if (isLong) "SELL_25" else "COVER_25") },
                                    enabled = actionBusy == null,
                                    modifier = Modifier.weight(1f)
                                ) { Text("25%", fontSize = 11.sp) }
                                OutlinedButton(
                                    onClick = { execute(position.symbol, if (isLong) "SELL_50" else "COVER_50") },
                                    enabled = actionBusy == null,
                                    modifier = Modifier.weight(1f)
                                ) { Text("50%", fontSize = 11.sp) }
                                Button(
                                    onClick = { execute(position.symbol, if (isLong) "SELL_ALL" else "COVER_ALL") },
                                    enabled = actionBusy == null,
                                    colors = ButtonDefaults.buttonColors(containerColor = Danger),
                                    modifier = Modifier.weight(1f)
                                ) { Text("EXIT", fontSize = 11.sp) }
                            }
                        }
                        if (index < data.openPositions.lastIndex) HorizontalDivider(color = Color(0xFF27313A))
                    }
                }
            }

            SettingsSection("Tomorrow's listing queue") {
                if (data.tomorrowCandidates.isEmpty()) {
                    Text("No authoritative NSE listing candidates currently queued.", color = Muted, fontSize = 12.sp)
                } else {
                    data.tomorrowCandidates.forEach { candidate ->
                        Text(
                            (candidate.symbol ?: "Symbol pending") + " • " + candidate.companyName +
                                " • " + if (candidate.isSme) "SME" else "Mainboard",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Text(
                            (candidate.issuePriceText ?: "Issue price pending") +
                                (candidate.subscriptionMultiple?.let { " • Subscription " + String.format(Locale.US, "%.2fx", it) } ?: "") +
                                " • " + candidate.resolutionStatus,
                            color = Muted,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            SettingsSection("Active D1-D30 universe") {
                Text(
                    data.active30dCandidates.size.toString() + " tracked • Mainboard " +
                        data.mainboardCoverage + " • SME " + data.smeCoverage,
                    fontWeight = FontWeight.SemiBold
                )
                data.active30dCandidates.take(30).forEach { candidate ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(candidate.symbol ?: "Pending", modifier = Modifier.width(100.dp), fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                        Text(
                            "D" + (candidate.tradingDayNumber ?: 0) + " • " +
                                if (candidate.isSme) "SME" else "Mainboard",
                            color = Muted,
                            fontSize = 10.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (candidate.symbolResolved) "READY" else "IDENTITY",
                            color = if (candidate.symbolResolved) Teal else Amber,
                            fontSize = 9.sp
                        )
                    }
                }
                Text(data.coverageRule, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
            }

            SettingsSection("Closed calls") {
                if (data.closedCalls.isEmpty()) {
                    Text("No completed IPO Sentinel calls yet.", color = Muted, fontSize = 12.sp)
                } else {
                    data.closedCalls.take(50).forEachIndexed { index, call ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(call.symbol + " • " + call.direction, fontWeight = FontWeight.SemiBold)
                                Text(
                                    call.quantity.toString() + " units • ₹" + String.format(Locale.US, "%.2f", call.entryPrice) +
                                        " → ₹" + String.format(Locale.US, "%.2f", call.exitPrice),
                                    color = Muted,
                                    fontSize = 10.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    call.outcome,
                                    color = if (call.outcome == "WIN") Teal else if (call.outcome == "LOSS") Danger else Muted,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "₹" + String.format(Locale("en", "IN"), "%+,.2f", call.netPnl),
                                    color = if (call.netPnl >= 0) Teal else Danger,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        if (index < data.closedCalls.take(50).lastIndex) HorizontalDivider(color = Color(0xFF27313A))
                    }
                }
            }

            SettingsSection("What we learned / strategy maintenance") {
                if (data.dailyLearning.isEmpty() && data.weeklyLearning.isEmpty()) {
                    Text(
                        "Learning records will appear after the daily 16:20 review and Sunday strategy revalidation.",
                        color = Muted,
                        fontSize = 12.sp
                    )
                }
                data.dailyLearning.take(7).forEach { record ->
                    Text("Daily • " + record.summary, fontSize = 11.sp)
                }
                data.weeklyLearning.take(4).forEach { record ->
                    Text("Weekly • " + record.summary, fontSize = 11.sp, color = Amber)
                }
                Text(
                    "Champions are retained unless future evidence invalidates their promotion gates. Repeatedly negative strategies are flagged for rework/demotion rather than silently reused.",
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
            }

            OutlinedButton(
                onClick = { refresh(showMessage = true) },
                enabled = !busy && actionBusy == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "Refreshing…" else "Refresh Research")
            }
        }
    }
}

@Composable
private fun StrategiesScreen(modifier: Modifier) {
    val context = LocalContext.current
    var summary by remember { mutableStateOf<StrategySummary?>(null) }
    var sourceMessage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val client = remember { BackendApi() }

    fun refresh() {
        if (busy) return
        busy = true
        scope.launch {
            val (result, value) = client.fetchStrategySummary()
            busy = false
            if (result.ok && value != null) {
                summary = value
                sourceMessage = null
                AppAudit.log(context, "STRATEGY_SUMMARY_SYNCED")
            } else {
                summary = LocalStrategyCatalog.summary()
                sourceMessage = "Showing the built-in strategy catalog. Replay statistics are unavailable until the direct on-device research engine has completed local evidence collection."
                AppAudit.log(
                    context,
                    "STRATEGY_SUMMARY_FALLBACK",
                    JSONObject().put("error", result.error ?: "unknown")
                )
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Strategies", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            "Replay evidence, family rankings and champion promotion status",
            color = Muted,
            fontSize = 13.sp
        )

        if (busy && summary == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        sourceMessage?.let {
            StatusCard(
                title = "Evidence status",
                primary = "CATALOG AVAILABLE",
                secondary = it,
                primaryColor = Amber
            )
        }

        summary?.let { data ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CompactMetricCard("Total", data.totalStrategyFamilies.toString(), Modifier.weight(1f))
                CompactMetricCard("Tested", data.testedFamilies.toString(), Modifier.weight(1f))
                CompactMetricCard("Champions", data.champions.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CompactMetricCard("Challengers", data.challengers.toString(), Modifier.weight(1f))
                CompactMetricCard(
                    "Coverage",
                    if (data.totalStrategyFamilies > 0)
                        ((data.testedFamilies * 100) / data.totalStrategyFamilies).toString() + "%"
                    else "0%",
                    Modifier.weight(1f)
                )
                CompactMetricCard("Research", data.untestedFamilies.toString(), Modifier.weight(1f))
            }

            SettingsSection("Top 5 Working Families") {
                if (data.topFive.isEmpty()) {
                    Text(
                        "No family has enough recorded replay evidence to be called a top performer yet.",
                        color = Muted,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                } else {
                    data.topFive.forEachIndexed { index, family ->
                        StrategyFamilyRow(index + 1, family)
                        if (index < data.topFive.lastIndex) {
                            HorizontalDivider(color = Color(0xFF27313A))
                        }
                    }
                }
            }

            SettingsSection("Promotion Pipeline") {
                Text(
                    "CHAMPION requires mature positive evidence after costs. CHALLENGER has sufficient testing but has not passed every promotion gate. RESEARCH is untested or has a small sample.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                HorizontalDivider(color = Color(0xFF27313A))
                Text("Champion gate", fontWeight = FontWeight.SemiBold)
                Text(
                    "≥30 trades • positive net expectancy • profit factor ≥1.15 • controlled drawdown • non-negative recent evidence",
                    color = Muted,
                    fontSize = 12.sp
                )
            }

            SettingsSection("Registered Families") {
                data.families.forEachIndexed { index, family ->
                    RegisteredFamilyRow(family)
                    if (index < data.families.lastIndex) {
                        HorizontalDivider(color = Color(0xFF27313A))
                    }
                }
            }

            Text(data.rankingNote, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)

            OutlinedButton(
                onClick = { refresh() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "Refreshing…" else "Refresh Strategy Evidence")
            }
        }
    }
}

@Composable
private fun GrowwSettingsScreen(
    modifier: Modifier,
    totpToken: String,
    onTotpTokenChange: (String) -> Unit,
    totpSecret: String,
    onTotpSecretChange: (String) -> Unit,
    staticIp: String,
    onStaticIpChange: (String) -> Unit,
    whitelistConfirmed: Boolean,
    onWhitelistConfirmedChange: (Boolean) -> Unit,
    onConfigurationSaved: () -> Unit,
    onValidated: (ValidationStatus) -> Unit
) {
    val context = LocalContext.current

    var busy by remember { mutableStateOf(false) }
    var exportBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageColor by remember { mutableStateOf(Muted) }
    var status by remember { mutableStateOf<ConnectionStatus?>(null) }
    var validation by remember { mutableStateOf<ValidationStatus?>(null) }
    var notificationAllowed by remember { mutableStateOf(NotificationHelper.notificationsAllowed(context)) }
    val scope = rememberCoroutineScope()
    val client = remember(context) { DirectGrowwClient(context) }

    fun refreshStatus(showMessage: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val (result, value) = client.fetchStatus()
            busy = false
            notificationAllowed = NotificationHelper.notificationsAllowed(context)
            if (result.ok && value != null) {
                status = value
                if (!value.expectedStaticIp.isNullOrBlank()) onStaticIpChange(value.expectedStaticIp)
                onWhitelistConfirmedChange(value.staticIpConfirmed)
                if (showMessage) {
                    message = "Settings status refreshed"
                    messageColor = Teal
                }
            } else if (showMessage) {
                message = result.error ?: "Unable to refresh settings"
                messageColor = Danger
            }
        }
    }

    LaunchedEffect(Unit) {
        val (result, value) = client.fetchStatus()
        if (result.ok && value != null) {
            status = value
            if (!value.expectedStaticIp.isNullOrBlank()) onStaticIpChange(value.expectedStaticIp)
            onWhitelistConfirmedChange(value.staticIpConfirmed)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            "Groww credentials, static-IP validation, notifications and weekly audit export.",
            color = Muted,
            fontSize = 13.sp
        )

        SettingsSection("Order Notifications") {
            CheckRow("Notification permission", notificationAllowed)
            Text(
                "IPO Sentinel requests normal Android notification permission. It does not request access to read notifications from other apps.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            if (!notificationAllowed) {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Notification Settings")
                }
            } else {
                OutlinedButton(
                    onClick = {
                        NotificationHelper.showOrderEvent(
                            context,
                            OrderLifecycleEvent(
                                id = System.currentTimeMillis(),
                                timestamp = java.time.Instant.now().toString(),
                                eventType = "ORDER_FILLED",
                                symbol = "TEST",
                                side = "BUY",
                                quantity = 1,
                                price = 100.0,
                                orderId = "TEST",
                                message = "Notification test only — no order was placed"
                            )
                        )
                        AppAudit.log(context, "TEST_NOTIFICATION_SENT")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Send Test Notification")
                }
            }
        }

        SettingsSection("Groww TOTP") {
            OutlinedTextField(
                value = totpToken,
                onValueChange = onTotpTokenChange,
                label = { Text("Groww TOTP token / API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = totpSecret,
                onValueChange = onTotpSecretChange,
                label = { Text("Groww TOTP secret") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                when {
                    status?.growwConfigured == true && totpToken.isBlank() && totpSecret.isBlank() ->
                        "Groww credentials are encrypted on this phone using Android Keystore. Enter new values only to replace them."
                    else ->
                        "The token and TOTP secret are stored only after you tap Save. They are encrypted locally and are never shown again."
                },
                color = Muted,
                fontSize = 12.sp
            )
        }

        SettingsSection("Static IP") {
            OutlinedTextField(
                value = staticIp,
                onValueChange = onStaticIpChange,
                label = { Text("Whitelisted static public IP") },
                placeholder = { Text("203.0.113.10") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = whitelistConfirmed,
                    onCheckedChange = onWhitelistConfirmedChange
                )
                Text(
                    "I have whitelisted this IP in Groww",
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                "This must be the fixed public IP registered in Groww for API order placement.",
                color = Muted,
                fontSize = 12.sp
            )
        }

        Button(
            onClick = {
                if (totpToken.isBlank() || totpSecret.isBlank() || staticIp.isBlank()) {
                    message = "Enter the Groww token, TOTP secret and static IP"
                    messageColor = Danger
                    return@Button
                }
                busy = true
                scope.launch {
                    val result = client.saveGrowwSettings(
                        totpToken = totpToken,
                        totpSecret = totpSecret,
                        expectedStaticIp = staticIp,
                        staticIpConfirmed = whitelistConfirmed
                    )
                    busy = false
                    if (result.ok) {
                        onConfigurationSaved()
                        message = "Groww settings saved securely on this phone — validating direct connection…"
                        messageColor = Teal
                        AppAudit.log(
                            context,
                            "GROWW_SETTINGS_SAVED",
                            JSONObject()
                                .put("static_ip", staticIp)
                                .put("whitelist_confirmed", whitelistConfirmed)
                        )
                        refreshStatus(showMessage = false)

                        val (validationResult, validationValue) = client.validate()
                        if (validationResult.ok && validationValue != null) {
                            validation = validationValue
                            onValidated(validationValue)
                            message = if (validationValue.liveExecutionReady) {
                                "Saved and validated — LIVE EXECUTION READY"
                            } else {
                                "Saved, but live execution is still locked by one or more validation checks"
                            }
                            messageColor = if (validationValue.liveExecutionReady) Teal else Amber
                        } else {
                            message = validationResult.error ?: "Settings saved, but validation failed"
                            messageColor = Amber
                        }
                    } else {
                        message = result.error ?: "Save failed"
                        messageColor = Danger
                        AppAudit.log(
                            context,
                            "GROWW_SETTINGS_SAVE_FAILED",
                            JSONObject().put("error", result.error ?: "unknown")
                        )
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Save Groww Settings")
        }

        OutlinedButton(
            onClick = {
                busy = true
                scope.launch {
                    val (result, value) = client.validate()
                    busy = false
                    if (result.ok && value != null) {
                        validation = value
                        onValidated(value)
                        message = if (value.liveExecutionReady) {
                            "Validation passed — live execution can be armed"
                        } else {
                            "Validation completed — one or more checks failed"
                        }
                        messageColor = if (value.liveExecutionReady) Teal else Amber
                        AppAudit.log(
                            context,
                            "GROWW_VALIDATION_RESULT",
                            JSONObject()
                                .put("ready", value.liveExecutionReady)
                                .put("auth_ok", value.growwAuthOk)
                                .put("static_ip_matches", value.staticIpMatches)
                                .put("calendar_ready", value.calendarReady)
                                .put("nse_identity_source_ready", value.nseIdentitySourceReady)
                        )
                    } else {
                        message = result.error ?: "Validation failed"
                        messageColor = Danger
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Validate Groww + Static IP")
        }

        TextButton(
            onClick = { refreshStatus(showMessage = true) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Refresh Status")
        }

        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        message?.let {
            Text(it, color = messageColor, fontWeight = FontWeight.SemiBold)
        }

        status?.let {
            SettingsSection("Saved status") {
                CheckRow("Android Keystore credential vault", it.secretStoreReady)
                CheckRow("Groww credentials saved", it.growwConfigured)
                CheckRow("Static IP marked as whitelisted", it.staticIpConfirmed)
            }
        }

        validation?.let {
            SettingsSection("Validation result") {
                CheckRow("Groww TOTP authentication", it.growwAuthOk)
                CheckRow("Static public IP matches", it.staticIpMatches)
                CheckRow("Groww whitelist confirmed", it.staticIpConfirmed)
                CheckRow("Android Keystore credential vault", it.secretStoreReady)
                CheckRow("Official NSE calendar ready", it.calendarReady)
                CheckRow("NSE listing identity source ready", it.nseIdentitySourceReady)
                HorizontalDivider(color = Color(0xFF27313A))
                Text(
                    "Detected static IP: " + (it.detectedEgressIp ?: "Unavailable"),
                    color = Muted,
                    fontSize = 12.sp
                )
                Text(
                    "Whitelisted IP: " + (it.expectedStaticIp ?: "Not configured"),
                    color = Muted,
                    fontSize = 12.sp
                )
                Text(
                    if (it.liveExecutionReady) "LIVE EXECUTION READY" else "LIVE EXECUTION LOCKED",
                    color = if (it.liveExecutionReady) Teal else Danger,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        SettingsSection("Weekly Verification Logs") {
            Text(
                "Exports the last 7 days of app audit events. Groww credentials and access tokens are excluded.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Button(
                onClick = {
                    if (exportBusy) return@Button
                    exportBusy = true
                    scope.launch {
                        runCatching {
                            val file = AppAudit.exportWeekly(context)
                            AppAudit.shareExport(context, file)
                        }.onFailure { error ->
                            message = "Log export failed: " + (error.message ?: "unknown error")
                            messageColor = Danger
                        }
                        exportBusy = false
                    }
                },
                enabled = !exportBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (exportBusy) "Preparing logs…" else "Export Weekly Logs")
            }
        }

        Text(
            "Security: Groww TOTP/API credentials are encrypted with Android Keystore and are never repopulated into the UI. IPO Sentinel uses fixed HTTPS endpoints for Groww and official market data; there is no server URL, device ID or device key to configure.",
            color = Muted,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = Card)) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
private fun CompactMetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier,
        colors = CardDefaults.elevatedCardColors(containerColor = Card)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
}

@Composable
private fun StrategyFamilyRow(rank: Int, family: StrategyFamilyStats) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#" + rank, color = Teal, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text(family.name, fontWeight = FontWeight.SemiBold)
                Text(family.phase.replace("_", " "), color = Muted, fontSize = 10.sp)
            }
            StrategyStatusBadge(family.status)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StrategyStat("Trades", family.trades.toString())
            StrategyStat("Win", String.format(Locale.US, "%.1f%%", family.winRatePct))
            StrategyStat("Exp.", String.format(Locale.US, "%.1f bps", family.expectancyBps))
            StrategyStat("PF", String.format(Locale.US, "%.2f", family.profitFactor))
        }
        Text(
            "Max DD " + String.format(Locale.US, "%.0f bps", family.maxDrawdownBps) +
                " • Recent " + String.format(Locale.US, "%+.0f bps", family.last20NetBps),
            color = Muted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun RegisteredFamilyRow(family: StrategyFamilyStats) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(family.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(
                family.phase.replace("_", " ") + " • " + family.trades + " replay trades",
                color = Muted,
                fontSize = 10.sp
            )
        }
        StrategyStatusBadge(family.status)
    }
}

@Composable
private fun StrategyStat(label: String, value: String) {
    Column {
        Text(label, color = Muted, fontSize = 9.sp)
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    }
}

@Composable
private fun StrategyStatusBadge(status: String) {
    val color = when (status.uppercase()) {
        "CHAMPION" -> Teal
        "CHALLENGER" -> Amber
        else -> Muted
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            status,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier, colors = CardDefaults.elevatedCardColors(containerColor = Card)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    primary: String,
    secondary: String,
    primaryColor: Color = Color.Unspecified
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = Card)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Muted, fontSize = 12.sp)
            Text(primary, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = primaryColor)
            Text(secondary, color = Muted, fontSize = 13.sp)
        }
    }
}
