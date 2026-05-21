# Deep Dive: `POST /user/register` — Every Line, Every Layer

This document traces a single user action — **registering a new account** — from the moment the user
clicks the link to the moment they see the success screen, naming every file, variable, and
intermediate object that participates.

If you are new to this codebase, read this top to bottom. The same pattern (Angular form →
HttpClient → Spring filter chain → controller → service → repo → DB → response → state update)
applies to every endpoint in the app, so understanding registration unlocks the rest.

---

## Cast of characters (the files involved)

### Frontend (`securecapitaapp/`)
| File | Role |
|---|---|
| `src/app/app.routing-module.ts` | Maps the URL `/register` to `RegisterComponent` |
| `src/app/component/register/register.component.html` | The form markup |
| `src/app/component/register/register.component.ts` | Form submit handler + state observable |
| `src/app/service/user.service.ts` | `register$` — the HTTP call |
| `src/app/interceptor/token.interceptor.ts` | Decides whether to attach a JWT (it won't) |
| `src/app/interface/user.interface.ts` | TypeScript shape of `UserInterface` |
| `src/app/interface/customhttpresponse.interface.ts` | Shape of the JSON envelope from the server |
| `src/app/interface/appstates.interface.ts` | `RegisterStateInterface` for the view state |
| `src/app/enumeration/datastate.enum.ts` | `DataState.LOADING / LOADED / ERROR` |

### Backend (`src/main/java/com/bob/angularspringbootfullstack/`)
| File | Role |
|---|---|
| `configuration/SecurityConfig.java` | Whitelists `POST /user/register` as `permitAll` |
| `filter/CustomAuthFilter.java` | JWT filter that skips public routes (including `/user/register`) |
| `controller/UserController.java` | The `@PostMapping("/register")` method `saveUser` |
| `model/User.java` | The entity Spring binds the JSON to |
| `service/UserService.java` + `serviceimpl/UserServiceImpl.java` | `createUser` — business logic |
| `repo/UserRepo.java` + `repoimpl/UserRepoImpl.java` | `create` — DB operations |
| `query/UserQuery.java` | Holds the `INSERT_USER_QUERY` constant |
| `dtomapper/UserDTOMapper.java` | Converts `User` (entity) → `UserDTO` (API contract) |
| `dto/UserDTO.java` | The DTO returned to the client (no password field) |
| `model/HttpResponse.java` | Envelope that wraps every API response |
| `repo/repoimpl/RoleRepoImpl.java` | `addRoleToUser` — gives the new user `ROLE_USER` |

### Database (MySQL, schema `db2`)
| Table | Role |
|---|---|
| `users` | One row per registered user |
| `roles` | Lookup table of roles + permissions (pre-seeded from `schema.sql`) |
| `userroles` | Join table — links user → role |
| `accountverifications` | Stores the UUID URL emailed to the user for activation |

---

## Phase 0 — Before the click

```
User in browser, somewhere on the app (often /login).
HTML link rendered by another component:
    <a [routerLink]="['/register']">Sign up</a>
```

`[routerLink]` is an Angular directive. When clicked, Angular's `Router` intercepts the click,
prevents the default browser navigation, and pushes `/register` onto the browser history. No
network request happens here — this is purely client-side navigation.

---

## Phase 1 — The route resolves and the component instantiates

### Step 1.1 — Router consults `app.routing-module.ts`

```typescript
// app.routing-module.ts:26
{ path: 'register', component: RegisterComponent },
```

Angular matches `/register` to this entry. Three things happen in order:

1. **No guard** — there's no `canActivate` on this route, so the navigation is allowed unconditionally.
2. **Component lookup** — Angular finds `RegisterComponent` (a standalone component because of
   `standalone: true` in its decorator) and prepares to instantiate it.
3. **Imports resolved** — the component declares `imports: [FormsModule, RouterLink, AsyncPipe]`,
   so Angular ensures those directives/pipes are available to its template.

### Step 1.2 — `RegisterComponent` constructor runs

```typescript
// register.component.ts:24-27
export class RegisterComponent {
  registerState$: Observable<RegisterStateInterface> = of({ dataState: DataState.LOADED });
  readonly DataState = DataState;
  protected readonly userService = inject(UserService);
```

Three field initializers run in order:

1. **`registerState$`** — an RxJS `Observable` emitting a single value `{ dataState: DataState.LOADED }`.
   This is the initial state — "we're not loading, we're not in error, just sit and wait for the
   user to fill out the form." `of(...)` is the simplest possible observable: emit once, complete.

2. **`readonly DataState = DataState`** — exposes the `DataState` enum to the template. Angular
   templates can only access component-class members, so to use `DataState.LOADING` in HTML we
   first store a reference on the component.

3. **`protected readonly userService = inject(UserService)`** — Angular's modern dependency
   injection. `inject()` walks up the injector tree, finds the singleton `UserService` (registered
   `providedIn: 'root'`), and returns it. The single shared instance is reused everywhere.

### Step 1.3 — Template renders

The HTML at `register.component.html:1` opens with:

```html
@if (registerState$ | async; as state) {
```

This is Angular 17+ control flow syntax:

- **`async` pipe** — subscribes to `registerState$`, emits the latest value, and unsubscribes
  automatically when the component is destroyed. Manual `subscribe()` / `unsubscribe()` would leak
  memory if you forgot; `async` makes that impossible.
- **`as state`** — binds the emitted value to a template-local variable `state`. So inside this
  block `state.dataState`, `state.registerSuccess`, `state.message` are all accessible.

Because `registerState$` initially emits `{ dataState: DataState.LOADED }`:
- `state.registerSuccess` is `undefined` (falsy) → `!state.registerSuccess` is `true` → the FORM
  block renders (lines 2-70).
- The "success screen" block (lines 73-101) does **not** render.

### Step 1.4 — The form's `NgForm` directive activates

```html
<!-- register.component.html:19 -->
<form #registerForm="ngForm" (ngSubmit)="register(registerForm)">
```

Three things happen here invisibly:

1. **`#registerForm="ngForm"`** — declares a template reference variable. The `FormsModule` (in
   the component's imports) provides the `NgForm` directive, which attaches itself to every
   `<form>` element. `#registerForm` now refers to that `NgForm` instance.
2. **`(ngSubmit)="register(registerForm)"`** — binds the form's submit event. When the form
   submits, Angular calls `this.register(registerForm)` with the directive instance as the arg.
3. **NgForm starts tracking state** — `pristine: true`, `touched: false`, `valid: false` (until
   we add and validate controls).

### Step 1.5 — Each `<input>` registers itself as a form control

For each input field, look at, e.g., line 22:

```html
<input ... name="firstName" ngModel required minlength="2" type="text">
```

- **`ngModel`** (with no value binding) creates a form control inside `NgForm`. The field is
  registered under the name from the `name` attribute (`firstName`). After all four inputs
  initialize, `registerForm.value` is:
  ```typescript
  { firstName: '', lastName: '', email: '', password: '' }
  ```
- **`required` + `minlength="2"`** — built-in Angular validators. They run on every change to the
  field. If the value fails any of them, the control's `valid` flag is false, which propagates up
  so the parent `NgForm`'s `valid` flag is also false.

### Step 1.6 — The submit button gating expression

```html
<!-- register.component.html:41-43 -->
<button
  [disabled]="state.dataState === DataState.LOADING || registerForm.invalid || registerForm.pristine"
  class="btn btn-primary" type="submit">
```

The button is disabled when ANY of three are true:
1. We're currently in the middle of a network call (`state.dataState === LOADING`)
2. The form is invalid (any validator failing)
3. The form is `pristine` (user has never touched a field) — prevents accidental submits

Initial state: `pristine === true`, so the button is greyed out.

---

## Phase 2 — User types into the form

### Step 2.1 — Keystroke into `firstName`

The user types `J`. The browser fires a native `input` event on the `<input>`.

1. `FormsModule`'s `NgModel` directive listens for the `input` event.
2. It calls a setter that updates the internal form control's value to `"J"`.
3. The form control transitions from `pristine: true` to `pristine: false` (the user has touched
   it now) and from `dirty: false` to `dirty: true`.
4. The validators run: `required` → pass (value is non-empty). `minlength="2"` → fail (1 < 2).
   The control's `valid` flag is now `false`, and `errors` is `{ minlength: { ... } }`.
5. The parent `NgForm` recomputes its aggregate state. Now `registerForm.invalid` is `true`,
   `registerForm.pristine` is `false`.
6. Angular's change detection runs. The button's `[disabled]` expression is re-evaluated:
   - `state.dataState === LOADING` → false
   - `registerForm.invalid` → still true (other fields are still empty)
   - `registerForm.pristine` → now false
   - Disabled remains `true`.

### Step 2.2 — Once every field passes validation

After the user types `John`, `Doe`, `john@example.com`, `P@ssw0rd123`:

- `firstName` (`"John"`) — required ✓, minlength 2 ✓ → valid
- `lastName` (`"Doe"`) — required ✓, minlength 2 ✓ → valid
- `email` (`"john@example.com"`) — required ✓, minlength 3 ✓, **`type="email"`** triggers email format check ✓ → valid
- `password` (`"P@ssw0rd123"`) — required ✓, minlength 4 ✓ → valid

`registerForm.valid === true`. The button's `[disabled]` is now `false` and it's clickable.

`registerForm.value` is:
```typescript
{ firstName: "John", lastName: "Doe", email: "john@example.com", password: "P@ssw0rd123" }
```

This is a plain JavaScript object built by `NgForm` from each child `NgModel` control's value.

---

## Phase 3 — User clicks "Create Account"

### Step 3.1 — Submit event fires

The button is `type="submit"`, so clicking it triggers the form's `submit` event. `NgForm` listens
for that event and emits `ngSubmit`. The bound expression `register(registerForm)` runs.

### Step 3.2 — `register(registerForm)` in the component

```typescript
// register.component.ts:39-51
register(registerForm: NgForm): void {
  this.registerState$ = this.userService.register$(registerForm.value).pipe(
    map((response) => {
      console.log(response);
      registerForm.reset();
      return { dataState: DataState.LOADED, registerSuccess: true, message: response.message };
    }),
    startWith({ dataState: DataState.LOADING, registerSuccess: false }),
    catchError((error: string) => {
      return of({ dataState: DataState.ERROR, registerError: true, error });
    }),
  );
}
```

This **replaces** `registerState$` with a new observable. The async pipe in the template
automatically unsubscribes from the old one and subscribes to the new one.

The new observable pipeline reads top-to-bottom in source code, but RxJS operators apply in this
runtime order:

1. **`startWith({ dataState: DataState.LOADING, ... })`** — emits a synthetic first value
   IMMEDIATELY, synchronously, **before** the HTTP request is even sent. This is why the
   "Saving..." spinner appears the instant you click.
2. **`userService.register$(...)`** — the underlying observable (the HTTP call). It hasn't emitted
   yet — we're still in the same JavaScript turn as the click.
3. **`map(...)`** — when the HTTP response arrives, transforms it into a "success" state object.
4. **`catchError(...)`** — if the HTTP observable throws, emit a single error state and complete
   the stream.

The `async` pipe in the template now sees three possible emissions:
- First: `{ dataState: LOADING, registerSuccess: false }` → button shows spinner
- Then either:
  - Success: `{ dataState: LOADED, registerSuccess: true, message: "..." }` → success screen
  - Error: `{ dataState: ERROR, registerError: true, error: "..." }` → error alert above form

### Step 3.3 — `userService.register$` is called

```typescript
// user.service.ts:68-71
register$ = (user: UserInterface & { password: string }): Observable<CustomHttpResponseInterface<ProfileInterface>> =>
  this.http
    .post<CustomHttpResponseInterface<ProfileInterface>>(`${this.server}/user/register`, user)
    .pipe(tap(console.log), catchError(this.handleError));
```

Breakdown:

- **Parameter `user`** — typed as `UserInterface & { password: string }`. `UserInterface` itself
  doesn't include `password` (it never gets returned from the API, so the response type doesn't
  carry it). The intersection forces the *call site* to include `password`, even though responses
  never echo it.
- **`this.http.post<T>(url, body)`** — Angular's `HttpClient`. Returns a "cold" observable —
  nothing happens until something subscribes. The `<T>` generic specifies the response type so
  TypeScript can type-check downstream operators.
- **`tap(console.log)`** — when a value flows through, log it to the browser console (side
  effect only, doesn't change the value). Useful for debugging without affecting behavior.
- **`catchError(this.handleError)`** — normalizes errors. Whatever the server returns (4xx, 5xx,
  network errors), `handleError` turns it into a single uniform error message.

At this point: the observable returned by `register$` is **constructed but not subscribed**. The
HTTP request hasn't been sent yet.

### Step 3.4 — `async` pipe subscribes, which finally triggers the HTTP request

The chain back in the component (`this.registerState$ = ... .pipe(...)`) is also still just
constructed. The actual subscription happens because the async pipe in the template re-runs:
1. Old `registerState$` was replaced (the assignment in step 3.2).
2. Async pipe sees the new observable; it subscribes to it.
3. Subscription propagates DOWN the pipe chain: `catchError` subscribes to its source, which is
   `startWith`, which subscribes to its source, which is the result of `userService.register$()`,
   which subscribes to `this.http.post(...)`, which **finally** actually fires the request.

`startWith` synchronously emits its initial value before `http.post` has time to do anything, so
the template re-renders into the LOADING state immediately.

---

## Phase 4 — The HTTP request leaves the browser

### Step 4.1 — The token interceptor inspects the request

Before the request leaves the browser, it passes through Angular's interceptor chain. The only
interceptor in this app is `tokenInterceptor`:

```typescript
// token.interceptor.ts:49-53
const publicRoutes = ['login', 'register', 'verify', 'resetpassword', 'refresh'];

if (publicRoutes.some(route => req.url.includes(route))) {
  return next(req);
}
```

The URL is `http://localhost:8080/user/register`. The substring `"register"` is in `publicRoutes`,
so the interceptor immediately forwards the request unchanged. **No `Authorization: Bearer` header
is attached** — which is correct, since the user obviously isn't logged in yet.

### Step 4.2 — The actual HTTP request

```
POST http://localhost:8080/user/register HTTP/1.1
Host: localhost:8080
Content-Type: application/json
Accept: application/json, text/plain, */*
Origin: http://localhost:4200
Referer: http://localhost:4200/register

{"firstName":"John","lastName":"Doe","email":"john@example.com","password":"P@ssw0rd123"}
```

In dev, this is a cross-origin request (Angular dev server on `:4200` → Spring backend on `:8080`).
The browser handles the CORS dance — first a preflight `OPTIONS` request, then this `POST`. Spring's
`SecurityConfig` allows `http://localhost:4200` as an origin.

---

## Phase 5 — Spring's filter chain receives the request

Tomcat (Spring Boot's embedded servlet container) accepts the connection and passes the request
into the Servlet API. Spring's `DispatcherServlet` is the entry servlet, but **before** the
DispatcherServlet runs, the Spring Security filter chain runs.

### Step 5.1 — `CustomAuthFilter.shouldNotFilter` short-circuits

```java
// CustomAuthFilter.java:55, 72-75
private static final String[] PUBLIC_ROUTES = {
    "/user/login", "/user/verify/code", "/user/register", "/actuator",
    "/user/refresh/token", "/user/image", "/user/verify/account"
};

@Override
protected boolean shouldNotFilter(HttpServletRequest request) {
  return request.getHeader(AUTHORIZATION) == null
      || !request.getHeader(AUTHORIZATION).startsWith(TOKEN_PREFIX)
      || request.getMethod().equalsIgnoreCase(HTTP_METHOD_OPTIONS)
      || asList(PUBLIC_ROUTES).contains(request.getRequestURI());
}
```

The request has no `Authorization` header (the interceptor didn't add one), so the first
condition is true. `shouldNotFilter` returns `true` and the filter is skipped entirely. The
SecurityContext stays empty (no authenticated user).

### Step 5.2 — `SecurityConfig.securityFilterChain` checks the authorization rules

```java
// SecurityConfig.java (abbreviated)
.authorizeHttpRequests(auth -> auth
    .requestMatchers(POST, "/user/register").permitAll()
    ...
)
```

`POST /user/register` matches `permitAll()` — no authentication required. The chain proceeds.

### Step 5.3 — `DispatcherServlet` routes to the matching controller method

Spring's `RequestMappingHandlerMapping` scans all `@RestController` beans for methods whose
`@RequestMapping` (or specialization like `@PostMapping`) matches the request method + path.

It finds:

```java
// UserController.java:102
@PostMapping("/register")  // inherits class-level @RequestMapping("/user")
public ResponseEntity<HttpResponse> saveUser(@RequestBody @Valid User user) {
```

Selected. Now Spring must construct the arguments.

---

## Phase 6 — Inside the controller

### Step 6.1 — `@RequestBody` triggers JSON deserialization

Spring sees `@RequestBody User user`. It needs to construct a `User` Java object from the JSON
body. `HttpMessageConverters` does this — for `Content-Type: application/json`, the
`MappingJackson2HttpMessageConverter` is selected. It uses Jackson to deserialize:

```json
{
  "firstName": "John",
  "lastName": "Doe",
  "email": "john@example.com",
  "password": "P@ssw0rd123"
}
```

into a `User` instance:

```java
User user = new User();
user.setFirstName("John");
user.setLastName("Doe");
user.setEmail("john@example.com");
user.setPassword("P@ssw0rd123");
// Other fields stay at defaults: id=null, imageUrl=null, enabled=false, isNotLocked=false, ...
```

The setters exist because of Lombok's `@Data` annotation on `User.java`.

### Step 6.2 — `@Valid` runs validation

```java
// User.java:28-35
@NotEmpty(message = "First name is required")  private String firstName;
@NotEmpty(message = "Last name is required")   private String lastName;
@Email(message = "Email is required")          private String email;
@NotEmpty(message = "Password is required")    private String password;
```

The `@Valid` annotation tells Spring to run Jakarta Bean Validation on the deserialized object.
Each field's validator runs:
- `@NotEmpty firstName` — pass
- `@NotEmpty lastName` — pass
- `@Email email` — pass (valid email format)
- `@NotEmpty password` — pass

If any failed, Spring would throw `MethodArgumentNotValidException` BEFORE entering the method
body, and `GlobalExceptionHandler` would catch it and return a 400 with validation details.

All passed — execution continues into `saveUser`.

### Step 6.3 — Controller calls the service

```java
// UserController.java:103-104
public ResponseEntity<HttpResponse> saveUser(@RequestBody @Valid User user) {
  UserDTO userDTO = userService.createUser(user);
```

`userService` is the `UserServiceImpl` bean, injected via `@RequiredArgsConstructor` (Lombok
generates the constructor; Spring autowires it).

---

## Phase 7 — Service layer

```java
// UserServiceImpl.java:38-41
@Override
public UserDTO createUser(User user) {
  return mapToUserDTO(userRepo.create(user));
}
```

Two things in one line:

1. **`userRepo.create(user)`** — delegate to the repository. Returns a `User` (with id populated
   after insert).
2. **`mapToUserDTO(...)`** — convert that `User` entity to a `UserDTO` (which lacks `password`).

The service layer is intentionally thin for `createUser`. Other methods in `UserServiceImpl` do
more orchestration; this one is essentially "call repo + map to DTO."

---

## Phase 8 — Repository layer + database

```java
// UserRepoImpl.java:127-153
@Override
@Transactional
public User create(User user) {
  if (getEmailCount(user.getEmail().trim().toLowerCase()) > 0)
    throw new ApiException("Email already exists, please use a different email address and try again");

  log.info("Creating new user with email: {}", user.getEmail());
  try {
    KeyHolder holder = new GeneratedKeyHolder();
    SqlParameterSource parameterSource = getSqlParameterSource(user);
    jdbcTemplate.update(INSERT_USER_QUERY, parameterSource, holder);
    user.setId(requireNonNull(holder.getKey()).longValue());

    roleRepository.addRoleToUser(user.getId(), ROLE_USER.name());

    String verificationURL = getVerificationURL(UUID.randomUUID().toString(), ACCOUNT.getType());
    jdbcTemplate.update(INSERT_ACCOUNT_VERIFICATION_URL_QUERY, of("userId", user.getId(), "url", verificationURL, "type", ACCOUNT.getType()));
    log.info("Account verification url {} sent to user with email: {}", verificationURL, user.getEmail());

    user.setEnabled(true);
    user.setNotLocked(true);
    return user;
  } catch (Exception exception) {
    log.error("Error creating user: {}", exception.getMessage(), exception);
    throw new ApiException("WE DON'T KNOW WHAT KIND, BUT SOME KIND OF ERROR HAS OCCURRED. SORRY!");
  }
}
```

### Step 8.1 — `@Transactional` opens a DB transaction

Before the method body executes, Spring's transaction interceptor wraps the call. A new
transaction is started — either everything inside commits, or everything rolls back. Critical for
multi-statement operations (insert user + insert role + insert verification URL).

### Step 8.2 — Email uniqueness check

```java
if (getEmailCount(user.getEmail().trim().toLowerCase()) > 0)
  throw new ApiException("Email already exists...");
```

`getEmailCount` runs `SELECT COUNT(*) FROM users WHERE email = :email`. If non-zero, the user
already exists. **Note:** the email is `trim().toLowerCase()`'d so the check is case-insensitive
and whitespace-tolerant (`"  John@Example.com  "` and `"john@example.com"` are treated as same).

For a fresh email, count is 0 — execution continues.

### Step 8.3 — Prepare SQL parameters

```java
// UserRepoImpl.java:174-180
private SqlParameterSource getSqlParameterSource(User user) {
  return new MapSqlParameterSource()
      .addValue("firstName", user.getFirstName())
      .addValue("lastName", user.getLastName())
      .addValue("email", user.getEmail().trim().toLowerCase())
      .addValue("password", passwordEncoder.encode(user.getPassword()));
}
```

The single most important line: `passwordEncoder.encode(user.getPassword())`. This is BCrypt
hashing — turns `"P@ssw0rd123"` into something like
`"$2a$10$abcdef...XYZ"` (60 chars, includes a unique salt). The plaintext password is **never**
written to the DB.

`passwordEncoder` is a `BCryptPasswordEncoder` bean defined elsewhere; the rounds default to 10
(2^10 = 1024 iterations per hash, ~100ms on a typical server — slow on purpose to resist
brute-force attacks).

### Step 8.4 — `INSERT INTO users`

```sql
-- UserQuery.java:19
INSERT INTO users (first_name, last_name, email, password)
VALUES (:firstName, :lastName, :email, :password)
```

`jdbcTemplate.update(...)` executes this against MySQL via the JDBC driver. The `KeyHolder`
collects auto-generated columns — here, the `id` column (BIGINT auto-increment).

After the call:
- A new row exists in `users` with `id=42` (or whatever the next auto-increment was).
- `holder.getKey()` returns `42`.
- `user.setId(42L)` records it on the in-memory `User` object.

### Step 8.5 — `roleRepository.addRoleToUser(user.getId(), ROLE_USER.name())`

Inserts a row into the `userroles` join table linking this user to the `ROLE_USER` role. The role
itself was pre-seeded in `schema.sql`:
```sql
INSERT INTO roles (name, permission)
VALUES ('ROLE_USER', 'READ:USER, READ:CUSTOMER'), ...
```

So now the new user has the `ROLE_USER` role with permissions `READ:USER, READ:CUSTOMER`.

### Step 8.6 — Generate and store the verification URL

```java
String verificationURL = getVerificationURL(UUID.randomUUID().toString(), ACCOUNT.getType());
```

- `UUID.randomUUID().toString()` → a v4 random UUID like `"550e8400-e29b-41d4-a716-446655440000"`.
- `ACCOUNT.getType()` → the string `"account"` (from the `VerificationType` enum).
- `getVerificationURL(...)` builds the URL using Spring's `ServletUriComponentsBuilder.fromCurrentContextPath()`,
  which reads the server's current host/port from the request. Result:
  `"http://localhost:8080/user/verify/account/550e8400-e29b-41d4-a716-446655440000"`

Then it's persisted:
```sql
INSERT INTO accountverifications (user_id, url) VALUES (:userId, :url)
```

This row will later be looked up when the user clicks the verification link — see
`UserController.verifyAccount`.

### Step 8.7 — The URL is logged (no real email yet)

```java
log.info("Account verification url {} sent to user with email: {}", verificationURL, user.getEmail());
```

This is what shows up in the Spring console:
```
INFO ... Account verification url http://localhost:8080/user/verify/account/550e8400-... sent to user with email: john@example.com
```

In a real production app, an `EmailService` would send this URL via SMTP. For this tutorial app
the link is just logged; the developer grabs it from the console for testing.

### Step 8.8 — In-memory flags set

```java
user.setEnabled(true);
user.setNotLocked(true);
return user;
```

> **WAIT.** Two important caveats here:
>
> 1. **The DB column for `enabled` defaults to FALSE.** This `setEnabled(true)` only updates the
>    *in-memory* Java object — it does NOT issue an `UPDATE` statement. So the row in MySQL still
>    has `enabled=0`. This is why login still fails ("User is disabled") until the user clicks the
>    verification URL — the `verifyAccount` endpoint is what actually flips the DB column to true.
> 2. **The return value carries these flag changes** anyway, so the HTTP response includes
>    `"enabled": true` — which is misleading to the client. A purist would either persist the flag
>    immediately or omit it from the response. This is one of the project's known quirks.

### Step 8.9 — `@Transactional` commits

The method returns normally → no exception → the transaction commits. All three INSERTs (user,
userroles, accountverifications) are durably saved.

---

## Phase 9 — Mapping `User` → `UserDTO`

Back in `UserServiceImpl.createUser`:

```java
return mapToUserDTO(userRepo.create(user));
```

`mapToUserDTO` calls `UserDTOMapper.fromUser`:

```java
// UserDTOMapper.java:31-35
public static UserDTO fromUser(User user) {
  UserDTO userDTO = new UserDTO();
  BeanUtils.copyProperties(user, userDTO);
  return userDTO;
}
```

**Why a separate DTO?**

- `User.java` has a `password` field. If we returned `User` directly, Jackson would serialize the
  password (hashed, but still — never put it in API responses).
- `UserDTO.java` is missing the `password` field. `BeanUtils.copyProperties` does a property-name
  match — fields without a match on the destination are silently skipped. So `password` doesn't
  get copied, and it never reaches the JSON response.

The resulting `UserDTO`:
```java
UserDTO {
  id=42,
  firstName="John",
  lastName="Doe",
  email="john@example.com",
  enabled=true,        // (in-memory only, see warning above)
  isNotLocked=true,    // (in-memory only)
  isUsing2FA=false,
  createdAt=null,      // not yet loaded from DB
  roleName=null,       // not populated by this method
  permissions=null,    // not populated by this method
  // ...
}
```

---

## Phase 10 — Building the HTTP response

Back in `UserController.saveUser`:

```java
// UserController.java:105-112
return ResponseEntity.created(getUri()).body(
  HttpResponse.builder()
    .timeStamp(now().toString())
    .data(of("user", userDTO))
    .message(String.format("User created successfully for user: " + userDTO.getEmail()))
    .status(CREATED)
    .statusCode(CREATED.value())
    .build());
```

Step by step:

1. **`HttpResponse.builder()`** — Lombok-generated builder (from `@SuperBuilder` on `HttpResponse`).
2. **`.timeStamp(now().toString())`** — `now()` is `java.time.LocalTime.now()` (static import).
   Records a timestamp like `"12:30:45.123456"`.
3. **`.data(of("user", userDTO))`** — `of("user", userDTO)` is `Map.of("user", userDTO)` (static
   import from `java.util.Map`). Creates an immutable single-entry map.
4. **`.message(...)`** — human-readable string.
5. **`.status(CREATED)`** — `HttpStatus.CREATED` enum (static import).
6. **`.statusCode(CREATED.value())`** — the numeric 201.
7. **`.build()`** — produces the `HttpResponse` instance.

Then `ResponseEntity.created(getUri())`:
- Wraps the body in a 201 Created response.
- `getUri()` returns the URI the new resource lives at, used for the `Location` header.

```java
// UserController.java:121-123
private URI getUri() {
  return URI.create(ServletUriComponentsBuilder.fromCurrentContextPath()
      .path("/user/get/<userId>").toUriString());
}
```

(Note: this is a placeholder; the literal `<userId>` is a TODO to be substituted with the real
new user's ID. Functionally inert because the client doesn't currently use the Location header.)

### Step 10.1 — Jackson serializes back to JSON

Spring's `MappingJackson2HttpMessageConverter` serializes the `HttpResponse` back to JSON. The
`@JsonInclude(NON_DEFAULT)` annotation on `HttpResponse` and `User` strips any fields that are
at their default value (null, false, 0) — keeping the response lean.

The response body:
```json
{
  "timeStamp": "12:30:45.123456",
  "statusCode": 201,
  "status": "CREATED",
  "message": "User created successfully for user: john@example.com",
  "data": {
    "user": {
      "id": 42,
      "firstName": "John",
      "lastName": "Doe",
      "email": "john@example.com",
      "enabled": true,
      "notLocked": true
    }
  }
}
```

The HTTP response:
```
HTTP/1.1 201 Created
Content-Type: application/json
Location: http://localhost:8080/user/get/<userId>
Access-Control-Allow-Origin: http://localhost:4200
Access-Control-Allow-Credentials: true

{...the JSON above...}
```

---

## Phase 11 — Back to the browser

### Step 11.1 — `HttpClient` emits the response

The 201 status + body flow back through Tomcat → the browser → Angular's `HttpClient`. The
observable returned by `this.http.post(...)` emits the deserialized response object.

### Step 11.2 — `tap(console.log)` fires

```typescript
.pipe(tap(console.log), catchError(this.handleError));
```

The full response object is logged to the browser console. Useful for debugging but no behavior
change.

### Step 11.3 — The `map` operator in the component runs

```typescript
// register.component.ts:41-45
map((response) => {
  console.log(response);
  registerForm.reset();
  return { dataState: DataState.LOADED, registerSuccess: true, message: response.message };
}),
```

1. `console.log(response)` — second log (this one for the component-layer perspective).
2. **`registerForm.reset()`** — clears every form control. All four inputs go back to empty
   strings; form is `pristine` again.
3. Returns a new state object: `{ dataState: LOADED, registerSuccess: true, message: "User created successfully for user: john@example.com" }`.

This object is emitted by the observable. The async pipe receives it and re-renders the template.

### Step 11.4 — The success screen renders

```html
<!-- register.component.html:73-101 -->
@if (state.registerSuccess) {
  <div class="container">
    ...
    <h5 class="card-title mt-4 mb-4">{{ state.message }}</h5>
    <i class="bi bi-check-circle-fill" style="font-size: 80px; color: green;"></i>
    <p class="mt-2" style="font-size: 20px;">Please access your email and confirm your account.</p>
    ...
  </div>
}
```

Because `state.registerSuccess === true`, the `!state.registerSuccess` branch (the form) is
removed from the DOM and the success branch is rendered.

The user sees:
- The SecureCapita header
- The server's success message
- A big green checkmark
- "Please access your email and confirm your account."
- Links to "Account Login" and "Create another account"

---

## Phase 12 — What still needs to happen

The user **cannot yet log in.** The `users.enabled` column in MySQL is still `0` because step
8.8's `setEnabled(true)` only updated the in-memory object, not the DB. Login will return:

```json
{ "reason": "User is disabled", "statusCode": 400, ... }
```

To activate the account, the user clicks the verification URL the server logged:

```
http://localhost:8080/user/verify/account/550e8400-e29b-41d4-a716-446655440000
```

(In dev, the developer changes `:8080` → `:4200` so it hits the Angular dev server first; see
`app.routing-module.ts` route `user/verify/account/:key`.)

This is a separate flow that:
1. Resolves the route to `VerifyComponent`
2. Calls `GET /user/verify/account/<key>`
3. The backend looks up the `accountverifications` row by URL
4. Flips `users.enabled` to TRUE
5. Returns success
6. The Angular component shows "Verified :)" with a link back to login

After verification, the user can log in successfully.

---

## Recap diagram

```
USER CLICKS "Create Account"
   │
   ▼ form.ngSubmit → register(registerForm)
[register.component.ts] register()
   │  registerState$ = userService.register$(form.value).pipe(...)
   │  startWith() ─────────► template re-renders into LOADING
   │
   ▼ HttpClient subscribes ────► triggers the actual HTTP call
[token.interceptor.ts]
   │  URL contains "register" → skip token injection
   │
   ▼ POST http://localhost:8080/user/register
   │  body: { firstName, lastName, email, password }
   │
   ▼  ──────────────────────── network boundary ────────────────────────
   │
[Spring Security filter chain]
   │  CustomAuthFilter.shouldNotFilter() → true (no Authorization header)
   │  SecurityConfig: POST /user/register → permitAll
   │
   ▼ DispatcherServlet → @PostMapping("/register")
[UserController.saveUser]
   │  @RequestBody → Jackson parses JSON → User instance
   │  @Valid     → Bean Validation runs; passes
   │  delegates to userService.createUser(user)
   │
   ▼
[UserServiceImpl.createUser]
   │  delegates to userRepo.create(user)
   │  pipes result through mapToUserDTO()
   │
   ▼
[UserRepoImpl.create] @Transactional
   │  emailCount(...)                  ──► SELECT COUNT(*) FROM users WHERE email=...
   │  passwordEncoder.encode(password) ──► BCrypt hash
   │  INSERT INTO users ...            ──► MySQL row created, id=42
   │  roleRepository.addRoleToUser    ──► INSERT INTO userroles (user_id=42, role_id=...)
   │  UUID + URL                       ──► INSERT INTO accountverifications
   │  log.info("Account verification url ...")
   │  user.setEnabled(true)            ──► in-memory only (DB still has enabled=0)
   │
   ▲ User entity returned (with id, enabled=true in memory)
   │
[UserDTOMapper.fromUser]
   │  BeanUtils.copyProperties — password silently excluded
   │
   ▲ UserDTO
   │
[UserController.saveUser]
   │  HttpResponse.builder() … .data(Map.of("user", userDTO)).build()
   │  ResponseEntity.created(URI).body(httpResponse)
   │
   ▼ Jackson serializes → JSON → 201 Created
   │  ──────────────────────── network boundary ────────────────────────
   │
[Angular HttpClient]
   │  observable emits the response object
   │
   ▼ tap(console.log) — debug logging
   ▼ map() in component pipeline
[register.component.ts]
   │  registerForm.reset()
   │  returns { dataState: LOADED, registerSuccess: true, message: ... }
   │
   ▼ async pipe receives new state
   ▼ template re-renders into SUCCESS SCREEN
USER SEES: "Please access your email and confirm your account"
```

---

## How to use this document

When you change any single file involved in this flow — e.g., you rename `register$` to
`registerUser$`, or you swap out `BCryptPasswordEncoder`, or you change the route path — find that
file in the **Cast of characters** table and re-read its phase. The other phases stay the same.

If you want to write a similar trace for **another endpoint** (e.g., `POST /user/login`), the
shape is identical: replace each phase's specifics. Use this as the template.
