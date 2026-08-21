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
- **The four handicap variants are not four implementations.** A, B, C and D are
  two independent knobs on the one pursuit engine — see below. Adding a fifth
  letter would be a mistake; adding a third knob might not be.
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
  data/config/aliases.yaml      # boat + design equivalences, seeded from sailing-pf
  data/config/design.yaml       # ignored/excluded designs, per-boat design overrides
  data/store/                   # THE ONLY COPY of everything (gitignored)
  data/archive/                 # pre-v2 SailSys-era store, kept for a future importer
  wiki/                         # git submodule -> the GitHub wiki
  src/main/java/org/mortbay/sailing/jinx/
    identity/                   # IdGenerator, Aliases, DesignCatalogue, BoatRegistry, FleetJson
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

The NOW log under the entrants table is the one piece of race-night state that is
deliberately **not** in the store: it is a per-tab scratchpad whose whole job is to
undo a time stamped against the wrong boat, by dragging it onto the right one. A
value a drag displaces goes back into the log rather than being lost.

`scoring.js` also carries the sail-number normalisation the register uses, so the
race page's add-a-boat type-ahead finds the boat the server would resolve to
rather than approximating it.

### The handicap variants: two knobs, not four algorithms

`config.yaml`'s `algorithm:` block selects a corner of a 2×2:

| Variant | `penaltyScaling` | `givebackGamma` |
|---|---|---|
| A | `fixed` | 0.0 |
| B | `fixed` | 1.0 |
| **C** | `perHour` | **0.0** — the default |
| D | `perHour` | 1.0 |

`variant: C` is shorthand for setting both. Either knob may be set alone; an
explicit knob beats a variant that contradicts it, with a warning. `givebackGamma`
is continuous, so the letters are corners of a square, not a menu — 0.35 is a real
setting.

Three things it is easy to get wrong here:

1. **`raceDuration` is measured, `targetElapsedMinutes` is a guess.** The handicap
   arithmetic runs on the median of the elapsed times the fleet actually sailed.
   The target is the pre-race estimate and is used *only* to publish start times.
   The engine deliberately does not read it in `processResults`.
2. **The penalty scaling and the §7 denominator must be the same quantity.** Under
   `perHour` the penalties scale by `raceDuration` and the TCF conversion divides by
   `raceDuration × TCF_med`; the two cancel, which is what makes C give the same
   correction whether the night was 45 minutes or two hours. Substitute the target
   in either place and the cancellation breaks silently. `HandicapVariantTest`
   pins it.
3. **Casuals are out of the handicap entirely.** `Competitor.seeded` is false for
   them: no rung on the penalty ladder, no share of the pool, and their elapsed
   time stays out of the median. Note this *contradicts* `Entrant.scoresHandicap()`,
   which still answers true for CASUAL — the client decides what to send
   (`scoring.js handicapEngineInput` sets `seeded: entryType === 'ROSTER'`), so
   that is the one line to change if the club wants casuals handicapped again.

`Design.noSpinnaker`-style asymmetry applies to `dnfInRaceDuration` too: retirements
draw from the pool because they sailed, but their elapsed time is an *allowance*
rather than a measurement, so it stays out of the median unless asked for.

### The corrected/scored distinction

Two numbers with similar names and different jobs:

- `correctedFinishSeconds` — finish **plus the head start given back only**.
  This is the report column a human transcribes, alongside the OCS flag.
- `scoredFinishSeconds` — also carries the 5-minute OCS penalty. This decides
  places and feeds the engine.

Both are tested. Do not "fix" one to match the other.

---

## Identity

**IDs and boat matching follow sailing-pf.** That project analyses cross-club
performance over the same fleet; a boat entered in one is the same physical hull
as in the other, so both normalise names the same way and share `aliases.yaml`.

| Entity | ID |
|---|---|
| Boat | `9-quicksilver-j24` — `{normSail}-{normName}-{designId}`, design omitted when unknown |
| Series | `myc.org.au/2026-winter-twilight` |
| Race | `myc.org.au-2026-06-05-0001` |

The club domain in `config.yaml` scopes the last two. Set once at installation —
changing it orphans every existing id.

Things that catch people out:

