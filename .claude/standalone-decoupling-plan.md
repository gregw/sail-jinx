# sail-jinx v2 — SailSys Decoupling Plan

> The plan that produced sail-jinx v2, kept as the record of *why* the
> architecture changed and which decisions were deliberate. Written before the
> work; the notes in **Outcome** at the end say where the result differed.

---

## Context

sail-jinx today is a *companion* to SailSys. SailSys owns series, races,
divisions, entrants, handicap definitions, computed results, and the user
identity/permission model. sail-jinx reads all of that over the SailSys REST
API, adds a locally-computed pursuit handicap (the "Jinx" algorithm), and
writes results, per-division timing, TCFs and visibility flags back.

Work was suspended at SailSys's request (`README.md:5`). The new architecture
removes SailSys entirely: sail-jinx becomes standalone, holds its own data,
captures its own times, computes the pursuit stagger and corrected finish order
itself, and emits **two reports that a human transcribes into SailSys by hand**.
There is no SailSys API client, no credentials, no network dependency. The build
and the app must run with zero SailSys connectivity.

This is one refactor, not a sequence of seam cuts: removing SailSys as the
entrant source forces a new local source of truth, which in turn changes the
race-officer workflow (entrants created locally, casuals entered on the night,
output printed rather than pushed).

---

## Decisions taken (from Q&A)

| # | Decision |
|---|---|
| D1 | **Pursuit only** in the UI. `division` stays as a field on `Boat`/`Entrant` so a fleet-start mode can be added later without a data migration, but no per-division start panel is built. |
| D2 | The **admin / race-officer distinction is kept in the model**, but **no authentication is implemented**. Every local connection is treated as admin. A single `currentRole(request)` seam returns `ADMIN` so an auth layer can be dropped in later (needed before Plan B/C hosting). |
| D3 | Casual entry = **type-ahead over the local register with three outcomes** (pick existing / create register entry / one-off for this race only). |
| D4 | Runs **locally for now** (club office PC). Plan B is the Raspberry Pi with rsync to the club Google Drive; Plan C is hosted. Persistence must therefore be a plain, rsync-friendly directory tree — no git automation, no server-side scheduling assumptions. |
| D5 | **Report 1** (start offsets): `Sail# · Boat · Offset · Start`. Offset is the `+0 / +6 / +13` minute form, because that is how it is entered in the other system. |
| D6 | **Report 2** (corrected finish): `Pl · Sail# · Boat · Finish · Corrected · Flags`. **Corrected = finish + early-start period only.** The 5-minute OCS penalty is *not* in this column — it is implied by the OCS flag. |
| D7 | Both reports are **print-friendly HTML pages**. No new dependencies. |
| D8 | **Start fresh.** Existing `data/store/` is archived intact, not migrated. A file importer (CSV/JSON) for replaying real race times into a test store is a **later** item; the store format is designed to make it easy. |
| D9 | **Per-race output only.** No series standings, no points accumulation — SailSys still does series scoring after transcription. TCF is the only thing that carries between races. |
| D10 | Race lifecycle is **derived from data**, not a state field: a race is *current* (live-timing affordances on, times editable) until its handicaps have been processed and saved; an explicit **Unlock/Reprocess** action clears that to fix a mistake. |

---

## 1. Every SailSys touch point

### 1.1 The client — `sailsys/SailSysClient.java` (758 lines) — **deleted whole**

