// `node --check` only parses. These pages fail at *runtime* on names that do not exist —
// a mistyped function name, a helper that got renamed. That has bitten three times, so:
// strip comments and string literals, then diff every called name against every declared
// one.
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

  if (missing.size) {
    problems += missing.size;
    console.log(`  ${page}`);
    for (const [name, line] of missing)
      console.log(`     line ${line}: ${name}() — called but never defined`);
  }
}
console.log(problems ? `\n${problems} undefined name(s)` : '\nno undefined names in any page');
process.exit(problems ? 1 : 0);
