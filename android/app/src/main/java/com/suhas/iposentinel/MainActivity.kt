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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import java.util.Locale

private val Bg = Color(0xFF090C10)
private val Panel = Color(0xFF101419)
private val Panel2 = Color(0xFF151A20)
private val Divider = Color(0xFF222A33)
private val TextPrimary = Color(0xFFF3F6F8)
private val TextSecondary = Color(0xFF8C98A5)
private val Green = Color(0xFF14C99A)
private val Red = Color(0xFFFF6472)
private val Amber = Color(0xFFF4B942)
private val Blue = Color(0xFF6C94FF)
private val Purple = Color(0xFFA985FF)

private enum class AppScreen { CALLS, RESEARCH, STRATEGIES, SYSTEM }
private enum class CallView { LIVE, CLOSED }

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppAudit.log(this, "NOTIFICATION_PERMISSION_RESULT", JSONObject().put("granted", granted))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.createChannels(this)
        RecoveryScheduler.ensureScheduled(this)
        OffMarketResearchScheduler.ensureScheduled(this)
        AppAudit.log(this, "APP_STARTED", JSONObject().put("version", BuildConfig.VERSION_NAME))

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
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
            delay(15L * 60L * 1000L)
            repository.refreshResearch(force = true)
            repository.refreshBrokerTruth(force = false)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Green, secondary = Blue, background = Bg, surface = Panel,
            surfaceVariant = Panel2, error = Red
        )
    ) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Panel, tonalElevation = 0.dp) {
                    BottomNavItem(screen, AppScreen.CALLS, Icons.Outlined.Bolt, "Calls") { screen = AppScreen.CALLS }
                    BottomNavItem(screen, AppScreen.RESEARCH, Icons.Outlined.TravelExplore, "Research") { screen = AppScreen.RESEARCH }
                    BottomNavItem(screen, AppScreen.STRATEGIES, Icons.Outlined.Analytics, "Strategies") { screen = AppScreen.STRATEGIES }
                    BottomNavItem(screen, AppScreen.SYSTEM, Icons.Outlined.Settings, "System") { screen = AppScreen.SYSTEM }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(Bg)) {
                when (screen) {
                    AppScreen.CALLS -> CallsScreen(state, repository)
                    AppScreen.RESEARCH -> ResearchScreen(state, repository)
                    AppScreen.STRATEGIES -> StrategiesScreen(state, repository)
                    AppScreen.SYSTEM -> SystemScreen(state, repository)
                }
            }
        }
    }
}

@Composable
private fun RowScope.BottomNavItem(
    current: AppScreen,
    target: AppScreen,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = current == target,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp)) },
        label = { Text(label, fontSize = 10.sp) }
    )
}

@Composable
private fun CallsScreen(state: AppState, repository: AppStateRepository) {
    val scope = rememberCoroutineScope()
    var view by rememberSaveable { mutableStateOf(CallView.LIVE) }
    val calls = if (view == CallView.LIVE) state.liveCalls else state.closedCalls

    Column(Modifier.fillMaxSize()) {
        AppHeader(
            title = "Calls",
            subtitle = marketStatusLine(state),
            busy = state.isRefreshing,
            onRefresh = {
                scope.launch {
                    repository.refreshResearch(force = true)
                    repository.refreshBrokerTruth(force = true)
                }
            }
        )
        CallSegmentedControl(view, state.liveCalls.size, state.closedCalls.size) { view = it }
        if (view == CallView.LIVE) LiveSummaryStrip(state) else ClosedSummaryStrip(state)
        HorizontalDivider(color = Divider)

        if (calls.isEmpty()) {
            EmptyCalls(view)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(calls, key = { it.callId }) { call ->
                    CallListRow(call, closed = view == CallView.CLOSED)
                    HorizontalDivider(color = Divider, modifier = Modifier.padding(start = 18.dp))
                }
            }
        }
    }
}

@Composable
private fun AppHeader(title: String, subtitle: String, busy: Boolean, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp)
        }
        TextButton(onClick = onRefresh, enabled = !busy) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text("Refresh", color = Green)
        }
    }
}

@Composable
private fun CallSegmentedControl(
    selected: CallView,
    liveCount: Int,
    closedCount: Int,
    onSelect: (CallView) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)
            .background(Panel2, RoundedCornerShape(10.dp)).padding(3.dp)
    ) {
        Segment("Live", liveCount, selected == CallView.LIVE, Modifier.weight(1f)) { onSelect(CallView.LIVE) }
        Segment("Closed", closedCount, selected == CallView.CLOSED, Modifier.weight(1f)) { onSelect(CallView.CLOSED) }
    }
}

