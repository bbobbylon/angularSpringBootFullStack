package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.LoginRiskAssessment;
import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.enumeration.LoginRiskReason;
import com.bob.angularspringbootfullstack.enumeration.StepUpMethod;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.model.LoginContext;
import com.bob.angularspringbootfullstack.model.SecuritySettings;
import com.bob.angularspringbootfullstack.repo.LoginRiskRepo;
import com.bob.angularspringbootfullstack.service.NotificationService;
import com.bob.angularspringbootfullstack.service.SecuritySettingsService;
import com.bob.angularspringbootfullstack.utils.RequestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for login-anomaly detection and its step-up escalation (SRS FR-TPF-1).
 *
 * <p>The value of this suite is that both of its failure directions are expensive. A detector that
 * flags too eagerly trains users to click through security prompts — which is why the
 * {@link #sameNetworkDifferentHostIsNotFlagged} and {@link #firstEverSignInIsNeverFlagged} cases
 * matter as much as the positive ones. A detector that throws costs a legitimate user their session,
 * because it runs <em>after</em> the password has already been accepted — hence
 * {@link #auditFailureDoesNotBreakTheLogin}.
 *
 * <p>No Spring context, no database, no SMTP: {@link LoginRiskRepo} and
 * {@link NotificationService} are mocked and the request is a {@link MockHttpServletRequest}. The
 * {@code @Value}-injected configuration fields are set directly, since field injection does not
 * happen outside a container.
 *
 * <p>Expected device strings are computed by calling {@link RequestUtils#getDevice} rather than
 * hardcoded. The exact {@code "OS - Browser - Device"} text is Yauaa's to define and can shift
 * between library versions; what this suite asserts is the <em>comparison</em> logic, so pinning
 * the parser's output would make it fail for a reason that has nothing to do with the behaviour
 * under test.
 */
@ExtendWith(MockitoExtension.class)
class LoginRiskServiceImplTest {

    private static final long USER_ID = 7L;
    private static final String EMAIL = "user@example.com";
    private static final String CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final String FIREFOX_UA =
            "Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0";

    /** The address the "current" request arrives from in every test. */
    private static final String CURRENT_IP = "203.0.113.99";
    /** A different host on the SAME /24 as {@link #CURRENT_IP} — a DHCP renewal, not a new place. */
    private static final String SAME_NETWORK_IP = "203.0.113.7";
    /** A genuinely different network. */
    private static final String OTHER_NETWORK_IP = "198.51.100.7";

    @Mock
    private LoginRiskRepo loginRiskRepo;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private SecuritySettingsService securitySettingsService;

    @InjectMocks
    private LoginRiskServiceImpl loginRiskService;

    private MockHttpServletRequest request;
    private UserDTO user;

    @BeforeEach
    void setUp() {
        // @Value fields are populated by the container, which is not running here.
        ReflectionTestUtils.setField(loginRiskService, "anomalyDetectionEnabled", true);
        ReflectionTestUtils.setField(loginRiskService, "historyLimit", 50);
        // No overrides on record by default, so every existing test below exercises the env-driven
        // fallback exactly as it did before the settings table existed. Tests that care about an
        // override re-stub this per-case. lenient() because the recordSuspiciousLogin-only tests
        // never call assess() and therefore never touch this mock — strict stubbing would otherwise
        // fail them for an unused stub.
        lenient().when(securitySettingsService.getSettings()).thenReturn(SecuritySettings.builder().build());

        request = requestFrom(CHROME_UA, CURRENT_IP);

        user = new UserDTO();
        user.setId(USER_ID);
        user.setEmail(EMAIL);
        user.setFirstName("Ada");
    }

    /**
     * Builds a request carrying the given User-Agent and source address. Requests are rebuilt
     * rather than mutated so each test states its whole input in one place.
     */
    private static MockHttpServletRequest requestFrom(String userAgent, String ipAddress) {
        MockHttpServletRequest built = new MockHttpServletRequest();
        built.addHeader("User-Agent", userAgent);
        built.setRemoteAddr(ipAddress);
        return built;
    }

    /** The device string the current request parses to — the value history must match to look familiar. */
    private String currentDevice() {
        return RequestUtils.getDevice(request);
    }

    private void historyIs(LoginContext... contexts) {
        when(loginRiskRepo.findRecentLoginContexts(anyLong(), anyInt())).thenReturn(List.of(contexts));
    }

    @Test
    @DisplayName("an account with no sign-in history is never flagged (there is nothing to differ from)")
    void firstEverSignInIsNeverFlagged() {
        historyIs();

        LoginRiskAssessment assessment = loginRiskService.assess(user, request);

        assertFalse(assessment.elevated(),
                "A first-ever sign-in has no baseline; flagging it would email every new user a security warning.");
    }

    @Test
    @DisplayName("a sign-in from a known device on a known network is not flagged")
    void familiarSignInIsNotFlagged() {
        historyIs(new LoginContext(currentDevice(), CURRENT_IP));

        assertFalse(loginRiskService.assess(user, request).elevated());
    }

    @Test
    @DisplayName("an unrecognised device is flagged as NEW_DEVICE")
    void newDeviceIsFlagged() {
        request = requestFrom(FIREFOX_UA, CURRENT_IP);
        // Same network, so only the device signal may fire.
        historyIs(new LoginContext("Windows 10 - Chrome - Desktop", CURRENT_IP));

        LoginRiskAssessment assessment = loginRiskService.assess(user, request);

        assertTrue(assessment.elevated());
        assertEquals(List.of(LoginRiskReason.NEW_DEVICE), assessment.reasons());
    }

    @Test
    @DisplayName("a different host on the SAME /24 is not a new network (ISPs rotate the last octet)")
    void sameNetworkDifferentHostIsNotFlagged() {
        historyIs(new LoginContext(currentDevice(), SAME_NETWORK_IP));

        assertFalse(loginRiskService.assess(user, request).elevated(),
                "Exact-IP matching would flag a user's own sofa daily and train them to click through.");
    }

    @Test
    @DisplayName("an address outside every known network is flagged as NEW_NETWORK")
    void newNetworkIsFlagged() {
        historyIs(new LoginContext(currentDevice(), OTHER_NETWORK_IP));

        LoginRiskAssessment assessment = loginRiskService.assess(user, request);

        assertEquals(List.of(LoginRiskReason.NEW_NETWORK), assessment.reasons());
    }

    @Test
    @DisplayName("a new device on a new network reports BOTH signals")
    void bothSignalsCanFireTogether() {
        historyIs(new LoginContext("Windows 10 - Chrome - Desktop", OTHER_NETWORK_IP));
        request = requestFrom(FIREFOX_UA, CURRENT_IP);

        LoginRiskAssessment assessment = loginRiskService.assess(user, request);

        assertEquals(List.of(LoginRiskReason.NEW_DEVICE, LoginRiskReason.NEW_NETWORK), assessment.reasons());
        assertEquals("a new device and a new network location", assessment.describe(),
                "The summary feeds the audit detail column and the step-up email body.");
    }

    @Test
    @DisplayName("the 'Unknown IP' sentinel is not treated as a distinct network")
    void unknownIpIsNotANetwork() {
        // A history recorded behind a misconfigured proxy holds only the sentinel; if that counted
        // as a network, the next real address would always look new.
        historyIs(new LoginContext(currentDevice(), "Unknown IP"));

        assertFalse(loginRiskService.assess(user, request).elevated());
    }

    @Test
    @DisplayName("the master switch short-circuits the check without touching the database")
    void disabledDetectionNeverFlagsAndNeverQueries() {
        ReflectionTestUtils.setField(loginRiskService, "anomalyDetectionEnabled", false);

        assertFalse(loginRiskService.assess(user, request).elevated());
        verifyNoInteractions(loginRiskRepo);
    }

    @Test
    @DisplayName("a securitysettings override of enabled=false wins over the env default of true")
    void dbOverrideDisablesDetectionEvenWhenEnvDefaultIsTrue() {
        // The env-driven field is still true (see setUp) — only the DB override says otherwise.
        when(securitySettingsService.getSettings())
                .thenReturn(SecuritySettings.builder().anomalyEnabled(false).build());

        assertFalse(loginRiskService.assess(user, request).elevated());
        verifyNoInteractions(loginRiskRepo);
    }

    @Test
    @DisplayName("a securitysettings override of the history limit is what gets passed to the repo")
    void dbOverrideOfHistoryLimitReachesTheRepo() {
        when(securitySettingsService.getSettings())
                .thenReturn(SecuritySettings.builder().anomalyHistoryLimit(5).build());
        historyIs(new LoginContext(currentDevice(), CURRENT_IP));

        loginRiskService.assess(user, request);

        // The env default (50, set in setUp) must NOT be what reaches the repo once an override
        // is on record — proves assess() re-resolves the effective limit rather than reading the
        // @Value field directly.
        verify(loginRiskRepo).findRecentLoginContexts(USER_ID, 5);
    }

    @Test
    @DisplayName("an unflagged sign-in records nothing and notifies nobody")
    void ordinarySignInIsNotRecorded() {
        loginRiskService.recordSuspiciousLogin(user, LoginRiskAssessment.NONE, StepUpMethod.NONE);

        verifyNoInteractions(eventPublisher, notificationService);
    }

    @Test
    @DisplayName("an account with no second factor gets the step-up email only — no duplicate alert")
    void emailStepUpDoesNotAlsoSendAnAlert() {
        LoginRiskAssessment flagged = new LoginRiskAssessment(List.of(LoginRiskReason.NEW_DEVICE));

        loginRiskService.recordSuspiciousLogin(user, flagged, StepUpMethod.EMAIL_CODE);

        // The step-up email itself carries the reason inline (sent from UserService), so a second
        // "unusual sign-in" message here would be noise.
        verify(notificationService, never()).sendSecurityAlert(anyString(), anyString(), anyString());
        verify(eventPublisher).publishEvent(any(NewUserEvent.class));
    }

    @Test
    @DisplayName("an already-challenged account gets an out-of-band alert explaining the challenge")
    void enrolledSecondFactorGetsASecurityAlert() {
        LoginRiskAssessment flagged = new LoginRiskAssessment(List.of(LoginRiskReason.NEW_NETWORK));

        loginRiskService.recordSuspiciousLogin(user, flagged, StepUpMethod.TOTP);

        // A TOTP prompt looks identical whether or not anything was flagged, so the reason has to
        // reach the user some other way.
        verify(notificationService).sendSecurityAlert("Ada", EMAIL, "a new network location");
    }

    @Test
    @DisplayName("a failing audit write never propagates into the login it is describing")
    void auditFailureDoesNotBreakTheLogin() {
        LoginRiskAssessment flagged = new LoginRiskAssessment(List.of(LoginRiskReason.NEW_DEVICE));
        doThrow(new RuntimeException("events table drifted")).when(eventPublisher).publishEvent(any(NewUserEvent.class));

        // The password was already accepted by this point — throwing here would turn a healthy
        // authentication into a 500, the exact failure mode that took logins down once before.
        assertDoesNotThrow(() -> loginRiskService.recordSuspiciousLogin(user, flagged, StepUpMethod.EMAIL_CODE));
    }
}
