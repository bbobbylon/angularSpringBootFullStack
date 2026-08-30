/**
 * Renders every `src/*.mmd` diagram to themed SVG + PNG, then builds a self-contained HTML gallery.
 *
 * Run with `npm run diagrams` from the repository root.
 *
 * WHY THIS SCRIPT EXISTS
 * ----------------------
 * The repository previously contained a single `auth-password-reset.svg` — a raw Mermaid export
 * with no source file kept. That made it un-editable and un-regenerable, and it rendered as dark
 * text on a transparent background, which is illegible against any dark editor or viewer. It also
 * still said "SecureCapita", a brand the app moved off.
 *
 * So: the `.mmd` files are the source of truth, everything under `svg/`, `png/` and `index.html` is
 * generated, and the theme is defined once, here.
 *
 * WHY PLAYWRIGHT RATHER THAN @mermaid-js/mermaid-cli
 * --------------------------------------------------
 * Mermaid needs a real browser to lay out text (it measures rendered glyphs to size the boxes).
 * `mermaid-cli` brings its own Puppeteer Chromium — a second ~150 MB browser download on top of the
 * one the E2E suite already installed. This drives Playwright's existing Chromium with Mermaid's
 * UMD bundle from `node_modules`, so the repository carries exactly one browser.
 *
 * DUAL THEMES
 * -----------
 * Each diagram is rendered twice, dark and light, each with an OPAQUE background rect painted in.
 * Transparent backgrounds are what made the original unreadable: an SVG with no background inherits
 * whatever it lands on, and dark-on-dark is the common failure. Carrying its own background means a
 * diagram looks deliberate wherever it is dropped — GitHub, a PDF, a slide, an IDE preview.
 */

import { chromium } from '@playwright/test';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, '..', '..');
const SRC_DIR = join(HERE, 'src');
const SVG_DIR = join(HERE, 'svg');
const PNG_DIR = join(HERE, 'png');
const PDF_DIR = join(HERE, 'pdf');
const MERMAID_BUNDLE = join(REPO_ROOT, 'node_modules', 'mermaid', 'dist', 'mermaid.min.js');

/** Filename of the combined, all-diagrams-in-one deck. */
const DECK_PDF = 'tesseraapp-flow-diagrams.pdf';

/**
 * Brand palette, mirroring the design tokens in `tesseraapp/src/styles.css`. Kept in sync by hand —
 * these are the same hex values the running app uses, so a diagram sits alongside a screenshot
 * without looking like it came from somewhere else.
 */
const BRAND = {
  accent: '#6b5bff', // --accent, "electric iris"
  accentStrong: '#8674ff', // --accent-strong
  ok: '#34d399',
  warn: '#fbbf24',
  danger: '#fb7185',
  info: '#22d3ee',
};

export const THEMES = {
  dark: {
    background: '#0f131c', // --surface-1
    surface: '#161b27', // --surface-2
    surfaceAlt: '#1d2433', // --surface-3
    text: '#f3f5f9', // --text-strong
    textMuted: '#c3cad9', // --text
    line: '#3a4358',
  },
  light: {
    background: '#ffffff',
    surface: '#f4f5fa',
    surfaceAlt: '#e9ebf5',
    text: '#141824',
    textMuted: '#3f4759',
    line: '#c3c8d8',
  },
};

/**
 * Maps a palette onto Mermaid's theme variables.
 *
 * Mermaid's `base` theme is the only one whose variables are fully overridable — the named themes
 * ("dark", "forest", …) compute derived colours internally and ignore much of what you pass. So we
 * start from `base` and set every relevant slot explicitly.
 */
