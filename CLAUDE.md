# sail-jinx

Please read [README.md](README.md) for a project overview.

This file is the map: what is where, and what looks wrong but is not. The reasons live
beside the code, in its javadoc, and the tests pin them. When this file and the code
disagree, the code is right and this file needs fixing.

## The one thing to know

**sail-jinx v2 is standalone.** It has no SailSys client and exchanges no data
with anything. Results reach SailSys because a human reads a printed report and
types them in.

If you find yourself adding an HTTP client for club data, an API key, or a "sync"
button, stop — that is the architecture this version exists to remove. The
SailSys-coupled implementation is preserved on the `backed-by-sailsys` branch; it
is history, not a reference.

**The one exception is the identity provider.** With `auth.yaml` configured the
server talks to Google to find out *who is asking* — see "Authentication" below.
That is the only outbound call, it carries no club data, and it is off by default.

`grep -ri sailsys src/ pom.xml` should return only comments explaining why
something is the way it is. No endpoints, no code.

---

## Scope

One club, one pursuit series at a time, ~40 regular boats plus occasional casuals,
~20 races a season, one race officer on one laptop. So: the dataset loads into memory
and can be hand-edited, the register is filtered client-side, and there is no
concurrency control.

Deliberately preserved pluggability:

- `HandicapEngine` is an interface; `PursuitHandicapEngine` is the one implementation.
- **One giveback, no variants.** A boat gets back a whole minute, or an even share
  among the DNFs or the DNCs — nothing else. The previous iteration's knobs (variants
  A–D, γ, per-hour penalties, DNF/DNC weights) are gone; their YAML keys still load
  and are ignored.
- Club identity and algorithm parameters are configuration (`config.yaml`), not code.
- **Pursuit only.** `division` survives on `Entrant` so fleet starts can be added
  without a data migration; nothing reads it except the display.

---

## Technology and layout

Java 21, embedded Jetty 12, plain HTML + JavaScript, YAML config, JSON files via
Jackson, Maven. No database, no framework. The only HTTP client is Jetty's, pulled in
by `jetty-openid` for the identity provider — `pom.xml` says so at the dependency.

```txt
data/config/config.yaml        club, algorithm defaults, port
data/config/auth.yaml          OIDC client secret — GITIGNORED; absent means no login
data/config/auth.yaml.example  committed template; must never carry a real secret
data/config/aliases.yaml       boat + design equivalences, shared with sailing-pf
data/config/design.yaml        ignored/excluded/no-spinnaker designs, per-boat overrides
data/store/                    THE ONLY COPY of everything (gitignored) — see JsonStore
data/archive/                  pre-v2 SailSys-era store, kept for a future importer
etc/                           systemd unit and installer for the club Pi
wiki/                          git submodule -> the GitHub wiki (user-facing docs)
src/main/java/org/mortbay/sailing/jinx/
  config/    JinxConfig, AuthConfig
  identity/  IdGenerator, Aliases, DesignCatalogue, BoatRegistry, FleetJson
  model/     records: Boat, Series, Race, Entrant, RaceTimes, Adjustment, ...
  pursuit/   HandicapEngine, PursuitHandicapEngine, SolarTimes
  server/    JinxServer, ApiServlet, AuthFilter, JinxSecurityHandler, SignedIn
  store/     JsonStore — atomic writes, journal, defensive load
src/main/resources/static/     the whole front end
```

---

## Where the scoring lives

**The browser decides what to feed the engine; the Java does the arithmetic.**

- `static/scoring.js` — effective start, OCS, scored and corrected finish, places, the
  engine input (`handicapEngineInput`), flag rules, and the default race view. The
  race page and the finish sheet both build a scorer from it, so screen and paper
  cannot disagree. `static/scoring-test.html` is its executable spec; run it with
  `node tools/run-scoring-test.mjs`, which reads the same page.
- `pursuit/PursuitHandicapEngine` — start times, penalties, giveback weights, and the
  conversion back to TCF. Its javadoc is the reference for the algorithm; the wiki's
  `Jinx-Handicaps.md` is the committee-facing version of the same thing.

`/process-handicaps` takes a client-supplied snapshot, not the store, because the
client holds unsaved edits and flag overrides.

Things that look wrong and are not:

- **Corrected finish ≠ scored finish.** Corrected gives back an early head start and
  nothing else; it is the transcribed column. Scored adds the 5-minute OCS penalty and
  decides places. `scoredElapsedSeconds` ranks Elapsed Place. Both pinned; do not
  reconcile them.
- **`latePlaces` leaves out OCS boats and boats with no timed start** — otherwise an
  untimed boat reports a confident `0:00` late. Ranks share ties via `rankBy`.
- **AVG (duty boat) goes to the engine as DNC** (`jinxStatus`).
- **Flags the RO set by hand are stored; flags the times imply are not.** Overrides
  are `{added, removed}` on `RaceTimes.BoatTimes.flags`; `toggleFlag` decides what may
  go into `removed`. The case it exists for is RET against a boat that never finished.
