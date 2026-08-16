import { HttpEvent, HttpHandlerFn, HttpRequest, HttpResponse } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { languageInterceptor } from './language.interceptor';
import { LANGUAGE_STORAGE_KEY } from '../service/language.service';
import { installMemoryLocalStorage, restoreLocalStorage } from '../testing/local-storage';

/**
 * Specs for {@link languageInterceptor} — attaches `Accept-Language` so
 * {@code CustomAccessDeniedHandler} (FUTURE-ENHANCEMENTS.md §3.3) can resolve its message in the
 * language the user actually has selected, not the server's default locale.
 *
 * <p>Exercised directly against a stub {@code HttpHandlerFn} rather than through
 * {@code HttpClient}/{@code HttpTestingController} — a functional interceptor's whole contract is
 * what it hands to {@code next}, so a stub handler tests exactly that. No {@code TestBed}
 * injection context is needed here, unlike {@code tokenInterceptor}, because this interceptor
 * reads `localStorage` directly rather than calling `inject()`.
 */
describe('languageInterceptor', () => {
  let handled: HttpRequest<unknown>[];
  const next: HttpHandlerFn = (request) => {
    handled.push(request);
    return of(new HttpResponse({ status: 200 })) as Observable<HttpEvent<unknown>>;
  };

  beforeEach(() => {
    installMemoryLocalStorage();
  });

  afterEach(() => {
    restoreLocalStorage();
  });

  it('attaches Accept-Language from the persisted choice', () => {
    localStorage.setItem(LANGUAGE_STORAGE_KEY, 'es');
    handled = [];

    languageInterceptor(new HttpRequest('GET', '/customer/list'), next).subscribe();

    expect(handled).toHaveLength(1);
    expect(handled[0].headers.get('Accept-Language')).toBe('es');
  });

  it('forwards the request unmodified when no language has been persisted yet', () => {
    handled = [];

    languageInterceptor(new HttpRequest('GET', '/customer/list'), next).subscribe();

    expect(handled).toHaveLength(1);
    expect(handled[0].headers.has('Accept-Language')).toBe(false);
  });

  it('does not mutate the original request object — HttpRequest is immutable', () => {
    localStorage.setItem(LANGUAGE_STORAGE_KEY, 'fr');
    const original = new HttpRequest('GET', '/customer/list');
    handled = [];

    languageInterceptor(original, next).subscribe();

    expect(original.headers.has('Accept-Language')).toBe(false);
    expect(handled[0]).not.toBe(original);
  });
});
