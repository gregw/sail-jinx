// Canonical scoring primitives — wiki/Jinx-Handicaps.md §5.1.
//
// This is the one implementation. The race page and the corrected-finish
// report both build a scorer from it, so the places the RO sees on screen and
// the places printed on the sheet that gets transcribed into SailSys cannot
// disagree.
//
// Everything works in whole seconds since midnight. The RO captures wall-clock
// times by hand and sub-second precision is not meaningful here; rounding to
// the second also means the value used for place sorting matches the HH:MM:SS
// that gets displayed, so two boats showing the same time can never be placed
// differently.
//
// Order of declaration mirrors the formula sheet: each step depends only on
// earlier ones. There is no circular reference because `effectiveStart` reads
// only the external OCS override, never the derived OCS.

const OCS_PENALTY_SECONDS = 5 * 60;

// Flags in display order. Anything else a caller supplies is preserved and
// shown after these, but is not offered as a checkbox.
const KNOWN_FLAGS = ['DNC', 'DNS', 'DNF', 'DSQ', 'AVG', 'OCS', 'RET', 'ABD'];

// Flags that mean "this boat has classified itself" — their presence stops the
// automatic came/finish inspection from adding one of its own.
const STATUS_FLAGS = ['DNC', 'DNS', 'DNF', 'DSQ', 'RET', 'ABD', 'AVG'];

// Flags that take a boat out of the placings entirely.
const UNPLACED_FLAGS = ['AVG', 'DNC', 'DNS', 'DNF', 'DSQ', 'RET', 'ABD'];

// --- time helpers ----------------------------------------------------------

// Accepts HH:MM and HH:MM:SS, and tolerates a legacy sub-second suffix.
function parseTime(s) {
  if (!s) return null;
  const m = String(s).trim().match(/^(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\.\d{1,3})?$/);
  if (!m) return null;
  return (Number(m[1]) * 60 + Number(m[2])) * 60 + (m[3] ? Number(m[3]) : 0);
}

function fmtElapsed(secs) {
  if (secs == null || !Number.isFinite(secs) || secs < 0) return '';
  const pad = (n) => String(n).padStart(2, '0');
  return pad(Math.floor(secs / 3600)) + ':'
    + pad(Math.floor((secs % 3600) / 60)) + ':'
    + pad(Math.floor(secs % 60));
}

// Time-of-day render. Wraps at 24h so a value past midnight still reads sanely.
function fmtTimeOfDay(secs) {
  if (secs == null || !Number.isFinite(secs)) return '';
  const total = Math.floor(((secs % 86400) + 86400) % 86400);
  const pad = (n) => String(n).padStart(2, '0');
  return pad(Math.floor(total / 3600)) + ':'
    + pad(Math.floor((total % 3600) / 60)) + ':' + pad(total % 60);
}

// Signed minutes as "+M:SS" / "-M:SS". Zero renders with a leading space so
// signed and unsigned values stay vertically aligned in a monospace column.
function fmtSignedMinSec(mins) {
  if (mins == null || isNaN(mins)) return '';
  const sign = mins > 0.0005 ? '+' : (mins < -0.0005 ? '-' : ' ');
  const abs = Math.abs(mins);
  let min = Math.floor(abs);
  let sec = Math.round((abs - min) * 60);
  if (sec === 60) { min += 1; sec = 0; }               // 59.5s rounds up to a minute
  return sign + min + ':' + String(sec).padStart(2, '0');
}

function localNow() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
}

// Normalise a user-typed time to HH:MM:SS. Tolerant of "H:M", a trailing
// colon, a single-digit second, and a legacy millis suffix. Returns null when
// it still does not parse, so the caller can leave the raw text for the user
// to fix rather than silently discarding what they typed.
function reformatTime(s) {
  if (!s) return null;
  const m = String(s).trim().match(/^(\d{1,2}):(\d{1,2})(?::(\d{0,2}))?(?:\.\d{1,3})?$/);
  if (!m) return null;
  const h = Number(m[1]);
  const mi = Number(m[2]);
  const ss = (m[3] != null && m[3] !== '') ? Number(m[3]) : 0;
  if (h > 23 || mi > 59 || ss > 59) return null;
  const pad = (n) => String(n).padStart(2, '0');
  return pad(h) + ':' + pad(mi) + ':' + pad(ss);
}

