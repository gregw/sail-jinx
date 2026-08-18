# sail-jinx

Please read [README.md](README.md) for a project overview.

## The one thing to know

**sail-jinx v2 is standalone.** It has no SailSys client, no credentials, no
outbound network calls of any kind. Results reach SailSys because a human reads
a printed report and types them in.

If you find yourself adding an HTTP client, an API key, or a "sync" button, stop
— that is the architecture this version exists to remove. The SailSys-coupled
implementation is preserved on the `backed-by-sailsys` branch; it is history,
not a reference.

`grep -ri sailsys src/ pom.xml` should return only comments explaining why
something is the way it is. No endpoints, no code.

---

## Scope

One club, one pursuit series at a time, ~40 regular boats plus occasional
casuals, ~20 races a season, one race officer on one laptop. Every design
decision leans on that:

- The whole dataset is small enough to load into memory and hand-edit.
- The fleet register is an array you filter client-side — no search index.
- There is no concurrency control, because there is one user.
- There is no authentication, because there is one machine. **This must change
  before the app is hosted anywhere.** The seam is `ApiServlet.currentRole()`.

Deliberately preserved pluggability:

- `HandicapEngine` is an interface. `PursuitHandicapEngine` is the first
  implementation; another algorithm can be added without touching the server or
  the store.
- Club identity and algorithm parameters are configuration, not code. Another
  club runs its own `config.yaml`.

The name reflects this: it is not called `myc-twilight` because it is useful
beyond that context.

### Pursuit only

v2 builds the pursuit path only — every boat gets its own staggered gun. Fleet
starts (one gun per division) are not implemented. `division` survives as a
field on `Boat` and `Entrant` so that mode can be added later without a data
migration, but nothing reads it except the display.

---

## Technology

| Concern | Choice |
|---|---|
| Language | Java 21 |
| HTTP server | Jetty 12 (embedded) |
| Front end | Plain HTML + JavaScript, served as static resources |
| Configuration | YAML (`data/config/config.yaml`) |
| Persistence | JSON files on disk via Jackson |
| Build | Maven |

No database. No framework. No HTTP client — the absence is deliberate and
`pom.xml` says so.

---

## Layout

```txt
sail-jinx/
  data/config/config.yaml       # club, algorithm defaults, port
  data/store/                   # THE ONLY COPY of everything (gitignored)
  data/archive/                 # pre-v2 SailSys-era store, kept for a future importer
  wiki/                         # git submodule -> the GitHub wiki
  src/main/java/org/mortbay/sailing/jinx/
    model/                      # records: Boat, Series, Race, Entrant, RaceEntrants, ...
    store/JsonStore.java        # atomic writes, journal, defensive load
    server/                     # JinxServer, ApiServlet, StaticResourceServlet
    pursuit/                    # HandicapEngine, PursuitHandicapEngine, SolarTimes
    config/JinxConfig.java
  src/main/resources/static/    # the whole front end
```

### Where the scoring lives

**In JavaScript, in `static/scoring.js`** — the wiki §5.1 primitives (effective
start, OCS, scored and corrected finish, places, engine input). Both the race
page and the corrected-finish report build a scorer from it, so what the RO sees
on screen and what gets printed cannot disagree.

The Java engine does the handicap *arithmetic* (`PursuitHandicapEngine`); the
browser decides *what to feed it*. That split is why `/process-handicaps` takes
a client-supplied snapshot rather than reading the store: the client knows about
unsaved edits and flag overrides.

`static/scoring-test.html` is the executable specification for that module — 42
assertions. Open the page to run them. Change `scoring.js`, run that page.

### The corrected/scored distinction

Two numbers with similar names and different jobs:

- `correctedFinishSeconds` — finish **plus the head start given back only**.
  This is the report column a human transcribes, alongside the OCS flag.
- `scoredFinishSeconds` — also carries the 5-minute OCS penalty. This decides
  places and feeds the engine.

Both are tested. Do not "fix" one to match the other.

---

## Data model

| File | Holds |
|---|---|
| `boats.json` | the fleet register; `currentTcf` is a **seed**, not authoritative |
| `series.json`, `races.json` | seasons and race dates |
| `roster/{seriesId}.json` | who is in for the season, and their starting TCF |
| `entrants/{raceId}.json` | who is in this race **and the TCF it was sailed on** |
| `start-sheet/{raceId}.json` | the published stagger |
| `race-times/{raceId}.json` | came / actual start / finish, as typed |
| `adjustments/{raceId}.json` | saved handicap output — **also the race lock** |
| `audit.json`, `journal/` | history |

Two things follow from this that are easy to get wrong:

1. **Each race's entrants carry their own TCF.** That is the per-race handicap
   history — processing race 5 cannot disturb what race 4 says. SailSys only
   ever kept the latest value, which is why v1 needed a separate snapshot file.
2. **The race lifecycle is derived, never stored.** A race is locked iff it has
   saved adjustments; unlocking is deleting them. There is no status field, and
   there should not be one — the v1 field was sticky and lied.

TCFs are held to four decimal places (`model/Tcf.java`), rounded half-up, at
every point one is recorded. They get read aloud and retyped; a value that
renders differently each time cannot survive that.

---

## Server API

All endpoints are local reads and writes. See the class javadoc on `ApiServlet`
for the full list. The shape worth knowing:

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/races/{id}` | **everything the race page needs, in one call** |
| POST | `/api/races/{id}/entrants/seed` | seed from the roster or the previous race |
| POST | `/api/races/{id}/start-times` | compute and publish the stagger |
| POST | `/api/races/{id}/process-handicaps` | run the engine (computes, saves nothing) |
| POST | `/api/races/{id}/save-handicaps` | save, and carry TCFs to the next race |
| DELETE | `/api/races/{id}/adjustments` | unlock for reprocessing |

---

## Testing

```bash
mvn test        # 89 tests, offline
```

- `JsonStoreTest` — round-trips, atomicity, journalling, corrupt-file recovery.
- `JinxApiIntegrationTest` — boots the real server on an ephemeral port and
  drives a season over HTTP with the JDK client. Start here to understand the
  workflow.
- `PursuitHandicapEngineTest` — executable spec for the algorithm, mapped to
  wiki sections.
- `static/scoring-test.html` — the browser half. Not run by Maven; open it.

Write the failing test first.

---

## Further reading

+ [Project overview](wiki/Home.md)
+ [The Jinx handicap algorithm](wiki/Jinx-Handicaps.md)
+ [Race officer workflow](wiki/myc-ro-ui-storyboard.md)
+ [Decoupling plan](.claude/standalone-decoupling-plan.md) — why v2 looks like this
+ `wiki/sailsys-api-reference.md` — historical; describes an integration that no
  longer exists
