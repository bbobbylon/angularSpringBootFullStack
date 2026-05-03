# Complete Spring Security Flow Documentation
## Your Angular Spring Boot Full Stack Application

---

## TABLE OF CONTENTS
1. [Complete Authentication Flow](#complete-authentication-flow)
2. [JWT Token Generation](#jwt-token-generation)
3. [GrantedAuthorities System](#grantedauthorities-system)
4. [Database Role/Permission Structure](#database-rolepermission-structure)
5. [Security Filter Chain Decisions](#security-filter-chain-decisions)
6. [Exception Handling (401 vs 403)](#exception-handling-401-vs-403)
7. [Method-to-Method Interaction](#method-to-method-interaction)

---

## Complete Authentication Flow

### STEP-BY-STEP: User Login Process

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. USER SUBMITS LOGIN REQUEST                                              │
├─────────────────────────────────────────────────────────────────────────────┤
│ POST /user/login                                                            │
│ Content-Type: application/json                                              │
│ {                                                                            │
│   "email": "bob@example.com",                                              │
│   "password": "myPassword123"                                              │
│ }                                                                            │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. SECURITY FILTER CHAIN INTERCEPTS REQUEST                                │
├─────────────────────────────────────────────────────────────────────────────┤
│ SecurityFilterChain.securityFilterChain() checks:                          │
│ - Is /user/login in public URLs? YES                                       │
│ - Allow request without authentication                                     │
│ - Route to UserController.login(loginForm)                                │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. CONTROLLER INITIATES AUTHENTICATION                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│ UserController.login(LoginForm form):                                      │
│                                                                             │
│ authenticationManager.authenticate(                                        │
│   new UsernamePasswordAuthenticationToken(                                 │
│     form.getEmail(),      // "bob@example.com"                            │
│     form.getPassword()    // "myPassword123"                              │
│   )                                                                         │
│ );                                                                          │
│                                                                             │
│ This token represents: "Someone claims to be bob@example.com with this pwd" │
│ Now AuthenticationManager will VERIFY this claim                           │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ 4. AUTHENTICATION MANAGER DELEGATES TO PROVIDER                            │
├─────────────────────────────────────────────────────────────────────────────┤
│ AuthenticationManager (ProviderManager).authenticate(token):               │
│   - Looks for AuthenticationProvider that can handle the token             │
│   - Finds: DaoAuthenticationProvider                                       │
│   - Calls: daoAuthenticationProvider.authenticate(token)                   │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ 5. DAO AUTHENTICATION PROVIDER - LOAD USER FROM DATABASE                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ DaoAuthenticationProvider.authenticate():                                  │
│   a) Extract email from token: "bob@example.com"                          │
│   b) Call: userDetailsService.loadUserByUsername(email)                   │
│      → Calls UserRepoImpl.loadUserByUsername(email)                        │
│      → Execute: SELECT * FROM users WHERE email = ?                       │
│      → Get User entity from database                                      │
│      → Call: roleRepository.getRoleByUserId(user.id)                      │
│         → Execute: SELECT r.* FROM roles r                               │
│                    JOIN userroles ur ON r.id = ur.role_id               │
│                    WHERE ur.user_id = ?                                 │
│      → Get Role entity with permission field                              │
│      → Permission example: "READ:USER,UPDATE:USER,DELETE:USER"          │
│      → Convert to List<GrantedAuthority>:                                │
│         Split by comma: ["READ:USER", "UPDATE:USER", "DELETE:USER"]     │
│         Map to: [SimpleGrantedAuthority("READ:USER"),                   │
│                  SimpleGrantedAuthority("UPDATE:USER"),                 │
│                  SimpleGrantedAuthority("DELETE:USER")]                 │
│      → Create UserPrincipal(user, authorities)                           │
│      → Return UserPrincipal (implements UserDetails)                     │
│                                                                            │
│   c) Now has UserPrincipal with user data + authorities                  │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ 6. DAO AUTHENTICATION PROVIDER - VERIFY PASSWORD                           │
├─────────────────────────────────────────────────────────────────────────────┤
│ DaoAuthenticationProvider.authenticate() continues:                        │
│   a) Get password hash from UserPrincipal:                                │
│      userPrincipal.getPassword()                                          │
│      → Returns: "$2a$12$R9h/cIPz0gi.URNN3..." (BCrypt hash from DB)      │
│                                                                            │
│   b) Get provided password from token:                                    │
│      authenticationToken.getCredentials()                                 │
│      → Returns: "myPassword123" (plain text)                             │
│                                                                            │
│   c) Compare using BCrypt:                                                │
│      passwordEncoder.matches(                                             │
│        "myPassword123",                                                   │
│        "$2a$12$R9h/cIPz0gi.URNN3..."                                     │
│      )                                                                     │
│      → BCrypt internally:                                                 │
│         1. Extract salt from hash: "$2a$12$..."                          │
│         2. Hash provided password with salt                              │
│         3. Compare with stored hash                                      │
│      → Returns: true (passwords match!)                                  │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ 7. DAO AUTHENTICATION PROVIDER - CREATE AUTHENTICATED AUTHENTICATION       │
├─────────────────────────────────────────────────────────────────────────────┤
│ DaoAuthenticationProvider.authenticate() final step:                       │
│   a) Create new Authentication object:                                    │
│      new UsernamePasswordAuthenticationToken(                             │
│        userPrincipal,              // principal (who is this?)           │
│        null,                       // credentials (don't expose password) │
│        authorities                 // GrantedAuthority list             │
│      )                                                                    │
│                                                                            │
│   b) Set authenticated = true                                             │
│      → Indicates: This user's identity is VERIFIED                       │
│                                                                            │
│   c) Return this Authentication object                                    │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ 8. SPRING SECURITY STORES AUTHENTICATION IN CONTEXT                        │
├─────────────────────────────────────────────────────────────────────────────┤
│ Back in DaoAuthenticationProvider:                                        │
│   - Authentication returned successfully                                  │
│                                                                            │
│ Back in Controller (UserController.login):                                │
│   - Spring Security automatically stores:                                 │
│     SecurityContextHolder.getContext().setAuthentication(auth)           │
│     → Auth object now in thread-local storage                           │
│     → Accessible throughout this request in:                            │
│        SecurityContextHolder.getContext().getAuthentication()           │
│        SecurityContextHolder.getContext().getPrincipal()                │
│                                                                            │
│   - Now available in controller, service, anywhere in the request       │
│   - NOT persisted to database (only in-memory for this request)         │
│   - Will be discarded when request ends (stateless)                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ 9. CONTROLLER GENERATES JWT TOKEN                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│ UserController.login():                                                    │
│   - Authentication succeeded                                              │
│   - Now generate JWT token for client                                    │
│   - Call: tokenProvider.createAccessToken(userPrincipal)                │
│                                                                            │
│ TokenProvider.createAccessToken(userPrincipal):                          │
│   a) Extract authorities as String array:                                │
│      getClaimsFromUser(userPrincipal)                                   │
│      → userPrincipal.getAuthorities() returns List<GrantedAuthority>   │
│      → Stream.map(GrantedAuthority::getAuthority)                      │
│      → Returns: ["READ:USER", "UPDATE:USER", "DELETE:USER"]           │
│                                                                            │
│   b) Build JWT token:                                                     │
│      JWT.create()                                                         │
│        .withIssuer("BOBBYLON_LLC")                                        │
│           → Who created this token? Our company/app                      │
│        .withAudience("BOBS_MANAGEMENT")                                   │
│           → Who is this token for? Our backend API                       │
│        .withIssuedAt(new Date())                                          │
│           → When was token created? Now                                  │
│        .withSubject(userPrincipal.getUsername())                          │
│           → Who is this token about? bob@example.com                     │
│        .withArrayClaim("authorities", ["READ:USER", "UPDATE:USER", "DELETE:USER"])
│           → What can this user do? These permissions                     │
│        .withExpiresAt(new Date(currentTimeMillis() + 1_800_000))        │
│           → When does token expire? 30 minutes from now                 │
│        .sign(HMAC512(secret.getBytes()))                                  │
│           → Sign with secret: Only backend knows secret                  │
│           → Creates: eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9....          │
│                                                                            │
│   c) Return token as String to client                                    │
│      → Client stores in localStorage/sessionStorage                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ 10. CONTROLLER RETURNS RESPONSE TO CLIENT                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│ HTTP 200 OK                                                                │
│ {                                                                           │
│   "timeStamp": "22:52:13",                                               │
│   "statusCode": 200,                                                      │
│   "status": "OK",                                                         │
│   "message": "Login successful!",                                        │
│   "data": {                                                               │
│     "user": {                                                             │
│       "id": 1,                                                            │
│       "firstName": "Bob",                                                │
│       "lastName": "Dylan",                                               │
│       "email": "bob@example.com",                                        │
│       // ... other user fields (NO PASSWORD)                            │
│     },                                                                    │
│     "token": "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9..."                 │
│   }                                                                        │
│ }                                                                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## JWT Token Structure

