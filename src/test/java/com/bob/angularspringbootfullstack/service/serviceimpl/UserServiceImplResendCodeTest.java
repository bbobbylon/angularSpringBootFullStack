package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.repo.UserRepo;
import com.bob.angularspringbootfullstack.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserServiceImpl#resendVerificationCode} — the "resend code" link on the
 * login screen's MFA/step-up panel.
 *
 * <p>Every branch here matters for the same reason as {@code UserControllerLoginEnumerationTest}:
 * this endpoint is reachable by anyone, pre-authentication, with only an email address. The suite
 * pins down that the three "don't resend" cases (unknown email, TOTP account, no pending
 * challenge) never call either dispatch method — a regression here would either leak account
 * existence/MFA configuration or let a bare email mint a fresh code with no first factor proven.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplResendCodeTest {

    @Mock
    private UserRepo<User> userRepo;
    @Mock
    private RoleRepo<Role> roleRepo;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private UserServiceImpl userService;

    /**
     * {@code getUserByEmail} routes every result through {@code mapToUserDTO}, which looks up the
     * role separately via {@code roleRepo.getRoleByUserId} — stub it once here so every test gets a
     * non-null role without repeating the same boilerplate. {@code lenient()} because the
     * unknown-email test never reaches this call at all.
     */
    @BeforeEach
    void stubRoleLookup() {
        Role role = new Role();
        role.setName("ROLE_USER");
        lenient().when(roleRepo.getRoleByUserId(anyLong())).thenReturn(role);
    }

    private static User user(long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }

    @Test
    @DisplayName("unknown email: no-op, never touches the repo's pending-code check")
    void unknownEmailIsANoOp() {
        when(userRepo.getUserByEmail(anyString())).thenThrow(new UsernameNotFoundException("no such user"));

        userService.resendVerificationCode("ghost@example.com");

        verify(userRepo, never()).hasPendingVerificationCode(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("TOTP account: no-op even with a pending row, since TOTP has no code to resend")
    void totpAccountIsANoOp() {
        User user = user(1L, "totp@example.com");
        user.setUsingTotp(true);
        when(userRepo.getUserByEmail(anyString())).thenReturn(user);

        userService.resendVerificationCode("totp@example.com");

        verify(userRepo, never()).hasPendingVerificationCode(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("no pending code: no-op — never mints a fresh challenge from a bare email")
    void noPendingCodeIsANoOp() {
        User user = user(2L, "nopending@example.com");
        user.setUsing2FA(true);
        when(userRepo.getUserByEmail(anyString())).thenReturn(user);
        when(userRepo.hasPendingVerificationCode(2L)).thenReturn(false);

        userService.resendVerificationCode("nopending@example.com");

        verify(userRepo, never()).sendVerificationCode(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("2FA account with a pending code: redelivers over the SMS/Twilio path")
    void twoFactorAccountRedeliversViaSms() {
        User user = user(3L, "sms@example.com");
        user.setUsing2FA(true);
        when(userRepo.getUserByEmail(anyString())).thenReturn(user);
        when(userRepo.hasPendingVerificationCode(3L)).thenReturn(true);

        userService.resendVerificationCode("sms@example.com");

        verify(userRepo, times(1)).sendVerificationCode(any());
        verify(userRepo, never()).issueVerificationCode(any());
    }

    @Test
    @DisplayName("no-2FA account with a pending code (FR-TPF-1 step-up): redelivers over email")
    void stepUpAccountRedeliversViaEmail() {
        User user = user(4L, "stepup@example.com");
        user.setFirstName("Step");
        user.setUsing2FA(false);
        when(userRepo.getUserByEmail(anyString())).thenReturn(user);
        when(userRepo.hasPendingVerificationCode(4L)).thenReturn(true);
        when(userRepo.issueVerificationCode(any())).thenReturn("ABC1234");

        userService.resendVerificationCode("stepup@example.com");

        verify(userRepo, never()).sendVerificationCode(any());
        verify(userRepo, times(1)).issueVerificationCode(any());
        verify(notificationService, times(1))
                .sendStepUpCode(eq("Step"), eq("stepup@example.com"), eq("ABC1234"), anyString());
    }
}