| Dir | Method | Endpoint | What it supplies today |
|---|---|---|---|
| R | `login` / `fetchCurrentUser` | `POST /auth`, `GET /users` | session token + identity + `adminLevel` |
| R | `fetchClubSeries` | `POST /series/all` | **the series list** |
| R | `fetchCurrentRaces` | `GET /races/current` | club-wide race list (RO view) |
| R | `fetchSeriesRaces` | `GET /series/{id}/races` | **races in a series** |
| R | `fetchSeriesDetail` | `GET /series/{id}` | series `raceType` (pursuit?) |
| R | `fetchRaceStatus` | `GET /races/{id}/status` | **race metadata**: date, number, seriesId, `divisionTiming[]` (start + course length + abandoned), `resultStatus`, visibility flags, `resultSaveToken`, `nextRaceId` |
| R | `fetchRaceEntrants` | `GET /races/{id}/entrants` | **the entrants table**: boat id/name/sail#, division, allocated `startTimeLocal`, `handicap.currentHandicaps[]` (TCF + spinnakerType) |
| R | `fetchHandicapDefinitions` | `GET /series/{id}/handicapDefinitions` | handicap-name catalogue |
| R | `fetchSeriesEntries` / `fetchSeriesEntryDetail` | `PUT /series/{id}/entries`, `GET .../{boatId}` | series roster, `spinnakerType`, division catalogue |
| R | `fetchDivisionPenalties` / `fetchRacePenaltiesComplete` | `/divisions/{id}/penalties`, `/races/{id}/results/penalties/complete` | penalty objects for flag push |
| R | `fetchRaceStarters` / `fetchRaceFinishers` | `GET /races/{id}/results/{starters,finishers}` | per-boat `startedRace` / `finishTime` / `penalties` |
| R | `fetchRaceResults` / `checkRaceResults` | `GET /races/{id}/results`, `.../results/check` | SailSys-computed place / points / elapsed / corrected |
| **W** | `setEntrantsVisibility`, `setStartTimesVisibility`, `setResultsStatus` | `PUT .../visibility/{n}`, `PUT .../results/status/{n}` | publish entrants / start sheet / results |
| **W** | `setRaceTiming` | `PUT /races/{id}/timing` | per-division start + course length; **triggers SailSys's staggering** |
| **W** | `abandonDivisions` | `PUT /races/{id}/divisions/abandon` | abandon |
| **W** | `putRaceStarters` / `putRaceFinishers` | `PUT /races/{id}/results/{starters,finishers}` | **push results** |
| **W** | `putRacePenaltiesComplete` | `PUT .../results/penalties/complete` | trigger SailSys scoring |
| **W** | `updateHandicap` / `updateRaceHandicaps` | `PUT /series/{id}/entries/{boat}/handicaps`, `PUT /races/{id}/handicaps` | **push TCFs** (single / bulk) |
| **W** | `confirmEntry` / `updateEntryDivision` | `PUT .../confirm`, `PUT .../division/{d}` | entry admin |

Also deleted: `sailsys/SailSysSession.java`, `src/test/.../SailSysSessionTest.java`,
and the Jetty `HttpClient` wiring in `server/JinxServer.java:42-48,78`.

### 1.2 Server handlers — `server/ApiServlet.java` (2793 lines)

**Removed outright (exist only to talk to SailSys):** `handleLogin`/`handleLogout`
(425/449), `handleSeries` (457), `handleGetHandicapDefinitions` (494),
`loadHandicapDefinitions` (517), `handleSeriesSpinnaker` (563),
`handleSeriesEntries` (604), `handleSeriesEntryCount` (662), `handleConfirmEntry`
(683), `handleEntryDivision` (709), `handleIsSeriesPursuit`/`seriesHasPursuit`/
`computeSeriesHasPursuit` (817-885), `handleSeriesRaces` (939),
`handleCurrentRaces` (989), `handleRaceEntrants` (1007), `handleSaveTcfs` (1056),
`handlePushResults` (1150), `handleComputedResults` (1356), `handleFinishers`
(1385), `handleProcessResults` (1408), `handleResultsStatus` (1493),
`handleVisibility` (1537), `handleDivisionStarts` (1580), `handleProcessRace`
(1845), `handleAbandon` + `abandonViaStarters` + `buildAbandonStarters`
(1954-2062), `handlePushRaceHandicaps` (2635), `pushSnapshotToSailSys` (2731),
`adminLevelForClub` (2765). Helpers that go with them:
`buildTemplateFromEntrants`, `findPenaltyByShortName`, `patchDivisionTiming`,
`divisionsFromBody`, `replaceTimePortion`, `recomputeUtc`, `parseIsoLocal`,
`projectSeriesEntries`, `projectDivisions`.

