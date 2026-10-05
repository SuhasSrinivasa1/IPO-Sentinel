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
            file.appendText(record.toString() + "
")
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
            zip.putNextEntry(ZipEntry("app-audit.jsonl"))
            zip.write(localAudit(context, 7).toByteArray())
            zip.closeEntry()

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
                .put("broker_truth_fetched_at", snapshot.brokerTruth?.fetchedAt)
                .put("broker_truth_error", snapshot.brokerTruth?.error)
                .put("strategy_total", strategy.totalStrategyFamilies)
                .put("strategy_tested", strategy.testedFamilies)
                .put("strategy_champions", strategy.champions)
                .put("notification_permission", NotificationHelper.notificationsAllowed(context))
                .put("note", "Groww credentials and access tokens are never included. Live execution is locked off.")

            zip.putNextEntry(ZipEntry("weekly-summary.json"))
            zip.write(summary.toString(2).toByteArray())
            zip.closeEntry()

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
            val calls = JSONArray()
            snapshot.calls.forEach { call ->
                calls.put(
                    JSONObject()
                        .put("call_id", call.callId)
                        .put("candidate_id", call.candidateId)
                        .put("symbol", call.symbol)
                        .put("company_name", call.companyName)
                        .put("state", call.state)
                        .put("action", call.action)
                        .put("recommended_at", call.recommendedAt)
                        .put("last_updated_at", call.lastUpdatedAt)
                        .put("closed_at", call.closedAt)
                        .put("close_reason", call.closeReason)
                        .put("broker_order_id", call.brokerOrderId)
                        .put("broker_order_status", call.brokerOrderStatus)
                        .put("broker_position_quantity", call.brokerPositionQuantity)
                        .put("last_broker_reconciled_at", call.lastBrokerReconciledAt)
                )
            }
            zip.putNextEntry(ZipEntry("calls-ledger.json"))
            zip.write(JSONObject().put("calls", calls).toString(2).toByteArray())
            zip.closeEntry()

            val broker = snapshot.brokerTruth
            zip.putNextEntry(ZipEntry("broker-truth-status.json"))
            zip.write(
                JSONObject()
                    .put("fetched_at", broker?.fetchedAt)
                    .put("trigger", broker?.trigger)
                    .put("order_count", broker?.orders?.size ?: 0)
                    .put("position_count", broker?.positions?.size ?: 0)
                    .put("error", broker?.error)
                    .toString(2)
                    .toByteArray()
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("research-source-health.json"))
            zip.write(JSONObject().put("sources", sources).toString(2).toByteArray())
            zip.closeEntry()
        }

        log(context, "WEEKLY_AUDIT_EXPORTED", JSONObject().put("file_name", zipFile.name))
        zipFile
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