function themeVariables(palette) {
  return {
    fontFamily: "'IBM Plex Sans', system-ui, -apple-system, 'Segoe UI', sans-serif",
    fontSize: '15px',

    background: palette.background,
    primaryColor: palette.surface,
    primaryTextColor: palette.text,
    primaryBorderColor: BRAND.accent,
    lineColor: palette.line,
    textColor: palette.text,
    secondaryColor: palette.surfaceAlt,
    tertiaryColor: palette.surfaceAlt,

    // Participant boxes along the top and bottom.
    actorBkg: palette.surface,
    actorBorder: BRAND.accent,
    actorTextColor: palette.text,
    actorLineColor: palette.line,

    // Arrows and their labels.
    signalColor: palette.textMuted,
    signalTextColor: palette.text,

    // alt / opt / loop frames.
    labelBoxBkgColor: BRAND.accent,
    labelBoxBorderColor: BRAND.accent,
    labelTextColor: '#ffffff',
    loopTextColor: palette.text,

    // Side notes — the annotations carrying the "why".
    noteBkgColor: palette.surfaceAlt,
    noteBorderColor: BRAND.accentStrong,
    noteTextColor: palette.text,

    // Activation bars on the lifelines.
    activationBkgColor: BRAND.accent,
    activationBorderColor: BRAND.accentStrong,

    // The autonumber bubbles.
    sequenceNumberColor: '#ffffff',
  };
}

/** Human-readable titles, derived from the filename when the source has no `title:` front matter. */
function titleFromSource(source, fallbackName) {
  const match = source.match(/^---[\s\S]*?title:\s*(.+?)\s*$/m);
  if (match) return match[1].trim();
  return fallbackName
    .replace(/^\d+-/, '')
    .replace(/-/g, ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

/**
 * Gives a rendered SVG an opaque background and concrete pixel dimensions.
 *
 * Mermaid emits `width="100%"` with a `viewBox` and a transparent background. That is fine inside a
 * page that constrains it, and wrong for a standalone file: viewers scale it unpredictably and it
 * inherits whatever colour sits behind it. Reading the intrinsic size out of the viewBox and
 * painting a background rect as the first child turns it into a self-describing image.
 */
function makeStandalone(svg, palette) {
  const viewBox = svg.match(/viewBox="([^"]+)"/);
  if (!viewBox) return svg;

  const [minX, minY, width, height] = viewBox[1].split(/\s+/).map(Number);

  const backgroundRect =
    `<rect x="${minX}" y="${minY}" width="${width}" height="${height}" ` +
    `fill="${palette.background}"/>`;

  return svg
    .replace(/width="100%"/, `width="${Math.round(width)}"`)
    .replace(/style="[^"]*max-width:[^"]*"/, `style="background-color: ${palette.background};"`)
    .replace(/(<svg[^>]*>)/, `$1${backgroundRect}`)
    .replace(/(<svg[^>]*?)>/, (m, open) =>
      open.includes('height=') ? `${open}>` : `${open} height="${Math.round(height)}">`,
    );
}

/** Reads a standalone SVG's intrinsic pixel size back out of its viewBox. */
function svgSize(svg) {
  const viewBox = svg.match(/viewBox="([^"]+)"/);
  if (!viewBox) return { width: 1200, height: 800 };
  const [, , width, height] = viewBox[1].split(/\s+/).map(Number);
  return { width: Math.round(width), height: Math.round(height) };
}

/**
 * Writes a single-page PDF sized to the diagram's own dimensions.
 *
 * Chromium prints the DOM rather than rasterising it, so the inlined SVG's paths and text survive as
 * real vectors — zoomable without blurring, and the text stays selectable and searchable. Sizing the
 * page to the diagram (rather than to A4) means nothing is scaled down, so a dense sequence diagram
 * stays legible instead of shrinking to fit a sheet it was never drawn for.
 */
async function writeDiagramPdf(browser, name, svg, palette) {
  const { width, height } = svgSize(svg);
  const page = await browser.newPage();
  await page.setContent(
    `<body style="margin:0;background:${palette.background}">${svg}</body>`,
    { waitUntil: 'load' },
  );
  await page.pdf({
    path: join(PDF_DIR, `${name}.pdf`),
    // The +2 absorbs sub-pixel rounding in the viewBox, which otherwise pushes a hairline
    // sliver of the diagram onto a second, near-empty page.
    width: `${width + 2}px`,
    height: `${height + 2}px`,
    printBackground: true,
    pageRanges: '1',
  });
  await page.close();
}