@Composable
private fun Segment(label: String, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clickable(onClick = onClick)
            .background(if (selected) Divider else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (selected) TextPrimary else TextSecondary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Text(count.toString(), color = if (selected) Green else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LiveSummaryStrip(state: AppState) {
    val replay = state.shadowReplay
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        InlineMetric("QUALIFIED", state.liveCalls.size.toString(), Green)
        InlineMetric("REPLAY TRADES", (replay?.replayTrades ?: 0).toString(), Blue)
        InlineMetric("CANDLES", (replay?.candlesStored ?: 0).toString(), Purple)
        InlineMetric("DATA", if (state.usingCachedResearch) "DEGRADED" else "CURRENT", if (state.usingCachedResearch) Amber else Green)
    }
}

@Composable
private fun ClosedSummaryStrip(state: AppState) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        InlineMetric("CLOSED", state.closedCalls.size.toString(), TextSecondary)
        InlineMetric(
            "BROKER SYNC",
            if (state.brokerTruth?.error == null && state.brokerTruth != null) "OK" else "CHECK",
            if (state.brokerTruth?.error == null && state.brokerTruth != null) Green else Amber
        )
        InlineMetric("EXECUTION", "OFF", Red)
    }
}

@Composable
private fun InlineMetric(label: String, value: String, color: Color) {
    Column {
        Text(label, color = TextSecondary, fontSize = 9.sp, letterSpacing = 0.7.sp)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyCalls(view: CallView) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (view == CallView.LIVE) "No qualified live calls" else "No closed calls yet",
            color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold
        )
        Text(
            if (view == CallView.LIVE)
                "Research names stay out of Calls until the exact NSE/Groww identity and a real candle/volume ensemble both qualify."
            else
                "Completed or expired strategy calls remain here with recommendation and close timestamps.",
            color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun CallListRow(call: RecommendationCall, closed: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        call.symbol?.removePrefix("NSE-") ?: "SYMBOL PENDING",
                        color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusPill(call.board, if (call.board == "SME") Purple else Blue)
                }
                Text(call.companyName, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (closed) formatDateTime(call.closedAt ?: call.lastUpdatedAt) else formatTime(call.recommendedAt),
                    color = if (closed) TextSecondary else Green,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
                Text(if (closed) "closed" else "recommended", color = TextSecondary, fontSize = 9.sp)
            }
        }

        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(call.strategyName ?: "Legacy research record", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            call.setupScore?.let {
                Spacer(Modifier.width(8.dp))
                StatusPill(String.format(Locale.ENGLISH, "%.0f", it), scoreColor(it))
            }
        }

        if (!closed && call.referencePrice != null) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                PriceMetric("REF", call.referencePrice)
                PriceMetric("VWAP", call.vwap)
                TextMetric("RVOL", call.relativeVolume?.let { String.format(Locale.ENGLISH, "%.2fx", it) } ?: "—")
            }
        }

        if (call.reasonCodes.isNotEmpty()) {
            Row(
                Modifier.padding(top = 9.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                call.reasonCodes.take(6).forEach { EvidenceChip(it.replace("_", " ")) }
            }
        }

        if (closed) {
            Text((call.closeReason ?: "CLOSED").replace("_", " "), color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            Text("Recommended " + formatDateTime(call.recommendedAt), color = TextSecondary, fontSize = 10.sp)
        } else if (call.signalAt != null) {
            Text(
                "Signal " + call.signalAt + "  •  source " + formatDateTime(call.sourceGeneratedAt),
                color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp)
            )
        }

        if ((call.brokerPositionQuantity ?: 0) != 0) {
            Text(
                "Broker position " + call.brokerPositionQuantity + " @ " + (call.brokerAveragePrice?.let { formatPrice(it) } ?: "—"),
                color = Green, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp)
            )
        }
    }
}

@Composable private fun PriceMetric(label: String, value: Double?) = TextMetric(label, value?.let(::formatPrice) ?: "—")

