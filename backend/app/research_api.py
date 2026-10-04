from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from .connection_settings import require_device_key
from .research_service import DailyResearchService
from .research_intelligence import ResearchIntelligenceService

router = APIRouter(prefix="/research", tags=["research"])
_service: DailyResearchService | None = None
_intelligence: ResearchIntelligenceService | None = None


def bind_service(service: DailyResearchService) -> None:
    global _service, _intelligence
    _service = service
    _intelligence = ResearchIntelligenceService(service)


def _require_service() -> DailyResearchService:
    if _service is None:
        raise HTTPException(status_code=503, detail="Research service is not initialized")
    return _service


@router.get("/plan", dependencies=[Depends(require_device_key)])
def research_plan() -> dict:
    service = _require_service()
    plan = service.store.load()
    if plan is None:
        return {
            "generated_at": None,
            "source_ready": False,
            "calendar_ready": service.calendar.source_ready,
            "next_trading_day": None,
            "next_trading_day_candidates": [],
            "week_candidates": [],
            "errors": ["RESEARCH_NOT_RUN_YET"],
        }
    return plan


@router.post("/refresh", dependencies=[Depends(require_device_key)])
def refresh_research() -> dict:
    return _require_service().refresh(trigger="manual_api")


@router.get("/dashboard", dependencies=[Depends(require_device_key)])
def research_dashboard() -> dict:
    if _intelligence is None:
        raise HTTPException(status_code=503, detail="Research intelligence is not initialized")
    return _intelligence.dashboard()


@router.post("/review/daily", dependencies=[Depends(require_device_key)])
def review_daily() -> dict:
    if _intelligence is None:
        raise HTTPException(status_code=503, detail="Research intelligence is not initialized")
    return _intelligence.review_day()


@router.post("/review/weekly", dependencies=[Depends(require_device_key)])
def review_weekly() -> dict:
    if _intelligence is None:
        raise HTTPException(status_code=503, detail="Research intelligence is not initialized")
    return _intelligence.review_week()
