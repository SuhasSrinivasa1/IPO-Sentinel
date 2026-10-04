from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from zoneinfo import ZoneInfo

from apscheduler.schedulers.background import BackgroundScheduler

IST = ZoneInfo("Asia/Kolkata")


@dataclass
class ResearchScheduler:
    research_job: Callable[..., None]
    market_scan_job: Callable[..., None] | None = None
    daily_review_job: Callable[..., None] | None = None
    weekly_review_job: Callable[..., None] | None = None
    order_monitor_job: Callable[..., None] | None = None

    def build(self) -> BackgroundScheduler:
        scheduler = BackgroundScheduler(timezone=IST)

        # Full refresh after the cash session on every calendar day, including weekends.
        # Weekend runs build the next-week plan from the latest official NSE issue feed.
        scheduler.add_job(
            lambda: self.research_job(trigger="after_market"),
            trigger="cron",
            hour=16,
            minute=5,
            id="ipo_research_after_market",
            replace_existing=True,
            max_instances=1,
            coalesce=True,
        )

        # Revalidate the queue before the market on weekdays because listing dates,
        # exchange symbols or broker instrument availability can change overnight.
        scheduler.add_job(
            lambda: self.research_job(trigger="pre_market"),
            trigger="cron",
            day_of_week="mon-fri",
            hour=8,
            minute=30,
            id="ipo_research_pre_market",
            replace_existing=True,
            max_instances=1,
            coalesce=True,
        )

        # Re-check instrument availability around special pre-open/listing transition.
        for minute in (0, 35, 46, 58):
            scheduler.add_job(
                lambda: self.research_job(trigger="listing_day_recheck"),
                trigger="cron",
                day_of_week="mon-fri",
                hour=9,
                minute=minute,
                id=f"ipo_listing_recheck_09{minute:02d}",
                replace_existing=True,
                max_instances=1,
                coalesce=True,
            )
        # Groww can publish a newly-listed CASH instrument a few minutes after the
        # exchange session transitions. Recheck repeatedly instead of assuming 10:00
        # availability. The execution gate still remains closed until an exact match exists.
        for hour, minutes in (
            (10, (0, 1, 2, 3, 5, 10, 15, 20, 25, 30, 40, 50)),
            (11, (0,)),
        ):
            for minute in minutes:
                scheduler.add_job(
                    lambda: self.research_job(trigger="continuous_open_recheck"),
                    trigger="cron",
                    day_of_week="mon-fri",
                    hour=hour,
                    minute=minute,
                    id=f"ipo_listing_recheck_{hour:02d}{minute:02d}",
                    replace_existing=True,
                    max_instances=1,
                    coalesce=True,
                )
        if self.market_scan_job is not None:
            scheduler.add_job(
                lambda: self.market_scan_job(trigger="minute_scan"),
                trigger="cron",
                day_of_week="mon-fri",
                hour="9-15",
                minute="*",
                id="ipo_market_scan_each_minute",
                replace_existing=True,
                max_instances=1,
                coalesce=True,
            )
        if self.daily_review_job is not None:
            scheduler.add_job(
                self.daily_review_job,
                trigger="cron",
                day_of_week="mon-fri",
                hour=16,
                minute=20,
                id="ipo_daily_learning_review",
                replace_existing=True,
                max_instances=1,
                coalesce=True,
            )
        if self.weekly_review_job is not None:
            scheduler.add_job(
                self.weekly_review_job,
                trigger="cron",
                day_of_week="sun",
                hour=17,
                minute=0,
                id="ipo_weekly_strategy_review",
                replace_existing=True,
                max_instances=1,
                coalesce=True,
            )
        if self.order_monitor_job is not None:
            scheduler.add_job(
                self.order_monitor_job,
                trigger="interval",
                seconds=30,
                id="ipo_order_position_monitor_30s",
                replace_existing=True,
                max_instances=1,
                coalesce=True,
            )
        return scheduler


def ist_now() -> datetime:
    return datetime.now(IST)
