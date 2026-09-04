/**
 * Generates `src/bootstrap-icons.subset.css` — Bootstrap Icons cut down to the icons this
 * application actually uses.
 *
 * WHY
 * ---
 * `bootstrap-icons.min.css` is 87 kB and defines 2,078 icons. The application uses 104 of
 * them, so roughly 95% of that file is dead weight sitting on the critical path: the
 * stylesheet it is bundled into is render-blocking (`index.html` links it plainly, because
 * `optimization.styles.inlineCritical` is `false`). This is the "subset bootstrap-icons"
 * item from `documentation/FUTURE-ENHANCEMENTS.md` §2.1.
 *
 * WHY IT IS GENERATED RATHER THAN HAND-WRITTEN
 * --------------------------------------------
 * §2.1 flagged the real hazard up front: "a hand-maintained subset breaks silently the
 * first time someone adds an icon". An icon whose rule was pruned does not error — the
 * `<i class="bi bi-x">` just renders nothing at all, which is easy to ship and easy to
 * miss in review. So the list is never written by hand. This script rebuilds it from the
 * templates on every build, which means adding an icon to a template is all anyone ever
 * has to do.
 *
 * It also fails the build on an icon name that does not exist upstream. That is a small
 * bonus the subsetting made possible: a typo like `bi-chevron-rigth` currently renders an
 * invisible glyph and is only ever caught by eye, and now it stops the build instead.
 *
 * WHAT IT SCANS, AND WHY THAT IS SUFFICIENT
 * -----------------------------------------
 * Every `bi-*` token in `src/**\/*.{html,ts,css}`. Templates reach for icons indirectly in
 * four places — `sortIconClass()` on the sortable tables, `getEventDisplay().icon` on the
 * activity timelines, and `command.icon`/`pin.icon` from the command-palette registry that
 * `FavoritesBarComponent` shares — but all four bottom out in string literals inside `.ts`
 * files, which this scan covers. No icon name is composed at runtime from a fragment, and
 * none arrives from the backend or from `public/assets/i18n/*.json` (which contain no
 * markup), so a static scan sees the complete set. If that ever stops being true — an
 * icon name built as `` `bi-${kind}` `` — this script must gain an explicit allow-list,
 * because the scan would silently under-collect.
 *
 * WHAT IT KEEPS
 * -------------
 * The `@font-face` and the shared `.bi::before` base rule verbatim (the base rule is what
 * sets the font family and metrics; dropping it would break every icon), plus one
 * `content:` rule per used icon. The `url()` in the `@font-face` is rewritten to a path
 * relative to `src/`, so Angular's bundler resolves, hashes and emits the woff2 exactly as
 * it did when the upstream file was listed in `angular.json`.
 *
 * The font *file* itself is not subset — it still carries all 2,078 glyphs (134 kB woff2).
 * Doing that properly needs a font toolchain (`fonttools`/`subset-font`) in the Docker
 * build, which is a heavier dependency decision; it is logged as a follow-up in §2.1.
 *
 * WIRING
 * ------
 * Runs from the `prebuild` and `prestart` npm hooks, so `npm run build` (used by both the
 * Dockerfile and `.github/workflows/ci.yml`) and `npm start` always regenerate it first.
 * The output is committed so that a bare `ng build`/`ng test` still works; the hooks mean
 * the committed copy can never be what actually ships. Run it directly with
 * `npm run generate:icons`.
 */

import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = resolve(fileURLToPath(new URL('.', import.meta.url)), '..');
const iconPackageDir = join(projectRoot, 'node_modules', 'bootstrap-icons', 'font');
const upstreamCss = join(iconPackageDir, 'bootstrap-icons.min.css');
const sourceRoot = join(projectRoot, 'src');
const outputFile = join(sourceRoot, 'bootstrap-icons.subset.css');

/** Where the woff2 lives relative to the generated file's home in `src/`. */
const FONT_PATH_FROM_SRC = '../node_modules/bootstrap-icons/font/fonts/';

