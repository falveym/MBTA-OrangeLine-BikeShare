# Orange Line & Bluebikes live collectors

A standalone project for collecting live MBTA Orange Line arrivals and Bluebikes
station availability. Collected data can be used by separate analysis projects
in their own repositories.

## Collection

Both collectors run continuously during **04:45 through 01:30 America/New_York**
(start inclusive, end exclusive). Between 01:30 and 04:45 they sleep until opening.
The zone handles daylight saving time; timestamps include their UTC offset.

* `SegmentHeadwayTracker` polls MBTA VehiclePositions every 30 seconds. It records
  Orange Line vehicles observed STOPPED_AT Sullivan (70030) or Ruggles (70010),
  once per (trip_id, stop_id). These are the plan's two directional platform IDs;
  opposite platforms are not included. Direction is the feed's numeric direction_id.
  Vehicle timestamps are used when available, otherwise the poll time. Observations
  older than five minutes or over one minute in the future are discarded.
* `BluebikesOrangeLineCapacity` polls station status every five minutes. It matches
  stations within 500 m of the original Malden–Sullivan and Forest Hills–Ruggles
  station coordinates, and refreshes station information each Eastern calendar day.
  It skips polls when VehiclePositions contains no Orange Line trips.

Failed HTTP downloads retry three times with 1- and 2-second backoffs and timeouts.
Errors go to stderr. Invalid or missing bike counts produce no synthetic zeros.
CSV errors are logged; the next poll retries opening the file. Writers flush every
row, rotate at Eastern midnight, and close on normal shutdown.

## Files and schemas

New data appends to `data/raw/`; empty files receive a single header.
UTF-8 CSV records use LF. Existing December 2025 CSVs are preserved unchanged in
`data/archive/`, including their original repeated headers and schemas.

| File | Columns |
| --- | --- |
| bikes_YYYY-MM-DD.csv | timestamp,station_id,segment,bikes,ebikes,docks,capacity |
| arrivals_YYYY-MM-DD.csv | timestamp,stop_id,trip_id,vehicle_id,direction |

`bikes` is GBFS num_bikes_available (includes ebikes); `ebikes` is its electric
subset. `docks` is available docks; `capacity` comes from station_information,
so it need not equal bikes plus docks. Segment is north or south.
Optional prediction snapshots are not implemented.

Compute headways in the notebook by sorting arrivals within stop and direction
and taking timestamp differences. Polling can miss short dwells; these are observed
stops rather than exact door-opening times. Deduplication is held for the current
service day in memory and resets on restart; deduplicate (service day, trip_id,
stop_id) in analysis as well. Before 04:45, the service day is the previous date.
A crash or disk error can leave a partial final record; inspect before analysis.
Analyze copies or completed days: Excel can lock a live file on Windows.

## Windows build and run

Install Java **17+** and Maven, with java and mvn on PATH:

```powershell
mvn package
.\run-collectors.bat
```

The package includes dependencies:
`target/mbta-orangeline-bikeshare-1.0-SNAPSHOT-jar-with-dependencies.jar`.
The launcher starts two windows and appends output to logs/arrivals.log and
logs/bikes.log. Close both windows to stop. For a single supervised process use
`run-collectors.bat arrivals` or `run-collectors.bat bikes`.
Running the jar directly starts the arrivals collector.
Do not run multiple copies of the same collector.

## Windows Task Scheduler deployment

To register current-user logon tasks (without starting them immediately), run
`powershell -NoProfile -File .\scripts\install-collector-tasks.ps1`.
Add `-IncludeDailyCommit` to also register the optional 02:00 commit/push task.
The installer refuses to overwrite existing tasks. Set Java on PATH or JAVA_HOME
persistently for the task account first; current-terminal changes alone do not
configure future logons. Use the manual instructions below for unattended startup.

Create **two separate tasks**, one for each collector. This allows Task Scheduler
to supervise each Java process rather than a launcher that immediately exits.

* Trigger at logon, or startup with credentials and “Run whether user is logged on
  or not” for unattended collection.
* Program: cmd.exe. Arguments:
  `/c ""C:\path\to\MBTA-OrangeLine-BikeShare\run-collectors.bat" arrivals"`
  (use bikes for the second task). Set “Start in” to the repository.
* Restart on failure every minute, up to three attempts.
* Disable “Stop the task if it runs longer than”. Choose “Do not start a new
  instance”. Ensure the task account has Java on PATH and write access.

For an optional third task at 02:00 Eastern, run powershell.exe with
`-NoProfile -File "C:\path\to\MBTA-OrangeLine-BikeShare\scripts\daily-commit.ps1"`.
The script commits only completed Eastern calendar days and pushes the current
branch, requiring existing Git authentication and an upstream. Check task history
for failures. It never force-pushes.

On the collection laptop, an administrator can disable plugged-in sleep:

```powershell
powercfg /change standby-timeout-ac 0
powercfg /change hibernate-timeout-ac 0
```

Set plugged-in lid closing to “Do nothing” in Power Options; keep battery sleep
settings. Keep power connected. Configure Windows Update active hours or pauses
as appropriate; startup tasks recover after reboot. These machine settings and
scheduled tasks are deployment steps, not applied by the repository scripts.

## Verification

After `mvn package`, run the offline checks:

```powershell
java -cp "target/test-classes;target/mbta-orangeline-bikeshare-1.0-SNAPSHOT-jar-with-dependencies.jar" edu.wit.CollectorChecks
```

These checks also run automatically as part of `mvn test` and `mvn package`.
For a bounded 15-minute live smoke test, with CSVs isolated under target/smoke:

```powershell
java -cp "target/test-classes;target/mbta-orangeline-bikeshare-1.0-SNAPSHOT-jar-with-dependencies.jar" edu.wit.LiveSmoke
```

During service hours, run both collectors for at least 15 minutes. Inspect logs
for matched station IDs, confirm both stop IDs appear, and verify plausible
headways against current service (6–10 minutes is a rough baseline, not a rule).
Check every matched bike station resolves in status. Offline checks exercise the
service gate at closing/opening boundaries without changing production constants.
Use an isolated test environment for network-failure checks; errors must not add
zero-valued rows. Logs append indefinitely; periodically rotate them.