// Same, to minute granularity — for the earliest-start input.
function reformatHHMM(s) {
  if (!s) return null;
  const m = String(s).trim().match(/^(\d{1,2}):(\d{1,2})(?::\d{0,2})?(?:\.\d{1,3})?$/);
  if (!m) return null;
  const h = Number(m[1]);
  const mi = Number(m[2]);
  if (h > 23 || mi > 59) return null;
  return String(h).padStart(2, '0') + ':' + String(mi).padStart(2, '0');
}

// TCFs are held and displayed to four decimals — see model/Tcf.java. Formatted
// here too so a value read off the screen matches the one in the store.
function fmtTcf(v) {
  return (typeof v === 'number' && Number.isFinite(v)) ? v.toFixed(4) : '';
}

// --- the scorer ------------------------------------------------------------

/**
 * Build a scorer over one race.
 *
 * state:
 *   entrants      [{boatId, sailNumber, name, tcf, entryType}]
 *   startSheet    {earliestStart, starts:[{boatId, startTime}]} or null
 *   times         {boatId: {came, actualStart, finish}}
 *   dutyBoatId    boatId of the duty boat, or null
 *   flagOverrides Map boatId -> {added:[], removed:[]}
 *   tcfOverrides  Map boatId -> Number (unsaved edits)
 */
