import { defineConfig, devices } from '@playwright/test';

/**
 * Where the shared signed-in browser state is cached between the `setup` project and the specs.
 * Gitignored — it holds a real (if throwaway) JWT, and it is regenerated on every run.
 */
export const STORAGE_STATE = 'e2e/.auth/admin.json';

/**
 * Playwright configuration for the end-to-end suite (FUTURE-ENHANCEMENTS.md §5,
 * "No end-to-end coverage").
 *
 * <p><b>Why this lives at the repository root</b> rather than inside {@code tesseraapp/}:
 * these tests exercise the <em>deployed artifact</em>, which is neither the frontend project
 * nor the backend project on its own. The Dockerfile bakes the built Angular bundle into the
 * Spring Boot jar's static resources, so what ships is a single origin serving both the SPA
 * and the API. Everything this suite is for — the token interceptor talking to the real
 * filter chain, the OAuth2 redirect round-trip, cache/CSP headers applied by real servlet
 * filters — only exists once those two halves are combined. A spec under {@code tesseraapp/}
 * would be claiming to be a frontend test while silently depending on a backend.
 *
 * <p><b>What it runs against:</b> {@code docker compose} with the E2E override layered on
 * (see {@code docker-compose.e2e.yml} for why that override is not optional). The stack
 * self-provisions: {@code spring.sql.init.mode: always} applies {@code schema.sql} on boot,
 * Hibernate's {@code ddl-auto: update} layers the JPA tables on top, and {@code DemoDataSeeder}
 * inserts the six role-representative accounts — so a completely cold {@code docker compose up}
 * reaches a testable state with no manual database step.
 *
 * <p><b>Relationship to the other two suites.</b> The 584 backend JUnit tests and the 174
 * frontend Vitest specs both mock across the boundary this suite refuses to mock: Vitest stubs
 * {@code HttpClient}, and the backend's {@code @SpringBootTest}s drive the API without a
 * browser. Neither can catch a seam break where both sides are individually correct — a
 * response envelope the SPA parses differently than the controller writes it, a filter ordering
 * change that strips a header, a public-route list that drifts. That gap is this file's entire
 * reason to exist, and the {@code ANOMALY_DETECTION_ENABLED} discovery documented in
 * {@code .env.e2e.example} was found by building it.
 */
