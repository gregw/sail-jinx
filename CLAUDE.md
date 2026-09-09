# sail-jinx

Please read [README.md](README.md) for a project overview.

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
An HTTP client here for anything else is still the regression.

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
- Authentication is **off by default** and on when `data/config/auth.yaml` says so.
  Off is right for one machine on one desk; on is required for anything reachable
  over a network. See "Authentication" below.

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

No database. No framework. The only HTTP client is Jetty's, pulled in by
`jetty-openid` and used for nothing but the identity provider — `pom.xml` says so
at the dependency.

---

## Layout

```txt
sail-jinx/
  data/config/config.yaml       # club, algorithm defaults, port
  data/config/auth.yaml         # OIDC client secret — GITIGNORED, absent means no login
  data/config/auth.yaml.example # …and the committed template for it
  data/config/aliases.yaml      # boat + design equivalences, seeded from sailing-pf
  data/config/design.yaml       # ignored/excluded designs, per-boat design overrides
  data/store/                   # THE ONLY COPY of everything (gitignored)
  data/archive/                 # pre-v2 SailSys-era store, kept for a future importer
  etc/sail-jinx.service         # systemd unit for the club Pi
  etc/install.sh                # installs it; safe to re-run for an upgrade
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

`static/scoring-test.html` is the executable specification for that module — 54
assertions. Open the page, **or run `node tools/run-scoring-test.mjs`**, which
executes the same assertions from that same file against a stub DOM. The runner
reads the page rather than copying it, so the two cannot drift: add a `check()` and
it runs in both.

**How a race is arranged is remembered per race, not per page.** `sail-jinx.raceViews`
in session storage is a map from race id to `{sort, filters, order}` — where `order` is
the manual row order a drag leaves behind — because a race officer
works across several races at once and wants each one differently — tonight's in start
order with the NOW buttons out, last week's in finishing order with them gone. One
setting for the page makes every switch between two races an argument about whose turn
it is.

The rules for a race nobody has arranged yet are `defaultRaceView` in `scoring.js`, not
in the page, so `scoring-test.html` pins them: **places beat a start sheet, a start sheet
beats nothing** (finishing order once there is one, otherwise the order the fleet goes
off in, otherwise by name); details hidden and nothing else filtered, since a boat hidden
by a default is a boat somebody forgets to look for; and **NOW hidden unless the race is
today**, because those buttons stamp the wall clock and on any other night they are the
one control on the page that can quietly write a wrong time. A view is stored only when
somebody changes something, so a race that has never been arranged keeps getting the
default as the race progresses.

**A drag is an arrangement, not an edit.** The manual order used to live in
`RaceTimes.boatOrder` and travel in `stateJson()`, so dragging a row marked the page
dirty and put a Save button in front of a change no boat had made. It is a view setting
now. The Java field survives as read-only legacy — old races still carry one and it
seeds a tab that has not dragged anything yet.

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
| **A** | `fixed` | **0.0** — the default |
| B | `fixed` | 1.0 |
| C | `perHour` | 0.0 |
| D | `perHour` | 1.0 |

`variant: A` is shorthand for setting both. Either knob may be set alone; an
explicit knob beats a variant that contradicts it, with a warning. `givebackGamma`
is continuous, so the letters are corners of a square, not a menu — 0.35 is a real
setting.

**The letters are gone from the settings screen**, at the committee's request: they
named combinations rather than explaining them, and a club reading "variant C" cannot
tell what it has chosen. The key is still read, so config files written against it keep
working; the form shows and sends the two knobs.

### The giveback comes back to the entry list, not to the starters

**This is the 2026 change and the one to understand.** The pool of penalty minutes is
shared over **everybody entered in the race**, by weights counted in *ordinary
finishers* — one boat that got round and was not on the penalty ladder:

| Who | Weight |
|---|---|
| a boat in a penalty place | `0` |
| any other finisher | `1 + γ·gap/maxGap` — just `1` at the default γ = 0 |
| DNF | the last finisher's weight + (`dnfWeight` − 1), i.e. + 0.2 |
| DNC | `dncWeight` (0.2) × (stayed home / entered) |
| RET, DSQ, DNS, ABN | `0` — frozen |

It used to go to the boats that *raced*, which divided a fixed pool by a varying fleet,
so a boat's gain went as **1/turnout**. Thirty out and the fifteen minutes spread thirty
ways; five out on a filthy night and the same fifteen came straight back to the five that
had just been charged them — the boat finishing **last of five** collected six minutes
against a one-minute penalty, a better handicap outcome than winning, while the
twenty-five at home did not move. `GivebackWeightsTest` is the executable spec.

Four things about it that look wrong and are not:

- **A penalty place draws nothing, at every γ.** The ladder is the club's statement of
  what a good result costs; handing part of it back in the same breath makes the printed
  5, 4, 3, 2, 1 a fiction. This also replaced γ = 1's one real job — stopping the winner
  drawing its own penalty back — and does it for *every* penalised place rather than only
  the one whose gap happened to be zero. **That is why the default moved from B to A.**
- **A non-starter's weight rises as the fleet empties.** `d/N` is the share of the entry
  list that stayed home. On a full night the two or three absentees are worth almost
  nothing; on a thin one most of the pool leaves the racing fleet, which is the point —
  five boats out of thirty all took a good result off a night the rest sat out. The
  product is bounded by one, so **absence is never worth more than racing**.
- **It is a de facto scaling of the penalties, deliberately placed here.** If the boats
  at home take a third of the pool then a five-minute penalty is worth 3:20 to the racing
  fleet — but the headline stays five minutes and nobody explains a fraction on a start
  sheet. Scaling `penaltyList` by turnout is the same arithmetic and unreadable: "first
  place, 0.83 minutes".
- **γ gives an extra boat for how far behind a boat was.** The weight is
  `1 + γ·gap/maxGap`, not the bare gap: one boat for getting round, up to one more for how
  far behind. `dnfWeight` and `dncWeight` do not scale with it, so turning γ up moves the
  pool towards the boats that got round. **These weights are deliberately not
  normalised** — a version that divided the finishers through by their own mean left them
  holding the same total weight at every γ, so the dial only reshuffled the pool among them
  and never shifted any of it their way. It survived the whole suite because every γ test
  compared two finishers with each other, and a ratio cannot see a factor common to both.
  Gap, not elapsed: the
  stagger makes `elapsed = gap + τ + constant` where τ depends only on rating, and τ
  spreads further than a night's finishing does. Anchoring at the leader is safe in
  *finish* terms — the first boat home is the minimum by definition — where the old
  elapsed anchor went negative whenever a slow-rated boat won.

**Nobody may receive ⇒ nothing is charged.** Every boat that raced on the ladder, no DNF,
nobody at home: `Σw = 0`, and the engine charges no penalty rather than keeping the pool.
That is a fleet no bigger than `penaltyList` — one boat alone, or a five-boat series on
`[5,4,3,2,1]`. **The answer is a shorter ladder, about one rung per six boats**, and the
series settings screen says what fleet a list suits. It is *series* size, not turnout: a
thirty-boat series keeps `[5,4,3,2,1]` on a six-boat night because the absentees absorb
the pool.

**DNF and RET are handicapped differently, and used to be identical.** DNF means the
boat was still racing when the race ended — it ran out of time, which is about its
speed, so its handicap eases. RET means it stopped for a reason of its own (gear,
injury, somewhere to be), which says nothing about its speed, so it is **frozen**
alongside DSQ/DNS/ABN and takes no part in the arithmetic. Easing a retirement's
handicap would reward a bad night with a better start, and a boat that retired often
would ratchet down the fleet without ever sailing a race. Both halves are pinned by
tests; do not re-merge the two cases.

A DNF draws the largest single share at **every** γ — it is scored at **what the last boat
home draws, plus `dnfWeight − 1`**, a constant 0.2 above the furthest-behind finisher.
Intended: a boat that could not get round in the time is the one whose handicap should ease
most.

**Additive, not multiplicative, and that is load-bearing.** Two readings were tried and each
broke one of two properties that pull against each other:

| reading | retirement always draws most | γ's bonus reaches the finishers |
|---|---|---|
| flat `1.2` | no — overtaken at about γ = 0.2 | yes |
| `1.2 × lastHome` | yes | no — their total goes flat |
| **`lastHome + 0.2`** | **yes** | **yes** |

All three are the same number at the club's γ = 0, so this only bites a club that turns the
dial up. `aRetirementOutdrawsEveryFinisherAtEveryGamma` and
`theProportionalWeightingMovesThePoolTowardsTheBoatsThatRaced` are the two tests, and each
rejected reading fails one of them — do not "simplify" this back.

**Said as a weight, because `dnfAllowance` said it as a distance and retired.** Scoring a
DNF at the last finisher plus so many minutes made what it actually drew depend on how
spread out the fleet was that night — five minutes was a nudge against a 90-minute race
and larger than the whole spread against a ten-minute finish, which is why the value had
to come down to 1 and why the right setting was never obvious. A weight has no scale in
it. Its other job was already dead: a DNF pays no penalty, so its elapsed never sized
anything. `aBoatThatRanOutOfTimeDrawsTheLargestShareAndThisIsHowLarge` pins it.

Three things it is easy to get wrong here:

1. **Nothing is measured against the fleet's median any more.** There was a
   `raceDuration` — the median of the elapsed times the fleet actually sailed — and it
   did two jobs. Both moved, at the committee's request, and it is gone:

   - **A `perHour` penalty is charged against the penalised boat's own elapsed time.**
     A boat out there for two hours has earned twice the penalty of one out for one,
     and the fleet's median said nothing about either of them.
   - **The §7 denominator is the race's *expected* duration** — `targetElapsedMinutes`,
     falling back to `defaultRaceDuration`. The number being computed is the handicap
     for the *next* race, and the next race is far likelier to run close to its expected
     duration than to the duration of the one just sailed. A night that overran because
     the breeze died should not shrink every correction the season makes.

   So `targetElapsedMinutes` now reaches `processResults`, where it deliberately did
   not before. `theExpectedDurationIsWhatAPenaltyIsMeasuredAgainst` and
   `aRaceWithNoTargetUsesTheConfiguredDefault` pin it.

2. **The old cancellation is gone, deliberately.** Penalty scaling and the §7
   denominator used to be the same quantity, so under `perHour` they cancelled and a
   45-minute night and a two-hour one gave the same correction. They are now different
   quantities and nothing cancels: a flat penalty gives the same correction whatever the
   night did, and a per-hour penalty gives a larger one on a longer night — which is
   what "per hour" means. Both are pinned; do not "fix" one back.
3. **Casuals are handicapped by a second pass.** `Competitor.seeded` is false for
   them, and `processResults` runs the algorithm twice: once over the series
   entrants alone, which is *their* answer, and once over everybody, from which
   only the *casuals'* answer is taken. A casual therefore gets a real TCF
   adjustment while being unable to shift anyone else's — including the size of
   their penalties, since a per-hour penalty is charged against the penalised boat's
   own elapsed and never against anybody else's.

   Two consequences that look like bugs and are not:
   - **When a casual wins, the top penalty is awarded twice** — to the casual and
     to the first series boat home. They won two different races.
   - **The merged result does not conserve.** Each pass redistributes its own pool
     in full, so `Σ net = 0` holds across the series entrants; the casuals' share
     comes from a race the series boats were not scored on. Making the totals add
     up would mean feeding the casual's residue back into the series fleet, which
     is the exact thing the two passes prevent.
     `conservationHoldsPerPassNotAcrossTheMergedAnswer` pins this.

   `scoring.js handicapEngineInput` decides who is seeded
   (`seeded: entryType === 'ROSTER'`), which keeps `Entrant.scoresHandicap()`
   honest: a casual still scores a handicap, just not in the series' race.

**Retirements never join the measured duration.** They draw from the pool because they
sailed, but their elapsed time is an *allowance* rather than a measurement, and a hard
night is exactly when there are enough of them to drag the median up. This was a
`dnfInRaceDuration` setting; the committee removed it, because the other position was
indefensible and a setting with one defensible value is not a setting. Old config files
carrying the key still load — unknown properties are ignored.

**`givebackFleet` retired too.** It aimed the pool at the back of the fleet as a share
counted by finish gap. Weight zero on the penalty places is that idea stated exactly
rather than as a fraction, and the pool now reaches boats with no finish gap to be counted
by, so the setting has no well-defined meaning. Old `config.yaml` and saved
`series-config/*.json` carrying either retired key still load — `Algorithm` carries its
own `@JsonIgnoreProperties`, **not** just the YAML mapper's setting, because a series
override comes back through the servlet's mapper, which does not disable the check.

### The corrected/scored distinction

Two numbers with similar names and different jobs:

- `correctedFinishSeconds` — finish **plus the head start given back only**.
  This is the report column a human transcribes, alongside the OCS flag.
- `scoredFinishSeconds` — also carries the 5-minute OCS penalty. This decides
  places and feeds the engine.

Both are tested. Do not "fix" one to match the other.

The finish sheet prints them side by side deliberately: **Corrected Finish** is the
transcribed number, without the penalty, and **Scored Elapsed** is beside it *with*
the penalty. `scoredElapsedSeconds` is that second one, and `places('scratch')` —
which ranks by it — is what the sheet's Elapsed Place column uses.

Rankings all go through `rankBy`, so the sailing tie convention — ties share the
better place, the next distinct key jumps past them — has one implementation.
`places` and `latePlaces` differ only in their key and in who they leave out.
**`latePlaces` leaves out two kinds of boat on purpose**: an OCS boat, because
crossing early is a penalty rather than the best possible start, and a boat with no
captured actual start, because `lateSeconds` falls back to the allocated gun and
would otherwise report a confident `0:00` for a boat nobody timed. It is the Start
Place column on the sheet.

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

**A series id is minted from its name, so two series cannot share one.** Creating a
second series called the same thing would mint the same id and land on top of the first,
inheriting its races and — since the create form sends `archived: false` — quietly
unarchiving it. `handleSaveSeries` answers **409** when the minted id is already taken,
and says so differently when the clash is archived, because that is the case that bites:
the series is not on screen, so the name looks free. The check is on *creating* only — an
edit carries the id it was given, so re-saving a series under its own name is not a
clash. Two *different* series with the same display name are allowed; they have different
ids and adopt nothing from each other.

Things that catch people out:

- **`AUS1234`, `AUS01234` and `1234` are one boat**, and the bare form wins. The
  country prefix and leading zeros are normalisation, not identity.
- **A design is part of the boat's ID.** A boat imported without one is
  `A123-slowpoke`; when a later import supplies the design it is *upgraded* to
  `A123-slowpoke-sydney38` and `JsonStore.rewriteBoatId` moves every reference —
  entrants, captured times, start sheets, adjustments. Miss one and a race is
  orphaned.
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
| `entrants/{raceId}.json` | who is in this race **and the TCF it was sailed on** |
| `start-sheet/{raceId}.json` | the published stagger |
| `race-times/{raceId}.json` | came / actual start / finish as typed, **and the flags the RO overrode** |
| `adjustments/{raceId}.json` | saved handicap output — **also the race lock** |
| `audit.json`, `journal/` | history — **and who did it**, when there is a login |

### What belongs to a boat, and what does not

**TCF, division and spinnaker are not properties of a boat.** A boat does not have
a handicap — it has one *for a given race*, and a different one the week after.
It can sail one season in Division 1 and the next in Division 2, and enter one
series with a kite and one without. **`Entrant` is the only place they live**;
there is no series-level copy, which is what the roster used to be.

| Lives on | Holds |
|---|---|
| `Boat` | sail number, name, design, active, casual, notes |
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
   there should not be one — the v1 field was sticky and lied. `abandoned` is the
   one exception and is not a lifecycle state: it is a judgement made on the water
   that no captured time can imply, so it has `POST /api/races/{id}/abandon` — its
   own endpoint rather than a field on the race editor, which fills anything the
   body omits from the series defaults and would quietly give a cancelled race a
   different name or first gun.

3. **Deleting a series deletes its races, and there is no undo.** `JsonStore.deleteSeries`
   takes the series row, every race in it, and every file hanging off those races —
   entrant lists, start sheets, captured times, adjustments — plus the series config
   override. Leaving those behind would litter the store with data no page can reach: a
   race id embeds the date and a sequence number, so no future race will ever mint the
   same one and adopt them. **The fleet register is untouched** — boats belong to the
   club, not to a season. It is `ADMIN`, it is audited, and `Archive` is the reversible
   option sitting next to it in the UI.

   The audit entry it writes **names no race**, because the races are gone and their ids
   will never be minted again; the series id and the race count go in `notes` instead.
   `audit.html` renders a dash rather than a link for an entry with a null `raceId` —
   without that guard the row showed a dead link labelled `null`.

4. **Abandoning a race is a way of processing it.** The Abandon Race button flags
   every boat `ABN`, saves, and processes — so an abandoned race is locked like any
   other scored race, and **Unlock results is the way back from both**. There is no
   separate un-abandon button: it would be a second door into the same room, and the
   label for it never stopped being confusing. Unlocking strips the `ABN` flags, and
   the derived ones — DNC, DNS, DNF, OCS — come back out of the came box and the
   times, which are kept throughout. A squall is no reason to throw away times
   somebody stood there and wrote down.

   `FinishStatus.ABN` is a real status, not a display-only flag, and that is what
   makes an abandoned race change nobody's handicap: the engine freezes everything it
   does not recognise as finishing or running out of time, so `ABN` lands in the
   frozen bucket by construction. A flag the browser knew about and the engine did
   not would have scored an abandoned race normally — including making whichever boat
   happened to be round first its winner.
   `anAbandonedRaceLeavesEveryHandicapExactlyWhereItWas` pins it.

5. **Flags the RO set by hand are stored; flags the times imply are not.** Most
   flags are derived — DNC, DNS, DNF, OCS all follow from came/started/finished, and
   deriving them is right, because a stored one would go stale the moment a time was
   corrected. What cannot be derived is a judgement that *contradicts* the times, and
   `RaceTimes.BoatTimes.flags` holds exactly that, as added/removed rather than one
   effective list — a derived flag has to be clearable, and one list cannot say "not
   this one". The case it exists for is **RET against a boat that never finished**:
   the times say DNF, which eases a handicap, where RET freezes it. `scoring.js`
   already suppressed the derived flag correctly; what was missing was anywhere to
   keep the RO's answer, so it lived in the browser tab and `sessionClear()` on
   processing turned every RET back into a DNF.

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

`EntryType.ROSTER` means "in for the season" and is **not** a reference to the
series roster, which is gone. The name is stored in every entrant file, so it stays.

**Seeding is additive, and it runs itself.** `POST /api/races/{id}/entrants/seed` adds
the boats missing from this race and leaves the ones already in it exactly as they are —
a TCF somebody typed by hand is a decision, and re-seeding over it would quietly undo
it. It used to refuse outright once a race had anybody in it, which left it useful for
one moment in a race's life and made "a boat joined the series" a per-race chore.

**It seeds from the previous race, and nothing else.** The first race of a series has
nothing to carry forward, so it seeds nothing and says `added: 0` — a success, not an
error, because the race page calls it unprompted. Its fleet is entered directly: the
add-a-boat form, or `POST /api/races/{id}/entrants/import`, which brings TCFs with it.

**There is no series roster, deliberately.** There was one — `roster/{seriesId}.json`,
a page, and two endpoints — holding a starting TCF, division and spinnaker per boat per
season. It was a second place to record the same fleet, and the facts it held are
per-race ones: a boat's handicap changes every week, so a "starting TCF" was only ever
true for race 1, and it drifted out of agreement with the entrant lists that were doing
the real work. Re-adding it would reintroduce that disagreement. `TcfSource.ROSTER`
survives as a legacy enum constant because the club's early entrant files say it, and
`anEntrantListWrittenWhenTheRosterExistedStillLoads` pins that they still load.

The race page runs it on the **first view of the page**, when an admin is looking, the
start sheet is not published, and the results are not locked. Once per page load, not
once per `load()` — that runs again after every save and every process, and re-seeding
there would put back a boat somebody had just deliberately removed. It replaced a Seed
entrants button that did nothing visible when there was nothing to seed from: seeding
zero boats onto zero boats looks exactly like a dead button.

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
| POST | `/api/races/{id}/entrants/seed` | add the boats this race is missing, from the previous race |
| POST | `/api/races/{id}/abandon` | call a race off, or put it back on |
| POST | `/api/races/{id}/start-times` | compute and publish the stagger (applies the sunset cap) |
| POST | `/api/races/{id}/process-handicaps` | run the engine (computes, saves nothing) |
| POST | `/api/races/{id}/save-handicaps` | save, and carry TCFs to the next race |
| DELETE | `/api/races/{id}/adjustments` | unlock for reprocessing |
| DELETE | `/api/series/{id}` | delete a series **and every race in it** |

---

## Authentication

Off unless `data/config/auth.yaml` exists and says `enabled: true`. With it off,
every request is an admin and there is no session handler, no security handler and
no outbound call — exactly what the server did before a login existed.

**With it on, a login is an invitation rather than a gate.** Three tiers:

| Tier | Who | May |
|---|---|---|
| `VIEWER` | anybody, signed in or not | read every page and every GET **except `/api/audit`** |
| `RACE_OFFICER` | a club-domain account | run a race night: times, start sheet, handicaps, unlock, and editing an entrant's TCF, division or casual flag |
| `ADMIN` | listed in `admins:` | series (**including deleting one and its races**), races, series config, the fleet register, and which boats are in a race at all |

**The audit log is the one exception to "every GET answers anybody".** It is not a
result: it records who changed what, and since it started naming them, publishing it
is a different decision from publishing the racing. `GET /api/audit` needs an admin,
the nav link carries `data-requires="admin"`, and the page itself says which of the
two things is wrong — not signed in, or signed in and not an admin — because "sign
in" answers one of them and nothing at all for the other.

The split between the last two is **composing versus running**. Deciding that there
is a race, who is in the series, and which boats tonight's race is scored over is the
admin's. Recording what those boats did — including processing the handicaps, which
is what running a race *is* — is the race officer's.

**That line cuts through one endpoint rather than between two.**
`POST /api/races/{id}/entrants` sends the whole list, deliberately, so that add,
remove and TCF edit cannot be separated on the way in. So the role is decided from
the **diff**: the same set of boat ids is an edit and needs a race officer; a boat
more or fewer is a different race and needs an admin. `changesTheFleet` compares as a
set, so reordering — which the race page's manual ordering saves through here — is an
edit, not a composition change. One-offs have no id and are counted instead.

Three consequences worth knowing:

- **`ApiServlet.denyUnless` answers 401 to a visitor and 403 to a race officer.**
  The codes are the difference between "sign in and this will work" and "signing in
  will not help", and the page puts a sign-in button in front of exactly one of them.
- **`JinxSecurityHandler` constrains one path.** Everything is `Constraint.ALLOWED`
  except `/auth/login`, which is `ANY_USER` — with nothing constrained, nothing would
  ever trigger the OIDC dance and the sign-in link would have nowhere to point.
  `AuthFilter` sends the browser back to `/` once the login lands.
- **Signing in with a non-club Google account costs you the read access you had.**
  `AuthFilter` 403s it, with a sign-out link, rather than silently demoting it to
  viewer — being told the account is wrong beats a page that mysteriously does
  nothing.

**`auth.yaml` is gitignored; `auth.yaml.example` beside it is committed.** The
example documents the Google Cloud console setup and must never carry a real
secret. If one is ever pushed, revoking the client is the fix — rewriting history
is not, because the value is public the moment it reaches a remote.

The pieces, and why each exists:

| Piece | Job |
|---|---|
| `AuthConfig` | loads `auth.yaml`; **throws** rather than starting half-configured |
| `JinxSecurityHandler` | `Constraint.ANY_USER` for every path, plus the loopback exemption |
| `OpenIdAuthenticator` | Jetty's; does the OIDC dance and puts the claims on the session |
| `AuthFilter` | **the club-domain check** — runs after login, 403s anyone else |
| `SignedIn` | reads the claims back off the session |
| `ApiServlet.currentRole` | admin vs race officer, from `admins:` |

Six things that are easy to get wrong:

1. **Jetty's authenticator alone is not access control.** It establishes that
   Google knows who you are — *any* Google account, personal Gmail included.
   `AuthFilter` is what restricts it to the club. Remove it and "sign in with
   Google" becomes "sign in with anything".
2. **The `hd` claim is the check; the `hd` request parameter is not.** The
   parameter is a hint to Google's account chooser and a client can ignore it.
   `AuthConfig.permits` checks the claim that comes back, and falls back to the
   address suffix.
3. **`allowLoopback` is dangerous behind a reverse proxy.** Nginx or a load
   balancer connects from 127.0.0.1, so the exemption would cover the whole
   internet. It is off by default and exists for one case: the club PC with the
   browser on the same machine, so a race night survives an internet outage —
   with auth on, the server needs Google reachable at startup and at every login.
   **The check lives in `SignedIn`, and it must test `auth.allowLoopback()`, not
   just the address.** It did not, once. That was survivable only while every path
   required a login, because an unauthenticated request never reached the servlet;
   the moment anonymous reads were allowed it would have made every visitor on the
   Pi an administrator. `loopbackIsAnAnonymousVisitorUnlessAllowLoopbackSaysOtherwise`
   pins it.
4. **A guard must stop the handler.** `denyIfNotAdmin` returns a boolean and every
   caller does `if (denyIfNotAdmin(req, resp)) return;`, matching `rejectIfLocked`.
   Its predecessor wrote a 403 and returned void, so the caller did the work
   anyway — invisible while everyone was an admin.
5. **`OpenIdAuthenticator`'s third argument is the error page**, and the fourth is
   the post-logout path. Passing `null` for the error page is Jetty's signal to
   answer a failed callback with a **bare 403 and no body** — which is what a wrong
   client secret, an expired code, a session lost to a restart and a stale browser
   tab all look like from the browser. `AuthFilter.ERROR_PATH` renders the reason
   Jetty puts in the query and logs it at WARN, so the diagnosis is in
   `journalctl` as well as on screen. `aFailedCallbackSaysWhyInsteadOfABare403`
   pins it.
6. **The token exchange needs an `HttpClient` with `WWWAuthenticationProtocolHandler`
   removed.** A refused client secret comes back from Google as *401 with a JSON body
   saying `invalid_client` and no `WWW-Authenticate` header* — a refusal, not a
   challenge. Jetty's client sees the 401, looks for the header it implies, and fails
   the exchange with "HTTP protocol violation: Authentication challenge without
   WWW-Authenticate header", **discarding the body that named the cause**. So the most
   likely setup mistake reports itself as a transport fault. `JinxServer.tokenExchangeClient`
   drops that handler — safe, since this client talks only to the token endpoint, which
   authenticates by form parameters and never challenges. **Remove it after
   `super.doStart()`**: `HttpClient` installs its default handlers as it starts, so a
   removal in the constructor is undone before the first request.
   `aRefusedClientSecretSaysSoRatherThanBlamingTheProtocol` pins it.

Saving handicaps and unlocking a race write an `AuditEntry` carrying the signed-in
address. **A null user is a real answer**, not a gap: it means the entry was written
with no login configured — the single-machine deployment, where every request is an
admin and there is nobody to name. The `allowLoopback` exemption records null for the
same reason: that request is an admin by configuration, not by identity. Writing
`"local"` there would be a lie on a networked server running with auth off, which is a
configuration that exists. `anAuditLogWrittenBeforeItRecordedTheUserStillLoads` pins
that an `audit.json` from before the field still loads — the club's copy is the only
one there is.

`isAdmin()` and `canEdit()` in the browser are a **UI hint only**, so buttons match
what they will do. The server checks the same thing and refuses.

Controls carry `data-requires="officer"` or `data-requires="admin"`, and
`common.js applyRoleGates()` hides the ones this caller may not use — hidden rather
than greyed out, because a disabled button invites a visitor to wonder what they are
missing. Pages call it again after rendering table rows, which are built long after
page load. `tools/check-scripts.mjs` rejects a `data-requires` value it does not
know, since an unrecognised one would fall through to the weakest tier and quietly
offer an admin's button to everybody.

`race.html` does not gate cell by cell: `readOnly()` is `locked || !canEdit()`, so a
visitor sees a race exactly as a locked one is seen. One read-only rendering path,
not two that can drift.

### Deployment

The first hosted install is **https://myc.mortbay.org**, on the same Raspberry Pi
as sailing-pf. `etc/install.sh` mirrors sailing-pf's: a system user, the source in
`/opt/sail-jinx`, the data in `/var/lib/sail-jinx`, Maven `exec:java` under
systemd.

Two differences from sailing-pf's installer, both deliberate:

- It **seeds config and never overwrites it**, so `git pull && sudo etc/install.sh`
  cannot revert the club's settings, its learned aliases or its OAuth client. The
  rsync excludes `data` entirely — `--delete` across it would take the store.
- It **restarts the service only if it was already running**, checked before anything
  is touched — after `systemctl enable` the unit would look active either way. A first
  install is left stopped, since the operator still has `auth.yaml` to fill in. A
  restart that does not come back exits non-zero rather than printing "complete".
- The unit waits on `network-online.target`, not `network.target`. With
  authentication on, the OIDC discovery call happens during startup.

The server writes **one request-log line per request** to the same journal as
everything else — `journalctl -u sail-jinx` — under its own logger,
`org.mortbay.sailing.jinx.requests`, so `requests.LEVEL=WARN` in
`jetty-logging.properties` silences it without silencing the app. `requestLog: false`
under `server:` turns it off outright. It carries NCSA's fields but **not NCSA's
timestamp**: the line already passes through the logging implementation's stamp and
journald's, and a third is noise. It is on by default because the first question when
anything in front of this server misbehaves — proxy, sign-in redirect — is whether the
request arrived at all, and without it there was nothing that answered that.

Two things that will bite on that deployment specifically:

1. **Google rejects a plain `http://` redirect URI** for a web application —
   `http://localhost` is the only exception. So it needs TLS in front of it before
   sign-in works at all.
2. **`forwardedHeaders: true` is required behind that proxy**, or the
   `redirect_uri` is built from the server's own address and Google is sent to
   `http://localhost:8080/auth/callback`. It must stay `false` when the server is
   directly exposed, since the headers are attacker-controlled there.

---

## Testing

```bash
mvn test        # 264 tests, offline
```

- `JsonStoreTest` — round-trips, atomicity, journalling, corrupt-file recovery.
- `JinxApiIntegrationTest` — boots the real server on an ephemeral port and
  drives a season over HTTP with the JDK client. Start here to understand the
  workflow.
- `PursuitHandicapEngineTest` — executable spec for the algorithm, mapped to
  wiki sections.
- `GivebackWeightsTest` — the giveback as weights over the entry list, including
  two rows checked straight off the committee's spreadsheet. If a change moves those
  numbers, the model is what has to move first.
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
