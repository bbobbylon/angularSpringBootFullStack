package com.bob.angularspringbootfullstack.utils;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static com.bob.angularspringbootfullstack.constants.Constants.USER_AGENT_HEADER;
import static com.bob.angularspringbootfullstack.utils.RequestUtils.getIpAddress;

/**
 * Operator-facing (console-only) diagnostics for authentication and RBAC decisions
 * on the sign-in path.
 *
 * <p><b>Why this class exists.</b> {@code UserController#authenticate} deliberately
 * collapses every login failure — unknown email, wrong password, disabled/locked
 * account, brute-force lockout, missing role — into a single, indistinguishable client
 * response ({@code "Invalid email or password."}) to defeat user enumeration
 * (SRS FR-AUTH-4, NFR-SEC-7). That is correct for the HTTP response, but it also means
 * the <em>server</em> loses the real reason unless we record it separately. This helper
 * is that separate channel: it writes the precise cause to the backend log so an operator
 * can tell a credential-stuffing probe from a genuinely locked account from an RBAC
 * misconfiguration — <em>without</em> that detail ever reaching the caller.
 *
 * <p><b>Security boundary.</b> Everything here goes to the log, never to an HTTP body.
 * Logs are operator-facing, so naming the email and the reason is not an enumeration
 * oracle (the same rationale under which {@code UserRepoImpl} already logs
 * "User not found in our database: {email}"). The client-visible response is unchanged.
 *
 * <p><b>Grep tags.</b> Denials are tagged {@link #DENY_TAG}; grants {@link #GRANT_TAG}.
 * Mirrors the existing {@code [ROLE-CASING]} diagnostic convention in
 * {@link com.bob.angularspringbootfullstack.repo.repoimpl.RoleRepoImpl}.
 *
 * @see com.bob.angularspringbootfullstack.controller.UserController
 */
@Slf4j
public final class AuthDiagnosticsLogger {

    /** Grep tag for a rejected sign-in (authentication, 401). */
    public static final String DENY_TAG = "[AUTH-DENY]";
    /** Grep tag for a first-factor-accepted sign-in (before any MFA gate). */
    public static final String GRANT_TAG = "[AUTH-GRANT]";
    /** Grep tag for an authorization denial on an already-authenticated request (RBAC, 403). */
    public static final String FORBIDDEN_TAG = "[RBAC-DENY]";
    /** Grep tag for a persistent account lock triggered by the brute-force threshold. */
    public static final String LOCK_TAG = "[AUTH-LOCK]";
    /** The single client-visible message every credential failure surfaces (never varies). */
    private static final String GENERIC_CLIENT_MESSAGE = "Invalid email or password.";
    /** Cap on the raw User-Agent echoed into a log line, so a hostile header can't flood the log. */
    private static final int USER_AGENT_LOG_LIMIT = 120;

    private AuthDiagnosticsLogger() {
        // static-only helper; matches ExceptionUtils / UserUtils / RequestUtils
    }

    /**
     * The mutually exclusive reasons a sign-in can be denied, each with a plain-English
     * explanation aimed at whoever is reading the backend console. The order matters only
     * for {@link #classify(UserDTO, Throwable)}, which resolves the most specific cause first.
     */
    public enum LoginDenialReason {
        /** No user row matched the submitted email — the attempt never resolved an account. */
        UNKNOWN_EMAIL("no account exists with this email; the attempt never matched a user row"),
        /** Account is known and in good standing, but the password failed the BCrypt comparison. */
        BAD_PASSWORD("account exists and is enabled/unlocked, but the supplied password did not match the stored BCrypt hash"),
        /** Password would have been accepted, but the account is not enabled (email unverified or admin-disabled). */
        ACCOUNT_DISABLED("account exists but is not enabled (email never verified, or disabled by an administrator) — checked before the password"),
        /** Account exists but notLocked=false — admin lock or a lockout policy. */
        ACCOUNT_LOCKED("account exists but is locked (notLocked=false) — an administrator locked it or a lockout policy tripped; checked before the password"),
        /** Sliding-window failure count exceeded the threshold; rejected before the password check. */
        BRUTE_FORCE_LOCKOUT("too many recent failed attempts for this email inside the sliding window; rejected before the password was checked"),
        /** RBAC gap: identity authenticates, but no role/authorities could be resolved, so no session can be issued. */
        NO_ROLE_ASSIGNED("RBAC gap: the identity exists and authenticates, but no role/authorities could be resolved, so no session can be granted"),
        /** Anything not matched above — inspect the accompanying stack trace. */
        UNEXPECTED_ERROR("an unclassified error occurred while authenticating; see the stack trace logged below");

