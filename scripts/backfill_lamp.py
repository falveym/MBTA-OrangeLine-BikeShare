"""Backfill Orange Line stop events from MBTA LAMP daily files.

The live collector recorded Ruggles southbound (70010) instead of Ruggles
northbound (70011) until 2026-11-08. LAMP publishes every subway stop event per
service day, so this script rebuilds those days for the segment platforms and
writes them in the collector's arrivals schema, plus a few LAMP-only columns.

Usage (from the repository root):
    pip install -r scripts/requirements-backfill.txt
    python scripts/backfill_lamp.py                      # 2026-10-31 through yesterday
    python scripts/backfill_lamp.py --start 2026-11-08 --end 2026-11-08 --force

Output: data/backfill/lamp_arrivals_<service date>.csv (one file per LAMP service
day, so post-midnight trips stay with the day they started). Existing files are
skipped unless --force is given. A validation summary compares LAMP with the
collector's own rows for the same trips at 70030 and 70010.
"""
from __future__ import annotations

import argparse
import datetime as dt
import io
import sys
import urllib.error
import urllib.request
from pathlib import Path

import pandas as pd

LAMP_URL = "https://performancedata.mbta.com/lamp/subway-on-time-performance-v1/{date}-subway-on-time-performance-v1.parquet"
ZONE = "America/New_York"
SERVICE_DAY_START = dt.time(4, 45)  # matches the collector's ServiceWindow.START
DEFAULT_STOPS = ["70030", "70011", "70010"]
COLLECTOR_COLUMNS = ["timestamp", "stop_id", "trip_id", "vehicle_id", "direction"]
LAMP_COLUMNS = ["headway_trunk_seconds", "dwell_time_seconds", "service_date"]
REPO = Path(__file__).resolve().parent.parent


def fetch_parquet(source: str, date: dt.date) -> pd.DataFrame | None:
    """Read one LAMP day from a URL or local path template; None if not published."""
    location = source.format(date=date.isoformat())
    if not location.startswith(("http://", "https://")):
        path = Path(location)
        return pd.read_parquet(path) if path.exists() else None
    request = urllib.request.Request(location, headers={"User-Agent": "MBTA-OrangeLine-BikeShare-backfill"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return pd.read_parquet(io.BytesIO(response.read()))
    except urllib.error.HTTPError as e:
        if e.code in (403, 404):
            return None
        raise


def to_arrivals(lamp: pd.DataFrame, stops: list[str]) -> tuple[pd.DataFrame, int]:
    """Filter LAMP events to Orange Line stops and convert to the collector schema."""
    rows = lamp[(lamp["route_id"] == "Orange") & (lamp["stop_id"].astype(str).isin(stops))].copy()
    no_stop_time = int(rows["stop_timestamp"].isna().sum())
    rows = rows[rows["stop_timestamp"].notna()]
    stamps = pd.to_datetime(rows["stop_timestamp"].astype("int64"), unit="s", utc=True).dt.tz_convert(ZONE)
    out = pd.DataFrame({
        "timestamp": stamps.map(lambda t: t.isoformat()),
        "stop_id": rows["stop_id"].astype(str),
        "trip_id": rows["trip_id"].astype(str),
        "vehicle_id": rows["vehicle_id"].astype(str),
        "direction": rows["direction_id"].astype("Int64"),
        "headway_trunk_seconds": rows["headway_trunk_seconds"].astype("Int64"),
        "dwell_time_seconds": rows["dwell_time_seconds"].astype("Int64"),
        "service_date": rows["service_date"].astype("Int64"),
        "_sort": stamps,
    })
    out = out.sort_values(["_sort", "stop_id"]).drop(columns="_sort")
    return out.drop_duplicates(["trip_id", "stop_id"]), no_stop_time


def collector_rows(day: dt.date) -> pd.DataFrame:
    """Collector arrivals for one service day (calendar day plus the next morning before 04:45)."""
    frames = []
    for d in (day, day + dt.timedelta(days=1)):
        path = REPO / "data" / "raw" / f"arrivals_{d.isoformat()}.csv"
        if path.exists():
            frames.append(pd.read_csv(path, dtype=str))
    if not frames:
        return pd.DataFrame(columns=COLLECTOR_COLUMNS)
    raw = pd.concat(frames, ignore_index=True)
    local = pd.to_datetime(raw["timestamp"], utc=True, format="ISO8601").dt.tz_convert(ZONE)
    service_day = (local - pd.Timedelta(hours=SERVICE_DAY_START.hour, minutes=SERVICE_DAY_START.minute)).dt.date
    raw = raw[service_day == day].copy()
    raw["_t"] = local[service_day == day]
    return raw.drop_duplicates(["trip_id", "stop_id"])


def validate(lamp: pd.DataFrame, raw: pd.DataFrame, stop: str) -> str:
    """Summarize agreement between LAMP and the collector at one platform."""
    l, r = lamp[lamp["stop_id"] == stop], raw[raw["stop_id"] == stop]
    if r.empty:
        return f"{stop}: LAMP {len(l)}, collector 0"
    m = l.merge(r, on=["trip_id", "stop_id"], suffixes=("_lamp", "_raw"))
    diff = (pd.to_datetime(m["timestamp_lamp"], utc=True, format="ISO8601") - m["_t"].dt.tz_convert("UTC")).dt.total_seconds()
    median = f"{diff.abs().median():.0f}s" if len(m) else "n/a"
    return f"{stop}: LAMP {len(l)}, collector {len(r)}, matched trips {len(m)}, median |time diff| {median}"


def main() -> int:
    eastern_today = pd.Timestamp.now(tz=ZONE).date()
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--start", type=dt.date.fromisoformat, default=dt.date(2026, 10, 31))
    p.add_argument("--end", type=dt.date.fromisoformat, default=eastern_today - dt.timedelta(days=1))
    p.add_argument("--stops", nargs="+", default=DEFAULT_STOPS)
    p.add_argument("--out", type=Path, default=REPO / "data" / "backfill")
    p.add_argument("--source", default=LAMP_URL, help="URL or local path template containing {date}")
    p.add_argument("--force", action="store_true", help="overwrite existing backfill files")
    args = p.parse_args()
    if args.end < args.start:
        p.error("--end is before --start")

    args.out.mkdir(parents=True, exist_ok=True)
    missing = []
    day = args.start
    while day <= args.end:
        target = args.out / f"lamp_arrivals_{day.isoformat()}.csv"
        if target.exists() and not args.force:
            print(f"{day}: exists, skipped (use --force to rebuild)")
        else:
            lamp = fetch_parquet(args.source, day)
            if lamp is None:
                print(f"{day}: not published yet")
                missing.append(day)
            else:
                arrivals, no_stop_time = to_arrivals(lamp, args.stops)
                if arrivals.empty:
                    print(f"{day}: no Orange Line rows for {args.stops}; nothing written", file=sys.stderr)
                else:
                    arrivals[COLLECTOR_COLUMNS + LAMP_COLUMNS].to_csv(target, index=False, lineterminator="\n")
                    counts = ", ".join(f"{s}={int((arrivals['stop_id'] == s).sum())}" for s in args.stops)
                    print(f"{day}: wrote {len(arrivals)} rows ({counts}; {no_stop_time} events without a stop time skipped)")
                    raw = collector_rows(day)
                    for stop in ("70030", "70010"):
                        if stop in args.stops:
                            print("    check " + validate(arrivals, raw, stop))
        day += dt.timedelta(days=1)
    if missing:
        print("Not yet published: " + ", ".join(d.isoformat() for d in missing) + ". LAMP posts each day after it ends; rerun later.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
