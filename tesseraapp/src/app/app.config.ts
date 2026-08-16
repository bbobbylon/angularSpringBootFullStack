import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { IMAGE_CONFIG } from '@angular/common';
import { PreloadAllModules, provideRouter, withComponentInputBinding, withPreloading } from '@angular/router';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';

import { routes } from './app.routes';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { tokenInterceptor } from './interceptor/token.interceptor';
import { languageInterceptor } from './interceptor/language.interceptor';
import { provideToastr } from 'ngx-toastr';
import { provideTransloco } from '@jsverse/transloco';
import { TranslocoHttpLoader } from './service/transloco-loader';
import { isDevMode } from '@angular/core';

/**
 * Root application configuration for the Angular standalone app.
 *
 * In Angular 15+ standalone apps there is no AppModule — this object replaces it.
 * It is passed to bootstrapApplication() in main.ts and tells Angular which
 * global services and features to make available across the entire application.
 * Every provider registered here is singleton-scoped for the lifetime of the app.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    /**
     * Catches unhandled JavaScript errors that occur outside Angular's change
     * detection (e.g. in setTimeout, Promise rejections, or native browser events)
     * and routes them through Angular's ErrorHandler so they appear in the console
     * and can be intercepted by a custom error handler later.
     */
    provideBrowserGlobalErrorListeners(),

    /**
     * Registers the Angular Router and wires it to the route definitions in
     * app.routing-module.ts. Without this, [routerLink], router.navigate(), and
     * route guards would not function. The routes array maps URL paths to the
     * components that should render at those paths (e.g. /profile → ProfileComponent).
     */
    provideRouter(routes, withComponentInputBinding(), withPreloading(PreloadAllModules)),

    /**
     * Registers Angular's HttpClient so it can be injected anywhere in the app
     * (services, components, etc.) to make HTTP requests to the backend.
     *
     * withInterceptors([languageInterceptor, tokenInterceptor]) plugs both into the HTTP
     * pipeline in order. languageInterceptor runs first and only ever adds an Accept-Language
     * header — it never short-circuits the request — before tokenInterceptor attaches the JWT
     * header and forwards to the server.
     *
     * There used to be a third, cacheInterceptor, doing GET-response caching from an in-memory
     * store keyed by URL with no freshness check — it could not tell when another user's write
     * made its cached copy stale (POST-SUBMISSION-UPGRADES.md #3). That responsibility has moved
     * to the backend: HttpCacheHeadersFilter now sends Cache-Control: private, no-cache plus an
     * ETag on every cacheable GET, so the browser's own native HTTP cache always revalidates with
     * the server before reusing a response — no Angular-side interceptor or service required.
     */
    provideHttpClient(withInterceptors([languageInterceptor, tokenInterceptor])),

    { provide: IMAGE_CONFIG, useValue: { disableImageSizeWarning: true } },
    provideAnimationsAsync(),
    provideToastr({ timeOut: 4000, positionClass: 'toast-bottom-right', preventDuplicates: true }),

    /**
     * Runtime internationalisation (ROADMAP §2).
     *
     * Transloco resolves translations at runtime from JSON dictionaries under
     * public/assets/i18n, rather than compiling one bundle per language the way
     * @angular/localize does. That choice is what makes the navbar's language switcher
     * possible: changing language swaps a dictionary in place instead of loading a
     * different build, so the user stays exactly where they were in the app. See
     * TranslocoHttpLoader for the full trade-off.
     *
     * fallbackLang + missingHandler: a key with no translation in the active language
     * falls back to English rather than rendering as its raw key. That matters most
     * during incremental translation — a half-translated Spanish UI should read as
     * mostly-Spanish-with-some-English, which is usable, not as a page full of
     * "nav.customers", which is not.
     *
     * reRenderOnLangChange is required for a live switcher; without it the pipes
     * resolve once and the page keeps its original language until a full reload.
     */
    provideTransloco({
      config: {
        // Kept in step with LanguageService.available — that service owns the user-facing list
        // (labels, ordering, the short navbar code); this array is what Transloco will accept as
        // an active language. A code in one and not the other is the failure mode: present here
        // but not there is unreachable, present there but not here is silently refused.
        //
        // RTL locales (ar, he) are deliberately absent. They need dir="rtl" on the document plus
        // a pass converting the stylesheet's physical properties (margin-left, float, text-align:
        // left) to logical ones — shipping one before that work renders a visibly broken page,
        // which serves an Arabic speaker worse than not offering Arabic at all.
        availableLangs: ['en', 'es', 'fr', 'de', 'pt', 'zh'],
        defaultLang: 'en',
        fallbackLang: 'en',
        missingHandler: { useFallbackTranslation: true },
        reRenderOnLangChange: true,
        prodMode: !isDevMode(),
      },
      loader: TranslocoHttpLoader,
    }),
  ],
};
