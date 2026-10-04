from __future__ import annotations

import json
import math
import os
import re
from datetime import date, datetime, time as clock_time, timedelta, timezone
from pathlib import Path
from threading import RLock
from typing import Any

from .audit import audit_log
from .connection_api import store as groww_settings_store
from .domain import Action, LiveFeatures
from .groww_session import GrowwCredentials, GrowwSession
from .live_pnl import live_ledger
from .execution_service import GrowwExecutionService
from .live_state import live_state_store
from .order_events import order_events
from .research_service import DailyResearchService
from .scheduler import IST
from .strategy import ListingDecisionEngine
from .strategy_dashboard import StrategyEvidenceStore, strategy_summary


def _float(value: Any, default: float = 0.0) -> float:
    try:
        parsed = float(value)
        return parsed if math.isfinite(parsed) else default
    except (TypeError, ValueError):
        return default


def _quote_source(quote: Any) -> dict[str, Any]:
    if not isinstance(quote, dict):
        return {}
    payload = quote.get("payload")
    return payload if isinstance(payload, dict) else quote


def _candles(payload: Any) -> list[list[Any]]:
    if not isinstance(payload, dict):
        return []
    source = payload.get("payload") if isinstance(payload.get("payload"), dict) else payload
    values = source.get("candles", []) if isinstance(source, dict) else []
    return [row for row in values if isinstance(row, list) and len(row) >= 6]


def _issue_price(text: Any) -> float | None:
    values = []
    for token in re.findall(r"\d+(?:\.\d+)?", str(text or "")):
        try:
            value = float(token)
            if value > 0:
                values.append(value)
        except ValueError:
            pass
    return max(values) if values else None


