from __future__ import annotations

from datetime import datetime, time as clock_time
from typing import Any

from .audit import audit_log
from .connection_api import store as groww_settings_store
from .execution_service import GrowwExecutionService
from .live_pnl import live_ledger
from .scheduler import IST


class OrderPositionMonitor:
    """Reconciles only IPO Sentinel registered orders and marks only app-owned positions."""

    def run(self) -> dict[str, Any]:
        now = datetime.now(IST)
        if now.weekday() >= 5 or now.time() < clock_time(9, 0) or now.time() >= clock_time(15, 45):
            return {"status": "MARKET_CLOSED", "orders": 0, "positions": 0}
        if groww_settings_store.load() is None:
            return {"status": "GROWW_NOT_CONFIGURED", "orders": 0, "positions": 0}

        service = GrowwExecutionService()
        reconciled = 0
        try:
            for row in live_ledger.pending_orders():
                order_id = str(row.get("order_id") or "")
                symbol = str(row.get("symbol") or "")
                side = str(row.get("side") or "")
                quantity = int(row.get("requested_quantity") or 0)
                if not order_id or not symbol or not side or quantity <= 0:
                    continue
                try:
                    service.reconcile_order(
                        groww_order_id=order_id,
                        symbol=symbol,
                        side=side,
                        quantity=quantity,
                        is_exit=bool(row.get("is_exit")),
                    )
                    reconciled += 1
                except Exception as exc:
                    audit_log.append(
                        "ORDER_RECONCILE_FAILED",
                        severity="WARN",
                        order_id=order_id,
                        symbol=symbol,
                        error=exc.__class__.__name__,
                    )

            session = service._session().api
            summary = live_ledger.summary()
            marks: dict[str, float] = {}
            for position in summary.get("open_positions", []):
                symbol = str(position.get("symbol") or "")
                if not symbol:
                    continue
                try:
                    quote = session.get_quote(
                        exchange=session.EXCHANGE_NSE,
                        segment=session.SEGMENT_CASH,
                        trading_symbol=symbol,
                    )
                    price = service._extract_ltp(quote)
                    if price > 0:
                        marks[symbol] = price
                except Exception:
                    continue
            if marks:
                live_ledger.update_marks(marks)
            return {"status": "OK", "orders": reconciled, "positions": len(marks)}
        except Exception as exc:
            audit_log.append("ORDER_MONITOR_FAILED", severity="WARN", error=exc.__class__.__name__)
            return {"status": "ERROR", "orders": reconciled, "positions": 0}
