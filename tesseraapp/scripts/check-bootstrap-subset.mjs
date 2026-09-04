/**
 * Fails the build when a template uses a Bootstrap class that `src/bootstrap.custom.scss`
 * removed.
 *
 * WHY THIS EXISTS
 * ---------------
 * `src/bootstrap.custom.scss` replaces the full `bootstrap.min.css` distribution with a
 * compiled-from-source subset, dropping the components and utility groups this
 * application does not reference (see `documentation/FUTURE-ENHANCEMENTS.md` §2.1). That
 * trade has exactly one failure mode, and it is a nasty one: CSS has no concept of an
 * undefined class, so the first time someone writes `class="position-absolute"` in a new
 * template the element simply renders wrong. No compiler error, no console warning, no
 * failing test — just a layout that is subtly off, usually noticed by a person rather
 * than by CI.
 *
 * This script closes that hole. It converts "silently wrong at runtime" into "loudly
 * wrong at build time", which is the only reason the subsetting is a safe thing to do at
 * all.
 *
 * HOW IT WORKS
 * ------------
 *  1. Compile `src/bootstrap.custom.scss` — what the application will actually ship.
 *  2. Read `node_modules/bootstrap/dist/css/bootstrap.min.css` — the full upstream build.
 *  3. Diff the class selectors. The difference is precisely the set this subset removed;
 *     it is derived from the two builds, never hand-maintained, so it cannot drift when
 *     Bootstrap is upgraded or when the subset is edited.
 *  4. Scan `src/**` for those classes, matching only in places a CSS class can actually
 *     land (a `class=` attribute, a `[class]`/`[ngClass]` binding, or a string literal),
 *     so prose in a Javadoc-style comment cannot trip it.
 *
 * WHY IT COMPILES INSTEAD OF READING `dist/`
 * ------------------------------------------
 * It has to run *before* `ng build`, so there is no build output to inspect yet. Sass is
 * already a dependency of `@angular/build`, and compiling this one file takes about a
 * second, so the check is cheap enough to run on every build rather than being a separate
 * step someone has to remember.
 *
 * WIRING
 * ------
 * Invoked from the `prebuild` and `prestart` npm hooks, so it guards `npm run build`
 * (which is what both the Dockerfile and `.github/workflows/ci.yml` run) and `npm start`.
 * Run it directly with `npm run check:css`.
 *
 * FIXING A FAILURE
 * ----------------
 * The report names the class and the files using it. Re-enable it in
 * `src/bootstrap.custom.scss` — either delete the entry from `$removed-utilities` or
 * uncomment the component's `@import` — and re-run. Do not delete the class from the
 * template to silence the check unless the class was genuinely a mistake.
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { compile } from 'sass';

const projectRoot = resolve(fileURLToPath(new URL('.', import.meta.url)), '..');
const subsetEntry = join(projectRoot, 'src', 'bootstrap.custom.scss');
const upstreamCss = join(
  projectRoot,
  'node_modules',
  'bootstrap',
  'dist',
  'css',
  'bootstrap.min.css',
);
const sourceRoot = join(projectRoot, 'src');

/**
 * Class selectors appearing in a stylesheet.
 *
 * Deliberately a coarse regex rather than a real CSS parser: both inputs are Bootstrap
 * builds, so the shapes are known and predictable.
 *
 * Comments and `url(...)` values are stripped first, and that is not cosmetic. Upstream's
 * `bootstrap.min.css` ends with `/*# sourceMappingURL=bootstrap.min.css.map *\/`, from
 * which a naive scan happily extracts ".min" and ".css" as class names — and since every
 * component declares `styleUrls: ['./x.component.css']`, the check then reports thirty-two
 * files as using a trimmed class. Any noise the two inputs do not share becomes a false
 * failure, so both sides are cleaned identically.
 *
 * @param {string} css stylesheet text
 * @returns {Set<string>} every class name it defines
 */
function classSelectors(css) {
  const stripped = css.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/url\([^)]*\)/g, ' ');
  const found = new Set();
  for (const match of stripped.matchAll(/\.((?:[a-z0-9]|-(?![-]))[a-zA-Z0-9_-]*)/g)) {
    found.add(match[1]);
  }
  return found;
}