@Composable
private fun TextMetric(label: String, value: String) {
    Column {
        Text(label, color = TextSecondary, fontSize = 9.sp)
        Text(value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EvidenceChip(text: String) {
    Text(
        text, color = TextSecondary, fontSize = 9.sp, maxLines = 1,
        modifier = Modifier.background(Panel2, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 4.dp)
    )
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

@Composable
private fun ResearchScreen(state: AppState, repository: AppStateRepository) {
    val scope = rememberCoroutineScope()
    val plan = state.researchPlan
    val resolved = plan?.allKnownCandidates.orEmpty().filter { it.symbolResolved }
        .sortedWith(compareBy<ResearchCandidate> { it.tradingDayNumber ?: 999 }.thenBy { it.companyName })
    val pending = plan?.allKnownCandidates.orEmpty().filter { !it.symbolResolved }.sortedBy { it.companyName }

    Column(Modifier.fillMaxSize()) {
        AppHeader(
            "Research", "Identity, market evidence and IPO coverage", state.isRefreshing,
            onRefresh = { scope.launch { repository.refreshResearch(force = true) } }
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                ResearchHealthBar(state)
                SectionHeader("Verified instruments", resolved.size.toString())
            }
            items(resolved, key = { "resolved:" + it.candidateId }) {
                ResearchRow(it, true, plan?.generatedAt)
                HorizontalDivider(color = Divider, modifier = Modifier.padding(start = 18.dp))
            }
            item { SectionHeader("Identity pending", pending.size.toString()) }
            items(pending, key = { "pending:" + it.candidateId }) {
                ResearchRow(it, false, plan?.generatedAt)
                HorizontalDivider(color = Divider, modifier = Modifier.padding(start = 18.dp))
            }
            item {
                SectionHeader("Source health", state.researchSources.size.toString())
                state.researchSources.forEach { SourceHealthRow(it) }
            }
        }
    }
}

@Composable
private fun ResearchHealthBar(state: AppState) {
    val plan = state.researchPlan
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        InlineMetric("KNOWN", (plan?.candidateCount ?: 0).toString(), TextPrimary)
        InlineMetric("NSE VERIFIED", (plan?.nseIdentityConfirmedCount ?: 0).toString(), Green)
        InlineMetric("GROWW EXACT", (plan?.growwResolvedCount ?: 0).toString(), Blue)
        InlineMetric("HEALTH", plan?.researchHealth ?: "—", if (plan?.researchHealth == "OK") Green else Amber)
    }
}

@Composable
private fun SectionHeader(title: String, count: String) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title.uppercase(Locale.ENGLISH), color = TextSecondary, fontSize = 10.sp, letterSpacing = 0.9.sp)
        Spacer(Modifier.weight(1f))
        Text(count, color = TextSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun ResearchRow(candidate: ResearchCandidate, verified: Boolean, generatedAt: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(candidate.symbol ?: "NSE SYMBOL PENDING", color = if (verified) TextPrimary else Amber, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(candidate.companyName, color = TextSecondary, fontSize = 12.sp)
            }
            StatusPill(if (verified) "EXACT MATCH" else candidate.lifecycleState.replace("_", " "), if (verified) Green else Amber)
        }
        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            TextMetric("NSE", candidate.symbol ?: "pending")
            TextMetric("GROWW", candidate.growwSymbol ?: "pending")
            TextMetric("ISIN", candidate.isin ?: "—")
        }
        Text(
            "Listing " + (candidate.listingDate ?: "—") + (candidate.tradingDayNumber?.let { "  •  D" + it } ?: "") +
                "  •  " + if (candidate.isSme) "SME" else "MAINBOARD",
            color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp)
        )
        Text(
            if (verified) "Eligible for strategy replay; it becomes a Call only after market evidence qualifies."
            else "Research only. The app will not guess a broker symbol.",
            color = if (verified) Blue else TextSecondary, fontSize = 10.sp
        )
        generatedAt?.let { Text("Research snapshot " + formatDateTime(it), color = TextSecondary, fontSize = 9.sp) }
    }
}

