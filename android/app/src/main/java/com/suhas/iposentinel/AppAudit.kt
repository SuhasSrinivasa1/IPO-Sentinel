package com.suhas.iposentinel

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AppAudit {
    private fun auditDir(context: Context): File = File(context.filesDir, "audit")

    fun log(context: Context, eventType: String, payload: JSONObject = JSONObject()) {
        runCatching {
            val dir = auditDir(context).apply { mkdirs() }
            val day = LocalDate.now(ZoneOffset.UTC).toString()
            val file = File(dir, "$day.jsonl")
            val record = JSONObject()
                .put("timestamp", Instant.now().toString())
                .put("event_type", eventType)
                .put("payload", payload)
            file.appendText(record.toString() + "\n")
        }
    }

    private fun localAudit(context: Context, days: Int): String {
        val dir = auditDir(context)
        if (!dir.exists()) return ""
        val today = LocalDate.now(ZoneOffset.UTC)
        val wanted = (0 until days).map { today.minusDays(it.toLong()).toString() }.toSet()
        return dir.listFiles()
            ?.filter { it.extension == "jsonl" && it.nameWithoutExtension in wanted }
            ?.sortedBy { it.name }
            ?.joinToString("") { it.readText() }
            .orEmpty()
    }

    suspend fun exportWeekly(context: Context): File = withContext(Dispatchers.IO) {
        val snapshot = AppStateRepository.get(context).snapshot()
        val strategy = snapshot.strategySummary
        val plan = snapshot.researchPlan
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeTime = Instant.now().toString().replace(":", "-")
        val zipFile = File(exportDir, "IPO-Sentinel-Weekly-Audit-$safeTime.zip")

        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            putText(zip, "app-audit.jsonl", localAudit(context, 7))

            val summary = JSONObject()
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("exported_at", Instant.now().toString())
                .put("period_days", 7)
                .put("groww_connection_ready", snapshot.growwConnectionReady)
                .put("live_execution_ready", false)
                .put("next_trading_day", plan?.nextTradingDay)
                .put("research_health", plan?.researchHealth ?: "UNKNOWN")
                .put("research_using_cached_data", snapshot.usingCachedResearch)
                .put("candidate_count", plan?.candidateCount ?: 0)
                .put("groww_resolved_count", plan?.growwResolvedCount ?: 0)
                .put("live_call_count", snapshot.liveCalls.size)
                .put("closed_call_count", snapshot.closedCalls.size)
                .put("today_shadow_pnl_rupees", snapshot.todayShadowPnlRupees)
                .put("today_closed_shadow_pnl_rupees", snapshot.todayClosedShadowPnlRupees)
                .put("today_open_shadow_pnl_rupees", snapshot.todayOpenShadowPnlRupees)
                .put("broker_realised_pnl_rupees", snapshot.brokerRealisedPnlRupees)
                .put("shadow_account_capital_rupees", ShadowExecutionPolicy.ACCOUNT_CAPITAL_RUPEES)
                .put("trade_review_budget_rupees", snapshot.tradeReviewSettings.budgetRupees)
                .put("live_review_mode", snapshot.tradeReviewSettings.liveReviewMode)
                .put("trade_review_mode", if (snapshot.tradeReviewSettings.liveReviewMode) "GROWW_HANDOFF" else "MANUAL_BROKER_CONFIRMATION")
                .put("groww_trading_broker_ready", snapshot.liveTradingReadiness?.brokerReady)
                .put("groww_trading_nse_enabled", snapshot.liveTradingReadiness?.nseEnabled)
                .put("groww_trading_cash_segment_enabled", snapshot.liveTradingReadiness?.cashSegmentEnabled)
                .put("groww_trading_ddpi_enabled", snapshot.liveTradingReadiness?.ddpiEnabled)
                .put("groww_trading_readiness_blockers", JSONArray(snapshot.liveTradingReadiness?.blockers.orEmpty()))
                .put("last_signal_scan_at", snapshot.signalScan?.scannedAt)
                .put("last_signal_scan_evaluated", snapshot.signalScan?.evaluatedSymbols ?: 0)
                .put("last_signal_scan_signals", snapshot.signalScan?.signalsFound ?: 0)
                .put("last_replay_at", snapshot.replaySummary?.generatedAt)
                .put("last_replay_sessions", snapshot.replaySummary?.evaluatedSessions ?: 0)
                .put("last_replay_signals", snapshot.replaySummary?.emittedSignals ?: 0)
                .put("last_replay_missed_moves", snapshot.replaySummary?.missedMoves?.size ?: 0)
                .put("broker_truth_fetched_at", snapshot.brokerTruth?.fetchedAt)
                .put("broker_truth_error", snapshot.brokerTruth?.error)
                .put("strategy_total", strategy.totalStrategyFamilies)
                .put("strategy_tested", strategy.testedFamilies)
                .put("strategy_champions", strategy.champions)
                .put("strategy_challengers", strategy.challengers)
                .put("notification_permission", NotificationHelper.notificationsAllowed(context))
                .put("note", "Calls are exact-identity strategy signals. Groww credentials/access tokens are excluded. Order intents use live margin preflight; securities-order submission remains locked off.")
            putText(zip, "weekly-summary.json", summary.toString(2))

            val sources = JSONArray()
            snapshot.researchSources.forEach { source ->
                sources.put(
                    JSONObject()
                        .put("name", source.name)
                        .put("status", source.status)
                        .put("using_cached_data", source.usingCachedData)
                        .put("last_success_at", source.lastSuccessAt)
                        .put("error", source.error)
                )
            }
            putText(zip, "research-source-health.json", JSONObject().put("sources", sources).toString(2))

            val calls = JSONArray()
            snapshot.calls.forEach { call ->
                calls.put(
                    JSONObject()
                        .put("call_id", call.callId)
                        .put("candidate_id", call.candidateId)
                        .put("nse_symbol", call.symbol)
                        .put("groww_symbol", call.growwSymbol)
                        .put("isin", call.isin)
                        .put("company_name", call.companyName)
                        .put("board", call.board)
                        .put("state", call.state)
                        .put("direction", call.direction)
                        .put("strategy_id", call.strategyId)
                        .put("strategy_name", call.strategyName)
                        .put("confirming_strategy_ids", JSONArray(call.confirmingStrategyIds))
                        .put("recommended_at", call.recommendedAt)
                        .put("last_updated_at", call.lastUpdatedAt)
                        .put("signal_score", call.signalScore)
                        .put("entry_price", call.entryPrice)
                        .put("stop_loss", call.stopLoss)
                        .put("target1", call.target1)
                        .put("target2", call.target2)
                        .put("current_price", call.currentPrice)
                        .put("return_pct", call.returnPct)
                        .put("evidence_summary", JSONArray(call.evidenceSummary))
                        .put("candle_pattern", call.candlePattern)
                        .put("volume_ratio", call.volumeRatio)
                        .put("vwap", call.vwap)
                        .put("benchmark_relative_bps", call.benchmarkRelativeBps)
                        .put("hold_policy", call.holdPolicy)
                        .put("max_hold_trading_sessions", call.maxHoldTradingSessions)
                        .put("shadow_quantity", call.shadowQuantity)
                        .put("shadow_entry_value", call.shadowEntryValue)
                        .put("shadow_pnl_rupees", call.shadowPnlRupees)
                        .put("shadow_session_pnl_rupees", call.shadowSessionPnlRupees)
                        .put("shadow_session_pnl_date", call.shadowSessionPnlDate)
                        .put("closed_at", call.closedAt)
                        .put("close_reason", call.closeReason)
                        .put("exit_price", call.exitPrice)
                        .put("broker_order_id", call.brokerOrderId)
                        .put("broker_order_status", call.brokerOrderStatus)
                        .put("broker_position_quantity", call.brokerPositionQuantity)
                        .put("last_broker_reconciled_at", call.lastBrokerReconciledAt)
                )
            }
            putText(zip, "calls-ledger.json", JSONObject().put("calls", calls).toString(2))

            val families = JSONArray()
            strategy.families.forEach { family ->
                families.put(
                    JSONObject()
                        .put("strategy_id", family.familyId)
                        .put("name", family.name)
                        .put("phase", family.phase)
                        .put("status", family.status)
                        .put("trades", family.trades)
                        .put("win_rate_pct", family.winRatePct)
                        .put("expectancy_bps", family.expectancyBps)
                        .put("profit_factor", family.profitFactor)
                        .put("max_drawdown_bps", family.maxDrawdownBps)
                        .put("last20_net_bps", family.last20NetBps)
                        .put("ranking_score", family.rankingScore)
                )
            }
            putText(
                zip,
                "strategy-evidence.json",
                JSONObject()
                    .put("ranking_note", strategy.rankingNote)
                    .put("families", families)
                    .toString(2)
            )

            val replay = snapshot.replaySummary
            val missed = JSONArray()
            replay?.missedMoves.orEmpty().forEach { miss ->
                missed.put(
                    JSONObject()
                        .put("nse_symbol", miss.nseSymbol)
                        .put("trade_date", miss.tradeDate)
                        .put("max_upside_bps", miss.maxUpsideBps)
                        .put("blockers", JSONArray(miss.blockers))
                )
            }
            putText(
                zip,
                "shadow-replay-summary.json",
                JSONObject()
                    .put("generated_at", replay?.generatedAt)
                    .put("evaluated_symbols", replay?.evaluatedSymbols ?: 0)
                    .put("evaluated_sessions", replay?.evaluatedSessions ?: 0)
                    .put("emitted_signals", replay?.emittedSignals ?: 0)
                    .put("missed_moves", missed)
                    .put("errors", JSONArray(replay?.errors.orEmpty()))
                    .toString(2)
            )

            val broker = snapshot.brokerTruth
            putText(
                zip,
                "broker-truth-status.json",
                JSONObject()
                    .put("fetched_at", broker?.fetchedAt)
                    .put("trigger", broker?.trigger)
                    .put("order_count", broker?.orders?.size ?: 0)
                    .put("position_count", broker?.positions?.size ?: 0)
                    .put("error", broker?.error)
                    .toString(2)
            )
        }

        log(context, "WEEKLY_AUDIT_EXPORTED", JSONObject().put("file_name", zipFile.name))
        zipFile
    }

    private fun putText(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
    }

    fun shareExport(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export IPO Sentinel weekly logs"))
    }
}