- **A race's arrangement is a view, not an edit.** Sort, filters and dragged row order
  live in session storage per race (`sail-jinx.raceViews`); `defaultRaceView` in
  `scoring.js` holds the defaults and the test page pins them. Dragging never marks
  the page dirty. `RaceTimes.boatOrder` is read-only legacy.
- **The race page's working copy must be a deep copy** (`structuredClone` in `load()`).
  Sharing objects with `bundle` made edits invisible to `entrantsChanged()` and they
  were silently never saved. If an edit "does not stick", check that first.
- **The NOW log is deliberately not in the store** — a per-tab scratchpad for moving a
  time stamped against the wrong boat.

## The handicap: what to know before changing it

Read `PursuitHandicapEngine.processResults` and `minuteGiveback`. The tests that pin
the model are `MinuteGivebackTest`, `HandicapRulesTest` and `PursuitHandicapEngineTest`.

- **Whole minutes, both ways.** `penaltyList` is whole minutes (fractions are rounded
  on load). The pool comes back a minute at a time: each DNF, then the last boat home,
  the second last, … stopping short of the penalty places. A big night reads
  `+5 +4 +3 +2 +1 0 … 0 −1 … −1`.
- **Nobody who raced gets more than a minute back.** More DNFs than minutes share
  evenly; minutes the racers cannot take go evenly to the DNCs (which may exceed a
  minute, but only in a series too small for its ladder).
- **Nobody at home and minutes left ⇒ they are discarded**, and that race does not
  conserve (`withNobodyAtHomeTheLeftoverIsDiscarded`). It needs the whole entry list
  out and most of it retired.
- **DNF and RET differ**: DNF is served first, RET is frozen with DSQ/DNS/ABN. Do not
  re-merge.
- **Nobody may receive ⇒ nothing is charged.** The fix for a small series is a
  shorter `penaltyList` (about one rung per six boats), not the arithmetic.
- **§7 divides by the NEXT race's expected duration** (`nextRaceMinutes`), so a
  5-minute penalty moves the next start by 5 minutes while the median TCF holds. The
  last race of a series falls back to its own target. Editing the next race's target
  after saving does not reapply — unlock and process again. No median elapsed anywhere.
- **Casuals get a second pass** (`Competitor.seeded == false`). The top penalty can be
  awarded twice and the merged answer does not conserve — see
  `conservationHoldsPerPassNotAcrossTheMergedAnswer`.
- **`ABN` is a real `FinishStatus`**, which is what freezes an abandoned race.
- **Retired config keys still load** — `Algorithm` has its own
  `@JsonIgnoreProperties` because series overrides come back through the servlet's
  mapper. See the `Algorithm` javadoc for the list.

---

## Identity

