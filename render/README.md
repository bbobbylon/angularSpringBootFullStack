# Render Deployment (free tier)

**Version:** 1.0
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
5. **Fill those in yourself in Render's own form.** They are credentials; they belong in the
   dashboard, not in this repo, not in a chat, not in a commit. The ones the service will not
   start without:

   | Variable | Value |
   |---|---|
   | `SPRING_DATASOURCE_URL` | `jdbc:mysql://<host>.aivencloud.com:<port>/db3?sslMode=VERIFY_IDENTITY` |
   | `SPRING_DATASOURCE_USERNAME` | the Aiven user (`avnadmin`) |
   | `SPRING_DATASOURCE_PASSWORD` | the Aiven password |
   | `ORG_IDP_SECRET_ENCRYPTION_KEY` | **the existing value from AWS** — see the warning below |
   | `MAIL_USERNAME` / `MAIL_PASSWORD` | the Gmail address + 16-char App Password |

   Everything else can stay blank on the first pass. `UI_APP_URL` and `OAUTH2_REDIRECT_BASE_URL`
   are filled in at §4, once the URL exists.

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

## 4. After the first deploy — set the public origin

The service comes up at `https://tesseraapp.onrender.com` (or whatever name Render assigns if
that one is taken). Two variables need that value, and could not have it before the service
existed:

- `UI_APP_URL` = `https://<your-service>.onrender.com`
- `OAUTH2_REDIRECT_BASE_URL` = the same string

Set both in **Settings → Environment**, then redeploy. `UI_APP_URL` also drives CORS and the
WebAuthn relying-party id, so leaving it wrong is not cosmetic.

**Ordinary email/password login works before this step.** It is federated sign-in, passkeys and
email verification links that depend on the origin being right.

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
