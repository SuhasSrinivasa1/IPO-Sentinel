package com.suhas.iposentinel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AppBg = Color(0xFF090C10)
private val NavBg = Color(0xFF0F1318)
private val Raised = Color(0xFF121820)
private val Line = Color(0xFF242B35)
private val TextPrimary = Color(0xFFF2F5F8)
private val TextSecondary = Color(0xFF8E9AA7)
private val Positive = Color(0xFF00C28A)
private val Negative = Color(0xFFFF5A70)
private val Warning = Color(0xFFE9B949)
private val Info = Color(0xFF6EA8FE)

private enum class AppScreen { CALLS, RESEARCH, STRATEGIES, SYSTEM }
private enum class CallsMode { LIVE, CLOSED }

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
        ResearchLearningScheduler.ensureScheduled(this)
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
    var screen by rememberSaveable { mutableStateOf(AppScreen.CALLS) }

    LaunchedEffect(Unit) { repository.initialize() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5L * 60L * 1000L)
            repository.refreshSignals()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30L * 60L * 1000L)
            repository.refreshResearch(force = true)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Positive,
            secondary = Info,
            background = AppBg,
            surface = Raised,
            error = Negative,
            onBackground = TextPrimary,
            onSurface = TextPrimary
        )
    ) {
        Scaffold(
            containerColor = AppBg,
            bottomBar = {
                NavigationBar(containerColor = NavBg, tonalElevation = 0.dp) {
                    BottomNavItem(screen, AppScreen.CALLS, "●", "Calls") { screen = AppScreen.CALLS }
                    BottomNavItem(screen, AppScreen.RESEARCH, "⌕", "Research") { screen = AppScreen.RESEARCH }
                    BottomNavItem(screen, AppScreen.STRATEGIES, "⌁", "Strategies") { screen = AppScreen.STRATEGIES }
                    BottomNavItem(screen, AppScreen.SYSTEM, "⚙", "System") { screen = AppScreen.SYSTEM }
                }
            }
        ) { padding ->
            when (screen) {
                AppScreen.CALLS -> CallsScreen(
                    modifier = Modifier.padding(padding),
                    state = state,
                    onScan = { repository.refreshSignals() },
                    onReview = { call -> repository.previewOrder(call) }
                )
                AppScreen.RESEARCH -> ResearchScreen(
                    modifier = Modifier.padding(padding),
                    state = state,
                    onRefresh = { repository.refreshResearch(force = true) }
                )
                AppScreen.STRATEGIES -> StrategiesScreen(
                    modifier = Modifier.padding(padding),
                    state = state,
                    onReplay = { repository.runShadowReplay() }
                )
                AppScreen.SYSTEM -> SystemScreen(
                    modifier = Modifier.padding(padding),
                    state = state,
                    repository = repository
                )
            }
        }
    }
}

@Composable
private fun RowScope.BottomNavItem(
    current: AppScreen,
    target: AppScreen,
    glyph: String,
    label: String,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = current == target,
        onClick = onClick,
        icon = {
            Text(
                glyph,
                fontSize = 18.sp,
                color = if (current == target) Positive else TextSecondary
            )
        },
        label = {
            Text(
                label,
                fontSize = 10.sp,
                color = if (current == target) TextPrimary else TextSecondary
            )
        }
    )
}

@Composable
private fun CallsScreen(
    modifier: Modifier,
    state: AppState,
    onScan: suspend () -> Unit,
    onReview: suspend (RecommendationCall) -> OrderReviewResult
) {
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(CallsMode.LIVE) }
    val calls = if (mode == CallsMode.LIVE) state.liveCalls else state.closedCalls

    Column(modifier.fillMaxSize()) {
        ProductHeader(
            eyebrow = "IPO SENTINEL",
            title = "Calls",
            status = when {
                state.isRefreshing -> "Scanning market…"
                state.signalScan == null -> "Waiting for first verified scan"
                state.signalScan?.errors?.isNotEmpty() == true -> "Scan degraded"
                else -> "Last scan " + formatIst(state.signalScan?.scannedAt)
            }
        )

        CallsSummaryStrip(state)
        DailyPnlPanel(state)

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CallsModeSegment(
                label = "LIVE",
                count = state.liveCalls.size,
                selected = mode == CallsMode.LIVE,
                modifier = Modifier.weight(1f)
            ) { mode = CallsMode.LIVE }
            CallsModeSegment(
                label = "CLOSED",
                count = state.closedCalls.size,
                selected = mode == CallsMode.CLOSED,
                modifier = Modifier.weight(1f)
            ) { mode = CallsMode.CLOSED }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (mode == CallsMode.LIVE) {
                    "Only strategy-triggered calls. Research candidates never appear here."
                } else {
                    "Closed by stop, target, session close or verified broker reconciliation."
                },
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { scope.launch { onScan() } },
                enabled = !state.isRefreshing
            ) {
                Text("Scan now")
            }
        }

        if (state.isRefreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        if (calls.isEmpty()) {
            EmptyCallsState(mode, state)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(calls, key = { it.callId }) { call ->
                    CallRow(
                        call = call,
                        liveReviewMode = state.tradeReviewSettings.liveReviewMode,
                        onReview = onReview
                    )
                    HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 18.dp))
                }
                item { Spacer(Modifier.height(18.dp)) }
            }
        }
    }
}