### What is a JWT Token?

A JWT (JSON Web Token) is a self-contained token with three parts separated by dots:

```
eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.
eyJpc3MiOiJCT0JCWUxPTl9MTEMiLCJhdWQiOiJCT0JTX01BTkFHRU1FTlQiLCJpYXQiOjE3MTMyNDU1MzMsInN1YiI6ImJvYkBleGFtcGxlLmNvbSIsImF1dGhvcml0aWVzIjpbIlJFQURfVVNFUiIsIlVQREFURV9VU0VSIiwiREVMRVRFX1VTRVIiXSwicm9sZSI6IlVTRVIiLCJleHAiOjE3MTMyNDczMzN9.
AJ8DGt-X6y2K9pL4mN8oP5qR6sT7uV8wX9yZ0aB1cD2eF3gH4iJ5kL6mN7oP8qR9sT0uV1wX2yZ3aB4cD5eF6gH7iJ8k
```

### JWT Part Breakdown

**Part 1: Header (Base64URL encoded)**
```json
{
  "alg": "HS512",    // Algorithm used to sign
  "typ": "JWT"       // Type of token
}
```

**Part 2: Payload (Base64URL encoded)**
```json
{
  "iss": "BOBBYLON_LLC",                                  // Issuer
  "aud": "BOBS_MANAGEMENT",                              // Audience
  "iat": 1713245533,                                     // Issued at (Unix timestamp)
  "sub": "bob@example.com",                             // Subject (who)
  "authorities": ["READ:USER", "UPDATE:USER", "DELETE:USER"], // Permissions
  "exp": 1713247333                                      // Expiration time (Unix timestamp)
}
```