class SignalStore:
    def __init__(self) -> None:
        self._lock = RLock()
        self._path = Path(os.getenv("IPO_SENTINEL_SIGNAL_FILE", ".runtime/signals.json"))

    def _read(self) -> dict[str, Any]:
        if not self._path.exists():
            return {"signals": {}, "history": []}
        try:
            raw = json.loads(self._path.read_text(encoding="utf-8"))
            return raw if isinstance(raw, dict) else {"signals": {}, "history": []}
        except Exception:
            return {"signals": {}, "history": []}

    def _write(self, payload: dict[str, Any]) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self._path.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload, separators=(",", ":"), ensure_ascii=False), encoding="utf-8")
        tmp.replace(self._path)

    def active(self) -> list[dict[str, Any]]:
        with self._lock:
            state = self._read()
        rows = [
            dict(value)
            for value in (state.get("signals") or {}).values()
            if isinstance(value, dict) and value.get("status") == "READY"
        ]
        return sorted(rows, key=lambda row: (_float(row.get("confidence")), _float(row.get("score"))), reverse=True)

    def get(self, symbol: str) -> dict[str, Any] | None:
        ticker = symbol.upper().strip()
        with self._lock:
            state = self._read()
        row = (state.get("signals") or {}).get(ticker)
        return dict(row) if isinstance(row, dict) else None

    def action_done(self, symbol: str, action: str) -> bool:
        row = self.get(symbol) or {}
        return action.upper().strip() in {
            str(value).upper().strip()
            for value in (row.get("managed_actions") or [])
        }

    def mark_action(self, symbol: str, action: str, order_id: str | None = None) -> None:
        ticker = symbol.upper().strip()
        normalized = action.upper().strip()
        with self._lock:
            state = self._read()
            row = (state.get("signals") or {}).get(ticker)
            if not isinstance(row, dict):
                return
            actions = [str(value) for value in (row.get("managed_actions") or [])]
            if normalized not in {value.upper() for value in actions}:
                actions.append(normalized)
            row["managed_actions"] = actions
            if order_id:
                row.setdefault("managed_order_ids", []).append(order_id)
            row["updated_at"] = datetime.now(timezone.utc).isoformat()
            state["signals"][ticker] = row
            self._write(state)

    def all_history(self, limit: int = 200) -> list[dict[str, Any]]:
        with self._lock:
            state = self._read()
        rows = [dict(item) for item in state.get("history", []) if isinstance(item, dict)]
        return list(reversed(rows[-max(1, min(1000, int(limit))):]))

    def upsert_ready(self, signal: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        symbol = str(signal.get("symbol") or "").upper().strip()
        if not symbol:
            raise ValueError("signal symbol is required")
        now = datetime.now(timezone.utc).isoformat()
        with self._lock:
            state = self._read()
            signals = state.setdefault("signals", {})
            previous = signals.get(symbol) if isinstance(signals.get(symbol), dict) else None
            changed = (
                previous is None
                or previous.get("status") != "READY"
                or previous.get("direction") != signal.get("direction")
                or abs(_float(previous.get("entry_price")) - _float(signal.get("entry_price"))) / max(1.0, _float(signal.get("entry_price"))) >= 0.005
            )
            row = dict(signal)
            row["symbol"] = symbol
            row["status"] = "READY"
            row["updated_at"] = now
            row["created_at"] = (previous or {}).get("created_at") or now
            signals[symbol] = row
            if changed:
                state.setdefault("history", []).append(dict(row))
                state["history"] = state["history"][-1000:]
            self._write(state)
        return row, changed

    def invalidate(self, symbol: str, reason: str) -> bool:
        ticker = symbol.upper().strip()
        with self._lock:
            state = self._read()
            current = (state.get("signals") or {}).get(ticker)
            if not isinstance(current, dict) or current.get("status") != "READY":
                return False
            current = dict(current)
            current["status"] = "INVALIDATED"
            current["invalidated_reason"] = reason
            current["updated_at"] = datetime.now(timezone.utc).isoformat()
            state["signals"][ticker] = current
            state.setdefault("history", []).append(dict(current))
            state["history"] = state["history"][-1000:]
            self._write(state)
        return True


class LearningJournal:
    def __init__(self) -> None:
        self._lock = RLock()
        self._path = Path(os.getenv("IPO_SENTINEL_LEARNING_FILE", ".runtime/research-learning.json"))

    def _read(self) -> dict[str, Any]:
        if not self._path.exists():
            return {"daily": [], "weekly": []}
        try:
            raw = json.loads(self._path.read_text(encoding="utf-8"))
            return raw if isinstance(raw, dict) else {"daily": [], "weekly": []}
        except Exception:
            return {"daily": [], "weekly": []}

    def append(self, kind: str, record: dict[str, Any]) -> None:
        key = "weekly" if kind == "weekly" else "daily"
        with self._lock:
            state = self._read()
            state.setdefault(key, []).append(record)
            state[key] = state[key][-180:]
            self._path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self._path.with_suffix(".tmp")
            tmp.write_text(json.dumps(state, separators=(",", ":"), ensure_ascii=False), encoding="utf-8")
            tmp.replace(self._path)

    def latest(self, limit: int = 14) -> dict[str, list[dict[str, Any]]]:
        with self._lock:
            state = self._read()
        return {
            "daily": list(reversed([x for x in state.get("daily", []) if isinstance(x, dict)][-limit:])),
            "weekly": list(reversed([x for x in state.get("weekly", []) if isinstance(x, dict)][-limit:])),
        }


signal_store = SignalStore()
learning_journal = LearningJournal()


class LiveOpportunityScanner:
    """Server-side minute scanner for exact NSE/Groww-resolved IPO candidates."""

    def __init__(self, research: DailyResearchService) -> None:
        self.research = research
        self.engine = ListingDecisionEngine()

    def _session(self) -> GrowwSession | None:
        saved = groww_settings_store.load()
        if not saved:
            return None
        try:
            return GrowwSession.from_credentials(
                GrowwCredentials(totp_token=saved.totp_token, totp_secret=saved.totp_secret)
            )
        except Exception:
            return None

    @staticmethod
    def _depth_totals(source: dict[str, Any]) -> tuple[float, float]:
        buy = _float(source.get("total_buy_quantity") or source.get("buy_quantity"))
        sell = _float(source.get("total_sell_quantity") or source.get("sell_quantity"))
        depth = source.get("depth") or source.get("market_depth")
        if isinstance(depth, dict):
            if buy <= 0:
                buy = sum(_float(row.get("quantity")) for row in (depth.get("buy") or []) if isinstance(row, dict))
            if sell <= 0:
                sell = sum(_float(row.get("quantity")) for row in (depth.get("sell") or []) if isinstance(row, dict))
        return buy, sell

    def _rvol(self, session: GrowwSession, symbol: str, listing_date: date, today: date, current_volume: float) -> float:
        try:
            start = max(listing_date, today - timedelta(days=40))
            payload = session.historical(
                symbol=symbol,
                start_time=f"{start.isoformat()} 00:00:00",
                end_time=f"{today.isoformat()} 23:59:59",
                interval=session.api.CANDLE_INTERVAL_DAY,
            )
            rows = _candles(payload)
            prior = [_float(row[5]) for row in rows[:-1] if _float(row[5]) > 0]
            if not prior:
                return 1.0
            average = sum(prior[-20:]) / len(prior[-20:])
            return max(0.0, current_volume / average) if average > 0 else 1.0
        except Exception:
            return 1.0

    def _first_five(self, session: GrowwSession, symbol: str, today: date, listing_day: bool, now: datetime) -> tuple[float | None, float | None]:
        start_clock = "10:00:00" if listing_day else "09:15:00"
        try:
            payload = session.historical(
                symbol=symbol,
                start_time=f"{today.isoformat()} {start_clock}",
                end_time=now.strftime("%Y-%m-%d %H:%M:%S"),
                interval=session.api.CANDLE_INTERVAL_MIN_1,
            )
            rows = _candles(payload)[:5]
            if not rows:
                return None, None
            return max(_float(row[2]) for row in rows), min(_float(row[3]) for row in rows)
        except Exception:
            return None, None

    def scan(self, *, trigger: str = "minute_scan") -> dict[str, Any]:
        now = datetime.now(IST)
        if now.weekday() >= 5 or now.time() < clock_time(9, 15) or now.time() >= clock_time(15, 30):
            return {"trigger": trigger, "scanned": 0, "signals": 0, "status": "MARKET_CLOSED"}
        if not self.research.calendar.source_ready or not self.research.calendar.is_trading_day(now.date()):
            return {"trigger": trigger, "scanned": 0, "signals": 0, "status": "OFFICIAL_CALENDAR_CLOSED"}

        plan = self.research.store.load() or {}
        universe = [
            item
            for item in plan.get("all_known_candidates", [])
            if isinstance(item, dict)
            and item.get("symbol_resolved")
            and item.get("nse_listing_confirmed")
            and 1 <= int(item.get("trading_day_number") or (1 if item.get("listing_date") == now.date().isoformat() else 0)) <= 30
        ]
        session = self._session()
        if session is None:
            return {"trigger": trigger, "scanned": 0, "signals": 0, "status": "GROWW_NOT_AUTHENTICATED"}

        ready = 0
        scanned = 0
        for candidate in universe[:40]:
            symbol = str(candidate.get("symbol") or "").upper().strip()
            if not symbol:
                continue
            try:
                listing_date = date.fromisoformat(str(candidate.get("listing_date")))
            except (TypeError, ValueError):
                continue
            if listing_date == now.date() and now.time() < clock_time(10, 5):
                continue

            try:
                quote = session.api.get_quote(
                    exchange=session.api.EXCHANGE_NSE,
                    segment=session.api.SEGMENT_CASH,
                    trading_symbol=symbol,
                )
                source = _quote_source(quote)
                ltp = _float(source.get("last_price"))
                avg_price = _float(source.get("average_price"))
                bid = _float(source.get("bid_price"))
                ask = _float(source.get("offer_price"))
                if min(ltp, avg_price, bid, ask) <= 0:
                    signal_store.invalidate(symbol, "LIVE_PRICE_OR_BOOK_UNAVAILABLE")
                    continue

                owned_qty = live_ledger.position_quantity(symbol)
                existing_plan = signal_store.get(symbol)
                if owned_qty != 0 and existing_plan:
                    if live_state_store.load().enabled:
                        stop = _float(existing_plan.get("stop_loss"))
                        target1 = _float(existing_plan.get("target1"))
                        target2 = _float(existing_plan.get("target2"))
                        auto_action: str | None = None
                        if owned_qty > 0:
                            if stop > 0 and ltp <= stop:
                                auto_action = "SELL_ALL"
                            elif target2 > 0 and ltp >= target2:
                                auto_action = "SELL_ALL"
                            elif target1 > 0 and ltp >= target1 and not signal_store.action_done(symbol, "AUTO_T1"):
                                auto_action = "SELL_50"
                        else:
                            if stop > 0 and ltp >= stop:
                                auto_action = "COVER_ALL"
                            elif target2 > 0 and ltp <= target2:
                                auto_action = "COVER_ALL"
                            elif target1 > 0 and ltp <= target1 and not signal_store.action_done(symbol, "AUTO_T1"):
                                auto_action = "COVER_50"

                        management_key = (
                            "AUTO_STOP_OR_T2" if auto_action in {"SELL_ALL", "COVER_ALL"} else
                            "AUTO_T1" if auto_action else None
                        )
                        if auto_action and management_key and not signal_store.action_done(symbol, management_key):
                            try:
                                response = GrowwExecutionService().manual_submit(
                                    symbol=symbol,
                                    action=auto_action,
                                    manual=False,
                                )
                                signal_store.mark_action(
                                    symbol,
                                    management_key,
                                    str(response.get("groww_order_id") or "") or None,
                                )
                                order_events.publish(
                                    "STRATEGY_REVIEW",
                                    symbol=symbol,
                                    message=f"Auto position management submitted {auto_action}",
                                    metadata={"action": auto_action, "ltp": ltp},
                                )
                            except Exception as exc:
                                audit_log.append(
                                    "AUTO_POSITION_MANAGEMENT_BLOCKED",
                                    severity="WARN",
                                    symbol=symbol,
                                    action=auto_action,
                                    reason=str(exc),
                                )
                    # A filled position is managed as a position, not discarded because the
                    # original entry signal later weakens.
                    continue

                spread_bps = (ask - bid) / ((ask + bid) / 2.0) * 10_000.0
                buy_qty, sell_qty = self._depth_totals(source)
                current_volume = _float(source.get("volume"))
                rvol = self._rvol(session, symbol, listing_date, now.date(), current_volume)
                first_high, first_low = self._first_five(session, symbol, now.date(), listing_date == now.date(), now)
                upper = _float(source.get("upper_circuit_limit"))
                lower = _float(source.get("lower_circuit_limit"))
                circuit_distance = None
                if upper > ltp > 0 and lower > 0:
                    circuit_distance = min((upper / ltp - 1.0) * 100.0, (ltp / lower - 1.0) * 100.0)

                issue = _issue_price(candidate.get("issue_price_text"))
                ohlc = source.get("ohlc") if isinstance(source.get("ohlc"), dict) else {}
                listing_price = _float(ohlc.get("open")) or None
                features = LiveFeatures(
                    symbol=symbol,
                    at=now,
                    ltp=ltp,
                    vwap=avg_price,
                    rvol=rvol,
                    spread_bps=spread_bps,
                    buy_qty=buy_qty,
                    sell_qty=sell_qty,
                    first_5m_high=first_high,
                    first_5m_low=first_low,
                    listing_price=listing_price,
                    issue_price=issue,
                    circuit_distance_pct=circuit_distance,
                    shortable=bool(candidate.get("sell_allowed")),
                    data_fresh=True,
                )
                decision = self.engine.decide(features, live_state_store.load().budget_rupees)
                scanned += 1
                if decision.action not in {Action.PROBE_LONG, Action.BUILD_LONG, Action.PROBE_SHORT, Action.BUILD_SHORT}:
                    if signal_store.invalidate(symbol, "EDGE_NO_LONGER_CONFIRMED"):
                        order_events.publish(
                            "SIGNAL_INVALIDATED",
                            symbol=symbol,
                            message="Previously valid opportunity no longer passes live signal gates",
                            metadata={"score": decision.score, "reason_codes": list(decision.reason_codes)},
                        )
                    continue

                direction = "LONG" if decision.action in {Action.PROBE_LONG, Action.BUILD_LONG} else "SHORT"
                entry = ask if direction == "LONG" else bid
                risk_pct = 0.025 if candidate.get("is_sme") else 0.015
                if direction == "LONG":
                    stop = min(entry * (1.0 - risk_pct), avg_price * 0.995)
                    risk = max(entry * 0.005, entry - stop)
                    target1 = entry + risk
                    target2 = entry + 2.0 * risk
                else:
                    stop = max(entry * (1.0 + risk_pct), avg_price * 1.005)
                    risk = max(entry * 0.005, stop - entry)
                    target1 = entry - risk
                    target2 = entry - 2.0 * risk

                lot = max(1, int(candidate.get("groww_lot_size") or 1))
                budget = live_state_store.load().budget_rupees
                quantity = int(budget // entry // lot) * lot
                if quantity <= 0:
                    continue
                gross_at_t2 = quantity * abs(target2 - entry)
                estimated_round_trip_cost = quantity * entry * 0.0025
                expected_net = gross_at_t2 - estimated_round_trip_cost
                minimum_edge = budget * 0.005
                if expected_net < minimum_edge:
                    signal_store.invalidate(symbol, "EXPECTED_NET_EDGE_BELOW_0_5_PERCENT_OF_BUDGET")
                    continue

                signal, changed = signal_store.upsert_ready(
                    {
                        "company_name": candidate.get("company_name") or symbol,
                        "direction": direction,
                        "action": str(decision.action),
                        "score": decision.score,
                        "confidence": decision.confidence,
                        "entry_price": round(entry, 4),
                        "stop_loss": round(stop, 4),
                        "target1": round(target1, 4),
                        "target2": round(target2, 4),
                        "quantity": quantity,
                        "budget_rupees": budget,
                        "expected_net_at_target2": round(expected_net, 2),
                        "minimum_net_edge_rupees": round(minimum_edge, 2),
                        "spread_bps": round(spread_bps, 2),
                        "relative_volume": round(rvol, 3),
                        "buy_quantity": round(buy_qty, 2),
                        "sell_quantity": round(sell_qty, 2),
                        "trading_day_number": candidate.get("trading_day_number") or 1,
                        "is_sme": bool(candidate.get("is_sme")),
                        "listing_date": candidate.get("listing_date"),
                        "reason_codes": list(decision.reason_codes),
                    }
                )
                ready += 1
                if changed:
                    order_events.publish(
                        "SIGNAL_READY",
                        symbol=symbol,
                        side="BUY" if direction == "LONG" else "SELL",
                        quantity=quantity,
                        price=round(entry, 4),
                        message=f"{direction} signal • T1 ₹{target1:.2f} • T2 ₹{target2:.2f} • SL ₹{stop:.2f}",
                        metadata=signal,
                    )

                state = live_state_store.load()
                daily = live_ledger.daily_performance(
                    target_rupees=5_000,
                    capital_base=state.budget_rupees,
                )
                if (
                    state.enabled
                    and not daily.get("target_achieved")
                    and live_ledger.position_quantity(symbol) == 0
                    and not signal_store.action_done(symbol, "AUTO_ENTRY")
                ):
                    try:
                        response = GrowwExecutionService().manual_submit(
                            symbol=symbol,
                            action="BUY" if direction == "LONG" else "SHORT",
                            manual=False,
                        )
                        signal_store.mark_action(
                            symbol,
                            "AUTO_ENTRY",
                            str(response.get("groww_order_id") or "") or None,
                        )
                    except Exception as exc:
                        audit_log.append(
                            "AUTO_SIGNAL_ORDER_BLOCKED",
                            severity="WARN",
                            symbol=symbol,
                            direction=direction,
                            reason=str(exc),
                        )
            except Exception as exc:
                audit_log.append(
                    "MARKET_SCAN_CANDIDATE_FAILED",
                    severity="WARN",
                    symbol=symbol,
                    error=exc.__class__.__name__,
                )

        return {"trigger": trigger, "scanned": scanned, "signals": ready, "status": "OK"}


class ResearchIntelligenceService:
    def __init__(self, research: DailyResearchService) -> None:
        self.research = research
        self.strategy_store = StrategyEvidenceStore()

    @staticmethod
    def _research_rank(candidate: dict[str, Any], next_trading_day: str | None) -> dict[str, Any]:
        score = 40.0
        reasons: list[str] = []
        if candidate.get("nse_listing_confirmed"):
            score += 15
            reasons.append("OFFICIAL_NSE_IDENTITY")
        if candidate.get("symbol_resolved"):
            score += 15
            reasons.append("EXACT_GROWW_INSTRUMENT")
        sub = _float(candidate.get("subscription_multiple"), -1.0)
        if sub >= 10:
            score += 14
            reasons.append("VERY_STRONG_SUBSCRIPTION")
        elif sub >= 3:
            score += 9
            reasons.append("STRONG_SUBSCRIPTION")
        elif sub >= 1:
            score += 4
            reasons.append("FULLY_SUBSCRIBED")
        elif 0 <= sub < 1:
            score -= 6
            reasons.append("WEAK_SUBSCRIPTION")
        if candidate.get("is_sme"):
            score -= 3
            reasons.append("SME_LIQUIDITY_RISK")
        else:
            score += 3
            reasons.append("MAINBOARD")
        if next_trading_day and candidate.get("listing_date") == next_trading_day:
            score += 8
            reasons.append("NEXT_LISTING_DAY")
        day = int(candidate.get("trading_day_number") or 0)
        if 1 <= day <= 5:
            score += 5
            reasons.append("EARLY_POST_LISTING_WINDOW")
        elif 6 <= day <= 30:
            score += 3
            reasons.append("ACTIVE_D1_D30_WINDOW")
        return {
            "symbol": candidate.get("symbol"),
            "company_name": candidate.get("company_name"),
            "listing_date": candidate.get("listing_date"),
            "trading_day_number": candidate.get("trading_day_number"),
            "board": "SME" if candidate.get("is_sme") else "MAINBOARD",
            "research_score": round(max(0.0, min(100.0, score)), 2),
            "pre_market_bias": "WATCH_LONG" if score >= 68 else "WAIT_LIVE_CONFIRMATION",
            "reasons": reasons,
            "symbol_resolved": bool(candidate.get("symbol_resolved")),
            "resolution_status": candidate.get("resolution_status"),
            "note": "Research ranking only; live direction requires price/volume/order-book confirmation.",
        }

    def dashboard(self) -> dict[str, Any]:
        plan = self.research.store.load() or {}
        state = live_state_store.load()
        daily = live_ledger.daily_performance(target_rupees=5_000, capital_base=state.budget_rupees)
        active_30d = [
            item for item in plan.get("recent_30d_candidates", [])
            if isinstance(item, dict) and 1 <= int(item.get("trading_day_number") or 0) <= 30
        ]
        tomorrow = [item for item in plan.get("next_trading_day_candidates", []) if isinstance(item, dict)]
        ranked_pool = tomorrow + [item for item in active_30d if item not in tomorrow]
        ranked = sorted(
            (self._research_rank(item, plan.get("next_trading_day")) for item in ranked_pool),
            key=lambda row: row["research_score"],
            reverse=True,
        )
        signals = signal_store.active()
        return {
            "generated_at": datetime.now(IST).isoformat(),
            "daily_goal": daily,
            "active_signals": signals,
            "top_three": signals[:3] if signals else ranked[:3],
            "tomorrow_candidates": tomorrow,
            "active_30d_candidates": active_30d,
            "open_positions": live_ledger.summary(capital_base=state.budget_rupees).get("open_positions", []),
            "closed_calls": live_ledger.closed_calls(limit=100),
            "strategy": strategy_summary(self.strategy_store),
            "learning": learning_journal.latest(),
            "coverage": {
                "mainboard": sum(1 for item in active_30d + tomorrow if not item.get("is_sme")),
                "sme": sum(1 for item in active_30d + tomorrow if item.get("is_sme")),
                "rule": "Official NSE identity + exact Groww NSE/CASH instrument required for execution.",
            },
        }

    def review_day(self) -> dict[str, Any]:
        snapshot = self.dashboard()
        daily = snapshot["daily_goal"]
        strategy = snapshot["strategy"]
        weak = [
            row for row in strategy.get("families", [])
            if row.get("trades", 0) >= 10
            and row.get("expectancy_bps", 0) < 0
            and row.get("last_20_net_bps", 0) < 0
        ]
        record = {
            "at": datetime.now(IST).isoformat(),
            "type": "DAILY_REVIEW",
            "closed_calls": daily.get("closed_calls", 0),
            "wins": daily.get("wins", 0),
            "losses": daily.get("losses", 0),
            "realized_net_pnl": daily.get("realized_net_pnl", 0.0),
            "target_achieved": daily.get("target_achieved", False),
            "active_signals": len(snapshot.get("active_signals", [])),
            "active_30d": len(snapshot.get("active_30d_candidates", [])),
            "weak_strategy_families": [row.get("family_id") for row in weak[:5]],
            "learning": "Retain positive replay evidence; investigate weak families across volume/trend/market regimes before changing eligibility.",
        }
        learning_journal.append("daily", record)
        audit_log.append("DAILY_STRATEGY_LEARNING_REVIEW", **record)
        return record

    def review_week(self) -> dict[str, Any]:
        summary = strategy_summary(self.strategy_store)
        champions = [row["family_id"] for row in summary.get("families", []) if row.get("status") == "CHAMPION"]
        failing = [
            row["family_id"]
            for row in summary.get("families", [])
            if row.get("trades", 0) >= 20
            and row.get("expectancy_bps", 0) < 0
            and row.get("last_20_net_bps", 0) < 0
            and row.get("out_of_sample_net_pnl_bps", 0) <= 0
        ]
        record = {
            "at": datetime.now(IST).isoformat(),
            "type": "WEEKLY_STRATEGY_REVALIDATION",
            "retained_champions": champions,
            "demotion_candidates": failing,
            "rule": "Champions are retained; repeatedly negative families are flagged for demotion/rework, never promoted without positive post-cost held-out evidence.",
        }
        learning_journal.append("weekly", record)
        order_events.publish(
            "STRATEGY_REVIEW",
            message=f"Weekly review: {len(champions)} champions retained; {len(failing)} weak families flagged",
            metadata=record,
        )
        audit_log.append("WEEKLY_STRATEGY_REVALIDATION", **record)
        return record