**Kept, already SailSys-free:** `handleGetSeriesConfig`/`handleSaveSeriesConfig`
(893/912), `handleGetRaceTimes`/`handleSaveRaceTimes` (2128/2141),
`handleGetPendingHandicaps` (2520), `handleGetRaceTcfSnapshot` (2534),
`handleSaveRaceTcfs` + `mergeTcfSnapshot` (2554/2581),
`handleDeleteRaceTcfSnapshot` (2670), `handleSaveRaceTcfSnapshot` (2695),
`handleCoursePlan` + `computeCoursePlan` (2088/2224), and — most importantly —
`handleProcessHandicaps` (2318), which is **already a pure function of a
client-supplied snapshot**.

**SailSys tail to cut:** `handleSaveHandicaps` (2406) fetches race status only
for `nextRaceId`; `buildSnapshotFromAdjustments` (2481) fetches entrants only
for `spinnakerType`. Both become local store lookups.

### 1.3 Front end

- `common.js:98-120` — auth widget renders "SailSys: email (role)"; `isAdmin()`
  derives from SailSys `adminLevel`.
- `index.html` — the whole page is a SailSys login form.
- `series.html`, `races.html`, `entries.html` — pure SailSys payload
  projections; `races.html:27-29` decodes SailSys visibility/processing enums.
- `race.html` (5264 lines) — consumes the SailSys entrants shape (`e.boat.id`,
  `e.boat.sailNumber`, `e.division.name`, `e.startTimeLocal`,
  `e.handicap.currentHandicaps[]`), `currentStatus.divisionTiming[]`, and the
  computed-results payload (`parseSailsysResults`, 392). Carries the publish /
  process / abandon controls (3110-3261), the "Sailsys Elapsed / Corrected"
  diagnostic columns (1463-1475), and the TCF push/reset mismatch banner
  (4550/4607).
- `boats.html` / `audit.html` are already local-only skeletons.

**The key finding:** `race.html:1012-1255` already implements wiki §5.1 in full
locally — `allocatedStartSeconds`, `effectiveStartSeconds`,
`scoredFinishSecondsForEntrant`, `scoredElapsedSecondsForEntrant`,
`correctedSecondsForEntrant`, `recomputePlaces`. SailSys's computed results are
only ever a side-by-side diagnostic. The scoring brain is already local; what is
missing is the *inputs*. Likewise `PursuitHandicapEngine.computeStartTimes` is
written, tested, and **currently dead code** — SailSys did the staggering.

---

## 2. What the standalone system must own

