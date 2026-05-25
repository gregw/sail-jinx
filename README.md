# sail-jinx

*Handicap management for pursuit race series. Results may vary. 

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