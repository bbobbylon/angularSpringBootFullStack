package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.ApiKeyDTO;
import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.event.NewUserEvent;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.form.CreateServiceAccountForm;
import com.bob.angularspringbootfullstack.form.IssueApiKeyForm;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.service.ApiKeyService;
import com.bob.angularspringbootfullstack.service.ServiceAccountService;
import com.bob.angularspringbootfullstack.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.bob.angularspringbootfullstack.enumeration.EventType.API_KEY_REVOKED;
import static com.bob.angularspringbootfullstack.utils.UserUtils.getAuthenticatedUser;
import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.OK;

/**
 * Administrative REST endpoints for service accounts and their API keys
 * (FUTURE-ENHANCEMENTS.md §3.1, P2-3 Option A) — the machine-to-machine sibling of
 * {@code AdminUserController}'s human-user management.
 *
 * <p>Authorization posture: every route here falls under {@code SecurityConfig}'s
 * {@code /admin/**} catch-all ({@code UPDATE:USER}/{@code UPDATE:ROLE}), same as
 * {@code AdminUserController} — see {@code CapabilityCatalog}'s
 * {@code capability.manageServiceAccounts} rule for the 403 message this surface reports. No new
 * {@code SecurityConfig} matcher is needed since nothing here is more permissive than that floor.
 *
 * <p>Nested-resource ownership: {@code /admin/serviceaccounts/{id}/apikeys/**} routes verify the
 * {@code keyId} path segment actually belongs to the {@code id} segment before acting on it
 * (see {@link #requireOwnedKey}), so a caller cannot revoke or reference a key by guessing an id
 * that belongs to a different service account, even though the authority check above would let an
 * admin manage any service account regardless.
 */
@RestController
@RequestMapping(path = "/admin/serviceaccounts")
@RequiredArgsConstructor
public class AdminServiceAccountController {

    private final ServiceAccountService serviceAccountService;
    private final ApiKeyService apiKeyService;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Lists every service account.
     *
     * @return 200 OK with {@code serviceAccounts}
     */
    @GetMapping
    public ResponseEntity<HttpResponse> listServiceAccounts() {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("serviceAccounts", serviceAccountService.list()))
                        .message("Service accounts retrieved.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Creates a new service account. The creating admin cannot grant it a role above their own
     * (see {@code RoleType#canAssign}).
     *
     * @param authentication the current Spring Security authentication (the creating admin)
     * @param form           the account's display name and role
     * @return 200 OK with the newly created {@code serviceAccount}
     */
    @PostMapping
    public ResponseEntity<HttpResponse> createServiceAccount(Authentication authentication, @Valid @RequestBody CreateServiceAccountForm form) {
        UserDTO admin = getAuthenticatedUser(authentication);
        UserDTO serviceAccount = serviceAccountService.create(form.getName(), form.getRole(), admin.getId());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("serviceAccount", serviceAccount))
                        .message("Service account created.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Deactivates a service account — {@code enabled = false} — so it can no longer authenticate
     * with any of its API keys, revoked or not.
     *
     * @param id the service account's {@code users.id}
     * @return 200 OK with the refreshed {@code serviceAccounts} list
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<HttpResponse> deactivateServiceAccount(@PathVariable Long id) {
        serviceAccountService.deactivate(id);
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("serviceAccounts", serviceAccountService.list()))
                        .message("Service account deactivated.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Lists a service account's API keys — active, revoked, and expired.
     *
     * @param id the service account's {@code users.id}
     * @return 200 OK with {@code apiKeys}
     */
    @GetMapping("/{id}/apikeys")
    public ResponseEntity<HttpResponse> listApiKeys(@PathVariable Long id) {
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("apiKeys", apiKeyService.listForUser(id)))
                        .message("API keys retrieved.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Issues a new API key for a service account. The raw key is returned <b>exactly once</b>,
     * in {@code rawKey} — it is never retrievable again after this response.
     *
     * @param authentication the current Spring Security authentication (the issuing admin)
     * @param id             the service account's {@code users.id}
     * @param form           the key's label and optional expiry
     * @return 200 OK with {@code rawKey} (shown once) and the refreshed {@code apiKeys} list
     */
    @PostMapping("/{id}/apikeys")
    public ResponseEntity<HttpResponse> issueApiKey(Authentication authentication, @PathVariable Long id, @Valid @RequestBody IssueApiKeyForm form) {
        UserDTO admin = getAuthenticatedUser(authentication);
        String rawKey = apiKeyService.issue(id, form.getName(), form.getExpiresAt(), admin.getId());
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("rawKey", rawKey, "apiKeys", apiKeyService.listForUser(id)))
                        .message("API key issued. Copy it now — it will not be shown again.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Revokes one of a service account's API keys.
     *
     * @param id    the service account's {@code users.id}
     * @param keyId the key's id; must belong to {@code id}
     * @return 200 OK with the refreshed {@code apiKeys} list
     */
    @DeleteMapping("/{id}/apikeys/{keyId}")
    public ResponseEntity<HttpResponse> revokeApiKey(@PathVariable Long id, @PathVariable Long keyId) {
        requireOwnedKey(id, keyId);
        apiKeyService.revoke(keyId);
        UserDTO serviceAccount = userService.getUserById(id);
        eventPublisher.publishEvent(new NewUserEvent(serviceAccount.getEmail(), API_KEY_REVOKED));
        return ResponseEntity.ok(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("apiKeys", apiKeyService.listForUser(id)))
                        .message("API key revoked.")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    /**
     * Confirms {@code keyId} belongs to service account {@code serviceAccountId} before a nested
     * route acts on it — see class Javadoc for why this check exists alongside the authority gate.
     */
    private void requireOwnedKey(Long serviceAccountId, Long keyId) {
        ApiKeyDTO key = apiKeyService.findById(keyId)
                .orElseThrow(() -> new ApiException("API key not found."));
        if (!key.getUserId().equals(serviceAccountId)) {
            throw new ApiException("API key not found.");
        }
    }
}
