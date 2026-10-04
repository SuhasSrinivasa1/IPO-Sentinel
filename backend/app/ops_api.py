from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import PlainTextResponse
from pydantic import BaseModel, Field

from .audit import audit_log
from .connection_api import validate_connection
from .connection_settings import require_device_key
from .live_state import live_state_store
from .execution_service import GrowwExecutionService
from .live_pnl import live_ledger
from .order_events import order_events

router = APIRouter(tags=["operations"])


class ManualCardOrderRequest(BaseModel):
    symbol: str = Field(min_length=1, max_length=32)
    action: str = Field(min_length=3, max_length=16)
    fraction: float = Field(default=1.0, gt=0.0, le=1.0)


class LiveStateRequest(BaseModel):
    enabled: bool
    budget_rupees: int = Field(default=100_000, ge=10_000, le=100_000)


@router.get("/live/state", dependencies=[Depends(require_device_key)])
def get_live_state() -> dict:
    state = live_state_store.load()
    return {
        "enabled": state.enabled,
        "budget_rupees": state.budget_rupees,
        "updated_at": state.updated_at,
        "event_id": order_events.latest_id(),
    }


@router.post("/live/state", dependencies=[Depends(require_device_key)])
async def set_live_state(payload: LiveStateRequest) -> dict:
    if payload.enabled:
        readiness = await validate_connection()
        if not readiness.get("live_execution_ready", False):
            audit_log.append(
                "LIVE_ENABLE_REJECTED",
                severity="WARN",
                groww_auth_ok=readiness.get("groww_auth_ok", False),
                static_ip_matches=readiness.get("static_ip_matches", False),
                static_ip_confirmed=readiness.get("static_ip_confirmed", False),
            )
            raise HTTPException(status_code=409, detail="Groww and static-IP validation must pass before live execution")

    try:
        state = live_state_store.save(payload.enabled, payload.budget_rupees)
    except ValueError as exc:
        audit_log.append(
            "LIVE_STATE_REJECTED",
            severity="WARN",
            enabled=payload.enabled,
            requested_budget_rupees=payload.budget_rupees,
            reason=str(exc),
        )
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    event_type = "LIVE_ENABLED" if state.enabled else "LIVE_DISABLED"
    event = order_events.publish(
        event_type,
        message=(
            f"Live auto-trading enabled with budget ₹{state.budget_rupees}"
            if state.enabled
            else "Live auto-trading disabled"
        ),
        metadata={"budget_rupees": state.budget_rupees},
    )
    audit_log.append(
        "LIVE_STATE_CHANGED",
        enabled=state.enabled,
        budget_rupees=state.budget_rupees,
        order_event_id=event.id,
    )
    return {
        "enabled": state.enabled,
        "budget_rupees": state.budget_rupees,
        "updated_at": state.updated_at,
        "event_id": event.id,
    }


@router.get("/events/orders", dependencies=[Depends(require_device_key)])
def get_order_events(after_id: int = 0, limit: int = 100) -> dict:
    events = order_events.after(max(0, after_id), limit)
    return {
        "events": [event.__dict__ for event in events],
        "last_id": events[-1].id if events else max(0, after_id),
    }


@router.get(
    "/audit/export",
    dependencies=[Depends(require_device_key)],
    response_class=PlainTextResponse,
)
def export_audit(days: int = Query(default=7, ge=1, le=31)) -> str:
    audit_log.append("AUDIT_EXPORT_REQUESTED", days=days)
    return audit_log.export(days)


@router.post("/orders/manual", dependencies=[Depends(require_device_key)])
def manual_card_order(payload: ManualCardOrderRequest) -> dict:
    try:
        response = GrowwExecutionService().manual_submit(
            symbol=payload.symbol,
            action=payload.action,
            fraction=payload.fraction,
        )
        audit_log.append(
            "MANUAL_CARD_ORDER_ACCEPTED",
            symbol=payload.symbol.upper(),
            action=payload.action.upper(),
            fraction=payload.fraction,
        )
        return response
    except (RuntimeError, ValueError) as exc:
        audit_log.append(
            "MANUAL_CARD_ORDER_REJECTED",
            severity="WARN",
            symbol=payload.symbol.upper(),
            action=payload.action.upper(),
            reason=str(exc),
        )
        raise HTTPException(status_code=409, detail=str(exc)) from exc


@router.get("/pnl/ipo-sentinel", dependencies=[Depends(require_device_key)])
def ipo_sentinel_pnl() -> dict:
    state = live_state_store.load()
    return {
        "daily": live_ledger.daily_performance(
            target_rupees=5_000,
            capital_base=state.budget_rupees,
        ),
        "summary": live_ledger.summary(capital_base=state.budget_rupees),
        "closed_calls": live_ledger.closed_calls(limit=200),
    }
