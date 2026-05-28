# sail-jinx

*Race-officer companion UI for SailSys, with a punitive pursuit handicap on the side. Results may vary.*

---

## What this is

**sail-jinx** is a companion application to [SailSys](sailsys.com.au) that provides an
alternate race-processing UI. It has two layers:

- A **general SailSys companion** for race officers. Lists series and races,
  presents an entrants table with came / actual-start / finish / flags
  capture, drag-to-reorder, NOW-button live timing, hide-DNC filter,
  duty-boat selection, drives the SailSys results publish/process workflow.
  Works for every series at the club, of any race type, scored against any
  handicap definition — what's editable adapts to the user's permissions
  and the series / race type.

- The **Jinx handicap algorithm** for the MYC Twilight pursuit series. When
  a race belongs to a series scored against the configured handicap
  definition (MYC TCF at MYC), the page also offers TCF editing and a
  Process Handicaps step that will back-calculate the next race's TCFs and
  push them to SailSys. The algorithm itself avoids "poisoning" the common
  PHS handicap pool with punitive PHS settings.

The primary use case is the **MYC Twilight Series** at [Manly Yacht Club](myc.org.au),
Sydney. Whilst SailSys valiantly tries to apply a fair PHS-style handicapping
algorithm to this fleet, the sailors have demanded a return to a more punitive
system that applies fixed penalties to the place getters. Rather than poison
the common handicap pool with punitive PHS settings, this system takes those
handicaps out and handles them separately.

### Behaviour by series / race type

| Series default handicap | Race type | TCF column | Process Handicaps | Per-entrant Start | Per-division Start |
|---|---|---|---|---|---|
| Configured handicap (e.g. MYC TCF) | Pursuit (raceType=0) | Editable (admin) | Available (admin) | Yes | — |
| Configured handicap | Non-pursuit | Editable (admin) | Available (admin) | — | Yes |
| Other handicap | Pursuit | View-only | — | Yes | — |
| Other handicap | Non-pursuit | View-only | — | — | Yes |

In all cases the race-results workflow (came / finish / flags / results
visibility) is editable, subject to the user being signed in as a club
admin or race officer.

**Why "SailJinx"?**
A jinx is bad luck visited upon the undeserving by forces beyond their control —
which is, almost universally, how sailors describe their handicap. SailJinx
embraces that honestly: it runs your race-officer workflow, publishes your
start times, manages your pursuit handicaps, and makes sure someone different
gets to feel jinxed each week.

---

## What it is not

- It is not a replacement for SailSys. SailSys remains the system of record for
  boat registration, series management, and race administration. sail-jinx
  reads from SailSys and writes results / TCFs back to it.
- It is not a time-on-time scoring engine. There is no elapsed-time correction
  in the pursuit model.
- It is not a general results management system *for handicap algorithms other
  than the configured one*. For other series it offers the SailSys results
  workflow with the configured handicap pinned as view-only.

---