- **`AUS1234`, `AUS01234` and `1234` are one boat**, and the bare form wins. The
  country prefix and leading zeros are normalisation, not identity.
- **A design is part of the boat's ID.** A boat imported without one is
  `A123-slowpoke`; when a later import supplies the design it is *upgraded* to
  `A123-slowpoke-sydney38` and `JsonStore.rewriteBoatId` moves every reference —
  entrants, captured times, start sheets, adjustments, rosters. Miss one and a
  race is orphaned.
- **`- GM` / `- U18` suffixes are stripped**, so `Foobar - GM` and `Foobar` are
  one boat. `Sticky`, `Sticky 2` and `Sticky II` collapse too, under the same
  sail number.
- **Two different designs on one sail+name is a CONFLICT, never a guess.**
  Merging fuses two hulls; creating splits one. A person adds an override.
- **Designs are learned, never entered.** There is no design screen: a design
  exists because someone typed one while adding a boat. Generic labels
  (`yacht`, `sloop`, `custom`) are on `design.yaml`'s ignored list and discarded,
  because a boat that is half design-less and half `…-yacht` has its history in
  two places.

`BoatRegistry.findOrCreate` is the **only** correct way to create a boat.
Calling `JsonStore.putBoat` directly skips alias resolution and design learning.

Learned aliases are written back to `aliases.yaml` immediately, not held in
memory — the failure being defended against is the process dying without a clean
stop. An unreadable `aliases.yaml` is never overwritten.

`SailingPfCompatibilityTest` runs sailing-pf's own `IdGeneratorTest` assertions
against this port. A failure there is a compatibility break between the two
projects, not just a local regression.

---

## Data model

| File | Holds |
|---|---|
| `boats.json` | the fleet register: identity only — sail number, name, design |
| `designs.json` | hull types, learned from boat entry |
| `series.json`, `races.json` | seasons and race dates |
| `roster/{seriesId}.json` | who is in for the season, **and the terms they enter on** |
| `entrants/{raceId}.json` | who is in this race **and the TCF it was sailed on** |
| `start-sheet/{raceId}.json` | the published stagger |
| `race-times/{raceId}.json` | came / actual start / finish, as typed |
| `adjustments/{raceId}.json` | saved handicap output — **also the race lock** |
| `audit.json`, `journal/` | history |

### What belongs to a boat, and what does not

**TCF, division and spinnaker are not properties of a boat.** A boat does not have
a handicap — it has one *for a given series*, and a different one by the end of
it. It can sail one season in Division 1 and the next in Division 2, and enter
one series with a kite and one without.

| Lives on | Holds |
|---|---|
| `Boat` | sail number, name, design, active, casual, notes |
| `Roster.Entry` | starting TCF, division, spinnaker — the terms of a **series** entry |
| `Entrant` | the TCF actually in force for a **race**, plus division and spinnaker |
| `Design` | `noSpinnaker` — a cat rig genuinely cannot fly one; that *is* a hull fact |

Consequences worth knowing:

- The handicap engine takes `Competitor(boatId, tcf)`, not `Boat`. Handing it a
  Boat would mean inventing a handicap field on the register just to have
  somewhere to put the value in transit — which is how the field got there in
  the first place.
- A boat joining a series has to be **given** a TCF; there is no register value
  to inherit. The default is 1.0, visibly a starting point rather than a figure
  anybody chose.
- **Imports come from sailing-pf's `handicaps-*.json` export**, and there are two,
  because the file mixes both kinds of fact:
  `POST /api/boats/import` takes identity only and **ignores handicap and
  variant**; `POST /api/races/{id}/entrants/import` takes the same file and uses
  the handicap as the race TCF and the variant as the spinnaker.
- The export's `boatId` is minted by sailing-pf with *our* rules, so it is read,
  not treated as a foreign key: an exact hit is the strongest match available,
  and its trailing segment is where a design-less boat's design comes from. An id
  that disagrees with the sail number and name beside it yields no design rather
  than a guessed one.
- `Design.noSpinnaker` supplies the *default* for an entry, not the value: a boat
  that can fly a kite may still enter without one. And it is **one-directional** —
  the mark means NS, its absence means *nothing*, not S. `defaultSpinnaker` used to
  return S for every unmarked hull, so a fleet nobody had ever been asked about
  displayed as if the whole lot carried kites. Unknown renders as a dash, like a
  missing design does.