@Composable
private fun SourceHealthRow(source: ResearchSourceStatus) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(7.dp).background(
                if (source.status == "FRESH") Green else if (source.status == "CACHED") Amber else Red,
                RoundedCornerShape(50)
            )
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(source.name, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
                source.error ?: ("Last success " + formatDateTime(source.lastSuccessAt)),
                color = TextSecondary, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Text(source.status, color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StrategiesScreen(state: AppState, repository: AppStateRepository) {
    val scope = rememberCoroutineScope()
    val replay = state.shadowReplay
    val definitions = StrategyLibrary.definitions.associateBy { it.id }

    Column(Modifier.fillMaxSize()) {
        AppHeader(
            "Strategies", "No-lookahead shadow replay • five ensemble models", state.isRefreshing,
            onRefresh = { scope.launch { repository.runShadowReplay() } }
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                StrategyLabSummary(state)
                Text(
                    "A strategy earns RESEARCH → REPLAYING → CHALLENGER → CHAMPION from stored replay evidence. No strategy is presented as proven before the numbers support it.",
                    color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
                SectionHeader("Ranked ensembles", state.strategySummary.families.size.toString())
            }
            items(state.strategySummary.families, key = { it.familyId }) { family ->
                StrategyRow(family, definitions[family.familyId])
                HorizontalDivider(color = Divider, modifier = Modifier.padding(start = 18.dp))
            }
            item {
                if (!replay?.errors.isNullOrEmpty()) {
                    SectionHeader("Replay diagnostics", replay?.errors?.size.toString())
                    replay?.errors?.take(8)?.forEach {
                        Text(it, color = Amber, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategyLabSummary(state: AppState) {
    val replay = state.shadowReplay
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        InlineMetric("CANDLES", (replay?.candlesStored ?: 0).toString(), Purple)
        InlineMetric("REPLAY TRADES", (replay?.replayTrades ?: 0).toString(), Blue)
        InlineMetric("CHAMPIONS", state.strategySummary.champions.toString(), Green)
        InlineMetric("CHALLENGERS", state.strategySummary.challengers.toString(), Amber)
        InlineMetric("LAST RUN", formatTime(replay?.generatedAt), TextSecondary)
    }
}

@Composable
private fun StrategyRow(family: StrategyFamilyStats, definition: StrategyDefinition?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(family.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(family.phase, color = TextSecondary, fontSize = 10.sp)
            }
            StatusPill(family.status, strategyStatusColor(family.status))
        }
        Row(
            Modifier.padding(top = 9.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            TextMetric("TRADES", family.trades.toString())
            TextMetric("WIN", if (family.trades > 0) String.format(Locale.ENGLISH, "%.1f%%", family.winRatePct) else "—")
            TextMetric("EXPECT", if (family.trades > 0) String.format(Locale.ENGLISH, "%.0f bps", family.expectancyBps) else "—")
            TextMetric("PF", if (family.trades > 0) String.format(Locale.ENGLISH, "%.2f", family.profitFactor) else "—")
            TextMetric("MAX DD", if (family.trades > 0) String.format(Locale.ENGLISH, "%.0f bps", family.maxDrawdownBps) else "—")
        }
        Text(definition?.thesis ?: family.description, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 9.dp))
        definition?.evidence?.let { evidence ->
            Row(
                Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                evidence.forEach { EvidenceChip(it.uppercase(Locale.ENGLISH)) }
            }
        }
    }
}

@Composable
private fun SystemScreen(state: AppState, repository: AppStateRepository) {
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

    Column(Modifier.fillMaxSize()) {
        AppHeader(
            "System", "Connection, recovery, data health and audit", state.isRefreshing,
            onRefresh = {
                scope.launch {
                    repository.validateGrowwAndStaticIp()
                    repository.refreshBrokerTruth(force = true)
                }
            }
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                SystemStatusSection(state, listenerEnabled)
                SectionHeader("Recovery", "")
                SystemTextRow(
                    "Broker truth",
                    state.brokerTruth?.let {
                        if (it.error == null)
                            "Synced " + formatDateTime(it.fetchedAt) + " • " + it.orders.size + " orders • " + it.positions.size + " positions"
                        else "Attention • " + it.error
                    } ?: "Not synced"
                )
                SystemTextRow("Off-market research", "08:35 IST pre-market refresh • 18:45 IST post-market shadow replay")
                SystemTextRow("Execution", "LOCKED OFF — strategy calls are research recommendations, not automatic orders", Red)

                SectionHeader("Notification recovery", "")
                SystemTextRow("Notification access", if (listenerEnabled) "Enabled" else "Not enabled", if (listenerEnabled) Green else Amber)
                Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Open access") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                            RecoveryScheduler.ensureScheduled(context)
                            OffMarketResearchScheduler.ensureScheduled(context)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Re-check") }
                }

                SectionHeader("Groww API", "")
                SystemTextRow(
                    "Stored credentials",
                    if (state.connectionStatus?.growwConfigured == true) "Encrypted in Android Keystore" else "Not configured",
                    if (state.connectionStatus?.growwConfigured == true) Green else Amber
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, label = { Text("TOTP token / API key") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
                )
                OutlinedTextField(
                    value = secret, onValueChange = { secret = it }, label = { Text("TOTP secret") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
                )
                OutlinedTextField(
                    value = staticIp, onValueChange = { staticIp = it.trim() }, label = { Text("Whitelisted static public IP") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
                )
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = whitelist, onCheckedChange = { whitelist = it })
                    Text("This IP is whitelisted in Groww", color = TextPrimary, fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        if (token.isBlank() || secret.isBlank() || staticIp.isBlank()) {
                            message = "Enter token, secret and static IP only when replacing/saving credentials."
                        } else {
                            scope.launch {
                                val result = repository.saveGrowwSettings(token, secret, staticIp, whitelist)
                                if (result.ok) {
                                    token = ""
                                    secret = ""
                                    repository.validateGrowwAndStaticIp()
                                    repository.runShadowReplay()
                                    message = "Groww settings saved and strategy lab refreshed."
                                } else message = result.error ?: "Unable to save settings."
                            }
                        }
                    },
                    enabled = !state.isRefreshing,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)
                ) { Text("Save & validate") }

                SectionHeader("Verification", "")
                state.validation?.let {
                    SystemCheckRow("Groww TOTP auth", it.growwAuthOk)
                    SystemCheckRow("Static IP match", it.staticIpMatches)
                    SystemCheckRow("Whitelist confirmed", it.staticIpConfirmed)
                    SystemCheckRow("Android Keystore", it.secretStoreReady)
                }
                SystemCheckRow("Exact-symbol research pipeline", (state.researchPlan?.growwResolvedCount ?: 0) > 0)

                SectionHeader("Audit", "")
                Text(
                    "Weekly export includes call evidence, exact symbols, broker recovery, source health and shadow-replay status. Secrets and access tokens are excluded.",
                    color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)
                ) { Text(if (exportBusy) "Preparing…" else "Export weekly verification") }

                message?.let {
                    Text(it, color = if (it.contains("saved", true)) Green else Amber, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
                }
                state.lastError?.let {
                    Text(it, color = Amber, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun SystemStatusSection(state: AppState, listenerEnabled: Boolean) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        InlineMetric("GROWW", if (state.growwConnectionReady) "READY" else "CHECK", if (state.growwConnectionReady) Green else Amber)
        InlineMetric("NSE", state.researchPlan?.researchHealth ?: "—", if (state.researchPlan?.researchHealth == "OK") Green else Amber)
        InlineMetric("LISTENER", if (listenerEnabled) "ON" else "OFF", if (listenerEnabled) Green else Amber)
        InlineMetric("AUTO TRADE", "OFF", Red)
    }
}

@Composable
private fun SystemTextRow(label: String, value: String, valueColor: Color = TextSecondary) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.width(112.dp))
        Text(value, color = valueColor, fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SystemCheckRow(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "●" else "○", color = if (ok) Green else Amber, fontSize = 12.sp)
        Spacer(Modifier.width(10.dp))
        Text(label, color = TextPrimary, fontSize = 12.sp)
    }
}

private fun marketStatusLine(state: AppState): String {
    val replay = state.shadowReplay
    return state.liveCalls.size.toString() + " live • " + state.closedCalls.size + " closed" +
        (replay?.let { " • replay " + it.replayTrades } ?: "") +
        if (state.usingCachedResearch) " • source degraded" else ""
}

private fun scoreColor(score: Double): Color = when {
    score >= 90 -> Green
    score >= 80 -> Blue
    score >= 75 -> Amber
    else -> TextSecondary
}

private fun strategyStatusColor(status: String): Color = when (status) {
    "CHAMPION" -> Green
    "CHALLENGER" -> Blue
    "REPLAYING" -> Amber
    else -> TextSecondary
}

private fun formatPrice(value: Double): String = "₹" + String.format(Locale.ENGLISH, "%.2f", value)

private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM HH:mm")

private fun formatTime(value: String?): String {
    if (value.isNullOrBlank()) return "—"
    return runCatching { Instant.parse(value).atZone(IST).format(timeFormatter) }
        .getOrElse { value.takeLast(8) }
}

private fun formatDateTime(value: String?): String {
    if (value.isNullOrBlank()) return "—"
    return runCatching { Instant.parse(value).atZone(IST).format(dateTimeFormatter) }
        .getOrElse { value.replace("T", " ").take(16) }
}