function createScorer(state) {
  const entrants = state.entrants || [];
  const times = state.times || {};
  const dutyBoatId = state.dutyBoatId == null ? null : String(state.dutyBoatId);
  const flagOverrides = state.flagOverrides || new Map();
  const tcfOverrides = state.tcfOverrides || new Map();

  // boatId -> allocated start (seconds), from the published start sheet.
  const allocated = new Map();
  if (state.startSheet && Array.isArray(state.startSheet.starts)) {
    for (const s of state.startSheet.starts) {
      const t = parseTime(s.startTime);
      if (t != null) allocated.set(String(s.boatId), t);
    }
  }

  const key = (e) => String(e && e.boatId);

  function timesFor(e) {
    return times[key(e)] || { came: false, actualStart: null, finish: null };
  }

  function tcf(e) {
    const k = key(e);
    if (tcfOverrides.has(k)) return Number(tcfOverrides.get(k));
    return Number(e.tcf);
  }

  function allocatedStartSeconds(e) {
    const v = allocated.get(key(e));
    return v == null ? null : v;
  }

  function actualStartSeconds(e) {
    return parseTime(timesFor(e).actualStart);
  }

  function finishSeconds(e) {
    return parseTime(timesFor(e).finish);
  }

  // The external OCS signal only — a manual override. Kept separate from the
  // derived OCS so the two can combine without a circular reference.
  function isOcsOverridden(e) {
    const ov = flagOverrides.get(key(e));
    if (!ov) return false;
    const added = (ov.added || []).includes('OCS');
    const removed = (ov.removed || []).includes('OCS');
    return added && !removed;
  }

  function isOcsRemoved(e) {
    const ov = flagOverrides.get(key(e));
    return !!(ov && (ov.removed || []).includes('OCS'));
  }

  // effective_start:
  //   actual captured    -> actual
  //   no actual, flagged -> allocated - 1s   (token early period so the flat
  //                                           penalty is not the only effect)
  //   no actual, no flag -> allocated
  function effectiveStartSeconds(e) {
    const a = actualStartSeconds(e);
    if (a != null) return a;
    const s = allocatedStartSeconds(e);
    if (s == null) return null;
    return isOcsOverridden(e) ? (s - 1) : s;
  }

  // Positive late = crossed after the gun. Positive early = crossed before it.
  function lateSeconds(e) {
    const eff = effectiveStartSeconds(e);
    const s = allocatedStartSeconds(e);
    return (eff == null || s == null) ? null : eff - s;
  }

  function earlySeconds(e) {
    const l = lateSeconds(e);
    return l == null ? null : -l;
  }

  // Automatic flags implied by the captured times.
  function autoFlags(e) {
    if (dutyBoatId && key(e) === dutyBoatId) return ['AVG'];  // no times to judge

    const t = timesFor(e);
    const ov = flagOverrides.get(key(e));
    const added = new Set((ov && ov.added) || []);
    const flags = [];

    // An explicit classification suppresses the automatic one: if the RO has
    // said DNF, we must not also derive DNS from the absent finish time.
    if (!STATUS_FLAGS.some(f => added.has(f))) {
      if (!t.came) {
        flags.push('DNC');
      } else if (!t.finish) {
        // Came but never finished: DNF if it started, DNS if it never did.
        flags.push(t.actualStart ? 'DNF' : 'DNS');
      }
    }
    // Came AND finished adds nothing here, even with no actual start recorded:
    // wiki §5.1 treats a missing actual start as "use allocated", not as
    // "didn't start". The start sheet is for the start line, not the
    // handicapper.

    // Over the line early. Every boat has its own gun in a pursuit race, so
    // this is derivable rather than a judgement call.
    const a = actualStartSeconds(e);
    const s = allocatedStartSeconds(e);
    if (a != null && s != null && a < s) flags.push('OCS');

    return flags;
  }

  // effective flags = (auto ∪ added) \ removed, in KNOWN_FLAGS order with any
  // unrecognised ones appended. The single answer to "what flags does this
  // boat have right now", used by the placings, the engine input, and display.
  function flags(e) {
    const set = new Set(autoFlags(e));
    const ov = flagOverrides.get(key(e));
    if (ov) {
      for (const f of ov.added || []) set.add(f);
      for (const f of ov.removed || []) set.delete(f);
    }
    const known = KNOWN_FLAGS.filter(f => set.has(f));
    return known.concat([...set].filter(f => !KNOWN_FLAGS.includes(f)));
  }

  function isOcs(e) {
    return flags(e).includes('OCS');
  }

  // Place-sort key, and the Scored Finish column: the boat's finish with its
  // head start given back and the OCS penalty applied.
  function scoredFinishSeconds(e) {
    const f = finishSeconds(e);
    if (f == null) return null;
    if (!isOcs(e)) return Math.round(f);
    const early = earlySeconds(e);
    return Math.round(f + (early == null ? 0 : early) + OCS_PENALTY_SECONDS);
  }

  /**
   * The corrected finish time printed on the report — the finish with only the
   * head start taken back, and NOT the OCS penalty.
   *
   * This is deliberately narrower than scoredFinishSeconds. The report goes to
   * a human who types it into SailSys along with the OCS flag; SailSys applies
   * the penalty that the flag implies. Folding the penalty in here as well
   * would apply it twice. The two numbers have similar names and different
   * jobs — do not "fix" one to match the other.
   */
  function correctedFinishSeconds(e) {
    const f = finishSeconds(e);
    if (f == null) return null;
    const early = earlySeconds(e);
    return Math.round(f + (early != null && early > 0 ? early : 0));
  }

  function scoredElapsedSeconds(e) {
    const sf = scoredFinishSeconds(e);
    const s = allocatedStartSeconds(e);
    return (sf == null || s == null) ? null : Math.round(sf - s);
  }


  // What the handicap engine is given: actual sailing time, measured from the
  // effective start. A boat that started 30s late physically sailed 30s less
  // and the engine should see that (wiki §5.1).
  function handicapElapsedSeconds(e) {
    const f = finishSeconds(e);
    const eff = effectiveStartSeconds(e);
    return (f == null || eff == null) ? null : Math.round(f - eff);
  }

  function handicapElapsedMinutes(e) {
    const s = handicapElapsedSeconds(e);
    return s == null ? null : s / 60.0;
  }

  function correctedSeconds(e) {
    const se = scoredElapsedSeconds(e);
    if (se == null) return null;
    const t = tcf(e);
    return Math.round(se * (Number.isFinite(t) ? t : 1.0));
  }

  // The FinishStatus the engine is told. The server does the final
  // partitioning; this only decides what we send.
  function jinxStatus(e) {
    const f = flags(e);
    if (f.includes('AVG')) return 'DNC';
    if (f.includes('DSQ')) return 'DSQ';
    if (f.includes('DNC')) return 'DNC';
    if (f.includes('RET')) return 'RET';
    if (f.includes('DNF')) return 'DNF';
    if (f.includes('DNS')) return 'DNS';
    return 'FIN';
  }

  /**
   * Places by the given mode, as a Map of boatId -> place.
   *   'pursuit' (default) -> scored finish time; in a pursuit race the finish
   *                          order IS the result
   *   'scratch'           -> scored elapsed
   *   'tcf'               -> corrected time (TCF x scored elapsed)
   *
   * 'scratch' is also what the finish sheet's Elapsed Place column ranks by: the
   * scored elapsed, with the OCS penalty in it.
   *
   * Boats carrying any UNPLACED_FLAGS are left out. Ties share the better
   * place and the next distinct key jumps past them, so three boats tied at
   * 5th are followed by 8th — the sailing convention. Keys are whole seconds,
   * so equality is exact.
   */
  function places(mode) {
    const excluded = new Set(UNPLACED_FLAGS);
    return rankBy(e => {
      if (flags(e).some(f => excluded.has(f))) return null;
      if (mode === 'scratch') return scoredElapsedSeconds(e);
      if (mode === 'tcf') return correctedSeconds(e);
      return scoredFinishSeconds(e);
    });
  }

  /**
   * Rank the fleet by a key, smallest first. A null or non-finite key means unranked,
   * and the boat is simply absent from the result.
   *
   * <p>Ties share the better place and the next distinct key jumps past them, so three
   * boats tied at 5th are followed by 8th — the sailing convention. Keys are whole
   * seconds, so equality is exact. Extracted so every ranking on the finish sheet uses
   * the one implementation of that rule rather than three that could drift.
   */
  function rankBy(keyOf) {
    const ranked = [];
    for (const e of entrants) {
      const k = keyOf(e);
      if (k == null || !Number.isFinite(k)) continue;
      ranked.push({ id: key(e), k });
    }
    ranked.sort((a, b) => a.k - b.k);

    const out = new Map();
    let place = 0;
    let lastKey = null;
    ranked.forEach((x, i) => {
      if (lastKey === null || x.k !== lastKey) {
        place = i + 1;
        lastKey = x.k;
      }
      out.set(x.id, place);
    });
    return out;
  }

  /**
   * How well each boat started, ranked: on the gun is best, then progressively later.
   *
   * <p>Two kinds of boat are deliberately unranked. **OCS**, because crossing early is
   * not a better start than a perfect one — it is a penalty, and ranking it first would
   * reward it. And any boat with **no captured actual start**: lateSeconds falls back to
   * the allocated gun when no actual time was taken (wiki §5.1), which reads as exactly
   * zero late, so ranking it would hand the best start of the night to a boat nobody
   * timed.
   */
  function latePlaces() {
    return rankBy(e => {
      if (actualStartSeconds(e) == null || isOcs(e)) return null;
      const l = lateSeconds(e);
      return (l == null || l < 0) ? null : l;
    });
  }


  /**
   * The payload for POST /api/races/{id}/process-handicaps. Only boats that
   * carry a handicap forward are included — a one-off visitor has no register
   * boat for a new TCF to land on.
   */
  function handicapEngineInput(mode) {
    const placeMap = places(mode);
    return entrants
      .filter(e => e.entryType !== 'ONE_OFF' && e.boatId)
      .map(e => ({
        boatId: e.boatId,
        currentTcf: tcf(e),
        // Seeded means "was on the start sheet before tonight". A casual raced and is
        // scored, but takes no part in the handicap: it is not on the penalty ladder,
        // neither pays into the pool nor draws from it, and its elapsed time stays out
        // of the measured duration the rest of the fleet is judged against.
        seeded: e.entryType === 'ROSTER',
        status: jinxStatus(e),
        elapsedMinutes: handicapElapsedMinutes(e),
        // How far behind the leader a boat finished is what the giveback is shared by,
        // and the engine cannot work it out from elapsed: in a pursuit race the boats
        // start at different times, so finish order is not elapsed order. Sent as whole
        // seconds, the same units correctedFinishSeconds already rounds to, so two boats
        // showing the same corrected time get exactly the same share.
        correctedFinishSeconds: correctedFinishSeconds(e),
        finishPosition: placeMap.get(key(e)) ?? null
      }));
  }

  return {
    tcf,
    timesFor,
    allocatedStartSeconds,
    actualStartSeconds,
    finishSeconds,
    effectiveStartSeconds,
    lateSeconds,
    earlySeconds,
    flags,
    isOcs,
    isOcsRemoved,
    scoredFinishSeconds,
    correctedFinishSeconds,
    scoredElapsedSeconds,
    latePlaces,
    handicapElapsedSeconds,
    handicapElapsedMinutes,
    correctedSeconds,
    jinxStatus,
    places,
    handicapEngineInput
  };
}

