import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { AppComponent } from './app/app.component';

/*
 * Bootstrap's JavaScript, imported one component at a time.
 *
 * This used to be `node_modules/bootstrap/dist/js/bootstrap.bundle.min.js` in the `scripts`
 * array of angular.json — a single pre-bundled file carrying every Bootstrap component plus
 * Popper, shipped whole on every page load. Because it arrives as a classic script rather than
 * through the module graph, esbuild cannot see inside it and cannot drop the parts nothing
 * references, so modals, carousels, tooltips, popovers, offcanvas, toasts and scrollspy were
 * all being downloaded and executed for an application that uses none of them.
 *
 * Importing the ESM entry points instead puts the code in the module graph, so only these four
 * are bundled. A sweep of every `data-bs-*` attribute in the templates says that is the
 * complete set in use:
 *
 *   dropdown  — the navbar account and language menus (6 uses). Pulls in Popper for placement;
 *               this is the only reason Popper is in the bundle at all.
 *   collapse  — the mobile navbar toggler (1 use).
 *   tab       — the profile page's pill navigation. Bootstrap's tab module owns both
 *               `data-bs-toggle="tab"` and `data-bs-toggle="pill"` (5 uses).
 *   alert     — the `data-bs-dismiss="alert"` close buttons (7 uses).
 *
 * These are side-effect imports on purpose: each module registers its own document-level
 * data-api listeners at import time, which is what makes the `data-bs-*` attributes work
 * without any per-component wiring. Nothing here is referenced by name, so the imports look
 * unused and must not be "tidied away" — deleting one silently breaks that widget everywhere,
 * and it breaks by doing nothing rather than by throwing.
 *
 * If a template ever starts using another Bootstrap component (a modal, a tooltip), add the
 * matching import here; the symptom of a missing one is a control that renders correctly and
 * then ignores every click.
 */
import 'bootstrap/js/dist/dropdown';
import 'bootstrap/js/dist/collapse';
import 'bootstrap/js/dist/tab';
import 'bootstrap/js/dist/alert';

bootstrapApplication(AppComponent, appConfig).catch((err) => console.error(err));