Two more things that are easy to get wrong:

1. **Each race's entrants carry their own TCF.** That is the per-race handicap
   history — processing race 5 cannot disturb what race 4 says. SailSys only
   ever kept the latest value, which is why v1 needed a separate snapshot file.
2. **The race lifecycle is derived, never stored.** A race is locked iff it has
   saved adjustments; unlocking is deleting them. There is no status field, and
   there should not be one — the v1 field was sticky and lied.

### What the race actually needs

`targetElapsedMinutes` and `earliestStart` are the only per-race inputs. There is
**no course length**: what the RO lays on the water is a judgement from the
breeze, and recording a figure the app cannot verify would be a second, quietly
wrong answer to "how long is this race meant to take".

The **sunset cap** (`limitBySunset`, per series) therefore applies to the target
duration, at the moment start times are computed — it depends on the race date
and the earliest start, both of which can change until then. When sunset falls at
or before the earliest start the computation is **refused**, not capped to zero: a
nought-minute target would emit a start sheet with every boat on the same gun.

### The race page's working copy

`race.html` keeps `bundle` as the last thing the server said and `entrants` /
`timesMap` as the working copy, and `entrantsChanged()` compares one against the
other. **The working copy must be a deep copy** — `load()` uses
`structuredClone`. Sharing the objects has bitten twice: an edit mutates both
sides, the comparison finds no difference, and the change is silently never
saved. If an edit on that page appears to "not stick", check that first.

### Casual entrants

`Entrant.EntryType` drives two different questions, and they have different
answers:

- `scoresHandicap()` — does this race adjust its TCF? True for ROSTER and CASUAL;
  they both sailed. False for ONE_OFF, which has no register boat.
- `seedsNextRace()` — is it carried into the next race automatically? **Only
  ROSTER.** A casual turned up once, and a boat nobody expects appearing on a
  printed start sheet costs more than the two clicks to add it again.

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
| POST | `/api/boats/import` | load the fleet from a sailing-pf export (`?dryRun=true` previews) |
| POST | `/api/races/{id}/entrants/import` | add entrants from the same export, with TCFs |
| POST | `/api/races/{id}/entrants/seed` | seed from the roster or the previous race |
| POST | `/api/races/{id}/start-times` | compute and publish the stagger (applies the sunset cap) |
| POST | `/api/races/{id}/process-handicaps` | run the engine (computes, saves nothing) |
| POST | `/api/races/{id}/save-handicaps` | save, and carry TCFs to the next race |
| DELETE | `/api/races/{id}/adjustments` | unlock for reprocessing |

---

## Testing

```bash
mvn test        # 169 tests, offline
```

- `JsonStoreTest` — round-trips, atomicity, journalling, corrupt-file recovery.
- `JinxApiIntegrationTest` — boots the real server on an ephemeral port and
  drives a season over HTTP with the JDK client. Start here to understand the
  workflow.
- `PursuitHandicapEngineTest` — executable spec for the algorithm, mapped to
  wiki sections.
- `BoatRegistryTest`, `AliasesTest`, `DesignCatalogueTest` — identity and
  matching, including the design upgrade and its reference rewriting.
- `SailingPfCompatibilityTest` — the cross-project contract described above.
- `static/scoring-test.html` — the browser half. Not run by Maven; open it.

```bash
node tools/check-scripts.mjs    # optional; needs node, not part of the build
```

Catches calls to names that do not exist — a mistyped function, a helper that
got renamed. `node --check` cannot: those pages parse perfectly and then throw at
runtime, which in a `onchange` handler means the control silently does nothing.
That has bitten three times. Run it after editing anything under `static/`.

Write the failing test first.

---

## Further reading

+ [Project overview](wiki/Home.md)
+ [The Jinx handicap algorithm](wiki/Jinx-Handicaps.md)
+ [Race officer workflow](wiki/myc-ro-ui-storyboard.md)
+ [Decoupling plan](.claude/standalone-decoupling-plan.md) — why v2 looks like this
+ `wiki/sailsys-api-reference.md` — historical; describes an integration that no
  longer exists
