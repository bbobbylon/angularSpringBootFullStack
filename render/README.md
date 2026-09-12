# Render Deployment (free tier)

**Version:** 1.1
**Last Updated:** 2026-09-12
**Status:** **Repo side ready; not yet cut over.** The Blueprint
([`../render.yaml`](../render.yaml)) is written and committed, but the Render service itself has
not been created — that is an account-side step, §3 below.

Deploy TesseraApp to **Render** — the $0/month, no-card-required option, and the third target in
this repo beside [`../aws/`](../aws/) (ECS Fargate) and [`../gcp/`](../gcp/) (Cloud Run).

**This does not retire AWS.** Everything under `aws/` is untouched and stays that way, so the ECS
environment can be brought back whenever you want it — see
[`../aws/RUNBOOK.md`](../aws/RUNBOOK.md). Render is additive, exactly like `gcp/` is.

---

## 1. Why Render, and what it costs you

| | AWS (ECS Fargate) | GCP (Cloud Run) | **Render (free)** |
|---|---|---|---|
| Cost | ~$57/mo | ~$20/mo | **$0** |
| Card required | yes | yes | **no** |
| Cold start | none (always on) | ~2–5 s | **~50 s after 15 min idle** |
| Memory | task-sized | request-sized | **512 MB, fixed** |
| Persistent disk | via S3 | via GCS | **none on free** |
| TLS / custom domain | CloudFront | Cloud Run | included, free |

The honest summary: Render is the cheapest way to keep the project **live and clickable**, and it
is meaningfully worse than the other two for anything resembling real traffic. For a portfolio
link, the sleep is the only thing a visitor notices, and they notice it once.

## 2. What it reuses

Nothing here is a fork of the deployment. It is the same build the other two targets use:

- **The same [`../Dockerfile`](../Dockerfile)** — Angular is compiled into the Spring Boot jar, so
  one container serves the SPA and the API on one origin. No separate frontend host, no CORS
  between halves, no second service to keep in sync.
- **The same Aiven MySQL (`db3`)** the AWS environment uses, reached over TLS with
  `sslMode=VERIFY_IDENTITY`. The Dockerfile already imports
  [`../certs/aiven-mysql-ca.pem`](../certs/) into the JRE truststore, which is what makes
  *verification* (not just encryption) possible.
- **The same `prod` Spring profile.** `application-prod.yml` is cloud-agnostic on purpose — it
  pins TLS, turns off detail-leaking error responses, and reads CORS from `UI_APP_URL`. There is
  no AWS-specific setting in it to undo.

## 3. Create the service

1. Push `master` (the Blueprint must exist on the branch Render reads).
2. Go to <https://dashboard.render.com/blueprints> → **New Blueprint Instance**.
3. Connect the GitHub repo `bbobbylon/angularSpringBootFullStack` and pick branch `master`.
4. Render reads [`../render.yaml`](../render.yaml) and shows one web service, `tesseraapp`, on the
   free plan, plus a form for every variable marked `sync: false`.
5. **Fill these in yourself, in Render's own form.** Most are credentials, and credentials
   belong in the dashboard — not in this repo, not in a chat, not in a commit. The ones the
   service will not start without:

   | Variable | Value |
   |---|---|
   | `SPRING_DATASOURCE_URL` | `jdbc:mysql://<host>.aivencloud.com:<port>/db3?sslMode=VERIFY_IDENTITY` |
   | `SPRING_DATASOURCE_USERNAME` | the Aiven user (`avnadmin`) |
   | `SPRING_DATASOURCE_PASSWORD` | the Aiven password |
   | `ORG_IDP_SECRET_ENCRYPTION_KEY` | **the existing value from AWS** — see the warning below |
   | `MAIL_USERNAME` / `MAIL_PASSWORD` | the Gmail address + 16-char App Password |
   | `UI_APP_URL` | `https://tesseraapp.onrender.com` — **not optional**, see below |
   | `VERIFY_EMAIL_HOST` | the same string as `UI_APP_URL` |

   The last two are the counter-intuitive ones, and an earlier draft of this file got them
   wrong by saying they could wait until §4. They cannot. `application.yml` writes these
   variables with **no default**, and `application-prod.yml` resolves CORS through
   `${CORS_ALLOWED_ORIGINS:${UI_APP_URL}}`, whose inner placeholder has no default either.
   Spring treats an unresolvable placeholder as a startup failure — that file says so in its
   own comment — so a blank field here is a container that builds, deploys, and then
   crash-loops without ever serving a request. `UI_APP_URL` has to be guessed before the URL
   exists; §4 covers checking the guess.

   `MAIL_HOST` and `MAIL_PORT` are in the same no-default category but are **not** secrets,
   so the Blueprint sets them (`smtp.gmail.com` / `587`) and Render will not ask.

   `OAUTH2_REDIRECT_BASE_URL` and the optional integrations (`GOOGLE_*`, `GITHUB_*`,
   `MICROSOFT_*`, `TURNSTILE_*`, `TWILIO_*`, `INTERNAL_DOMAINS`) *can* stay blank on the
   first pass — those genuinely degrade instead of crashing.