// --- boat identity ---------------------------------------------------------
//
// The same normalisation the server applies (identity/IdGenerator.java,
// identity/Aliases.java). Kept in step deliberately: the race page's type-ahead has to
// find the boat the server would resolve to, or the RO picks one boat and gets another.

/** Uppercase, strip everything that is not a letter or digit. */
function normSail(raw) {
  return String(raw || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
}

/** Lowercase, strip everything that is not a letter or digit. */
function normName(raw) {
  return String(raw || '').toLowerCase().replace(/[^a-z0-9]/g, '');
}

/**
 * Drop an Australian country or fleet prefix and any leading zeros, so AUS01234,
 * AUS1234 and 1234 are one boat. Only strips the prefix when a digit follows, or
 * "AUSTRALIA" would become "TRALIA".
 */
function stripSailPrefix(sail) {
  let s = normSail(sail);
  for (const prefix of ['JAUS', 'EAUS', 'VAUS', 'SAUS', 'AUS']) {
    if (s.startsWith(prefix) && s.length > prefix.length && /[0-9]/.test(s[prefix.length])) {
      s = s.slice(prefix.length);
      break;
    }
  }
  while (s.length > 1 && s[0] === '0' && /[0-9]/.test(s[1])) s = s.slice(1);
  return s;
}

/** True when two sail numbers are the same boat's, prefixes and zeros aside. */
function sameSailNumber(a, b) {
  return stripSailPrefix(a) === stripSailPrefix(b) && stripSailPrefix(a) !== '';
}

// --- how a race is arranged --------------------------------------------------
//
// Not scoring, but it belongs beside it: what a race should look like when nobody has
// said depends on what the race has — places, a start sheet, a date — and those are the
// same facts the scorer is built from. Keeping the rules here rather than inside the
// page means they are pinned by scoring-test.html, and the fallback chain is exactly the
// kind of thing that rots silently when it lives in a render function.

/** The tick boxes whose state is remembered, in the order they appear on the page. */
const RACE_VIEW_FILTERS = ['hide-dnc', 'hide-finished', 'hide-details', 'hide-now'];

/** Column keys a stored sort may name. Anything else is stale and is discarded. */
const RACE_VIEW_SORT_KEYS = ['sail', 'name', 'design', 'spin', 'tcf', 'allocated',
  'actualStart', 'late', 'finish', 'corrected', 'scoredFinish', 'elapsed', 'place',
  'adjustment'];

/**
 * How to show a race nobody has arranged yet.
 *
 * <p>The sort follows what the race has reached: once there are places, the finishing
 * order is the answer to every question being asked of the page; before that, the order
 * the fleet goes off in is; and before there is a start sheet there is nothing to order
 * by but the name.
 *
 * <p>Details are hidden because they are reference rather than capture. Nothing else is
 * filtered out — a boat hidden by a default is a boat somebody forgets to look for.
 *
 * <p>NOW is hidden unless the race is today. Those buttons stamp the wall clock, so on
 * any other night they are not merely useless but the one control on the page that can
 * quietly write a wrong time.
 *
 * @param ctx {hasPlaces, hasStarts, raceDate, today} — dates as YYYY-MM-DD
 */
function defaultRaceView(ctx) {
  const c = ctx || {};
  const key = c.hasPlaces ? 'place' : c.hasStarts ? 'allocated' : 'name';
  const filters = {};
  for (const id of RACE_VIEW_FILTERS) filters[id] = false;
  filters['hide-details'] = true;
  filters['hide-now'] = !(c.raceDate && c.today && c.raceDate === c.today);
  return { sort: [{ key, dir: 1 }], filters };
}

/**
 * A remembered view, with anything it does not say taken from the default.
 *
 * <p>Merged rather than replaced, so a view stored before a tick box existed does not
 * leave that box undefined, and a stored sort naming a column that has since gone does
 * not leave the table unsorted.
 */
function raceView(stored, ctx) {
  const base = defaultRaceView(ctx);
  if (!stored || typeof stored !== 'object') return base;

  const sort = Array.isArray(stored.sort)
    ? stored.sort
      .filter(s => s && RACE_VIEW_SORT_KEYS.includes(s.key))
      .map(s => ({ key: s.key, dir: s.dir < 0 ? -1 : 1 }))
    : [];

  const filters = Object.assign({}, base.filters);
  const from = (stored.filters && typeof stored.filters === 'object') ? stored.filters : {};
  for (const id of RACE_VIEW_FILTERS)
    if (typeof from[id] === 'boolean') filters[id] = from[id];

  // An empty sort is a real arrangement — it is what a drag leaves behind — but only
  // when the stored view actually said so.
  return { sort: Array.isArray(stored.sort) ? sort : base.sort, filters };
}