        private final String operatorExplanation;

        LoginDenialReason(String operatorExplanation) {
            this.operatorExplanation = operatorExplanation;
        }

        /** @return a human-readable, operator-facing description of this denial reason. */
        public String explanation() {
            return operatorExplanation;
        }
    }

    /**
     * Maps the {@code (was the account resolved?) + (which exception fired?)} pair back to the
     * concrete {@link LoginDenialReason} that {@code authenticate()} flattened away.
     *
     * <p>Resolution order (most specific first):
     * <ol>
     *   <li>{@link DisabledException}/{@link LockedException} are Spring's pre-authentication
     *       account-state checks, thrown <em>before</em> the password is compared — so they win
     *       even when the password is also wrong.</li>
     *   <li>A {@code null} resolved account means the email never matched a row → {@code UNKNOWN_EMAIL}.</li>
     *   <li>{@link BadCredentialsException} with a known account → {@code BAD_PASSWORD}.</li>
     *   <li>{@link UsernameNotFoundException} with a <em>known</em> account is the tell-tale of the
     *       RBAC path: {@code UserRepoImpl#loadUserByUsername} converts a missing role into this
     *       exception, so email-known + not-found = {@code NO_ROLE_ASSIGNED}.</li>
     *   <li>Everything else → {@code UNEXPECTED_ERROR}.</li>
     * </ol>
     * Brute-force rejection is <em>not</em> classified here — it originates from our own
     * {@code ApiException} guard, so the caller passes {@link LoginDenialReason#BRUTE_FORCE_LOCKOUT}
     * explicitly.
     *
     * @param resolvedUser the account resolved by the pre-check lookup, or {@code null} if the email is unknown
     * @param cause        the exception that ended the authentication attempt
     * @return the specific reason the sign-in was denied
     */
    public static LoginDenialReason classify(UserDTO resolvedUser, Throwable cause) {
        if (cause instanceof DisabledException) return LoginDenialReason.ACCOUNT_DISABLED;
        if (cause instanceof LockedException) return LoginDenialReason.ACCOUNT_LOCKED;
        if (resolvedUser == null) return LoginDenialReason.UNKNOWN_EMAIL;
        if (cause instanceof BadCredentialsException) return LoginDenialReason.BAD_PASSWORD;
        if (cause instanceof UsernameNotFoundException) return LoginDenialReason.NO_ROLE_ASSIGNED;
        return LoginDenialReason.UNEXPECTED_ERROR;
    }

    /**
     * Writes the true reason a sign-in was denied to the backend console at {@code WARN},
     * while the caller returns the unchanged generic message to the client.
     *
     * <p>The one-liner is deliberately greppable ({@link #DENY_TAG} + {@code reason=...}). For an
     * {@code UNEXPECTED_ERROR} the full stack trace is also emitted (it is genuinely unexpected);
     * for every classified reason the underlying cause is dropped to {@code DEBUG} so normal runs
     * stay readable.
     *
     * @param email        the submitted email (safe to log server-side)
     * @param resolvedUser the resolved account, or {@code null} for an unknown email
     * @param reason       the classified denial reason
     * @param cause        the exception that ended the attempt (may be {@code null})
     * @param request      the current HTTP request, for client IP and User-Agent context
     */
    public static void logDenied(String email, UserDTO resolvedUser, LoginDenialReason reason,
                                 Throwable cause, HttpServletRequest request) {
        String userId = resolvedUser != null ? String.valueOf(resolvedUser.getId()) : "n/a";
        String enabled = resolvedUser != null ? String.valueOf(resolvedUser.isEnabled()) : "n/a";
        String notLocked = resolvedUser != null ? String.valueOf(resolvedUser.isNotLocked()) : "n/a";

        log.warn("{} reason={} email={} userId={} enabled={} notLocked={} ip={} ua=\"{}\" — {}. " +
                        "Client response is the generic \"{}\" (anti-enumeration preserved).",
                DENY_TAG, reason.name(), email, userId, enabled, notLocked,
                getIpAddress(request), shortUserAgent(request), reason.explanation(),
                GENERIC_CLIENT_MESSAGE);

        if (reason == LoginDenialReason.UNEXPECTED_ERROR && cause != null) {
            log.warn("{} stack trace for the unclassified failure (email={}):", DENY_TAG, email, cause);
        } else if (cause != null) {
            log.debug("{} underlying cause for reason={} (email={}): {}", DENY_TAG, reason.name(), email, cause.toString());
        }
    }

