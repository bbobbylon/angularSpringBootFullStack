/**
 * Development environment configuration.
 *
 * Loaded by default during `ng serve` and `ng build --configuration development`.
 * The production build swaps this file for environment.production.ts via the
 * `fileReplacements` entry in angular.json, so services import a single symbol
 * (`environment.apiUrl`) with no runtime branching needed.
 */

/** Port the Spring Boot backend listens on locally (`CONTAINER_PORT` in application-dev.yml). */
const DEV_API_PORT = 8080;

/**
 * Builds the dev API base URL from whatever host the page was actually loaded from.
 *
 * A hardcoded `http://localhost:8080` works only when the browser and the backend are the
 * same machine. Open the app from a phone on the LAN (`http://192.168.1.50:4200`) and
 * `localhost` resolves to the *phone*, so every API call dies against a port nothing is
 * listening on — the page paints and nothing else works.
 *
 * Deriving the host from `window.location` fixes that with no configuration: the PC still
 * gets `http://localhost:8080`, and the phone gets `http://192.168.1.50:8080`, which is
 * reachable because Spring Boot binds to all interfaces by default.
 *
 * A dev-server proxy would be the other obvious fix, but it is the wrong tool for THIS
 * app: its SPA routes and API routes share a URL space — `/user/verify/account/:key` and
 * `/oauth2/callback` are both real Angular routes AND real backend endpoints — so any
 * path-prefix proxy rule would swallow the email-verification landing page and the OAuth2
 * callback route.
 *
 * The guard covers non-browser execution (a spec running under a bare Node environment);
 * jsdom and every real browser take the second branch.
 */
function resolveDevApiUrl(): string {
  if (typeof window === 'undefined' || !window.location?.hostname) {
    return `http://localhost:${DEV_API_PORT}`;
  }
  return `http://${window.location.hostname}:${DEV_API_PORT}`;
}

export const environment = {
  production: false,
  apiUrl: resolveDevApiUrl(),
};