**Part 3: Signature (Base64URL encoded)**
```
HMACSHA512(
  Base64URL(header) + "." + Base64URL(payload),
  secret_key
)
```

### Why JWT is Secure

1. **Base64URL Encoding**: Part 1 and 2 are encoded, not encrypted (CAN be decoded by anyone)
   - Don't put secrets in payload!
   
2. **Signature (Part 3)**: Only backend knows the secret key
   - If someone modifies payload or signature, verification fails
   - Only backend can CREATE valid tokens
   - Can't forge without the secret

### JWT Verification Flow

When client sends JWT with request:

```
POST /user/delete/5
Authorization: Bearer eyJhbGciOiJIUzUxMiIs...

Backend receives token:
1. Split by dots: [header, payload, signature]
2. Decode header and payload
3. Recreate signature:
   newSignature = HMACSHA512(header + "." + payload, secret)
4. Compare:
   newSignature == providedSignature?
   → YES: Token is valid, unchanged, and from us
   → NO: Token is forged or modified, reject it
5. Check expiration:
   exp > currentTime?
   → YES: Token still valid
   → NO: Token expired, reject it
6. Extract claims from payload:
   authorities = ["READ:USER", "UPDATE:USER", "DELETE:USER"]
7. Check authorization:
   hasAnyAuthority("DELETE:USER")?
   → YES: Allow DELETE request
   → NO: Return 403 Forbidden
```

