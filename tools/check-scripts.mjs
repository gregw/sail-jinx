// `node --check` only parses. These pages fail at *runtime* on names that do not exist —
// a mistyped function name, a helper that got renamed, an element id that was renamed in
// the markup but not in the handler that binds it. Every one of those parses perfectly
// and then throws, and in an onchange handler that means the control silently does
// nothing. So: two checks, neither of which the parser can make.
//
//   1. Strip comments and string literals, then diff every called name against every
//      declared one.
//   2. Diff every getElementById('literal') against the ids the markup actually has.
import fs from 'node:fs';

const dir = new URL('../src/main/resources/static/', import.meta.url).pathname;

/** Blank out comments, strings and regex literals so only code remains. */
function stripLiterals(src) {
  let out = '', i = 0, n = src.length;
  while (i < n) {
    const c = src[i], d = src[i + 1];
    if (c === '/' && d === '/') { while (i < n && src[i] !== '\n') i++; continue; }
    if (c === '/' && d === '*') { i += 2; while (i < n && !(src[i] === '*' && src[i+1] === '/')) i++; i += 2; continue; }
    if (c === '"' || c === "'" || c === '`') {
      const q = c; i++;
      while (i < n && src[i] !== q) { if (src[i] === '\\') i++; i++; }
      i++; out += '""'; continue;
    }
    out += c; i++;
  }
  return out;
}

const KEYWORDS = new Set(['if','for','while','switch','catch','return','typeof','function',
  'await','new','else','do','of','in','case','async','import','export','yield','delete',
  'void','instanceof','throw','super','this','class','const','let','var','try','finally']);

const BROWSER = new Set(['document','window','location','history','localStorage','sessionStorage',
  'fetch','alert','confirm','prompt','console','setTimeout','clearTimeout','setInterval',
  'JSON','Math','Number','String','Boolean','Array','Object','Map','Set','Date','RegExp',
  'Promise','URLSearchParams','CSS','structuredClone','isNaN','isFinite','parseInt',
  'parseFloat','encodeURIComponent','decodeURIComponent','Error','TypeError','FileReader']);

const scriptsOf = (html) =>
  [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]).join('\n');

let problems = 0;
for (const page of fs.readdirSync(dir).filter(f => f.endsWith('.html') && !f.startsWith('_'))) {
  const html = fs.readFileSync(dir + page, 'utf8');
  const shared = ['common.js', 'scoring.js'].filter(s => html.includes('src="/' + s + '"'));
  const raw = shared.map(s => fs.readFileSync(dir + s, 'utf8')).join('\n') + '\n' + scriptsOf(html);
  const src = stripLiterals(raw);

  // Ids the page can actually produce: its own markup, the shared nav it includes, and
  // anything it writes itself through innerHTML — which is why this scans the raw text
  // rather than the stripped copy.
  const navHtml = html.includes('INCLUDE _nav.html')
    ? fs.readFileSync(dir + '_nav.html', 'utf8') : '';
  const ids = new Set([...(html + navHtml + raw).matchAll(/\bid="([\w-]+)"/g)].map(m => m[1]));

  const declared = new Set([...BROWSER,
    ...[...src.matchAll(/\bfunction\s+([A-Za-z_$][\w$]*)/g)].map(m => m[1]),
    ...[...src.matchAll(/\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)/g)].map(m => m[1]),
    ...[...src.matchAll(/\bclass\s+([A-Za-z_$][\w$]*)/g)].map(m => m[1]),
    // destructured and parameter names
    ...[...src.matchAll(/\b(?:const|let|var)\s*\{([^}]*)\}/g)]
        .flatMap(m => m[1].split(',').map(x => x.trim().split(':').pop().trim())),
    ...[...src.matchAll(/function\s*[A-Za-z_$\w]*\s*\(([^)]*)\)/g)]
        .flatMap(m => m[1].split(',').map(x => x.trim().split('=')[0].trim())),
    ...[...src.matchAll(/\(([^)]*)\)\s*=>/g)]
        .flatMap(m => m[1].split(',').map(x => x.trim().split('=')[0].trim())),
    ...[...src.matchAll(/([A-Za-z_$][\w$]*)\s*=>/g)].map(m => m[1])]);

  const lines = src.split('\n');
  const missing = new Map();
  lines.forEach((line, idx) => {
    for (const m of line.matchAll(/(^|[^.\w$])([A-Za-z_$][\w$]*)\s*\(/g)) {
      const name = m[2];
      if (KEYWORDS.has(name) || declared.has(name)) continue;
      if (!missing.has(name)) missing.set(name, idx + 1);
    }
  });

  // Only a plain literal is checked; an id built by concatenation cannot be resolved
  // here and is left alone rather than guessed at.
  //
  // And only where the result is used on the spot — `getElementById(x).onchange = …`,
  // which is the shape that throws. A lookup assigned to a variable is very often
  // followed by a null check (common.js does exactly that for the nav widget, which the
  // report pages legitimately do not have), and flagging those buries the real ones.
  const badIds = new Map();
  raw.split('\n').forEach((line, idx) => {
    for (const m of line.matchAll(/getElementById\(\s*['"]([\w-]+)['"]\s*\)\s*[.[]/g))
      if (!ids.has(m[1]) && !badIds.has(m[1])) badIds.set(m[1], idx + 1);
  });

  // data-requires gates a control on the caller's role. A value applyRoleGates does not
  // know falls through to the weakest tier, so a typo does not break anything visibly —
  // it just quietly offers an admin's button to a race officer.
  const ROLES = new Set(['officer', 'admin']);
  const badRoles = new Map();
  (html + raw).split('\n').forEach((line, idx) => {
    for (const m of line.matchAll(/data-requires="([^"]*)"/g))
      if (!ROLES.has(m[1]) && !badRoles.has(m[1])) badRoles.set(m[1], idx + 1);
  });

  if (missing.size || badIds.size || badRoles.size) {
    problems += missing.size + badIds.size + badRoles.size;
    console.log(`  ${page}`);
    for (const [name, line] of missing)
      console.log(`     line ${line}: ${name}() — called but never defined`);
    for (const [id, line] of badIds)
      console.log(`     line ${line}: getElementById('${id}') — no such id in the markup`);
    for (const [role, line] of badRoles)
      console.log(`     line ${line}: data-requires="${role}" — not one of ${[...ROLES].join(', ')}`);
  }
}
console.log(problems ? `\n${problems} problem(s)` : '\nno undefined names or ids in any page');
process.exit(problems ? 1 : 0);
