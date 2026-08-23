// Runs static/scoring-test.html's assertions from the command line.
//
// That page is the executable specification for scoring.js, and it was browser-only:
// you had to remember to open it. The scoring primitives decide places and corrected
// times, so "remember to open a page" is a thin guard for the half of this app that
// Maven does not test. This runs the same assertions, unchanged, against a stub DOM.
//
//   node tools/run-scoring-test.mjs
//
// It reads the page rather than duplicating it, so the browser and the terminal cannot
// drift: add a check() to the page and it runs in both.
import fs from 'node:fs';

const dir = new URL('../src/main/resources/static/', import.meta.url).pathname;
const page = fs.readFileSync(dir + 'scoring-test.html', 'utf8');
const inline = [...page.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]).join('\n');

const cells = [];
const el = () => ({
  set innerHTML(v) { cells.push(v); },
  set textContent(v) { cells.push(v); },
  set className(v) {},
  style: {}
});
globalThis.document = {
  getElementById: el,
  querySelectorAll: () => [],
  querySelector: () => null,
  addEventListener() {}
};
globalThis.window = { addEventListener() {} };
globalThis.location = { search: '' };
globalThis.sessionStorage = { getItem: () => null, setItem() {}, removeItem() {} };
// common.js kicks off a build-version fetch on load; it is noise here.
globalThis.fetch = async () => ({ ok: false, status: 0, json: async () => ({}) });

const src = fs.readFileSync(dir + 'common.js', 'utf8') + '\n'
          + fs.readFileSync(dir + 'scoring.js', 'utf8') + '\n'
          + inline + '\nreturn results;';

const results = new Function(src)();
const failed = results.filter(r => !r.pass);
for (const r of failed) {
  console.log(`FAIL  ${r.name}`);
  console.log(`        expected ${JSON.stringify(r.expected)}`);
  console.log(`        actual   ${JSON.stringify(r.actual)}`);
}
console.log(failed.length
  ? `\n${results.length - failed.length} of ${results.length} passed — ${failed.length} FAILED`
  : `\n${results.length} of ${results.length} scoring assertions passed`);
process.exit(failed.length ? 1 : 0);