/**
 * Writes the combined deck: a cover page plus every diagram, one per page, at a uniform A4
 * landscape size.
 *
 * A PDF document has a single page size throughout, so unlike the per-diagram files these pages
 * scale each diagram to fit. That is the deliberate trade: uniform pages are what make it feel like
 * a document you can hand someone, and the per-diagram PDFs remain available when a specific flow
 * needs to be read at full size.
 */
async function writeDeckPdf(browser, diagrams, palette) {
  const page = await browser.newPage();
  await page.setContent(buildDeck(diagrams, palette), { waitUntil: 'load' });
  await page.pdf({
    path: join(PDF_DIR, DECK_PDF),
    format: 'A4',
    landscape: true,
    printBackground: true,
    margin: { top: '0', right: '0', bottom: '0', left: '0' },
  });
  await page.close();
}

async function main() {
  const files = (await readdir(SRC_DIR)).filter((f) => f.endsWith('.mmd')).sort();
  if (files.length === 0) {
    console.error(`No .mmd sources found in ${SRC_DIR}`);
    process.exit(1);
  }

  await mkdir(SVG_DIR, { recursive: true });
  await mkdir(PNG_DIR, { recursive: true });
  await mkdir(PDF_DIR, { recursive: true });

  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1600, height: 1200 } });

  // about:blank has no origin, which blocks addScriptTag in some Chromium builds; a data URL
  // document gives the page a workable origin without needing a server.
  await page.goto('data:text/html,<!doctype html><html><body></body></html>');
  await page.addScriptTag({ path: MERMAID_BUNDLE });

  const rendered = [];

  for (const file of files) {
    const name = file.replace(/\.mmd$/, '');
    const source = await readFile(join(SRC_DIR, file), 'utf8');
    const title = titleFromSource(source, name);

    const variants = {};

    for (const [themeName, palette] of Object.entries(THEMES)) {
      const svgRaw = await page.evaluate(
        async ({ code, vars, id }) => {
          // Re-initialising per render is deliberate: mermaid caches theme state globally, so
          // rendering light after dark without a reset silently reuses the dark palette.
          window.mermaid.initialize({
            startOnLoad: false,
            theme: 'base',
            themeVariables: vars,
            securityLevel: 'loose',
            sequence: {
              // Generous horizontal margin, not cosmetic: messages originating at the leftmost
              // actor render their label left-aligned from the lifeline, so a narrow margin lets
              // longer labels ("Enter new password + confirmation") spill off the canvas edge and
              // clip. 72px clears the longest label in the current set.
              diagramMarginX: 72,
              diagramMarginY: 28,
              // Wide columns so long labels break at phrase boundaries rather than mid-parenthesis.
              actorMargin: 96,
              boxMargin: 14,
              noteMargin: 14,
              messageMargin: 48,
              mirrorActors: true,
              wrap: true,
              width: 220,
            },
          });
          const { svg } = await window.mermaid.render(id, code);
          return svg;
        },
        { code: source, vars: themeVariables(palette), id: `d-${name}-${themeName}` },
      );

      const svg = makeStandalone(svgRaw, palette);
      variants[themeName] = svg;
      await writeFile(join(SVG_DIR, `${name}-${themeName}.svg`), svg, 'utf8');
    }

    // PNG from the dark variant — the one that matches the app's own look, and the one most likely
    // to be dropped into a slide or a chat.
    const pngPage = await browser.newPage();
    await pngPage.setContent(
      `<body style="margin:0;display:inline-block;background:${THEMES.dark.background}">${variants.dark}</body>`,
    );
    const element = await pngPage.$('svg');
    await element.screenshot({
      path: join(PNG_DIR, `${name}.png`),
      scale: 'device',
      omitBackground: false,
    });
    await pngPage.close();

    // Vector PDF, dark to match the PNG and the app's own look. The light SVG remains the one to
    // reach for when a diagram has to go into a printed report or a light-background document.
    await writeDiagramPdf(browser, name, variants.dark, THEMES.dark);

    rendered.push({ name, title, variants });
    console.log(`  rendered  ${name}  —  ${title}`);
  }

  await writeDeckPdf(browser, rendered, THEMES.dark);
  await browser.close();

  await writeFile(join(HERE, 'index.html'), buildGallery(rendered), 'utf8');
  console.log(
    `\n${rendered.length} diagrams → svg/ (light + dark), png/, pdf/ (per-diagram + ${DECK_PDF}), and index.html`,
  );
}