    /**
     * Records a successful first factor at {@code INFO}, surfacing the RBAC outcome — the resolved
     * role and the authority string that will be baked into the JWT — so grants are as visible in
     * the console as denials. Called the moment {@code AuthenticationManager} succeeds, i.e. before
     * any MFA/TOTP challenge; a subsequent second-factor step (if any) has its own audit event.
     *
     * @param email   the submitted email
     * @param user    the authenticated principal, carrying the flattened role name and permissions
     * @param request the current HTTP request, for client IP context
     */
    public static void logGranted(String email, UserDTO user, HttpServletRequest request) {
        boolean secondFactorPending = user.isUsingTotp() || user.isUsing2FA();
        log.info("{} email={} userId={} role={} authorities=[{}] ip={} — first factor accepted; {}",
                GRANT_TAG, email, user.getId(), user.getRoleName(), user.getPermissions(),
                getIpAddress(request),
                secondFactorPending
                        ? "second factor still required before tokens are issued"
                        : "issuing access/refresh token pair");
    }

    /**
     * Records that the brute-force threshold tripped a <em>persistent</em> account lock at
     * {@code WARN}. Distinct from {@link #logDenied} with {@code BRUTE_FORCE_LOCKOUT}: that logs the
     * per-attempt rejection inside the sliding window, whereas this logs the one-time state change
     * (notLocked → false) that keeps the account locked until an administrator unlocks it — a fact
     * an operator needs, since the user can no longer recover on their own by waiting out the window.
     *
     * @param user      the account being locked
     * @param maxTries  the failure threshold that was reached
     * @param windowMin the sliding-window length, in minutes, over which failures were counted
     * @param request   the current HTTP request, for client IP context
     */
    public static void logAutoLock(UserDTO user, int maxTries, int windowMin, HttpServletRequest request) {
        log.warn("{} email={} userId={} — reached {} failed attempts within {} min; account LOCKED " +
                        "(notLocked=false). It will stay locked until an administrator unlocks it. ip={}",
                LOCK_TAG, user.getEmail(), user.getId(), maxTries, windowMin, getIpAddress(request));
    }

    /**
     * Records an authorization (403) denial at {@code WARN} — the post-login RBAC counterpart to
     * {@link #logDenied}. Fires from {@code CustomAccessDeniedHandler} when an <em>authenticated</em>
     * principal hits a resource whose authority requirement they do not satisfy.
     *
     * <p>The line names the principal, the authorities they actually hold, and the exact
     * method+URI they were refused — which is the information needed to decide whether a role
     * grant is missing or the request was a genuine over-reach. Spring does not hand the handler
     * the specific authority the matcher demanded, so that is not logged; what the caller
     * <em>held</em> plus the resource is the actionable pair. The client's 403 body is unchanged.
     *
     * @param authentication the current principal from the SecurityContext, or {@code null} if none
     * @param request        the forbidden HTTP request, for method/URI/IP context
     */
    public static void logForbidden(Authentication authentication, HttpServletRequest request) {
        Object principal = authentication != null ? authentication.getPrincipal() : null;
        String email = principal instanceof UserDTO u ? u.getEmail() : "anonymous";
        String authorities = authentication != null ? authentication.getAuthorities().toString() : "[]";
        log.warn("{} email={} heldAuthorities={} method={} uri={} ip={} — authenticated but lacks the " +
                        "authority required for this resource; returning 403. Client body is unchanged.",
                FORBIDDEN_TAG, email, authorities, request.getMethod(), request.getRequestURI(), getIpAddress(request));
    }

    /**
     * Returns the request's User-Agent, truncated to {@link #USER_AGENT_LOG_LIMIT} characters so a
     * crafted header cannot flood a log line. Intentionally uses the raw header rather than
     * {@link RequestUtils#getDevice(HttpServletRequest)}: the latter builds a heavyweight
     * {@code UserAgentAnalyzer} on every call, which on the failed-login hot path would hand an
     * attacker a cheap way to burn CPU per attempt.
     *
     * @param request the current HTTP request, or {@code null}
     * @return a short, safe User-Agent string (never {@code null})
     */
    private static String shortUserAgent(HttpServletRequest request) {
        if (request == null) return "n/a";
        String ua = request.getHeader(USER_AGENT_HEADER);
        if (ua == null || ua.isBlank()) return "n/a";
        return ua.length() > USER_AGENT_LOG_LIMIT ? ua.substring(0, USER_AGENT_LOG_LIMIT) + "…" : ua;
    }
}