| Data | Today | After |
|---|---|---|
| Identity / role | SailSys login, `adminLevel` | Hard-coded ADMIN behind a `currentRole()` seam (D2) |
| Club, handicap-definition catalogue | SailSys | **gone** — one handicap, the Jinx TCF |
| Series | SailSys `POST /series/all` | **local** `series.json` |
| Races (number, name, date, target duration, earliest start) | SailSys `/races/{id}/status` | **local** `races.json` |
| Course length / target duration | SailSys `divisionTiming[].courseLength` | **local**, on the race; still sized by `computeCoursePlan` (V₀ + sunset cap) |
| **Fleet register** (sail#, name, division, spinnaker, active) | SailSys boats | **local** `boats.json` — new |
| Series roster | SailSys series entries | **local** `roster/{seriesId}.json` — new |
| **Race entrants** incl. casuals | SailSys `/races/{id}/entrants` | **local** `entrants/{raceId}.json` — new |
| TCF in effect per race | SailSys handicaps + local shadow | **local only** — `race-tcfs/{raceId}.json` becomes authoritative |
| Allocated (staggered) start per boat | SailSys computed after `PUT /timing` | **local** — `computeStartTimes` promoted, result persisted |
| came / actual start / finish / flags | local `race-times/` + SailSys overlay | **local only** |
| Places, corrected times | SailSys computed, local shadow | **local only** |
| Adjustments + audit | local | unchanged |

The two genuinely new owned entities are **the boat register** and **per-race
entrants**. Everything else already has a local home or a local computation
waiting to be promoted.

---

## 3. Local persistence and backup

Keep JSON files: ~40 boats, ~20 races/season, and hand-inspectable text matters
when this is the sole record. But `JsonStore` as written is **not safe as a
system of record**:

1. `MAPPER.writeValue(file.toFile(), …)` (`JsonStore.java:130,137,150,167,184,
   205,228,250`) truncates then streams — a crash or full disk mid-write leaves
   a corrupt file and the data is gone.
2. No history: last write silently wins.
3. No off-machine copy.

**Design (D4 — must stay a plain, rsync-friendly tree):**

- **Atomic writes.** One private `write(Path, Object)` helper: serialise to a
  sibling `.tmp` in the same directory, flush/fsync, then `Files.move(...,
  ATOMIC_MOVE, REPLACE_EXISTING)`. Every store method routes through it.
  Mechanical, and removes the corruption class entirely.
- **Append-only journal.** Each mutation also appends one line to
  `data/store/journal/{yyyy-MM}.jsonl` — `{ts, entity, key, payload}`. Replay
  reconstructs any file; recovers the "crash between two related writes" case.
- **Timestamped snapshots.** On startup and on `POST /api/backup`, zip
  `data/store/` to `data/backups/store-{timestamp}.zip`, keeping the last N.
  One copyable artifact for the operator, and a natural rsync target.
- **Defensive load.** Per-file parse failures are caught, logged, and surfaced
  in the UI rather than failing startup — hand-editing JSON stays viable.
- **Export/import.** `GET /api/export` returns one season-bundle JSON; the
  matching importer is where D8's "replay a real race's times from a file"
  eventually lands.
- **No git automation.** Rejected for now: the target is rsync-to-Drive, and an
  auto-committing repo inside `data/` would fight that.

**Risks to state plainly in the docs:**

- *Single copy on an office PC.* Snapshots and journals do not survive a dead or
  stolen machine. Off-machine durability depends entirely on someone running the
  rsync (or moving to Plan B/C). This is the largest residual risk and it is a
  process risk, not a code one.
- *No authentication (D2).* Anyone who can reach the port can edit or delete a
  season. Acceptable on a single office PC; **must be fixed before Plan B/C.**
- *No concurrency control.* `JsonStore` is `synchronized`, fine for one process,
  but two browsers editing one race last-write-wins each other.
- *No cross-file transaction.* Save-handicaps writes adjustments and the next
  race's TCF snapshot separately; the journal makes a half-state recoverable,
  not self-healing.

**Existing data (D8):** `data/store/` is `.gitignore`d, so those ~25 races of
`race-times` / `race-tcfs` exist **only on this machine**. Step one of the
refactor moves the directory to `data/archive/sailsys-2026/` (a copy, verified,
before anything else runs) so the new store starts empty and nothing is lost.

---

## 4. Behaviour by race type — survives / changes / removed

### Survives unchanged
- **The Jinx algorithm** — `PursuitHandicapEngine`, `SolarTimes`,
  `HandicapEngine`, `computeCoursePlan`, and per-series algorithm config
  (`series-config/{id}.json`). No SailSys involvement today.
- **The §5.1 scoring primitives** in `race.html:1012-1255`.
- Live-timing affordances: NOW buttons, NOW log, drag-to-reorder, duty boat
  (AVG), hide-DNC / hide-finished filters, flag popover, sessionStorage
  restore of unsaved edits.
- Local per-race TCF snapshot history (wiki §8.1) — now the *only* TCF record.

### Changes
- **Race type.** Per D1 the pursuit path is the only one built: per-entrant
  Allocated/Actual Start + Late columns always on; `activeCols()`'s
  `!isPursuit` branches (`race.html:1594-1603`) and the per-division start
  panel are removed. `division` survives as a data field only.
- **TCF editing gate.** Today: series `defaultHandicap` must equal the
  configured `handicapDefinitionId`, and `adminLevel==0`. After: every series in
  sail-jinx is scored on the Jinx TCF, so the `isOurHandicap` test disappears;
  the gate is just the lifecycle lock (D10). `README.md`'s behaviour-by-series
  table collapses to a single row and is rewritten.
- **Process Handicaps gate.** No longer conditioned on the handicap definition —
  only on "this race has results".
- **"Is the race current".** Today `raceEntrantVisibility === 1 &&
  resultStatus >= 3`, a workaround for SailSys's sticky `resultStatus`
  (`CLAUDE.md`). After (D10): derived from whether this race's handicaps have
  been processed and saved, with an Unlock/Reprocess escape hatch. The sticky-
  `resultStatus` workaround and its rationale are deleted.
- **Allocated start times.** From "SailSys staggers after we `PUT /timing`" to
  "we compute them": `computeStartTimes` goes live behind
  `POST /api/races/{id}/start-times`, and its output is persisted per race so
  the start sheet is stable once published. The two 90-second poll loops
  (`handleProcessRace`, `handleProcessResults`) vanish — the computation is
  instant.
- **Places.** From "prefer SailSys, fall back to local" to "local, always".
  `isResultsClean()`, `parseSailsysResults`, `recomputeSailsysPlaces`,
  `sailsysPointsValue` and the Sailsys Elapsed / Corrected columns go.
- **Handicap selector** collapses to the local Scratch / TCF / Pursuit modes of
  wiki §5.2. No SailSys-authoritative mode, no definition catalogue.
- **Boats page** becomes a real fleet editor rather than a read-only skeleton.

### Removed (existed only to drive SailSys)
- Login / logout / session and everything keyed off `adminLevel`.
- Entrants-visibility and start-times-visibility toggles; results status
  Hidden / Provisional / Final.
- Push results, Process results (SailSys recalc), Push handicaps, and the TCF
  mismatch banner with its Push/Reset buttons — the mismatch it reconciles
  cannot exist once there is one copy of the TCF.
- Series-entries actions: confirm entry, change division, entry stages,
  entry-count probes, is-pursuit probes.
- SailSys abandon endpoints (abandon survives as a local flag on the race).
- The `spinnakerType` plumbing that existed solely to satisfy SailSys write
  validation. Spinnaker S/NS stays as a boat attribute for display.
- Series points / SailSys-computed points (D9).

---

## 5. Competitors and entries (deliberately small)

One club, ~40 regulars, a handful of casuals a night. Not a general entry system.

**Boat register** — `data/store/boats.json`, `Map<boatId, Boat>`. Extend
`model/Boat` with `spinnaker` (S/NS), `active`, `notes`. `boatId` becomes a
locally-minted stable id (UUID or sail-number slug), no longer a SailSys
integer. Edited on a rewritten `boats.html`: add / edit / retire, set division
and starting TCF. ~40 rows — no pagination, no server-side search.

**Series roster** — `data/store/roster/{seriesId}.json`: the subset of the
register entered for the season, each with a starting TCF. One checkbox screen
against the register (replaces `entries.html`).

**Race entrants** — `data/store/entrants/{raceId}.json`, seeded from the roster
when the race is created. On a normal night there is *nothing to type* — the
fleet is already there and the RO just ticks who came.

**Casuals (D3)** — one "Add boat" box on the race page taking sail number and/or
name. As the RO types, it filters the register client-side on a normalised key
(case-folded, whitespace/punctuation stripped, leading zeros dropped) and offers:

1. **Pick a match** → that registered boat is entered for this race.
2. **New boat** → added to the register (flagged `casual`) *and* entered, so it
   accrues TCF history if it keeps showing up.
3. **One-off** → entered for this race only with a free-text name and no
   register entry — no TCF history, excluded from handicap processing.

The register is an array of ~40; the match is ~30 lines of client-side filter —
no index, no fuzzy library. A later dedupe/merge tool is *not precluded*
(entrants reference stable boat ids) but is not built.

---

## 6. The two reports

Both are **print-friendly HTML pages** (D7) served from the race page, sharing
one print stylesheet. No new dependencies.

### Report 1 — Start offsets (`/report-starts.html?raceId=…`)

Ordered slowest-first (start order). Offset is the transcription form (D5).

```
MYC Twilight — Race 4 — Thu 12 Feb 2026        Earliest start 18:00

Sail#    Boat             Offset   Start
------   --------------   ------   -----
A123     Slow Poke           +0    18:00
5678     Mid Fleet           +6    18:06
AUS9     Quick Silver       +13    18:13
```

`Offset` = `round(τ_max − τᵢ)` in whole minutes (wiki §4) = `Start − earliest`.

### Report 2 — Corrected finish (`/report-finish.html?raceId=…`)

Ordered by place. **Corrected = finish + early-start period only** (D6); the
5-minute OCS penalty is not folded in — it is implied by the OCS flag, which is
why the flag column sits alongside. Non-finishers listed below the placed boats
with their flag and no place.

```
MYC Twilight — Race 4 — Thu 12 Feb 2026

Pl   Sail#    Boat             Finish      Corrected   Flags
--   ------   --------------   ---------   ---------   -----
 1   AUS9     Quick Silver      19:44:20    19:44:20
 2   5678     Mid Fleet         19:45:02    19:45:02
 3   A123     Slow Poke         19:46:10    19:46:15    OCS
 -   770      Duty Boat                -           -    AVG
```

**Note on §5.1:** the wiki's internal `scored_finish_time` (which *does* include
`OCS_PENALTY`) is unchanged and still drives the engine and the place sort. The
report's `Corrected` column is deliberately the narrower "head-start correction
only" value, per D6. This distinction gets a paragraph in
`wiki/Jinx-Handicaps.md` so nobody later "fixes" one to match the other.