/** Builds the self-contained gallery page. Every SVG is inlined, so the file works offline. */
function buildGallery(diagrams) {
  const nav = diagrams
    .map((d) => `        <a class="nav__link" href="#${d.name}">${escapeHtml(d.title)}</a>`)
    .join('\n');

  const sections = diagrams
    .map(
      (d) => `
      <section class="flow" id="${d.name}">
        <header class="flow__head">
          <h2 class="flow__title">${escapeHtml(d.title)}</h2>
          <a class="flow__src" href="src/${d.name}.mmd">${d.name}.mmd</a>
        </header>
        <div class="flow__figure">
          <div class="only-dark">${d.variants.dark}</div>
          <div class="only-light">${d.variants.light}</div>
        </div>
        <p class="flow__links">
          <a href="svg/${d.name}-dark.svg">SVG (dark)</a>
          <a href="svg/${d.name}-light.svg">SVG (light)</a>
          <a href="png/${d.name}.png">PNG</a>
          <a href="pdf/${d.name}.pdf">PDF</a>
        </p>
      </section>`,
    )
    .join('\n');

  return `<!doctype html>
<html lang="en" data-theme="dark">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TesseraApp — Flow Diagrams</title>
<style>
  :root {
    --bg: #0a0c12; --surface: #0f131c; --surface-2: #161b27;
    --text: #f3f5f9; --muted: #8a93a6; --line: rgba(255,255,255,.09);
    --accent: #6b5bff; --accent-soft: rgba(107,91,255,.14);
  }
  html[data-theme="light"] {
    --bg: #f7f8fc; --surface: #ffffff; --surface-2: #f0f2f8;
    --text: #141824; --muted: #5b6477; --line: rgba(0,0,0,.10);
    --accent: #5646e0; --accent-soft: rgba(86,70,224,.10);
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; background: var(--bg); color: var(--text);
    font: 15px/1.6 'IBM Plex Sans', system-ui, -apple-system, 'Segoe UI', sans-serif;
  }
  .shell { display: grid; grid-template-columns: 268px minmax(0,1fr); min-height: 100vh; }
  .nav {
    position: sticky; top: 0; align-self: start; height: 100vh; overflow-y: auto;
    padding: 28px 20px; background: var(--surface); border-right: 1px solid var(--line);
  }
  .nav__brand { font-size: 17px; font-weight: 600; letter-spacing: -.01em; margin: 0 0 4px; }
  .nav__brand span { color: var(--accent); }
  .nav__sub { color: var(--muted); font-size: 12.5px; margin: 0 0 22px; }
  .nav__link {
    display: block; padding: 8px 12px; margin-bottom: 3px; border-radius: 9px;
    color: var(--muted); text-decoration: none; font-size: 13.5px; transition: .16s;
  }
  .nav__link:hover { background: var(--accent-soft); color: var(--text); }
  .toggle {
    margin-top: 26px; width: 100%; padding: 9px; cursor: pointer;
    background: var(--surface-2); color: var(--text);
    border: 1px solid var(--line); border-radius: 9px; font: inherit; font-size: 13px;
  }
  .toggle:hover { border-color: var(--accent); }
  main { padding: 40px clamp(20px, 4vw, 56px) 96px; min-width: 0; }
  .lede { max-width: 68ch; margin: 0 0 44px; }
  .lede h1 { font-size: 30px; letter-spacing: -.02em; margin: 0 0 10px; }
  .lede p { color: var(--muted); margin: 0 0 10px; }
  .lede code {
    font-family: 'IBM Plex Mono', ui-monospace, monospace; font-size: .88em;
    background: var(--surface-2); padding: 2px 6px; border-radius: 5px;
  }
  .lede__deck {
    display: inline-block; margin-top: 4px; padding: 9px 16px; border-radius: 9px;
    background: var(--accent-soft); border: 1px solid var(--accent);
    color: var(--text); text-decoration: none; font-size: 13.5px; font-weight: 500;
  }
  .lede__deck:hover { background: var(--accent); color: #fff; }
  .flow { margin-bottom: 56px; scroll-margin-top: 20px; }
  .flow__head {
    display: flex; align-items: baseline; gap: 14px; flex-wrap: wrap;
    padding-bottom: 10px; margin-bottom: 16px; border-bottom: 1px solid var(--line);
  }
  .flow__title { font-size: 20px; margin: 0; letter-spacing: -.01em; }
  .flow__src {
    font-family: 'IBM Plex Mono', ui-monospace, monospace; font-size: 12px;
    color: var(--muted); text-decoration: none;
  }
  .flow__src:hover { color: var(--accent); }
  /* Wide diagrams scroll inside their own container — the page body must never scroll sideways. */
  .flow__figure {
    overflow-x: auto; background: var(--surface); border: 1px solid var(--line);
    border-radius: 14px; padding: 14px;
  }
  .flow__figure svg { display: block; height: auto; max-width: none; }
  .flow__links { display: flex; gap: 16px; margin: 12px 0 0; font-size: 12.5px; }
  .flow__links a { color: var(--muted); text-decoration: none; }
  .flow__links a:hover { color: var(--accent); text-decoration: underline; }
  html[data-theme="dark"] .only-light, html[data-theme="light"] .only-dark { display: none; }
  @media (max-width: 900px) {
    .shell { grid-template-columns: 1fr; }
    .nav { position: static; height: auto; border-right: 0; border-bottom: 1px solid var(--line); }
  }
</style>
</head>
<body>
<div class="shell">
  <nav class="nav">
    <p class="nav__brand">Tessera<span>App</span></p>
    <p class="nav__sub">Flow diagrams</p>
${nav}
    <button class="toggle" id="toggle" type="button">Switch to light</button>
  </nav>
  <main>
    <div class="lede">
      <h1>Flow diagrams</h1>
      <p>
        Sequence diagrams for the authentication, authorization and session flows.
        Generated from the <code>.mmd</code> sources in <code>src/</code> — edit those and re-run
        <code>npm run diagrams</code> rather than editing any generated file.
      </p>
      <p>
        Each diagram is available as light and dark SVG, a PNG, and a vector PDF sized to the
        diagram itself. Every one carries its own opaque background, so it stays readable wherever
        it is embedded.
      </p>
      <p>
        <a class="lede__deck" href="pdf/${DECK_PDF}">Download all ${diagrams.length} as one PDF →</a>
      </p>
    </div>
${sections}
  </main>
</div>
<script>
  const root = document.documentElement;
  const button = document.getElementById('toggle');
  const label = () => {
    button.textContent = root.dataset.theme === 'dark' ? 'Switch to light' : 'Switch to dark';
  };
  button.addEventListener('click', () => {
    root.dataset.theme = root.dataset.theme === 'dark' ? 'light' : 'dark';
    label();
  });
  label();
</script>
</body>
</html>
`;
}