@Composable
private fun DailyPnlPanel(state: AppState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PnlTile(
            label = "SHADOW P&L",
            value = state.todayShadowPnlRupees,
            subtitle = "Today • ₹1,00,000 model",
            modifier = Modifier.weight(1f)
        )
        PnlTile(
            label = "CLOSED P&L",
            value = state.todayClosedShadowPnlRupees,
            subtitle = state.todayClosedCalls.size.toString() + " closed today",
            modifier = Modifier.weight(1f)
        )
    }
    Text(
        "Open shadow MTM " + formatRupees(state.todayOpenShadowPnlRupees) +
            "  •  Broker realised " + (state.brokerRealisedPnlRupees?.let(::formatRupees) ?: "—") +
            "  •  manual broker review",
        color = TextSecondary,
        fontSize = 10.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp)
    )
}

@Composable
private fun PnlTile(
    label: String,
    value: Double,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Raised,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(label, color = TextSecondary, fontSize = 9.sp, letterSpacing = 0.8.sp)
            Text(
                formatRupees(value),
                color = pnlColor(value),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Text(subtitle, color = TextSecondary, fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun CallsModeSegment(
    label: String,
    count: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) Raised else AppBg,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = if (selected) TextPrimary else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp
            )
            Spacer(Modifier.width(7.dp))
            Text(
                count.toString(),
                color = if (selected) Positive else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun CallsSummaryStrip(state: AppState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        MiniMetric("LIVE", state.liveCalls.size.toString(), if (state.liveCalls.isNotEmpty()) Positive else TextSecondary)
        MiniMetric("CLOSED", state.closedCalls.size.toString(), TextPrimary)
        MiniMetric(
            "NSE",
            state.researchPlan?.researchHealth ?: "—",
            if (state.researchPlan?.researchHealth == "OK") Positive else Warning
        )
        MiniMetric(
            "VERIFIED",
            (state.researchPlan?.growwResolvedCount ?: 0).toString(),
            Info
        )
    }
}

@Composable
private fun RowScope.MiniMetric(label: String, value: String, valueColor: Color) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, color = TextSecondary, fontSize = 9.sp, letterSpacing = 0.8.sp)
        Text(
            value,
            color = valueColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyCallsState(mode: CallsMode, state: AppState) {
    Box(modifier = Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (mode == CallsMode.LIVE) "No verified live calls" else "No closed calls yet",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (mode == CallsMode.LIVE) {
                    "A stock name is not a call. A call appears only after exact NSE ↔ Groww identity plus a composite strategy trigger on real 5-minute candles."
                } else {
                    "Completed strategy calls will accumulate here with signal time, close time, exit price and outcome."
                },
                color = TextSecondary,
                fontSize = 13.sp
            )
            state.signalScan?.let {
                Text(
                    "Verified " + it.verifiedSymbols + " • evaluated " + it.evaluatedSymbols + " • signals " + it.signalsFound,
                    color = if (it.errors.isEmpty()) Info else Warning,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun CallRow(
    call: RecommendationCall,
    liveReviewMode: Boolean,
    onReview: suspend (RecommendationCall) -> OrderReviewResult
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var expanded by rememberSaveable(call.callId) { mutableStateOf(false) }
    var reviewBusy by remember(call.callId) { mutableStateOf(false) }
    var orderReview by remember(call.callId) { mutableStateOf<OrderReviewResult?>(null) }
    val resultColor = when {
        call.returnPct == null -> TextSecondary
        call.returnPct >= 0.0 -> Positive
        else -> Negative
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        call.symbol ?: "UNRESOLVED",
                        color = TextPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusPill(
                        text = if (call.growwSymbol != null && call.symbol != null) "NSE VERIFIED" else "IDENTITY?",
                        color = if (call.growwSymbol != null && call.symbol != null) Positive else Warning
                    )
                }
                Text(
                    call.companyName,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (call.symbol != null && call.growwSymbol != null) {
                    Text(
                        "NSE " + call.symbol + "  ↔  Groww " + call.growwSymbol +
                            (call.isin?.let { "  •  " + it } ?: ""),
                        color = Info,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (call.state == "LIVE" && call.signalScore != null) {
                    Surface(
                        color = Info.copy(alpha = 0.12f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.clickable(enabled = !reviewBusy) {
                            scope.launch {
                                reviewBusy = true
                                val review = onReview(call)
                                orderReview = review
                                reviewBusy = false
                                if (liveReviewMode && review.ready) {
                                    openGrowwApp(context)
                                }
                            }
                        }
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                String.format("%.0f%%", call.signalScore),
                                color = Info,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                when {
                                    reviewBusy -> "CHECKING"
                                    liveReviewMode -> "OPEN GROWW"
                                    else -> "REVIEW ORDER"
                                },
                                color = TextSecondary,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Text(
                        call.returnPct?.let { signedPct(it) } ?: "LIVE",
                        color = resultColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    if (call.state == "LIVE") formatIst(call.recommendedAt) else formatIst(call.closedAt ?: call.lastUpdatedAt),
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        Text(
            call.strategyName ?: "Strategy evidence unavailable",
            color = Info,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (call.holdPolicy ?: "INTRADAY") +
                    "  •  shadow qty " + call.shadowQuantity +
                    "  •  " + formatRupees(call.shadowPnlRupees),
                color = if (call.shadowPnlRupees >= 0.0) Positive else Negative,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            if (call.shadowQuantity > 0) {
                Text(
                    "₹" + String.format("%.0f", call.shadowEntryValue) + " deployed",
                    color = TextSecondary,
                    fontSize = 10.sp
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            PriceMetric("ENTRY", call.entryPrice)
            PriceMetric("STOP", call.stopLoss)
            PriceMetric("T1", call.target1)
            PriceMetric("T2", call.target2)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Signal " + formatIst(call.recommendedAt),
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            call.signalScore?.let {
                Text("Score " + String.format("%.0f", it), color = TextSecondary, fontSize = 11.sp)
            }
        }

        if (expanded) {
            HorizontalDivider(color = Line)
            Text("Exact identity", color = TextSecondary, fontSize = 10.sp, letterSpacing = 0.7.sp)
            Text(
                "NSE " + (call.symbol ?: "—") + "  •  Groww " + (call.growwSymbol ?: "—") +
                    (call.isin?.let { "  •  ISIN " + it } ?: ""),
                color = TextPrimary,
                fontSize = 12.sp
            )
            Text(
                "Shadow execution: " + (call.holdPolicy ?: "INTRADAY") +
                    " • max " + call.maxHoldTradingSessions + " trading session" +
                    (if (call.maxHoldTradingSessions == 1) "" else "s") +
                    " • qty " + call.shadowQuantity,
                color = TextSecondary,
                fontSize = 11.sp
            )

            if (call.confirmingStrategyIds.isNotEmpty()) {
                Text(
                    "Confirmations: " + call.confirmingStrategyIds.mapNotNull {
                        CompositeStrategyCatalog.definition(it)?.name
                    }.joinToString(" • "),
                    color = Positive,
                    fontSize = 12.sp
                )
            }

            if (call.evidenceSummary.isNotEmpty()) {
                Text("Why it fired", color = TextSecondary, fontSize = 10.sp, letterSpacing = 0.7.sp)
                call.evidenceSummary.forEach {
                    Text("• " + it, color = TextPrimary, fontSize = 12.sp)
                }
            }

            call.closedAt?.let {
                Text(
                    "Closed " + formatIst(it) + " • " + (call.closeReason ?: "closed") +
                        (call.exitPrice?.let { px -> " • ₹" + String.format("%.2f", px) } ?: ""),
                    color = resultColor,
                    fontSize = 12.sp
                )
            }

            call.lastBrokerReconciledAt?.let {
                Text(
                    "Broker truth checked " + formatIst(it) +
                        (call.brokerOrderStatus?.let { status -> " • " + status } ?: ""),
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }

    orderReview?.let { review ->
        OrderReviewDialog(
            review = review,
            onDismiss = { orderReview = null }
        )
    }
}

@Composable
private fun OrderReviewDialog(
    review: OrderReviewResult,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (review.ready) "Broker-ready order" else "Order blocked",
                color = if (review.ready) Positive else Warning
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SystemLine("Symbol", review.tradingSymbol ?: "—", TextPrimary)
                SystemLine("Side", review.transactionType ?: "—", TextPrimary)
                SystemLine("Product", review.product ?: "—", TextPrimary)
                SystemLine("Quantity", review.quantity.toString(), TextPrimary)
                SystemLine(
                    "Reference price",
                    review.estimatedPrice?.let { "₹" + String.format("%.2f", it) } ?: "—",
                    TextPrimary
                )
                SystemLine(
                    "Notional",
                    review.estimatedNotional?.let { "₹" + String.format("%,.2f", it) } ?: "—",
                    TextPrimary
                )
                SystemLine(
                    "Required margin",
                    review.requiredMargin?.let { "₹" + String.format("%,.2f", it) } ?: "—",
                    TextPrimary
                )
                SystemLine(
                    "Available",
                    review.availableBalance?.let { "₹" + String.format("%,.2f", it) } ?: "—",
                    TextPrimary
                )
                if (review.blockers.isNotEmpty()) {
                    Text(
                        review.blockers.joinToString(" • ") { it.replace("_", " ") },
                        color = Warning,
                        fontSize = 11.sp
                    )
                }
                Text(
                    "This screen is a live Groww margin/cash preflight. IPO Sentinel does not submit the securities order.",
                    color = TextSecondary,
                    fontSize = 10.sp
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(review.brokerReadyText()))
                    }
                ) {
                    Text("Copy")
                }
                if (review.ready) {
                    val context = LocalContext.current
                    TextButton(onClick = { openGrowwApp(context) }) {
                        Text("Open Groww")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun RowScope.PriceMetric(label: String, value: Double?) {
    Column {
        Text(label, color = TextSecondary, fontSize = 9.sp)
        Text(
            value?.let { "₹" + String.format("%.2f", it) } ?: "—",
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
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
    val verified = plan?.allKnownCandidates.orEmpty().filter {
        it.nseListingConfirmed && it.symbolResolved && it.symbol != null &&
            it.growwSymbol?.uppercase() == "NSE-" + it.symbol.uppercase()
    }
    val active = verified.filter { (it.tradingDayNumber ?: 0) in 1..30 }
    val upcoming = plan?.weekCandidates.orEmpty()
    val pendingIdentity = plan?.allKnownCandidates.orEmpty()
        .filterNot { candidate ->
            candidate.nseListingConfirmed && candidate.symbolResolved && candidate.symbol != null &&
                candidate.growwSymbol?.uppercase() == "NSE-" + candidate.symbol.uppercase()
        }
        .distinctBy { it.candidateId }
        .sortedWith(compareBy<ResearchCandidate> { it.listingDate ?: "9999-99-99" }.thenBy { it.companyName })

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            ProductHeader(
                eyebrow = "RESEARCH DESK",
                title = "IPO universe",
                status = "Discovery is separate from calls • exact symbols only"
            )
        }

        item {
            ResearchStatusLine(state)
            HorizontalDivider(color = Line)
        }

        item {
            SectionHeader(
                title = "Next listings",
                count = upcoming.size,
                action = "Refresh",
                onAction = { scope.launch { onRefresh() } }
            )
        }
        if (upcoming.isEmpty()) {
            item { InlineEmpty("No next-listing candidates in the current research snapshot.") }
        } else {
            items(upcoming, key = { "up-" + it.candidateId }) { candidate ->
                CandidateRow(candidate, plan?.generatedAt)
                HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 18.dp))
            }
        }

        item { SectionHeader("D1–D30 verified", active.size) }
        if (active.isEmpty()) {
            item { InlineEmpty("No exact NSE/Groww identities currently in the D1–D30 window.") }
        } else {
            items(active, key = { "d-" + it.candidateId }) { candidate ->
                CandidateRow(candidate, plan?.generatedAt)
                HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 18.dp))
            }
        }

        item { SectionHeader("Identity pending", pendingIdentity.size) }
        if (pendingIdentity.isEmpty()) {
            item { InlineEmpty("No unresolved IPO identities in the current research universe.") }
        } else {
            items(pendingIdentity.take(40), key = { "pending-" + it.candidateId }) { candidate ->
                CandidateRow(candidate, plan?.generatedAt)
                HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 18.dp))
            }
        }

        item { SectionHeader("Source health", state.researchSources.size) }
        items(state.researchSources, key = { it.name }) { source ->
            SourceHealthRow(source)
            HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 18.dp))
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ResearchStatusLine(state: AppState) {
    val plan = state.researchPlan
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusPill(
            text = plan?.researchHealth ?: "NOT READY",
            color = if (plan?.researchHealth == "OK") Positive else Warning
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Snapshot " + formatIst(plan?.generatedAt) +
                " • " + (plan?.candidateCount ?: 0) + " known • " +
                (plan?.growwResolvedCount ?: 0) + " exact Groww identities",
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CandidateRow(candidate: ResearchCandidate, generatedAt: String?) {
    val exact = candidate.nseListingConfirmed &&
        candidate.symbolResolved &&
        candidate.symbol != null &&
        candidate.growwSymbol?.uppercase() == "NSE-" + candidate.symbol.uppercase()

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    candidate.symbol ?: candidate.companyName,
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    candidate.companyName,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusPill(
                text = if (exact) "EXACT" else candidate.lifecycleState.replace("_", " "),
                color = if (exact) Positive else Warning
            )
        }

        Text(
            listOfNotNull(
                candidate.listingDate?.let { "Listing " + it },
                candidate.tradingDayNumber?.let { "D" + it },
                if (candidate.isSme) "SME" else "MAINBOARD",
                candidate.subscriptionMultiple?.let { String.format("%.1f× subscribed", it) }
            ).joinToString("  •  "),
            color = TextSecondary,
            fontSize = 11.sp
        )

        Text(
            if (exact) {
                "NSE " + candidate.symbol + "  •  Groww " + candidate.growwSymbol +
                    (candidate.isin?.let { "  •  " + it } ?: "")
            } else {
                candidate.resolutionStatus.replace("_", " ")
            },
            color = if (exact) Info else Warning,
            fontSize = 11.sp
        )

        candidate.issuePriceText?.let {
            Text("Issue price " + it, color = TextSecondary, fontSize = 11.sp)
        }

        Text(
            "Research snapshot " + formatIst(generatedAt) + " • not a call until a strategy fires",
            color = TextSecondary,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun SourceHealthRow(source: ResearchSourceStatus) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(source.name.replace("_", " "), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("Last success " + formatIst(source.lastSuccessAt), color = TextSecondary, fontSize = 10.sp)
            source.error?.let { Text(it, color = Warning, fontSize = 10.sp) }
        }
        StatusPill(source.status, if (source.status == "FRESH") Positive else Warning)
    }
}

@Composable
private fun StrategiesScreen(
    modifier: Modifier,
    state: AppState,
    onReplay: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()
    val families = state.strategySummary.topFive.ifEmpty { state.strategySummary.families.take(5) }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            ProductHeader(
                eyebrow = "STRATEGY LAB",
                title = "Five composite playbooks",
                status = "Ranked by shadow replay • no made-up win rates"
            )
        }

        item { ReplaySummaryStrip(state) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    state.strategySummary.rankingNote,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { scope.launch { onReplay() } },
                    enabled = !state.isReplaying
                ) {
                    Text(if (state.isReplaying) "Replaying…" else "Run replay")
                }
            }
        }

        items(families, key = { it.familyId }) { family ->
            StrategyRow(family)
            HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 18.dp))
        }

        state.replaySummary?.let { replay ->
            item { SectionHeader("Missed opportunities", replay.missedMoves.size) }
            if (replay.missedMoves.isEmpty()) {
                item { InlineEmpty("No ≥4% missed upside sessions recorded in the last replay.") }
            } else {
                items(replay.missedMoves.take(8), key = { it.nseSymbol + it.tradeDate }) { miss ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row {
                            Text(miss.nseSymbol, color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(
                                "+" + String.format("%.1f", miss.maxUpsideBps / 100.0) + "%",
                                color = Positive,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            miss.tradeDate + " • blocked by " + miss.blockers.joinToString(", "),
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 18.dp))
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ReplaySummaryStrip(state: AppState) {
    val replay = state.replaySummary
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        if (state.isReplaying) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
            ReplayMetric("SESSIONS", replay?.evaluatedSessions?.toString() ?: "—")
            ReplayMetric("SIGNALS", replay?.emittedSignals?.toString() ?: "—")
            ReplayMetric("MISSED", replay?.missedMoves?.size?.toString() ?: "—")
        }
        Text(
            "Last full replay " + formatIst(replay?.generatedAt),
            color = TextSecondary,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun RowScope.ReplayMetric(label: String, value: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, color = TextSecondary, fontSize = 9.sp)
        Text(value, color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StrategyRow(family: StrategyFamilyStats) {
    val statusColor = when (family.status) {
        "CHAMPION" -> Positive
        "CHALLENGER" -> Info
        else -> Warning
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(family.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(family.phase.replace("_", " "), color = TextSecondary, fontSize = 10.sp)
            }
            StatusPill(family.status, statusColor)
        }

        Text(family.description, color = TextSecondary, fontSize = 11.sp)

        CompositeStrategyCatalog.definition(family.familyId)?.let { definition ->
            Text(
                definition.ingredients.joinToString("  •  "),
                color = Info,
                fontSize = 10.sp
            )
        }

        if (family.trades == 0) {
            Text(
                "No replay evidence yet. This strategy is not classified as working.",
                color = Warning,
                fontSize = 11.sp
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatText("TRADES", family.trades.toString())
                StatText("WIN", String.format("%.1f%%", family.winRatePct))
                StatText("EXP", String.format("%.0f bps", family.expectancyBps))
                StatText("PF", String.format("%.2f", family.profitFactor))
                StatText("MAX DD", String.format("%.0f", family.maxDrawdownBps))
            }
        }
    }
}

@Composable
private fun RowScope.StatText(label: String, value: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, color = TextSecondary, fontSize = 8.sp)
        Text(value, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SystemScreen(
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
    var budgetDraft by rememberSaveable {
        mutableStateOf(state.tradeReviewSettings.budgetRupees.toFloat())
    }

    LaunchedEffect(state.tradeReviewSettings.budgetRupees) {
        budgetDraft = state.tradeReviewSettings.budgetRupees.toFloat()
    }

    LaunchedEffect(state.connectionStatus?.expectedStaticIp, state.connectionStatus?.staticIpConfirmed) {
        state.connectionStatus?.expectedStaticIp?.let { if (staticIp.isBlank()) staticIp = it }
        if (state.connectionStatus?.staticIpConfirmed == true) whitelist = true
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ProductHeader(
            eyebrow = "SYSTEM",
            title = "Data & recovery",
            status = "Connections, source health, broker truth and device resilience"
        )

        FlatSection("Connectivity") {
            SystemLine("Groww", if (state.growwConnectionReady) "READY" else "NEEDS ATTENTION", if (state.growwConnectionReady) Positive else Warning)
            SystemLine("Groww token", "AUTO REFRESH", if (state.connectionStatus?.growwConfigured == true) Positive else Warning)
            Text(
                "TOTP credentials stay encrypted on-device. Access tokens are regenerated automatically after a 30-minute cache window and retried immediately after broker 401/403 responses.",
                color = TextSecondary,
                fontSize = 10.sp
            )
            SystemLine("NSE research", state.researchPlan?.researchHealth ?: "NOT READY", if (state.researchPlan?.researchHealth == "OK") Positive else Warning)
            SystemLine(
                "Broker truth",
                if (state.brokerTruth?.error == null && state.brokerTruth != null) "SYNCED" else "NOT SYNCED",
                if (state.brokerTruth?.error == null && state.brokerTruth != null) Positive else Warning
            )
            SystemLine("Live order submission", "LOCKED OFF", Negative)
        }

        FlatSection("Groww live-trading readiness") {
            val readiness = state.liveTradingReadiness
            SystemLine(
                "Broker environment",
                when {
                    readiness == null -> "NOT CHECKED"
                    readiness.brokerReady -> "READY"
                    else -> "BLOCKED"
                },
                when {
                    readiness?.brokerReady == true -> Positive
                    readiness == null -> TextSecondary
                    else -> Warning
                }
            )
            ValidationLine("Groww API / TOTP", readiness?.growwAuthOk == true)
            ValidationLine("Static IP matches", readiness?.staticIpMatches == true)
            ValidationLine("Static IP confirmed", readiness?.staticIpConfirmed == true)
            ValidationLine("NSE trading enabled", readiness?.nseEnabled == true)
            ValidationLine("CASH segment active", readiness?.cashSegmentEnabled == true)
            ValidationLine("DDPI enabled", readiness?.ddpiEnabled == true)
            readiness?.blockers?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    "Blockers: " + it.joinToString(", "),
                    color = Warning,
                    fontSize = 10.sp
                )
            }
            OutlinedButton(
                onClick = { scope.launch { repository.refreshLiveTradingReadiness() } },
                enabled = !state.isRefreshing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isRefreshing) "Checking…" else "Refresh broker readiness")
            }
            Text(
                "This readiness check uses Groww's authenticated user profile plus the app's current static-IP validation. It does not submit an order.",
                color = TextSecondary,
                fontSize = 10.sp
            )
        }

        FlatSection("Trade review") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Live Review Mode", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (state.tradeReviewSettings.liveReviewMode) {
                            "Confidence tap preflights and opens Groww for final confirmation"
                        } else {
                            "Confidence tap stays inside IPO Sentinel review"
                        },
                        color = TextSecondary,
                        fontSize = 9.sp
                    )
                }
                Switch(
                    checked = state.tradeReviewSettings.liveReviewMode,
                    onCheckedChange = { repository.saveLiveReviewMode(it) }
                )
            }
            SystemLine(
                "Execution mode",
                if (state.tradeReviewSettings.liveReviewMode) "GROWW HANDOFF" else "MANUAL BROKER CONFIRMATION",
                Info
            )
            SystemLine(
                "Per-order budget",
                "₹" + String.format("%,.0f", budgetDraft),
                TextPrimary
            )
            Slider(
                value = budgetDraft,
                onValueChange = { raw ->
                    budgetDraft = (kotlin.math.round(raw / 1000f) * 1000f)
                        .coerceIn(
                            TradeReviewSettings.MIN_BUDGET_RUPEES.toFloat(),
                            TradeReviewSettings.MAX_BUDGET_RUPEES.toFloat()
                        )
                },
                onValueChangeFinished = {
                    repository.saveTradeBudget(budgetDraft.toInt())
                },
                valueRange = TradeReviewSettings.MIN_BUDGET_RUPEES.toFloat()..
                    TradeReviewSettings.MAX_BUDGET_RUPEES.toFloat(),
                steps = 98
            )
            Text(
                "Live-call confidence opens a broker preflight using this budget. BUY previews use CASH/CNC; SELL previews use CASH/MIS. Groww available balance and required margin are checked before a review can be marked ready.",
                color = TextSecondary,
                fontSize = 11.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto buy", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("Live order submission unavailable in this build", color = TextSecondary, fontSize = 9.sp)
                }
                Switch(checked = false, onCheckedChange = null, enabled = false)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto sell", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("Live order submission unavailable in this build", color = TextSecondary, fontSize = 9.sp)
                }
                Switch(checked = false, onCheckedChange = null, enabled = false)
            }
            Text(
                "The app does not call Groww order-create, modify or cancel endpoints. It produces a broker-ready intent for explicit confirmation instead.",
                color = TextSecondary,
                fontSize = 10.sp
            )
        }

        FlatSection("Recovery on Vivo / Funtouch") {
            SystemLine("Notification access", if (listenerEnabled) "ENABLED" else "OFF", if (listenerEnabled) Positive else Warning)
            Text(
                "The listener is only a wake signal. Calls and replay evidence are durable; listener reconnect, boot, app open, WorkManager and broker truth restore state after process death.",
                color = TextSecondary,
                fontSize = 11.sp
            )
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open notification access") }
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open battery optimization") }
            TextButton(
                onClick = {
                    listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                    RecoveryScheduler.ensureScheduled(context)
                    ResearchLearningScheduler.ensureScheduled(context)
                    RecoveryCoordinator.request(context, "SYSTEM_RECOVERY_CHECK", force = true)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Refresh recovery status") }
            OutlinedButton(
                onClick = {
                    NotificationHelper.showOrderEvent(
                        context,
                        OrderLifecycleEvent(
                            id = System.currentTimeMillis(),
                            timestamp = Instant.now().toString(),
                            eventType = "STRATEGY_REVIEW",
                            symbol = "TEST",
                            message = "IPO Sentinel test — no order was placed"
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Send test notification") }
        }

        FlatSection("Groww credentials") {
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
                Text("This IP is whitelisted in Groww", color = TextPrimary)
            }
            Text(
                if (state.connectionStatus?.growwConfigured == true) {
                    "Credentials are already encrypted with Android Keystore. Enter values only to replace them."
                } else {
                    "Credentials will be encrypted locally with Android Keystore."
                },
                color = TextSecondary,
                fontSize = 11.sp
            )
            Button(
                onClick = {
                    if (token.isBlank() || secret.isBlank() || staticIp.isBlank()) {
                        message = "Enter token, TOTP secret and static IP before replacing Groww settings."
                    } else {
                        scope.launch {
                            val result = repository.saveGrowwSettings(token, secret, staticIp, whitelist)
                            if (result.ok) {
                                token = ""
                                secret = ""
                                repository.validateGrowwAndStaticIp()
                                repository.refreshAll()
                                message = "Groww settings saved and shared state refreshed."
                            } else {
                                message = result.error ?: "Unable to save Groww settings."
                            }
                        }
                    }
                },
                enabled = !state.isRefreshing,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save & validate") }
        }

        state.validation?.let {
            FlatSection("Validation") {
                ValidationLine("Groww TOTP authentication", it.growwAuthOk)
                ValidationLine("Static public IP matches", it.staticIpMatches)
                ValidationLine("Whitelist confirmed", it.staticIpConfirmed)
                ValidationLine("Android Keystore", it.secretStoreReady)
            }
        }

        FlatSection("Audit") {
            Text(
                "Weekly export includes calls, signal evidence, strategy replay, source health and broker recovery. Credentials and access tokens are excluded.",
                color = TextSecondary,
                fontSize = 11.sp
            )
            Button(
                onClick = {
                    exportBusy = true
                    scope.launch {
                        runCatching { AppAudit.shareExport(context, AppAudit.exportWeekly(context)) }
                            .onFailure { message = "Export failed: " + (it.message ?: "unknown") }
                        exportBusy = false
                    }
                },
                enabled = !exportBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (exportBusy) "Preparing…" else "Export weekly verification") }
        }

        message?.let {
            Text(
                it,
                color = if (it.contains("saved", ignoreCase = true)) Positive else Warning,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                fontSize = 12.sp
            )
        }
        state.lastError?.let {
            Text(it, color = Negative, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp), fontSize = 11.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProductHeader(eyebrow: String, title: String, status: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(eyebrow, color = Positive, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        Text(title, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(status, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(count.toString(), color = TextSecondary, fontSize = 12.sp)
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun InlineEmpty(text: String) {
    Text(
        text,
        color = TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)
    )
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text(
            text,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun FlatSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        content()
    }
    HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 18.dp))
}

@Composable
private fun SystemLine(label: String, value: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ValidationLine(label: String, ok: Boolean) {
    SystemLine(label, if (ok) "PASS" else "FAIL", if (ok) Positive else Negative)
}

private fun formatIst(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return runCatching {
        DateTimeFormatter.ofPattern("dd MMM • HH:mm:ss")
            .format(Instant.parse(iso).atZone(ZoneId.of("Asia/Kolkata")))
    }.getOrElse { iso.take(19) }
}

private fun formatRupees(value: Double): String =
    (if (value >= 0.0) "+" else "-") + "₹" + String.format("%,.2f", kotlin.math.abs(value))

private fun pnlColor(value: Double): Color = when {
    value > 0.0 -> Positive
    value < 0.0 -> Negative
    else -> TextPrimary
}

private fun openGrowwApp(context: android.content.Context) {
    val launch = context.packageManager.getLaunchIntentForPackage("com.nextbillion.groww")
    if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    } else {
        val playStore = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=com.nextbillion.groww")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(playStore)
    }
}

private fun signedPct(value: Double): String =
    (if (value >= 0.0) "+" else "") + String.format("%.2f%%", value)
