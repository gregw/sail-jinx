# sail-jinx

*Handicap management for pursuit race series. Results may vary. Complaints welcome.*

---

## What this is

**sail-jinx** is a companion application to [SailSys](sailsys.com.au) that provides an alternate race processing UI that:

 + Provides an alternative handicap algorithm to be applied.
 + Avoids "poisoning" the common PHS handicap pool via punative PHS settings.
 + Allows start times to be captured.

The primary use case is the **MYC Twilight Series** at [Manly Yacht Club](myc.org.au), Sydney. Whilst SailSys valiantly tries to apply a fair PHS style handicapping algorithm to this fleet, the sailors have demanded a return to a more punitive system that applies fixed penalties to the place getters.

Rather than poison the common handicap pool with punitive PHS setting, this system takes these handicaps out and handles them separately.

**Why "SailJinx"?**
A jinx is bad luck visited upon the undeserving by forces beyond their control - which is, almost universally, how sailors describe their handicap. SailJinx embraces that honestly: it manages your pursuit race handicaps, publishes your start times, and makes sure someone different gets to feel jinxed each week.

---

## What it is not

- It is not a replacement for SailSys. SailSys remains the system of record for boat registration, series management, and race administration. sail-jinx reads boat and series data from SailSys and writes TCFs back to it.
- It is not a time-on-time scoring engine. There is no post-race elapsed-time correction in the pursuit model.
- It is not a general results management system. It manages one thing: handicap TCFs and the pursuit start times that flow from them.

---

## Scope and extensibility

The immediate scope is the MYC Twilight pursuit handicap. However the architecture is intentionally general:

- The `HandicapEngine` interface is designed to be implemented by multiple algorithms. The pursuit algorithm is the first implementation.
- Other algorithms (e.g. a pure PHS/TCF pass-through, or a different reward distribution) can be added without changing the server, persistence, or SailSys integration layers.
- The series and club identifiers are configuration, not code. Another club running a pursuit series on SailSys could point sail-jinx at their own series with a different `config.yaml`.

The name reflects this: it is not called `myc-twilight` because it should be useful beyond that context.

---

## Technology stack

| Concern | Choice |
|---|---|
| Language | Java 21 |
| HTTP server | Jetty (embedded) |
| HTTP client | Jetty HttpClient (for SailSys API calls) |
| Front end | Plain HTML + JavaScript, served as static resources |
| Configuration | YAML (`config.yaml`) via SnakeYAML |
| Persistence | JSON files on disk via Jackson |
| Build | Maven |
| Target runtime | Local Linux or Windows JVM; designed to be deployable to Google App Engine standard (Java 21) or similar PaaS |

No database. No framework. No Google Sheets. No Apps Script. Modelled on the sailing-pf project (https://github.com/gregw/sailing-pf) which uses the same Jetty-based stack and SailSys integration pattern.

---

## Project structure

```txt
sail-jinx/
  CLAUDE.md                       # this file
  data/config/
    *.yaml                        # local config: credentials, series IDs, algorithm params
  data/store/
    *.json                        # persisted data (that cannot be stored in sailsys)  
  wiki/                           # cross link to wiki project
    Home.md                       # project overview
    myc-twilight-handicap-v2.md   # algorithm specification
    sailsys-api-reference.md      # reverse-engineered SailSys REST API reference
    myc-ro-ui-storyboard.md       # race officer UI storyboard
  src/
    main/java/org/mortbay/sailing/jinx/
      model/*.java                # Data model  
      store/*.java                # JSON persistence
      server/*.java               # The Server
      sailsys/*.java              # Sailsys integration
      pursuit/*.java              # The jinx handicap
    main/resources/
      static/                     # HTML, CSS, JS front end
  pom.xml
```

---

## Server API

The Jetty server exposes a small REST API consumed by the HTML front end. All endpoints return JSON.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/boats` | List fleet with current TCFs |
| GET | `/api/races` | List races with status |
| GET | `/api/races/{id}/startTimes` | Compute and return start sheet for a race |
| POST | `/api/races/{id}/results` | Submit finish order, elapsed times, and statuses |
| POST | `/api/races/{id}/process` | Run post-race TCF update, write audit entry |
| POST | `/api/races/{id}/push` | Push updated TCFs to SailSys (`?dryRun=true` supported) |
| GET | `/api/audit` | Full audit log |

---

## Further reading

 + [Project Overview](wiki/Home.md)
 + [The Jinx Handicap Algorithm](wiki/myc-twilight-handicap-v2.md)
 + [Sailsys integration](wiki/sailsys-api-reference.md)
 + [UI plan](wiki/myc-ro-ui-storyboard.md)