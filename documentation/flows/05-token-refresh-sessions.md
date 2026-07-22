# 05 · Token refresh, silent 401-refresh & session rotation

> Assumes [`00-anatomy-of-a-request.md`](./00-anatomy-of-a-request.md) (especially §6 JWT anatomy and
> §7.2 the 401→refresh branch). This doc goes deep on the **backend** half: rotation and reuse
> detection. For listing/revoking sessions in the UI, see [`12-sessions-and-devices.md`](./12-sessions-and-devices.md).

**Endpoint:** `GET /user/refresh/token` (public) — the **only** place a refresh token (`♻️`) is ever
sent. **Triggered by:** the `tokenInterceptor` automatically, when any protected request returns `401`.

---

## A · The silent refresh + rotation (happy path)

The user never sees this. An access token quietly expires (30 min), the next request 401s, and the
interceptor swaps in a fresh pair before the user notices.

```mermaid
sequenceDiagram
    autonumber
    participant CMP as (any component)
    participant TOK as tokenInterceptor
    participant SVC as UserService
    participant LS as localStorage
    participant FILT as CustomAuthFilter
    participant CTRL as UserController
    participant SESS as SessionServiceImpl
    participant TP as TokenProvider
    participant DB as refreshsessions

    CMP->>TOK: GET /user/profile (access token expired)
    TOK->>FILT: 🔑 expired access token
    FILT-->>TOK: 401 (entry point)
    Note over TOK: catchError sees 401 → handleRefreshToken  :57,101
    TOK->>TOK: isTokenRefreshing = true  :108
    TOK->>SVC: refreshToken$()  :106
    SVC->>FILT: GET /user/refresh/token<br/>Authorization: Bearer ♻️ refresh_token  user.service.ts:157
    Note over FILT: '/user/refresh/token' ∈ PUBLIC_ROUTES → filter SKIPPED  Constants:61
    FILT->>CTRL: sendNewRefreshToken(request)  :391
    Note over CTRL: reads Authorization header manually (no SecurityContext)
    CTRL->>SESS: rotate(refreshToken, request)  :404
    SESS->>TP: getSubject + isTokenValid (sig, exp, passwordChangedAt)  :106-109
    SESS->>TP: getTokenId → jti  :110
    SESS->>DB: SELECT * FROM refreshsessions WHERE jti  :115
    alt row missing
        DB-->>SESS: ∅ → ApiException "session could not be found"
    else row superseded/revoked
        DB-->>SESS: reuse → see branch B
    else row live
        DB-->>SESS: live session
        SESS->>DB: UPDATE superseded=TRUE, last_used_at=NOW() (old)  :124
        SESS->>DB: INSERT new row (same family, new jti, +5d)  :126
        SESS->>TP: createAccessToken + createRefreshToken (same family)  :131-132
        SESS-->>CTRL: TokenPair { user, 🔑 new access, ♻️ new refresh }
        CTRL-->>SVC: 200 { data:{ user, access_token, refresh_token } }
        SVC->>LS: overwrite both tokens (tap)  user.service.ts:159-165
        SVC-->>TOK: response
        TOK->>TOK: refreshTokenSubject.next(response)  :110
        TOK->>FILT: RETRY GET /user/profile + 🔑 new access  :113
        FILT->>CTRL: now authorized → 200 profile
        CTRL-->>CMP: original request resolves — user saw only a tiny delay
    end
```

### Sliding sessions
Each rotation INSERTs a row with a **fresh** 5-day expiry (`SessionServiceImpl.insertSessionRow:209`,
`REFRESH_TOKEN_EXPIRE_TIME`). So an actively-used device stays signed in indefinitely, while an idle
one ages out after 5 days — the row and its JWT always share the same horizon
(`SessionServiceImpl.java:51-53`).

---

## B · Reuse detection (stolen-token replay)

If an *old* (already-superseded) refresh token is presented — the signature of a stolen-and-replayed
token, or a token that leaked before rotation — the response is to **revoke the entire family**, not
just refuse the one token:

```mermaid
sequenceDiagram
    autonumber
    participant ATT as Attacker (replays old ♻️)
    participant CTRL as UserController
    participant SESS as SessionServiceImpl
    participant DB as refreshsessions
    participant EV as userevents
    ATT->>CTRL: GET /user/refresh/token + old ♻️
    CTRL->>SESS: rotate(oldRefresh)  :404
    SESS->>DB: SELECT by jti → row.superseded == TRUE  :119
    Note over SESS: handleReuse()  :120,183
    SESS->>DB: UPDATE revoked=TRUE WHERE family (REVOKE_FAMILY_QUERY) — COMMITS  :186
    SESS->>EV: publish TOKEN_REUSE_DETECTED audit event  :188
    SESS-->>CTRL: throw ApiException "This session has been revoked for your security."
    CTRL-->>ATT: 400 (and the legitimate user's whole family is now dead)
```

Because the revocation **commits before** the throw (the method is intentionally *not*
`@Transactional`, `SessionServiceImpl.java:42-49`), the attacker's replay permanently kills the
family. The real user's next refresh fails too, forcing a clean re-login — the safe outcome when
theft is suspected. The incident shows up in the user's own activity log via
`TOKEN_REUSE_DETECTED` ([`10-profile-and-account.md`](./10-profile-and-account.md)).