---

## GrantedAuthorities System

### Overview

GrantedAuthority = One permission/authority a user has

### Our Authority Format

We use **PERMISSION-BASED** authorities (not role-based):

```
Authority = "RESOURCE:ACTION"

Examples:
- READ:USER
- UPDATE:USER
- DELETE:USER
- READ:CUSTOMER
- UPDATE:CUSTOMER
- DELETE:CUSTOMER
```

### Authorities in Our App

**User Entity in Database:**
```
id | firstName | lastName | email           | password       | enabled | notLocked | using2FA
1  | Bob       | Dylan    | bob@example.com | $2a$12$Xyz... | true    | true      | false
```

**Role Entity in Database:**
```
id | name | permission
1  | USER | READ:USER,UPDATE:USER
2  | ADMIN| READ:USER,READ:CUSTOMER,UPDATE:USER,UPDATE:CUSTOMER,DELETE:USER,DELETE:CUSTOMER
```

**UserRoles Junction Table (Many-to-Many):**
```
user_id | role_id
1       | 1       (Bob has USER role)
```

### Authorities Flow

```
Database Role.permission Field:
"READ:USER,UPDATE:USER,DELETE:USER"
↓
Split by comma:
["READ:USER", "UPDATE:USER", "DELETE:USER"]
↓
Convert each to SimpleGrantedAuthority:
[
  SimpleGrantedAuthority("READ:USER"),
  SimpleGrantedAuthority("UPDATE:USER"),
  SimpleGrantedAuthority("DELETE:USER")
]
↓
UserPrincipal.getAuthorities() returns this List
↓
Spring Security checks:
Is "DELETE:USER" in this list?
→ For endpoint: .requestMatchers(DELETE, "/user/delete/**").hasAnyAuthority("DELETE:USER")
→ If YES: Allow request
→ If NO: Deny request (403)
↓
JWT Token embeds authorities array:
"authorities": ["READ:USER", "UPDATE:USER", "DELETE:USER"]
↓
When client sends token with request, backend extracts authorities and checks permissions
```

### Authorization Checks in Code

**In SecurityConfig:**
```java
.requestMatchers(DELETE, "/user/delete/**").hasAnyAuthority("DELETE:USER")
```

Spring Security:
1. Gets Authentication from SecurityContextHolder
2. Gets UserPrincipal from Authentication.getPrincipal()
3. Calls UserPrincipal.getAuthorities()
4. Checks if any authority.getAuthority().equals("DELETE:USER")
5. Allows/denies based on result

**With Annotations (Method-Level):**
```java
@PreAuthorize("hasAnyAuthority('DELETE:USER')")
public void deleteUser(Long userId) {
    // Implementation
}
```

Same check, but at method level instead of request level

---

## Database Role/Permission Structure

### Schema

```sql
CREATE TABLE roles (
  id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(50) NOT NULL,
  permission VARCHAR(500)
);

CREATE TABLE users (
  id INT PRIMARY KEY AUTO_INCREMENT,
  email VARCHAR(100) NOT NULL UNIQUE,
  password VARCHAR(255),
  enabled BOOLEAN,
  non_locked BOOLEAN,
  using_mfa BOOLEAN,
  -- ... other fields
);

CREATE TABLE userroles (
  user_id INT NOT NULL,
  role_id INT NOT NULL,
  PRIMARY KEY (user_id, role_id),
  FOREIGN KEY (user_id) REFERENCES users(id),
  FOREIGN KEY (role_id) REFERENCES roles(id)
);
```

### Sample Data

```sql
INSERT INTO roles (name, permission) VALUES
('USER', 'READ:USER,UPDATE:USER'),
('ADMIN', 'READ:USER,READ:CUSTOMER,UPDATE:USER,UPDATE:CUSTOMER,DELETE:USER,DELETE:CUSTOMER'),
('HELP_DESK', 'READ:USER,UPDATE:USER');

INSERT INTO users (email, password, enabled, non_locked) VALUES
('bob@example.com', '$2a$12$...', true, true),
('admin@example.com', '$2a$12$...', true, true);

INSERT INTO userroles (user_id, role_id) VALUES
(1, 1),  -- Bob has USER role
(2, 2);  -- Admin has ADMIN role
```