---

## 7. Execution plan

### Phase 0 — branch, archive, version
1. `git checkout -b backed-by-sailsys && git push -u origin backed-by-sailsys`
   — the SailSys-coupled implementation preserved intact on that branch.
2. Back on `main`: copy `data/store/` → `data/archive/sailsys-2026/`, verify,
   then let the new store start empty. (`data/store/` is gitignored, so this
   data exists only on this machine — the archive is the only copy.)
3. `pom.xml:9` `0.1.0-SNAPSHOT` → **`2.0.0-SNAPSHOT`**. That is the only
   occurrence of the version in the tree; a `version` field is added to
   `/api/config` so the UI can display it.

### Phase 1 — model + store (test-first, per `feedback_test_first`)
New / rewritten records under `model/`: `Series` (no SailSys fields), `Race`
(+ `seriesId`, `targetElapsedMinutes`, `earliestStart`, `courseLengthNm`,
`abandoned`), `Boat` (+ spinnaker/active/notes), `Entrant`, `RosterEntry`,
`StartSheet`. `JsonStore` gains atomic writes, the journal, defensive load, and
boats/series/roster/entrants/start-sheet accessors. Failing JUnit tests first;
extend `JsonStoreTest`.

### Phase 2 — server
Delete `sailsys/`; gut `ApiServlet` per §1.2; add local CRUD
(`/api/series`, `/api/races`, `/api/boats`, `/api/series/{id}/roster`,
`/api/races/{id}/entrants`) and `POST /api/races/{id}/start-times` wrapping the
now-live `computeStartTimes`; add `/api/reports/{raceId}/{starts,finish}`;
drop `HttpClient` from `JinxServer`. `ApiServletTest` keeps the surviving pure
helpers (`computeCoursePlan`, `mergeTcfSnapshot`) green; `SeriesEntriesProjectionTest`
is deleted with its projection.

