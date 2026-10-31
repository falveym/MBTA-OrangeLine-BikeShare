# Collector upgrade verification — October 31, 2026

## Build and regression checks

Java 23 compiled the project for Java 17. Maven package succeeded and produced
the jar with dependencies. Two automated tests passed with no failures or skips:

* Service-hour boundaries including daylight saving dates; next opening;
  arrival route/status filtering, deduplication and next-service-day reset;
  write failure followed by retry; midnight file rotation, reopening without
  duplicate headers, LF records; missing bike counts rejected and valid counts
  written with station-information capacity.
* Local HTTP server: recovery on the third download attempt and IOException
  after three unsuccessful attempts.

PowerShell scripts passed syntax parsing. Task registration, daily commit/push,
and power/update settings were not executed.

## Public-feed checks

The existing GBFS 1.1 station-information and station-status endpoints responded.
All matched IDs resolved in status: 18 south and 10 north. The MBTA
VehiclePositions endpoint parsed successfully and contained Orange Line vehicles.

A bounded live test ran October 31, 2026, approximately 14:34–14:49 Eastern.
Output was isolated under target/smoke rather than data/raw:

* Three bike polls produced 84 station rows.
* Sullivan (70030): observed at 14:39:25.
* Ruggles (70010): observed at 14:40:15 and 14:48:42.
* Ruggles observed headway: 8 minutes 27 seconds.

This is a smoke test, not a guarantee that 30-second sampling captures every
dwell or that future headways will fall within a particular range.
Service closing/reopening was checked using fixed instants, without temporarily
changing production constants or waiting through a real overnight closure.

The December 2025 CSVs were moved to data/archive without editing their contents;
their Git-normalized content hashes match the original files.