---

## C · Concurrent 401s collapse onto one refresh

If several requests 401 at once (common on a page that fires parallel GETs), only the **first**
performs the refresh; the rest wait on a shared `BehaviorSubject` and retry with the token the
single refresh produced — no thundering herd of refresh calls:

```mermaid
flowchart TD
    A["Request #1 → 401"] --> B{"isTokenRefreshing?<br/>token.interceptor.ts:102"}
    B -->|"false → I'll refresh"| C["refreshToken$()<br/>set flag, subject.next(null)"]
    A2["Requests #2..N → 401 (same tick)"] --> B
    B -->|"true → wait"| D["refreshTokenSubject<br/>.filter(!=null).take(1)  :124-127"]
    C --> E["new tokens → subject.next(response)  :110"]
    E --> D
    D --> F["each retries with shared new access token  :127"]
```

State lives in module-level `isTokenRefreshing` + `refreshTokenSubject`
(`token.interceptor.ts:11-12`). If the refresh itself fails, both tokens are cleared
(`:117-118`) and the error propagates so the app routes to `/login`.

---

## D · Failure paths

| Failure | Where | Result |
| --- | --- | --- |
| No/!Bearer Authorization header | `sendNewRefreshToken` guard (`UserController.java:393-403`) | `400` "Invalid or missing token" |
| Expired refresh / password changed | `rotate` → `isTokenValid` false (`SessionServiceImpl:107`) | `400` "Your session has expired. Please log in again." |
| Pre-M5 token (no `jti`) | `rotate` (`:111-113`) | `400` "Your session needs to be renewed." |
| Unknown `jti` | `findByJti` null (`:116-118`) | `400` "Your session could not be found." |
| Superseded/revoked (reuse) | `handleReuse` (`:119-122`) | `400` + family revoked + audit event |
| Refresh fails in interceptor | `token.interceptor.ts:115-119` | tokens cleared → redirect to `/login` |

---

## E · Wire-level detail

### Request — the one place `♻️` is sent
```http
GET /user/refresh/token HTTP/1.1
Host: localhost:8080
Authorization: Bearer eyJhbGciOiJIUzUxMiJ9.<refresh-payload>.<sig>     ← ♻️ refresh token, NOT access
Accept: application/json
```
Built explicitly in `refreshToken$` (`user.service.ts:153-157`) — note this is the *only* service
method that reads `Key.REFRESH_TOKEN` and puts it in the header. Everywhere else the interceptor
attaches the *access* token. Also note `refresh` is in both interceptors' bypass lists, so neither
caches it nor attaches the access token over it.

### Refresh-token JWT claims (decoded payload)
```jsonc
{ "iss": "BOBBYLON_LLC", "aud": "BOBS_MANAGEMENT",
  "jti": "9b1c…",            // row key in refreshsessions — the rotation pivot
  "sid": "4af2…",            // session family (shared with the access token)
  "sub": "42",               // user ID
  "iat": 1750000000, "exp": 1750432000 }   // 5-day lifetime; NO "authorities" claim
```
(Minted by `TokenProvider.createRefreshToken`, `:113`. The absent `authorities` claim is why
`CustomAuthFilter` refuses to authenticate it anywhere but here — [`00 §4.2`](./00-anatomy-of-a-request.md).)

### Success response (`200`)
```jsonc
{ "timeStamp":"…",
  "data": { "user": { …UserDTO… },
            "access_token": "eyJ…",     // 🔑 new, 30 min
            "refresh_token": "eyJ…" },  // ♻️ new, fresh 5-day window, SAME family
  "message":"New refresh token sent successfully!", "status":"OK","statusCode":200 }
```

### SQL executed

| Step | Query constant | SQL |
| --- | --- | --- |
| resolve token | `SessionQuery.SELECT_SESSION_BY_JTI_QUERY` | `SELECT * FROM refreshsessions WHERE jti = :jti` |
| retire old (rotate) | `SessionQuery.SUPERSEDE_SESSION_QUERY` | `UPDATE refreshsessions SET superseded=TRUE, last_used_at=NOW() WHERE id=:id` |
| issue new (rotate) | `SessionQuery.INSERT_SESSION_QUERY` | `INSERT INTO refreshsessions (user_id,family,jti,device,ip_address,expires_at) VALUES (…)` |
| reuse response | `SessionQuery.REVOKE_FAMILY_QUERY` | `UPDATE refreshsessions SET revoked=TRUE WHERE family=:family AND revoked=FALSE` |

---

## Cross-links
- The interceptor branch that calls this → [`00 §7.2`](./00-anatomy-of-a-request.md)
- Listing & manually revoking these sessions → [`12-sessions-and-devices.md`](./12-sessions-and-devices.md)
- Password change, which `revokeAllSessions` + reissues here → [`10-profile-and-account.md`](./10-profile-and-account.md)
- Where families are first opened (login/verify) → [`02-login-and-mfa.md`](./02-login-and-mfa.md)