### Phase 3 — front end
Rewrite `index.html` (dashboard, not login), `series.html`, `races.html`,
`boats.html` (fleet editor); replace `entries.html` with the roster screen; add
the two report pages. Surgically strip `race.html` of SailSys reads/pushes and
the non-pursuit branches while keeping its §5.1 scoring core intact.
`common.js` loses `refreshAuthWidget`/`isAdmin`; `_nav.html` loses the auth span.

### Phase 4 — docs
Rewrite `README.md` and `CLAUDE.md` for the standalone architecture, stating
plainly that **SailSys integration is now manual transcription of two reports,
outside the software**. Update `wiki/Home.md`; update `wiki/Jinx-Handicaps.md`
§8/§11 (local TCF store is now authoritative; add the §6 corrected-column note);
rewrite `wiki/myc-ro-ui-storyboard.md` for the new workflow; retire
`wiki/sailsys-api-reference.md` to a clearly-labelled historical appendix.
Commit this plan as `.claude/standalone-decoupling-plan.md`.

Structure and comment density follow the existing code — other club developers
maintain this, and the current codebase's explanatory comments are the reason it
is legible. Where a comment currently explains a SailSys quirk, it is deleted
rather than left dangling.

---

## 8. Verification

- `mvn test` green; `mvn dependency:tree` shows no HTTP client in `compile`
  scope.