> ### ⚠️ `ORG_IDP_SECRET_ENCRYPTION_KEY` is not a "generate a new one" field
>
> It is the AES-256-GCM key that encrypted **every organization's OIDC client secret already
> stored in `db3`**. Pointing this deployment at that database with a *new* key does not fail
> loudly — it silently makes those rows undecryptable, and per-org SSO breaks with no way back.
> Paste the same value the AWS task definition uses (Secrets Manager). Generate a fresh one only
> against a genuinely empty database.
>
> `JWT_SECRET` is the opposite and needs no care: the Blueprint generates it, because it only
> signs sessions. A new one logs everyone out and nothing else.

6. **Apply.** The first build takes roughly 10–15 minutes — it runs `npm ci`, an Angular
   production build, and a full Maven package inside the image. Later builds are faster only when
   Docker layer caching hits.

## 4. After the first deploy — confirm the origin you guessed

§3 has you set `UI_APP_URL` before the service exists, which means the value is a
prediction. Render derives the hostname from the service name in the Blueprint, so
`tesseraapp` gives `https://tesseraapp.onrender.com` — unless that name is already taken
somewhere on Render, in which case a suffix is appended and your guess is wrong.

Once the first deploy finishes, read the real URL off the service page and compare. If it
differs, update `UI_APP_URL`, `OAUTH2_REDIRECT_BASE_URL` and `VERIFY_EMAIL_HOST` in
**Settings → Environment** and redeploy.

A wrong-but-resolvable `UI_APP_URL` is not a crash — the app boots fine. It fails later and
less obviously: CORS rejects the browser's own origin, and the WebAuthn relying-party id
will not match the host, so passkeys stop working. Worth the 30-second check.

## 5. Federated login (Google / GitHub / Microsoft)

The callback URL changes with the host, and each provider only accepts URLs registered in its own
console. For each provider you actually use, add:

```
https://<your-service>.onrender.com/login/oauth2/code/{google|github|microsoft}
```

- Google — <https://console.cloud.google.com/apis/credentials>
- GitHub — <https://github.com/settings/developers> (OAuth Apps)
- Microsoft — <https://entra.microsoft.com> (App registrations)

Leave a provider's `CLIENT_ID` unset and its button simply does not render (`GET
/oauth2/providers` drives the UI), so a half-configured provider is never shown to a user.

## 6. What the free tier genuinely cannot do

These are limitations to plan around, not bugs to fix:

- **It sleeps after 15 minutes idle** and takes ~50 s to answer the next request. The first
  visitor after a quiet spell waits.
- **512 MB of memory, total.** `JAVA_TOOL_OPTIONS` in the Blueprint sizes the heap to fit; if the
  service still OOMs under load, that is the ceiling talking, and the answer is a paid instance
  rather than more tuning.
- **No persistent disk.** `IMAGE_STORAGE_TYPE=local` writes avatars to a directory that is wiped
  on every deploy and every wake. Nothing errors — the files are just gone. To keep them, set
  `IMAGE_STORAGE_TYPE=s3` plus `AWS_REGION` / `AWS_S3_BUCKET` and static credentials, pointing at
  the same `tessera-app-images` bucket ECS uses. That is the one place this deployment would
  still depend on AWS, which is why it is off by default.
- **Free instance hours are shared** across the whole Render workspace (750/month), so other free
  services in the same account draw from the same pool.

## 7. `tesseraapp.dev` — the DNS cutover

Deliberately **not** part of getting this live. Do it as a separate, reversible step once the
Render service has proven itself:

1. Render → the service → **Settings → Custom Domains** → add `tesseraapp.dev`.
2. Point the domain's DNS at the target Render gives you.
3. Update `UI_APP_URL` and `OAUTH2_REDIRECT_BASE_URL` to `https://tesseraapp.dev`, and add that
   callback to each OAuth provider.

Until then the domain keeps pointing wherever it points today, and rolling back is a DNS change
rather than a redeploy.

## 8. Troubleshooting

| Symptom | Cause |
|---|---|
| Deploy builds, then the instance restarts before ever going healthy, logs end at `Could not resolve placeholder 'X'` | A required variable is blank. `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `UI_APP_URL`, `VERIFY_EMAIL_HOST` and the three `SPRING_DATASOURCE_*` have no defaults in `application.yml` — unset is a crash, not a disabled feature. |
| Build succeeds, service "unreachable" | `PORT` / `CONTAINER_PORT` disagree. Both must be `8080` — `application.yml` reads `CONTAINER_PORT`, not Render's `PORT`. |
| Instance restarts repeatedly, no stack trace | OOM. 512 MB is the ceiling; check `JAVA_TOOL_OPTIONS` survived. |
| `redirect_uri_mismatch` on federated sign-in | §4 or §5 not done — the origin is wrong, or the callback is not registered. |
| DB connects locally, fails on Render | The URL is missing `sslMode=VERIFY_IDENTITY`, or Aiven's IP allowlist excludes Render. |
| Per-org SSO broken after deploy | `ORG_IDP_SECRET_ENCRYPTION_KEY` does not match the one that encrypted the rows. See §3. |

## 9. Related

- [`../aws/README.md`](../aws/README.md) — the ECS Fargate environment, kept intact.
- [`../aws/RUNBOOK.md`](../aws/RUNBOOK.md) — including how to pause AWS without deleting it.
- [`../gcp/README.md`](../gcp/README.md) — the Cloud Run pipeline.
- [`../.env.example`](../.env.example) — the authoritative description of every variable above.