/**
 * Builds the print document behind the combined deck PDF.
 *
 * Two details do the real work. `break-after: page` on each slide is what turns one HTML document
 * into a paginated PDF. And the diagram is constrained by BOTH `max-width` and `max-height` with
 * `height: auto` — capping only the width lets a tall sequence diagram overflow its page and get
 * silently guillotined across the page break.
 */
export function buildDeck(diagrams, palette) {
  const slides = diagrams
    .map(
      (d, index) => `
  <section class="slide">
    <header class="slide__head">
      <h2>${escapeHtml(d.title)}</h2>
      <span class="slide__num">${String(index + 1).padStart(2, '0')} / ${String(diagrams.length).padStart(2, '0')}</span>
    </header>
    <div class="slide__figure">${d.variants.dark}</div>
  </section>`,
    )
    .join('\n');

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>TesseraApp — Flow Diagrams</title>
<style>
  @page { size: A4 landscape; margin: 0; }
  * { box-sizing: border-box; }
  body {
    margin: 0; background: ${palette.background}; color: ${palette.text};
    font: 15px/1.6 'IBM Plex Sans', system-ui, -apple-system, 'Segoe UI', sans-serif;
    -webkit-print-color-adjust: exact; print-color-adjust: exact;
  }
  .cover, .slide {
    /* 100vh is the printed page height once @page margins are zero. */
    height: 100vh; padding: 34px 44px; break-after: page;
    display: flex; flex-direction: column; overflow: hidden;
  }
  .cover { justify-content: center; align-items: flex-start; gap: 12px; }
  .cover__brand { font-size: 54px; font-weight: 600; letter-spacing: -.03em; margin: 0; }
  .cover__brand span { color: ${BRAND.accent}; }
  .cover__title { font-size: 24px; font-weight: 400; color: ${palette.textMuted}; margin: 0; }
  .cover__rule { width: 92px; height: 4px; background: ${BRAND.accent}; border-radius: 2px; margin: 14px 0; }
  .cover__note { max-width: 74ch; color: ${palette.textMuted}; font-size: 13.5px; margin: 0; }
  .cover__list { columns: 2; gap: 40px; margin: 20px 0 0; padding: 0; list-style: none; max-width: 80ch; }
  .cover__list li { color: ${palette.textMuted}; font-size: 13px; padding: 3px 0; break-inside: avoid; }
  .cover__list b { color: ${palette.text}; font-weight: 500; }
  .slide__head {
    display: flex; align-items: baseline; justify-content: space-between; gap: 16px;
    padding-bottom: 9px; margin-bottom: 16px; border-bottom: 1px solid ${palette.line}; flex: none;
  }
  .slide__head h2 { font-size: 21px; margin: 0; letter-spacing: -.01em; }
  .slide__num {
    font-family: 'IBM Plex Mono', ui-monospace, monospace; font-size: 12px; color: ${BRAND.accent};
  }
  .slide__figure { flex: 1; min-height: 0; display: flex; align-items: center; justify-content: center; }
  /* Both axes must be capped — width alone lets a tall diagram run off the page. */
  .slide__figure svg { max-width: 100%; max-height: 100%; width: auto; height: auto; }
  .slide:last-child { break-after: auto; }
</style>
</head>
<body>
  <section class="cover">
    <p class="cover__brand">Tessera<span>App</span></p>
    <p class="cover__title">Authentication, authorization &amp; session flows</p>
    <div class="cover__rule"></div>
    <p class="cover__note">
      ${diagrams.length} sequence diagrams covering how a request is authenticated and authorized,
      how tokens are issued, rotated and revoked, and how the federated, enterprise-SSO and passkey
      sign-in paths differ. Generated from the Mermaid sources in
      <code>documentation/diagrams/src/</code>.
    </p>
    <ul class="cover__list">
${diagrams.map((d, i) => `      <li><b>${String(i + 1).padStart(2, '0')}</b> &nbsp;${escapeHtml(d.title)}</li>`).join('\n')}
    </ul>
  </section>
${slides}
</body>
</html>
`;
}

function escapeHtml(value) {
  return value.replace(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c],
  );
}

// Only render when run as a script. Guarding this keeps `buildDeck` / `THEMES` importable — useful
// for checking the deck's layout in a browser without regenerating every diagram to do it.
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error);
    process.exit(1);
  });
}
