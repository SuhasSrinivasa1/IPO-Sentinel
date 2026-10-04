from pathlib import Path

from app.audit import AuditLog
from app.live_state import LiveStateStore
from app.live_pnl import AttributableLiveLedger
from app.order_events import OrderEventStore


def test_live_state_defaults_off(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("IPO_SENTINEL_LIVE_STATE_FILE", str(tmp_path / "state.json"))
    store = LiveStateStore()
    assert store.load().enabled is False


def test_live_state_budget_is_bounded(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("IPO_SENTINEL_LIVE_STATE_FILE", str(tmp_path / "state.json"))
    store = LiveStateStore()
    assert store.save(False, 999_999).budget_rupees == 100_000
    assert store.save(False, 1).budget_rupees == 10_000


def test_order_events_are_monotonic(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("IPO_SENTINEL_ORDER_EVENT_FILE", str(tmp_path / "events.jsonl"))
    store = OrderEventStore()
    first = store.publish("ORDER_PLACING", symbol="ABC", side="BUY", quantity=10)
    second = store.publish("ORDER_FILLED", symbol="ABC", side="BUY", quantity=10, price=100)
    assert second.id == first.id + 1
    assert [e.event_type for e in store.after(first.id)] == ["ORDER_FILLED"]


def test_audit_export(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("IPO_SENTINEL_AUDIT_DIR", str(tmp_path / "audit"))
    log = AuditLog()
    log.append("TEST_EVENT", value=1)
    exported = log.export(7)
    assert "TEST_EVENT" in exported
    assert "value" in exported


def test_live_budget_is_locked_while_armed(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("IPO_SENTINEL_LIVE_STATE_FILE", str(tmp_path / "state.json"))
    store = LiveStateStore()
    store.save(True, 50_000)
    try:
        store.save(True, 60_000)
        raised = False
    except ValueError as exc:
        raised = True
        assert "locked" in str(exc).lower()
    assert raised is True
    assert store.load().budget_rupees == 50_000


def test_ipo_sentinel_daily_pnl_uses_only_registered_reconciled_fills(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("IPO_SENTINEL_LIVE_LEDGER_FILE", str(tmp_path / "ledger.json"))
    ledger = AttributableLiveLedger()
    ledger.register_order(
        order_id="BUY1",
        symbol="IPOABC",
        side="BUY",
        reference_id="IPO-ABC-0001",
        requested_quantity=10,
    )
    ledger.record_cumulative_fill(
        order_id="BUY1",
        symbol="IPOABC",
        side="BUY",
        cumulative_quantity=10,
        average_price=100.0,
        estimated_charge_bps_per_fill=0.0,
    )
    ledger.register_order(
        order_id="SELL1",
        symbol="IPOABC",
        side="SELL",
        reference_id="IPO-ABC-0002",
        requested_quantity=10,
        is_exit=True,
    )
    ledger.record_cumulative_fill(
        order_id="SELL1",
        symbol="IPOABC",
        side="SELL",
        cumulative_quantity=10,
        average_price=110.0,
        estimated_charge_bps_per_fill=0.0,
    )

    calls = ledger.closed_calls()
    assert len(calls) == 1
    assert calls[0]["outcome"] == "WIN"
    assert calls[0]["net_pnl"] == 100.0

    daily = ledger.daily_performance(target_rupees=5_000, capital_base=100_000)
    assert daily["realized_net_pnl"] == 100.0
    assert daily["source"] == "IPO_SENTINEL_RECONCILED_FILLS_ONLY"
    assert daily["remaining_rupees"] == 4_900.0


def test_pending_order_metadata_survives_partial_fill(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("IPO_SENTINEL_LIVE_LEDGER_FILE", str(tmp_path / "ledger.json"))
    ledger = AttributableLiveLedger()
    ledger.register_order(
        order_id="BUY1",
        symbol="IPOABC",
        side="BUY",
        reference_id="IPO-ABC-0001",
        requested_quantity=10,
    )
    ledger.record_cumulative_fill(
        order_id="BUY1",
        symbol="IPOABC",
        side="BUY",
        cumulative_quantity=4,
        average_price=100.0,
        estimated_charge_bps_per_fill=0.0,
    )
    pending = ledger.pending_orders()
    assert len(pending) == 1
    assert pending[0]["requested_quantity"] == 10
    assert pending[0]["cumulative_quantity"] == 4
