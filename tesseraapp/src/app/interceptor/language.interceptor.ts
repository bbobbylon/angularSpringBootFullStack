import { HttpEvent, HttpHandlerFn, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { Observable } from 'rxjs';
import { LANGUAGE_STORAGE_KEY } from '../service/language.service';

/**
 * languageInterceptor — Angular functional HTTP interceptor.
 *
 * Backend-driven i18n (FUTURE-ENHANCEMENTS.md §3.3) needs the server to know which language the
 * client wants, and the standard HTTP channel for that is the `Accept-Language` request header —
 * which is exactly what `HttpServletRequest#getLocale()` parses on the Spring side (see
 * `CustomAccessDeniedHandler`). Without this interceptor, the SPA's language switcher would only
 * ever change client-rendered (Transloco) text; anything the server generates — right now just
 * `CapabilityCatalog`'s permission-denied messages — would stay English regardless of what the
 * user picked.
 *
 * Reads the active language straight out of `localStorage` (the key `LanguageService` persists
 * to) rather than injecting `LanguageService` or `TranslocoService`, mirroring
 * `token.interceptor.ts`'s reasoning for reading `Key.TOKEN` directly: a functional interceptor
 * runs on every outgoing request, including ones fired before Angular has finished composing the
 * injector tree, so depending on the raw storage value avoids any interceptor/DI ordering
 * question entirely.
 *
 * Registered globally in app.config.ts via:
 *   provideHttpClient(withInterceptors([languageInterceptor, tokenInterceptor]))
 *
 * @param req  - the outgoing HTTP request
 * @param next - the next handler in the pipeline; calling it forwards the request
 * @returns an Observable of the HTTP event stream for this request
 */
export const languageInterceptor: HttpInterceptorFn = (req: HttpRequest<unknown>, next: HttpHandlerFn): Observable<HttpEvent<unknown>> => {
  const lang = localStorage.getItem(LANGUAGE_STORAGE_KEY);

  if (!lang) return next(req);

  return next(req.clone({ setHeaders: { 'Accept-Language': lang } }));
};