export default defineConfig({
  testDir: './e2e',

  /**
   * Fail the build rather than silently pass when someone commits a focused test.
   * `test.only` is invaluable while writing a spec and poisonous once merged — it turns a
   * green suite into a green single test.
   */
  forbidOnly: !!process.env.CI,

  /**
   * Retries exist for genuine flake (container warm-up, animation timing), not to paper over
   * real failures — hence zero locally, where a flaky test should be felt and fixed.
   */
  retries: process.env.CI ? 2 : 0,

  /**
   * Deliberately serial. These specs share one backend and one database: they log in as the
   * same seeded accounts and assert on audit/session state that concurrent workers would
   * interleave unpredictably. Parallelism here would buy seconds and cost reproducibility.
   */
  workers: 1,
  fullyParallel: false,

  /** Generous: the very first run has to build both Docker stages from scratch. */
  timeout: 60_000,
  expect: { timeout: 10_000 },

  reporter: process.env.CI
    ? [['github'], ['html', { open: 'never' }]]
    : [['list'], ['html', { open: 'never' }]],

  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8090',

    /** Artifacts only for failures — a green run should not leave hundreds of megabytes behind. */
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',

    /**
     * The stack is plain HTTP on localhost. Browsers treat localhost as a secure context
     * regardless, which is what lets the WebAuthn feature-detection path behave as it does
     * in production (see the passkey-prompt handling in e2e/support/app.ts).
     */
    ignoreHTTPSErrors: true,

    /**
     * Emulate `prefers-reduced-motion: reduce` for every context.
     *
     * <p>Not an accessibility test — a determinism fix. Playwright's actionability checks require an
     * element to be *stable*, meaning its bounding box is unchanged across two consecutive animation
     * frames, before it will dispatch a click. An element that is still animating never satisfies
     * that, and `click()` does not fail fast: it retries until the test's own timeout expires, with
     * the trace showing the click simply never completing and no error explaining why.
     *
     * <p>That is exactly how the session-revocation test failed. Every action in its trace finished
     * in milliseconds up to the sign-in button, and the click never returned — while the failure
     * screenshot showed a perfectly loaded dashboard, because by the time the sixty-second timeout
     * fired the app had long since navigated. The symptom points at the application; the cause was
     * the harness clicking into a moving target.
     *
     * <p>Safe here because the app genuinely honours the query — `styles.css`,
     * `command-palette.component.css` and `app.component.ts` all branch on it — so this turns the
     * app's own reduced-motion path on rather than fighting the animations from outside.
     */
    reducedMotion: 'reduce',
  },

  /**
   * Two projects, because signing in is a *rationed* operation in this application.
   *
   * <p>{@code RateLimitFilter} puts {@code /user/login} in its auth tier at 10 requests per minute
   * per client IP. Every request in this suite originates from the same host, so the whole run
   * shares one bucket — a suite that signed in inside each test would begin throttling itself
   * somewhere around the tenth test and fail with 429s that look nothing like the bug they would
   * be blamed on.
   *
   * <p>So {@code setup} signs in exactly once and writes the resulting browser storage to disk;
   * {@code chromium} loads that state, starting every spec already authenticated for the cost of
   * zero additional login requests. Specs that are *about* signing in opt out with
   * {@code test.use({ storageState: { cookies: [], origins: [] } })} and each spend one request
   * from the budget — a handful, well inside the limit.
   *
   * <p>This is also the idiomatic Playwright pattern, so the rate limit pushed the suite toward
   * the design it should have had regardless.
   */
  projects: [
    {
      name: 'setup',
      use: { ...devices['Desktop Chrome'] },
      testMatch: /.*\.setup\.ts/,
    },
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        storageState: STORAGE_STATE,
      },
      dependencies: ['setup'],
      // Excluded here and given its own project below — it must run after everything else.
      testIgnore: /rate-limit\.spec\.ts/,
    },
    /**
     * The rate-limit spec, isolated into a project that depends on `chromium` so it always runs
     * last.
     *
     * <p>It is the only spec that deliberately exhausts a resource shared by every other test:
     * `RateLimitFilter` buckets per client IP, and inside Docker the entire host is one IP, so
     * draining the auth-tier bucket drains it for the whole suite. Refill is gradual, not instant.
     *
     * <p>Left in the main project it ran in path order — after `auth.spec.ts` but before
     * `seam.spec.ts` — and broke it: seam's second session could not sign in, and because the
     * login form maps every non-200 to the same generic "Invalid email or password" (the
     * no-enumeration property `auth.spec.ts` asserts), a 429 was indistinguishable from a wrong
     * password. The test simply waited sixty seconds for a navigation that was never coming.
     *
     * <p>Renaming the file to sort last would have worked and would have been invisible. A project
     * dependency states the actual constraint — "this runs after everything else" — in a way that
     * survives someone renaming the file.
     */
    {
      name: 'rate-limit',
      use: {
        ...devices['Desktop Chrome'],
        storageState: STORAGE_STATE,
      },
      dependencies: ['chromium'],
      testMatch: /rate-limit\.spec\.ts/,
    },
  ],

  /**
   * Brings the compose stack up and waits for the actuator health endpoint to report ready.
   *
   * <p>Every run is a cold build — see the note on `reuseExistingServer` below for why the usual
   * "reuse locally, rebuild in CI" split is wrong for a stack whose image contains the compiled
   * application.
   *
   * <p>Playwright stops the compose process it started, but Docker containers outlive their
   * client — run `npm run e2e:down` to remove the containers and volumes for a truly clean
   * slate. That is also the fix if a run ever fails in a way that leaves the database dirty.
   */
  webServer: {
    command:
      'docker compose -f docker-compose.yml -f docker-compose.e2e.yml --env-file .env.e2e up --build',
    url: 'http://localhost:8090/actuator/health',
    timeout: 10 * 60 * 1000,

    // NEVER reuse, not even locally — this is the opposite of Playwright's usual advice, and the
    // reason is that this `webServer` is not a dev server.
    //
    // The default (`!process.env.CI`) exists for stacks where the running process picks up source
    // changes: a Vite dev server, `ng serve`, `nodemon`. Ours does the reverse. The command builds
    // a Docker image that BAKES IN the compiled jar and the built Angular bundle, so a container
    // left over from a previous run is a frozen snapshot of whatever the source looked like then.
    //
    // With reuse on, Playwright just sees port 8090 answering and skips the compose command
    // altogether. That is not a slow test run or an obvious error — it is a green-or-red result
    // reported against code you did not write. It happened: a run "failed" on rate limiting that
    // had already been raised, because the container under test predated the change and still had
    // the old capacity compiled in. Nothing in the output hinted that the image was stale.
    //
    // Rebuilding every time costs a few minutes. Trusting a result about the wrong artifact costs
    // considerably more.
    reuseExistingServer: false,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