/**
 * @param {string} dir directory to walk
 * @param {(path: string) => void} visit callback for each scannable file
 */
function walk(dir, visit) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) walk(path, visit);
    else if (/\.(html|ts|css)$/.test(entry.name)) visit(path);
  }
}

function main() {
  const css = readFileSync(upstreamCss, 'utf8');

  // Upstream's icon rules, as `name -> full rule text`, so the generated file reproduces
  // them byte for byte rather than re-deriving the codepoints.
  /** @type {Map<string, string>} */
  const rules = new Map();
  for (const match of css.matchAll(/\.(bi-[a-z0-9-]+)::before\{content:"[^"]*"\}/g)) {
    rules.set(match[1], match[0]);
  }

  /** @type {Map<string, Set<string>>} icon name -> files referencing it */
  const used = new Map();
  walk(sourceRoot, (path) => {
    if (path === outputFile) return; // never let the generated file seed its own input
    for (const match of readFileSync(path, 'utf8').matchAll(/\bbi-[a-z0-9-]+/g)) {
      if (!used.has(match[0])) used.set(match[0], new Set());
      used.get(match[0]).add(path);
    }
  });

  const unknown = [...used.keys()].filter((name) => !rules.has(name)).sort();
  if (unknown.length > 0) {
    console.error(
      '\n[generate-icon-subset] FAILED — these look like Bootstrap Icons but do not ' +
        'exist in bootstrap-icons. Most likely a typo; an icon that does not exist ' +
        'renders as nothing:\n',
    );
    for (const name of unknown) {
      console.error(`  ${name}`);
      for (const file of [...used.get(name)].sort()) {
        console.error(`      ${file.slice(projectRoot.length + 1)}`);
      }
    }
    console.error('');
    process.exit(1);
  }

  const names = [...used.keys()].sort();

  // The `@font-face` and the shared base rule, taken verbatim from upstream so a
  // Bootstrap Icons upgrade (new font hash, changed metrics) flows through untouched.
  const fontFace = css.match(/@font-face\{[^}]*\}/)?.[0];
  const baseRule = css.match(/\.bi::before,[^{]*\{[^}]*\}/)?.[0];
  if (!fontFace || !baseRule) {
    console.error(
      '[generate-icon-subset] FAILED — could not find the @font-face or the shared ' +
        '.bi::before rule in bootstrap-icons.min.css. Its structure changed; update the ' +
        'patterns in this script.',
    );
    process.exit(1);
  }

  const banner = `/*
 * GENERATED FILE — DO NOT EDIT BY HAND.
 *
 * Bootstrap Icons ${JSON.parse(readFileSync(join(projectRoot, 'node_modules', 'bootstrap-icons', 'package.json'), 'utf8')).version}, subset to the ${names.length} icons this application uses
 * (upstream defines ${rules.size}). Regenerate with \`npm run generate:icons\`; it also runs
 * automatically from the \`prebuild\`/\`prestart\` hooks, so adding an icon to a template is
 * all that is required.
 *
 * See scripts/generate-icon-subset.mjs for why this is generated rather than curated, and
 * documentation/FUTURE-ENHANCEMENTS.md §2.1 for the payload it saves.
 *
 * Bootstrap Icons is MIT licensed, Copyright 2019-2024 The Bootstrap Authors.
 */
`;

  const body = [
    fontFace.replace(/url\("fonts\//g, `url("${FONT_PATH_FROM_SRC}`),
    baseRule,
    ...names.map((name) => rules.get(name)),
  ].join('\n');

  writeFileSync(outputFile, `${banner}${body}\n`, 'utf8');

  const before = Buffer.byteLength(css, 'utf8');
  const after = Buffer.byteLength(`${banner}${body}\n`, 'utf8');
  console.log(
    `[generate-icon-subset] ${names.length}/${rules.size} icons kept — ` +
      `${(before / 1024).toFixed(1)} kB -> ${(after / 1024).toFixed(1)} kB ` +
      `(-${(((before - after) / before) * 100).toFixed(1)}%)`,
  );
}

main();