### Permission String Format

Permission stored as comma-separated authorities:
```
"READ:USER,UPDATE:USER,DELETE:USER"
```

Parsed in UserRepoImpl.loadUserByUsername():
```java
Role role = roleRepository.getRoleByUserId(userId);
String permissions = role.getPermission(); // "READ:USER,UPDATE:USER,DELETE:USER"

// In UserPrincipal.getAuthorities():
stream(permissions.split(","))
  .map(p -> new SimpleGrantedAuthority(p.trim()))
  .collect(toList())
// Returns: [SimpleGrantedAuthority("READ:USER"), SimpleGrantedAuthority("UPDATE:USER"), SimpleGrantedAuthority("DELETE:USER")]
```

---

## Security Filter Chain Decisions

### Request Flow Through Filter Chain

```
Incoming HTTP Request
↓
┌─────────────────────────────────────────┐
│ SecurityFilterChain Evaluation          │
├─────────────────────────────────────────┤
│ For each rule (in order):              │
│ Does request match this rule?          │
│ → YES: Apply this rule and stop        │
│ → NO: Try next rule                    │
└─────────────────────────────────────────┘
```

### Rules (From SecurityConfig)

1. **POST /user/register** → permitAll() → Allow
2. **POST /user/login** → permitAll() → Allow
3. **/actuator/** → permitAll() → Allow
4. **DELETE /user/delete/** → hasAnyAuthority("DELETE:USER") → Check authority
5. **DELETE /customer/delete/** → hasAnyAuthority("DELETE:CUSTOMER") → Check authority
6. **GET /** → hasAnyAuthority("READ:USER", "READ:CUSTOMER") → Check authority
7. **POST /** → hasAnyAuthority("UPDATE:USER", "UPDATE:CUSTOMER") → Check authority
8. **PUT /** → hasAnyAuthority("UPDATE:USER", "UPDATE:CUSTOMER", "UPDATE:ROLE") → Check authority
9. **All other requests** → authenticated() → Must be logged in

### Example Scenarios

**Scenario 1: Unauthenticated user tries GET /users**
```
Request: GET /users (no Authorization header)
↓
SecurityFilterChain checks rules
↓
Rule 6: GET /** requires hasAnyAuthority("READ:USER", "READ:CUSTOMER")
↓
Is user authenticated? NO
↓
Call: CustomAuthenticationEntryPoint.commence()
↓
Response: HTTP 401 Unauthorized
```

**Scenario 2: Authenticated user with READ:USER but no DELETE:USER**
```
Request: DELETE /user/delete/5
Authorization: Bearer eyJhbGciOi...
↓
SecurityFilterChain checks rules
↓
Rule 4: DELETE /user/delete/** requires hasAnyAuthority("DELETE:USER")
↓
Extract authorities from token: ["READ:USER", "UPDATE:USER"]
↓
Check if any equals "DELETE:USER" → NO
↓
Call: CustomAccessDeniedHandler.handle()
↓
Response: HTTP 403 Forbidden
```

**Scenario 3: Authenticated user with DELETE:USER**
```
Request: DELETE /user/delete/5
Authorization: Bearer eyJhbGciOi...
↓
SecurityFilterChain checks rules
↓
Rule 4: DELETE /user/delete/** requires hasAnyAuthority("DELETE:USER")
↓
Extract authorities from token: ["READ:USER", "UPDATE:USER", "DELETE:USER"]
↓
Check if any equals "DELETE:USER" → YES
↓
Allow request to proceed to UserController
↓
Controller method executes
↓
Response: HTTP 200 OK (or appropriate response)
```

---

## Exception Handling (401 vs 403)

### When is 401 Returned?

**CustomAuthenticationEntryPoint.commence()** is called when:
- User has NO valid authentication
- No token in Authorization header
- Token is invalid/expired
- User tries to access protected resource without logging in

**Flow:**
```
POST /users
(No Authorization header)
↓
SecurityFilterChain needs to check authorization
↓
Gets authentication: null (no token, no login)
↓
Rule requires authentication
↓
authenticationEntryPoint.commence()
↓
Return: HTTP 401 Unauthorized
Body: { "statusCode": 401, "reason": "Please login to access this resource" }
```

### When is 403 Returned?

**CustomAccessDeniedHandler.handle()** is called when:
- User IS authenticated
- User HAS valid credentials
- But user LACKS required authorities for this endpoint

**Flow:**
```
DELETE /user/delete/5
Authorization: Bearer <valid_token>
↓
SecurityFilterChain checks rule
↓
Gets authentication: Yes (valid token)
↓
Gets authorities from token: ["READ:USER", "UPDATE:USER"]
↓
Rule requires: hasAnyAuthority("DELETE:USER")
↓
User doesn't have DELETE:USER
↓
accessDeniedHandler.handle()
↓
Return: HTTP 403 Forbidden
Body: { "statusCode": 403, "reason": "You don't have permission to access this resource" }
```

### 401 vs 403 Summary

| HTTP Code | Meaning | Why? | Handler |
|-----------|---------|------|---------|
| 401 Unauthorized | "I don't know who you are" | No token, invalid token, not logged in | CustomAuthenticationEntryPoint |
| 403 Forbidden | "I know who you are, but you can't do this" | Has token but lacks permissions | CustomAccessDeniedHandler |

---

## Method-to-Method Interaction

### Complete Request Flow Map

```
UserController.login(LoginForm form)
├─ authenticationManager.authenticate(token)
│  └─ DaoAuthenticationProvider.authenticate(token)
│     ├─ userDetailsService.loadUserByUsername(email)
│     │  └─ UserRepoImpl.loadUserByUsername(email)
│     │     ├─ getUserByEmail(email) [database query]
│     │     ├─ roleRepository.getRoleByUserId(user.id) [database query]
│     │     ├─ Parse role.permission to List<GrantedAuthority>
│     │     └─ return new UserPrincipal(user, authorities)
│     ├─ userPrincipal.getPassword() [get BCrypt hash]
│     ├─ passwordEncoder.matches(provided, stored)
│     ├─ userPrincipal.getAuthorities() [returns List<GrantedAuthority>]
│     └─ return new Authentication(userPrincipal, null, authorities)
├─ [Spring Security stores in SecurityContextHolder]
├─ tokenProvider.createAccessToken(userPrincipal)
│  ├─ getClaimsFromUser(userPrincipal)
│  │  └─ userPrincipal.getAuthorities() [get List<GrantedAuthority>]
│  │     └─ map to String[] ["READ:USER", "UPDATE:USER", "DELETE:USER"]
│  └─ JWT.create()
│     .withSubject(userPrincipal.getUsername()) [email]
│     .withArrayClaim("authorities", claims)
│     .withExpiresAt(...)
│     .sign(HMAC512(secret))
│     └─ return JWT token string
├─ userService.getUserByEmail(email)
│  └─ UserRepoImpl.getUserByEmail(email) [database query]
├─ userDTOMapper.fromUser(user) [map User to UserDTO, exclude password]
└─ return ResponseEntity with token + userDTO
```

### Data Transformation Throughout Flow

```
Database User Row
↓
User Entity Object
↓ [Combined with Role]
UserPrincipal (UserDetails)
   ├─ User data (id, email, password, etc.)
   └─ List<GrantedAuthority> (authorities)
↓
JWT Token Creation
   ├─ Extract authorities: ["READ:USER", "UPDATE:USER"]
   ├─ Embed in token payload
   └─ Sign with HMAC512(secret)
↓
Response to Client
   ├─ JWT token string
   └─ UserDTO (User without password)
↓
Client stores JWT in localStorage
↓
Subsequent Requests
   ├─ Client includes: Authorization: Bearer <JWT>
   ├─ Backend extracts token
   ├─ Verifies signature with secret
   ├─ Extracts payload
   ├─ Gets authorities from token
   ├─ Checks permissions
   └─ Allows/Denies request
```

---

## ✨ Refresh Token Flow (NEW - May 2026)

### Why Refresh Tokens?

Access tokens expire after 30 minutes for security. If they lasted longer, a stolen token could be used for longer. 
Refresh tokens expire after 5 days and can only be used to request a new access token (not for API access).

```
Timeline:
Time 0:00   → User logs in
             → Receive access_token + refresh_token
             → Store both in localStorage

Time 0:20   → User calls /user/profile with access_token
             → Works fine (token valid for 30 min)

Time 0:35   → User calls /user/profile with access_token
             → ERROR: Access token expired (exp: 0:30)
             → Frontend detects 401

Time 0:35   → Frontend calls /user/refresh/token with refresh_token
             → Backend validates refresh_token
             → Creates new access_token
             → Returns new access_token
             → Frontend stores new access_token

Time 0:40   → User calls /user/profile with new access_token
             → Works fine (new token valid until 1:10)

Time 5:00   → Refresh token expires (exp: 5:00)
             → Frontend can no longer refresh
             → User must re-login to get new tokens
```

### Access Token vs Refresh Token

| Aspect | Access Token | Refresh Token |
|--------|--------------|---------------|
| **Purpose** | Authenticate API requests | Request new access token |
| **Validity** | 30 minutes | 5 days |
| **Authorities** | YES - includes permissions | NO - no permissions |
| **Used For** | GET /user/profile, etc. | GET /user/refresh/token ONLY |
| **In Header** | Authorization: Bearer <access_token> | Authorization: Bearer <refresh_token> |
| **Can Access APIs** | YES | NO - filter rejects (empty authorities) |
| **JWT Payload** | Contains "authorities": [...] | No "authorities" claim |

### Why Refresh Token Has No Authorities

Security principle: **Principle of Least Privilege**

- Refresh token should ONLY refresh, not access
- If refresh token had authorities, a thief could use it for API calls
- Filter rejects any token without authorities on non-public endpoints
- Refresh endpoint is public (whitelisted in SecurityConfig)

```
Hacker steals refresh_token from localStorage:
    ↓
Tries: GET /user/profile with refresh_token
    ↓
CustomAuthFilter.doFilterInternal():
  - Validates refresh_token (signature OK)
  - Extracts authorities: [] (empty)
  - Sees empty authorities
  - Clears SecurityContext (does NOT authenticate)
    ↓
SecurityConfig rule: .anyRequest().authenticated()
    ↓
CustomAuthenticationEntryPoint returns 401
    ↓
Hacker cannot access /user/profile
```

### Refresh Token Flow Code Path

```
UserController.sendNewRefreshToken(request)
├─ Check Authorization header has Bearer prefix
├─ Extract refresh_token from header
├─ Call isHeaderAndTokenValid(refresh_token)
│  ├─ tokenProvider.getSubject(refreshToken, request)
│  │  ├─ getJWTVerifier().verify(refreshToken) [validates signature, issuer, expiration]
│  │  ├─ Extract "sub" (email) from token
│  │  ├─ Return email (no authorities required!)
│  │  └─ Map any errors to BadCredentialsException
│  └─ tokenProvider.isTokenValid(email, refreshToken)
│     └─ Return true if valid
├─ userService.getUserByEmail(email)
│  └─ Get fresh user data from database
├─ getUserPrincipal(userDTO)
│  ├─ Load User entity
│  ├─ Load Role with authorities
│  └─ Create UserPrincipal(user, authorities)
├─ tokenProvider.createAccessToken(userPrincipal)
│  ├─ Extract authorities from UserPrincipal
│  ├─ Create NEW access token with authorities
│  ├─ Set exp: now + 30 minutes
│  └─ Return new token string
└─ Return 200 with new access_token
```

### Error Scenarios

**Scenario 1: Refresh token is invalid**
```
Frontend sends: GET /user/refresh/token -H "Authorization: Bearer invalid.token.here"
    ↓
tokenProvider.getSubject(invalidToken, request)
    ↓
getJWTVerifier().verify(invalidToken) throws JWTDecodeException
    ↓
Caught and mapped to BadCredentialsException("Could not decode the token...")
    ↓
ExceptionUtils.processError() → 400 Bad Request
    ↓
Frontend sees: {"reason": "Could not decode the token..."}
```

**Scenario 2: Refresh token is expired**
```
Frontend sends: GET /user/refresh/token -H "Authorization: Bearer eyJhbGci...expired_token"
    ↓
tokenProvider.getSubject(expiredToken, request)
    ↓
getJWTVerifier().verify(expiredToken) throws TokenExpiredException
    ↓
Re-thrown as TokenExpiredException
    ↓
ExceptionUtils.processError() → 401 Unauthorized
    ↓
Frontend sees: {"reason": "Token has expired"}
    ↓
Frontend must prompt user to log in again
```

**Scenario 3: Trying to use refresh token as access token**
```
Frontend sends: GET /user/profile -H "Authorization: Bearer eyJhbGci...refresh_token"
    ↓
CustomAuthFilter.doFilterInternal():
  1. Validates refresh token (signature OK, not expired)
  2. Extracts authorities: [] (empty array)
  3. Sees authorities.isEmpty() is TRUE
  4. Calls SecurityContextHolder.clearContext() (do NOT authenticate)
    ↓
Request continues without Authentication
    ↓
SecurityConfig rule: .anyRequest().authenticated()
    ↓
CustomAuthenticationEntryPoint.commence()
    ↓
Returns 401 Unauthorized
    ↓
Frontend sees: {"reason": "..."}
```

### Key Security Properties

1. **Access token cannot refresh**: It has no refresh endpoint that accepts access tokens, and it expires
2. **Refresh token cannot access APIs**: Filter explicitly rejects tokens without authorities
3. **Old tokens cannot be used**: Expired tokens throw TokenExpiredException which is caught and mapped to 401
4. **Malformed tokens are rejected**: JWTDecodeException mapped to 400 Bad Request
5. **Token signature ensures authenticity**: Only backend with secret key can create/verify tokens

---

## Key Takeaways

1. **Authentication** = "Who are you?" (Done once at login)
   - Verified by comparing provided password with stored BCrypt hash
   - Results in JWT tokens (access + refresh)

2. **Authorization** = "What can you do?" (Checked on each request)
   - Determined by user's authorities/permissions
   - Checked in SecurityFilterChain or with annotations

3. **Stateless** = No session storage
   - Tokens contain all info needed
   - Each request is independent
   - Scales horizontally

4. **JWT** = Self-contained token
   - Header: Algorithm info
   - Payload: User data + authorities + expiration
   - Signature: Proves backend created token

5. **Access vs Refresh Tokens** (NEW)
   - Access Token: 30 minutes, includes authorities, authenticates API calls
   - Refresh Token: 5 days, no authorities, only refreshes access tokens
   - Filter rejects refresh tokens on non-refresh endpoints (empty authorities)

6. **GrantedAuthority** = One permission
   - Format: "RESOURCE:ACTION"
   - Stored in database as comma-separated string
   - Converted to List<GrantedAuthority> at runtime

7. **SecurityContextHolder** = ThreadLocal storage
   - Available throughout request
   - Contains Authentication object
   - Accessible via: SecurityContextHolder.getContext().getAuthentication()

8. **Filter Logic** (NEW - May 2026)
   - CustomAuthFilter detects token type by checking authorities
   - Empty authorities → treat as refresh token → clear context
   - Non-empty authorities → treat as access token → set context
   - Prevents refresh token misuse

9. **Error Handling** (NEW - May 2026)
   - Malformed tokens → 400 Bad Request
   - Expired tokens → 401 Unauthorized
   - Invalid claims/signature → 401 Unauthorized
   - Missing token → 401 Unauthorized

10. **401 vs 403**
    - 401: Not authenticated (no token/invalid token)
    - 403: Authenticated but not authorized (lacks permissions)

