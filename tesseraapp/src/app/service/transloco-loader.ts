import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Translation, TranslocoLoader } from '@jsverse/transloco';
import { Observable } from 'rxjs';

/**
 * Fetches a language's translation dictionary at runtime.
 *
 * <p>Transloco calls this once per language, the first time that language is activated, and caches
 * the result — so switching back and forth costs one request per language for the whole session.
 *
 * <h3>Why the dictionaries are fetched rather than bundled</h3>
 * This is the decision that makes a live language switcher possible at all. Angular's built-in
 * {@code @angular/localize} resolves translations at <em>compile</em> time and emits one optimized
 * bundle per language, which is excellent for a public marketing site — the visitor downloads only
 * their own language and search engines get a distinct URL per locale. It is the wrong shape for an
 * authenticated SPA with an in-app switcher, because changing language means loading a different
 * build: a full page reload, and the user's place in the app is lost. Fetching JSON at runtime
 * keeps one bundle and swaps a dictionary, so the switch is instantaneous and the current view
 * stays exactly where it was.
 *
 * <p>The cost is honest and worth naming: the dictionaries are not tree-shaken, and an unused
 * translation still ships. At this application's string count that is a few kilobytes, fetched
 * lazily and cached — a trade heavily in favour of the switcher.
 *
 * <h3>Interaction with the HTTP interceptors</h3>
 * These requests go through {@code tokenInterceptor} like everything else, but they are same-origin
 * static asset fetches with no {@code /user} or {@code /admin} prefix, so no Authorization header is
 * attached and no 401-refresh cycle can be triggered by a missing dictionary. That matters because
 * the login screen loads translations <em>before</em> anyone is signed in.
 */
@Injectable({ providedIn: 'root' })
export class TranslocoHttpLoader implements TranslocoLoader {
  private readonly http = inject(HttpClient);

  /**
   * Loads one dictionary.
   *
   * @param lang - the language code Transloco is activating (e.g. {@code 'en'}, {@code 'es'})
   * @returns Observable emitting the parsed translation object
   */
  getTranslation(lang: string): Observable<Translation> {
    return this.http.get<Translation>(`/assets/i18n/${lang}.json`);
  }
}
