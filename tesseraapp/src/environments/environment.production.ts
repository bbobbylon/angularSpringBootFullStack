/**
 * Production environment configuration.
 *
 * Swapped in at build time by angular.json `fileReplacements` when building with
 * `--configuration production`.  In a reverse-proxy deployment (nginx in front of
 * both the SPA and the Spring Boot API), an empty string makes every API call a
 * same-origin relative URL; update to the fully-qualified backend URL if the
 * frontend and backend are served from different origins.
 */
export const environment = {
  production: true,
  apiUrl: '',
  /**
   * Cloudflare Turnstile site key (FUTURE-ENHANCEMENTS.md §3.1) — public by design, safe to
   * commit. Blank until a Cloudflare account + Turnstile site exist; `RegisterComponent` skips
   * rendering the widget while this is empty. Fill in with the real site key from the Cloudflare
   * dashboard, and set the matching `TURNSTILE_SECRET_KEY` on the backend — the widget appears
   * on the frontend only once both are configured.
   */
  turnstileSiteKey: '',
};
