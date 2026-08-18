# sail-jinx

*Standalone race management for pursuit racing, with a punitive handicap on the side. Results may vary.*

---

## What this is

**sail-jinx** runs a pursuit series end to end: it holds the fleet, the series,
the races and the entrants; it computes each boat's staggered start time; it
captures times on the night; it works out the corrected finish order; and it
adjusts every boat's handicap for next week.

It has two layers:

- A **race-officer UI**. Enter the fleet once, build a season roster, add the
  race dates. On the night: seed the entrants, compute and print the start
  sheet, then capture came / actual-start / finish / flags as boats cross —
  with NOW buttons for live timing, drag-to-reorder, filters, and a duty boat.

- The **Jinx handicap algorithm**. After the race, fixed penalties are applied
  to the place-getters and the whole pool is redistributed across the fleet,
  weighted so slower boats get a larger share. The result is converted back
  into a TCF change and carried forward to the next race.

The originating use case is the **MYC Twilight Series** at
[Manly Yacht Club](https://myc.org.au), Sydney. Whilst a PHS-style algorithm
tries to be fair to this fleet, the sailors demanded a return to a more punitive
system that applies fixed penalties to the place-getters.

**Why "sail-jinx"?**
A jinx is bad luck visited upon the undeserving by forces beyond their control —
which is, almost universally, how sailors describe their handicap. sail-jinx
embraces that honestly: it runs your race-officer workflow, publishes your start
times, manages your pursuit handicaps, and makes sure someone different gets to
feel jinxed each week.

---

## How results reach SailSys

**They are typed in by a human.**

sail-jinx has no connection to SailSys or to any other system. It is not a
client of anything, it makes no outbound network calls, and it will run with the
network cable pulled out. Each race produces two printable reports:

| Report | Columns | When |
|---|---|---|
| **Start sheet** | Sail # · Boat · Offset · Start | Before the race, once start times are computed |
| **Finish sheet** | Pl · Sail # · Boat · Finish · Corrected · Flags | After the race |

Someone reads those and types them into SailSys. That transcription is the only
link between the two systems and it happens outside this software.

> Versions before 2.0 were a SailSys companion that read entrants and pushed
> results and handicaps back over the SailSys API. That implementation is
> preserved on the **`backed-by-sailsys`** branch. It is not maintained.

### About the Corrected column

`Corrected` is the boat's finish time with any head start from crossing early
given back — nothing else. It deliberately does **not** include the 5-minute OCS
penalty; that is implied by the OCS flag beside it and applied when the results
are entered. Folding it in here as well would apply it twice.

Internally the algorithm also computes a *scored finish* that does carry the
penalty, and that is what decides places. Two similar names, two jobs. Both are
pinned by tests so neither gets "fixed" to match the other.

---

## Running it

```bash
mvn exec:java                       # serves http://localhost:8080/ from ./data
mvn exec:java -Djinx-data=/path/to/data
mvn test                            # 89 tests, no network required
```

Configuration lives in `data/config/config.yaml` — the club name and timezone,
the handicap algorithm defaults, and the port. Everything else is entered
through the UI.

Open <http://localhost:8080/> and work through: **Boats** → **Series** →
roster → **Races**.

### There is no login

Every connection is treated as an administrator. The machine it runs on is the
security boundary, which is fine on a club office PC and **not** fine anywhere
with a network around it. See `currentRole()` in `ApiServlet` — adding
authentication is one method, and it must happen before this is hosted.

---

## Your data

Everything lives in `data/store/` as human-readable JSON, one file per entity.
No database. The dataset is small — one club, ~40 boats, ~20 races a year — and
being able to read and hand-edit it on race night is worth more than a schema.

**This is the only copy.** There is no longer a SailSys behind it to re-fetch
from, so the store defends itself:

- **Atomic writes** — every file is written to a `.tmp` and moved into place, so
  a crash can never leave a half-written file where a good one used to be.
- **A journal** — `data/store/journal/{yyyy-MM}.jsonl` records every mutation,
  which is the recovery path for what atomic writes cannot cover.
- **Defensive loading** — a corrupt file is reported in the UI and skipped,
  never allowed to stop the server starting.

None of that survives a dead laptop. **Copy `data/` somewhere else regularly** —
the whole directory is plain files, so rsync, a USB stick, or a cloud-synced
folder all work. Nothing automates this for you.

---

## What it is not

- Not a replacement for SailSys. SailSys remains the club's system of record for
  registration, series management and published results. sail-jinx manages one
  pursuit series and hands you paper.
- Not a time-on-time scoring engine. There is no elapsed-time correction in the
  pursuit model.
- Not multi-user. One person, one browser, one race night. Two people editing
  the same race will overwrite each other.
- Not a general entry system. It expects roughly forty regulars and the
  occasional casual.

---

## Further reading

- [Project overview](https://github.com/gregw/sail-jinx/wiki/Home)
- [The Jinx handicap algorithm](https://github.com/gregw/sail-jinx/wiki/Jinx-Handicaps)
- [`.claude/standalone-decoupling-plan.md`](.claude/standalone-decoupling-plan.md) — why v2 looks like this
