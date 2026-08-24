package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.event.NewOrganizationEvent;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.Organization;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.bob.angularspringbootfullstack.enumeration.EventType.ORG_INVITE_REDEEMED;
import static com.bob.angularspringbootfullstack.utils.UserUtils.getAuthenticatedUser;
import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.OK;

/**
 * Self-service invite redemption for the organization dashboard revamp (2026-08-22) — the
 * counterpart to {@link OrganizationController}'s admin-facing invite management, but reachable by
 * <em>any</em> authenticated user, since the person clicking a shared invite link is by definition
 * not yet a member (and often not any kind of administrator) of the organization they're joining.
 * That is why this lives under {@code /user/organization} rather than
 * {@code /admin/organization}: {@code SecurityConfig} gates it with a plain
 * {@code .authenticated()} matcher, not {@code UPDATE:ORGANIZATION}.
 * <p>
 * Both endpoints resolve an unknown, expired, or already-redeemed code to the exact same "not
 * found" outcome (see {@link OrganizationService#previewInvite} and
 * {@link OrganizationService#redeemInvite}'s Javadoc) so a stale or guessed link cannot be used to
 * fingerprint whether it once existed (NFR-SEC-7).
 */
@RestController
@RequestMapping(path = "/user/organization")
@RequiredArgsConstructor
@Slf4j
public class OrganizationInviteController {

    private final OrganizationService organizationService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Previews an invite's organization name so the join page can ask "Join {name}?" before the
     * user commits — does not redeem the invite or alter any state.
     *
     * @param code the invite code from the join link
     * @return 200 OK with the organization name, or 404 if the code is unknown or expired
     */
    @GetMapping("/invite/{code}")
    public ResponseEntity<HttpResponse> previewInvite(@PathVariable String code) {
        return organizationService.previewInvite(code)
                .map(organizationName -> ResponseEntity.ok(
                        HttpResponse.builder()
                                .timeStamp(now().toString())
                                .data(of("organizationName", organizationName))
                                .message("Invite retrieved successfully.")
                                .status(OK)
                                .statusCode(OK.value())
                                .build()))
                .orElseThrow(() -> new ApiException("This invite link is invalid or has expired."));
    }

    /**
     * Redeems an invite: joins the authenticated caller to the invite's organization with its
     * granted role, then consumes the invite so it cannot be redeemed twice.
     *
     * @param authentication the redeeming (already-authenticated) user's authentication
     * @param code           the invite code from the join link
     * @return 200 OK with the organization the caller just joined
     */
    @PostMapping("/invite/{code}/redeem")
    public ResponseEntity<HttpResponse> redeemInvite(Authentication authentication, @PathVariable String code) {
        UserDTO caller = getAuthenticatedUser(authentication);
        Organization organization = organizationService.redeemInvite(code, caller.getId());
        eventPublisher.publishEvent(new NewOrganizationEvent(organization.getId(), caller.getId(), ORG_INVITE_REDEEMED, caller.getEmail()));
        log.info("'{}' redeemed an invite and joined organization '{}'", caller.getEmail(), organization.getName());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("organization", organization))
                        .message("You have joined " + organization.getName() + ".")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }
}
