package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.dto.LoginRiskAssessment;
import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.enumeration.StepUpMethod;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Service contract for the login-anomaly check and its step-up escalation (SRS FR-TPF-1).
 *
 * <p>Sits between {@link com.bob.angularspringbootfullstack.controller.UserController} and
 * {@link com.bob.angularspringbootfullstack.repo.LoginRiskRepo}: the controller asks "does this
 * sign-in look like it came from somewhere new?" and, if so, records the finding and notifies the
 * account owner. Keeping the comparison logic here (rather than in the repository) follows the
 * project's rule that business rules live in the service layer — the repository only reads rows.
 *
 * <p>The two operations are deliberately separate. {@link #assess} is a pure query with no side
 * effects, so the controller can branch on the verdict before deciding <em>which</em> step-up
 * applies; {@link #recordSuspiciousLogin} then writes the audit trail once that outcome is known,
 * so a single audit row captures both the signals and what the system did about them.
 */
public interface LoginRiskService {

    /**
     * Compares the current request against this account's own sign-in history.
     *
     * <p>Side-effect free: it neither writes an audit row nor sends mail, so it is safe to call on
     * every login regardless of which branch the caller ultimately takes.
     *
     * @param userDTO the account that just passed its first factor
     * @param request the live request, source of the device string and IP address
     * @return the verdict; {@link LoginRiskAssessment#NONE} when the sign-in looks ordinary, when
     *         the account has no baseline yet, or when the feature is disabled by configuration
     */
    LoginRiskAssessment assess(UserDTO userDTO, HttpServletRequest request);

    /**
     * Records an elevated-risk sign-in to the audit log and alerts the account owner.
     *
     * <p>Both actions are best-effort and never throw: the caller is on the login path, and a
     * failed notification must not cost a legitimate user their session (the same rule that made
     * {@link com.bob.angularspringbootfullstack.listener.NewUserEventListener} swallow audit
     * failures).
     *
     * <p>Whether an alert email is sent depends on {@code stepUp}: accounts escalated to
     * {@link StepUpMethod#EMAIL_CODE} already receive an email carrying the reason inline, so a
     * second one would be noise. See {@link StepUpMethod#isAlreadyChallenged()}.
     *
     * @param userDTO    the account that triggered the signals
     * @param assessment the verdict from {@link #assess}; ignored when not elevated
     * @param stepUp     which challenge the login was escalated to, recorded in the audit detail
     */
    void recordSuspiciousLogin(UserDTO userDTO, LoginRiskAssessment assessment, StepUpMethod stepUp);
}