/**
 * Compiles the subset entry point through Sass's JS API.
 *
 * Uses the API rather than shelling out to the `sass` CLI: `execFileSync` cannot launch a
 * `.cmd` shim on Windows under Node 20+ without `shell: true`, and going through a shell
 * to run a compiler this script already has in-process is needless. The deprecation
 * silences mirror `angular.json`, because both are compiling the same Bootstrap source —
 * if they drift, this check would print warnings the real build does not.
 *
 * @returns {string} the compiled subset stylesheet
 */
function compileSubset() {
  return compile(subsetEntry, {
    loadPaths: [join(projectRoot, 'node_modules', 'bootstrap', 'scss')],
    style: 'compressed',
    quietDeps: true,
    silenceDeprecations: ['import', 'global-builtin', 'color-functions'],
  }).css;
}

/**
 * Every place in a source file where a CSS class can realistically appear.
 *
 * Matching the whole file would flag the word "position" in a comment; matching only
 * `class="..."` would miss `[class]="'bi ' + icon"` and the icon/command registries in
 * `.ts`. This splits the difference: class attributes, class bindings, and quoted string
 * literals.
 *
 * @param {string} text file contents
 * @returns {string[]} candidate class-bearing fragments
 */
function classBearingFragments(text) {
  const fragments = [];
  const patterns = [
    /\bclass\s*=\s*"([^"]*)"/g, // class="..."
    /\bclass\s*=\s*'([^']*)'/g, // class='...'
    /\[(?:ngClass|class)[^\]]*\]\s*=\s*"([^"]*)"/g, // [class]="..." / [ngClass]="..."
    /'([^'\n]*)'/g, // 'string literal'
    /`([^`\n]*)`/g, // `template literal`
  ];
  for (const pattern of patterns) {
    for (const match of text.matchAll(pattern)) fragments.push(match[1]);
  }
  return fragments;
}

/**
 * @param {string} dir directory to walk
 * @param {(path: string) => void} visit callback for each source file
 */
function walk(dir, visit) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) walk(path, visit);
    else if (/\.(html|ts)$/.test(entry.name) && !entry.name.endsWith('.spec.ts')) visit(path);
  }
}

function main() {
  if (!statSync(upstreamCss, { throwIfNoEntry: false })) {
    console.error(`[check-bootstrap-subset] cannot find ${upstreamCss} — run npm ci first.`);
    process.exit(1);
  }

  const shipped = classSelectors(compileSubset());
  const upstream = classSelectors(readFileSync(upstreamCss, 'utf8'));
  const removed = new Set([...upstream].filter((name) => !shipped.has(name)));

  /** @type {Map<string, Set<string>>} class name -> files using it */
  const violations = new Map();
  walk(sourceRoot, (path) => {
    const text = readFileSync(path, 'utf8');
    for (const fragment of classBearingFragments(text)) {
      for (const token of fragment.split(/[^A-Za-z0-9_-]+/)) {
        if (!removed.has(token)) continue;
        if (!violations.has(token)) violations.set(token, new Set());
        violations.get(token).add(relative(projectRoot, path));
      }
    }
  });

  if (violations.size === 0) {
    console.log(
      `[check-bootstrap-subset] ok — ${shipped.size} classes shipped, ` +
        `${removed.size} trimmed, none in use.`,
    );
    return;
  }

  console.error(
    '\n[check-bootstrap-subset] FAILED — these Bootstrap classes are used but were ' +
      'trimmed out of src/bootstrap.custom.scss, so they would have no effect:\n',
  );
  for (const [name, files] of [...violations].sort()) {
    console.error(`  .${name}`);
    for (const file of [...files].sort()) console.error(`      ${file}`);
  }
  console.error(
    '\nRe-enable each one in src/bootstrap.custom.scss (remove it from ' +
      '$removed-utilities, or uncomment the component @import), then re-run.\n',
  );
  process.exit(1);
}

main();