- **Offline end-to-end.** Run with networking disabled
  (`unshare -rn mvn exec:java`) and drive a full race: create a series, create a
  race, build the roster, add a casual via each of the three paths, compute and
  print the start-offset report, capture actual starts and finishes with the NOW
  buttons, check places, print the corrected-finish report, process and save
  handicaps, confirm race N+1 inherits the new TCFs.
- **Crash safety.** Kill the process mid-save; confirm the store loads clean and
  the journal contains the write.
- **Grep gate.** `grep -ri sailsys src/ pom.xml` returns only documentation and
  historical comments — no code, no endpoint strings.

---

---

## 9. Outcome

Delivered across four commits on `main`, with the v1 implementation preserved on
`backed-by-sailsys`. 89 Java tests plus 42 browser-side scoring assertions.

Where the result differed from the plan:

- **TCF precision.** The plan dropped the four-decimal rule as a SailSys
  artefact. Wrong call, corrected on review: four decimals is the sailing
  convention and these numbers get read aloud and retyped. Restored in
  `model/Tcf.java`, but rounding **half-up** rather than truncating — truncation
  was only ever there to match SailSys, and applying it every race would walk
  the whole fleet's handicaps downward across a season.

- **`RaceTcfSnapshot` was deleted rather than kept.** Once entrants became local
  and per-race, each entrant could carry its own TCF, which *is* the per-race
  handicap history the snapshot existed to provide. That also removed the
  merge-not-replace hazard in `mergeTcfSnapshot`: editing one boat's TCF is now
  structurally incapable of dropping the rest of the fleet's.

- **The scoring core moved to `static/scoring.js`** rather than being left in
  `race.html`. The corrected-finish report needs the same primitives, and two
  implementations of §5.1 would eventually disagree — on the sheet someone types
  into SailSys.

- **`race.html` was rewritten, not stripped.** At 5264 lines it was shaped
  around SailSys payloads at every level; the surgical edit would have touched
  nearly every function. It is now ~800 lines.

- **Drag-to-reorder survived**, as planned. Per-division fleet starts did not:
  D1 scoped the UI to pursuit only.

- **One extra durability fix**, found by running the app rather than the tests:
  the store now recreates its directory on every write, so a store directory
  that vanishes underneath a running server heals on the next save instead of
  failing every write until a restart.

Phases 1 and 2 landed as a single commit — the model and the server rewrite
reference each other and do not compile apart.

### Still open

- **No authentication** (D2). Acceptable on a single office PC, and a blocker
  for the Raspberry Pi or hosted options. `ApiServlet.currentRole()` is the seam.
- **Backup is a human step** (D4). Snapshots and the journal do not survive a
  dead machine; something has to copy `data/` off it.
- **The importer** for replaying archived race times (D8) is designed for but
  not built. `data/archive/sailsys-2026/` is waiting for it.