IDs and boat matching follow [sailing-pf](https://github.com/gregw/sailing-pf), which
shares `aliases.yaml`. `SailingPfCompatibilityTest` runs sailing-pf's own assertions;
a failure there is a cross-project break.

| Entity | ID |
|---|---|
| Boat | `9-quicksilver-j24` — `{normSail}-{normName}-{designId}`, design omitted when unknown |
| Series | `myc.org.au/2026-winter-twilight` — minted from the name once; edits keep it |
| Race | `myc.org.au-2026-06-05-0001` |

The club domain scopes the last two; changing it orphans every id.

- `AUS1234`, `AUS01234` and `1234` are one boat; `- GM`/`- U18` suffixes are stripped;
  `Sticky`, `Sticky 2`, `Sticky II` collapse under one sail number.
- **A design is part of the boat's id.** When a design-less boat learns one,
  `JsonStore.rewriteBoatId` moves every reference. Miss one and a race is orphaned.
- **Two designs on one sail+name is a CONFLICT, never a guess.**
- **Designs are learned, never entered**; generic labels are in `design.yaml`'s
  ignored list.
- **`BoatRegistry.findOrCreate` is the only correct way to create a boat.**
  `JsonStore.putBoat` skips alias resolution and design learning.
- Learned aliases are written to `aliases.yaml` at once; an unreadable one is never
  overwritten.
- Creating a series whose minted id is taken answers **409** (`handleSaveSeries`).

---

## Data model

`JsonStore`'s javadoc has the store layout. The rules:

- **TCF, division and spinnaker belong to `Entrant`, never `Boat`.** Each race's
  entrant file carries the TCF it was sailed on, which is the handicap history. A boat
  joining gets 1.0. `Design.noSpinnaker` supplies a default NS; its absence means
  *unknown*, not S.
- **Two imports of the same sailing-pf file.** `POST /api/boats/import` takes identity
  only; `POST /api/races/{id}/entrants/import` also takes handicap → TCF and variant →
  spinnaker.
- **The race lifecycle is derived, never stored.** Locked iff it has saved
  adjustments; unlocking deletes them. `abandoned` is the one flag, set through its own
  endpoint. Abandon Race = flag every boat ABN, save, process; **Unlock results** is
  the way back from both.
- **Deleting a series deletes its races and their files**, not the boats. Audited with
  a null `raceId`; `audit.html` renders a dash for it.
- **Entry types**: `scoresHandicap()` is true for ROSTER and CASUAL; `seedsNextRace()`
  only for ROSTER. `EntryType.ROSTER` and `TcfSource.ROSTER` are legacy names kept
  because stored files say them — **there is no series roster**, deliberately.
- **Seeding is additive and runs itself**: the race page calls
  `/entrants/seed` once per page load when an admin is looking, there is no start sheet
  and the race is not locked. It seeds from the previous race only.
- **Save handicaps rewrites the next race's entrant list** from this race's
  season entrants at their new TCFs (`ApiServlet.carryForward`).
- TCFs are held to four decimals, half-up (`model/Tcf`).
- `targetElapsedMinutes` and `earliestStart` are the only per-race inputs. No course
  length. The sunset cap is applied when start times are computed, and refuses rather
  than capping to zero.

---

## Server API

The endpoint list is the `ApiServlet` class javadoc. `GET /api/races/{id}` returns
everything the race page needs in one call.

---

## Authentication

Off unless `data/config/auth.yaml` exists with `enabled: true`. Off, every request is
an admin and there is no session handler, security handler or outbound call.

| Tier | Who | May |
|---|---|---|
| `VIEWER` | anybody | read every page and every GET **except `/api/audit`** |
| `RACE_OFFICER` | a club-domain account | run a race night: times, start sheet, process, unlock, abandon, and edit an entrant's TCF, division or casual flag |
| `ADMIN` | listed in `admins:` — **or everyone, if the list is empty** | series, races, series config, the fleet register, and which boats are in a race |

- **Composing versus running.** `POST /api/races/{id}/entrants` takes the whole list,
  and the role is decided from the diff (`changesTheFleet`): same set of boat ids is a
  race officer's edit; a boat more or fewer needs an admin.
- **`denyUnless` answers 401 to a visitor and 403 to a signed-in non-admin**, and the
  page offers sign-in for exactly the 401. Every guard is
  `if (denyUnless(...)) return;`.
- **`JinxSecurityHandler` constrains only `/auth/login`** (`ANY_USER`); everything else
  is `ALLOWED`, and the tiers are enforced per operation in `ApiServlet`.
- **`AuthFilter` is the club-domain check.** Jetty's authenticator accepts any Google
  account. It checks the `hd` claim, not the request parameter, and 403s a non-club
  account (with a sign-out link) rather than demoting it.
- **The loopback exemption lives in `SignedIn` and must test `allowLoopback()`** —
  behind a reverse proxy every request is from 127.0.0.1.
- `OpenIdAuthenticator`'s third argument is the error page (`AuthFilter.ERROR_PATH`);
  `JinxServer.tokenExchangeClient` removes `WWWAuthenticationProtocolHandler` *after*
  `super.doStart()`. Both javadocs say why; each is pinned by an `AuthIntegrationTest`.
- Audit entries record the signed-in address; **null is a real answer** (no login).
- In the browser, `isAdmin()`/`canEdit()` and `data-requires="officer|admin"` with
  `applyRoleGates()` are UI hints only; the server refuses regardless. Controls are
  hidden, not disabled. `race.html` uses one read-only path: `readOnly()` is
  `locked || !canEdit()`.

### Deployment

https://myc.mortbay.org, on the Raspberry Pi beside sailing-pf. `etc/install.sh` seeds
config without ever overwriting it, excludes `data` from its rsync, and restarts only
a service that was already running. The unit waits on `network-online.target`
because OIDC discovery runs at startup.

- Google rejects plain `http://` redirect URIs, so it needs TLS in front.
- Behind that proxy `server.forwardedHeaders: true` is required; directly exposed it
  must stay false.
- One request-log line per request, logger `org.mortbay.sailing.jinx.requests`, no
  timestamp of its own; `server.requestLog: false` turns it off.

---

## Testing

```bash
mvn test                          # offline
node tools/run-scoring-test.mjs   # scoring.js spec, from scoring-test.html
node tools/check-scripts.mjs      # undefined names/ids and data-requires values in pages
```

Run both node tools after editing anything under `static/` — a mistyped name parses
fine and then throws at runtime, which in an `onchange` handler means a control that
silently does nothing. `JinxApiIntegrationTest` drives a season over HTTP and is the
place to start reading the workflow.

Write the failing test first.

---

## Further reading

+ [Project overview](wiki/Home.md)
+ [The Jinx handicap algorithm](wiki/Jinx-Handicaps.md)
+ [Race officer workflow](wiki/myc-ro-ui-storyboard.md)
+ [Decoupling plan](.claude/standalone-decoupling-plan.md) — why v2 looks like this
+ `wiki/sailsys-api-reference.md` — historical; describes an integration that no
  longer exists
