package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.enumeration.RoleType;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.Services;
import com.bob.angularspringbootfullstack.service.OrganizationService;
import com.bob.angularspringbootfullstack.service.ServicesCatalogService;
import com.bob.angularspringbootfullstack.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;

import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.OK;

/**
 * Administrative CRUD for the services catalog (ROADMAP §2 — "Create / manage services").
 *
 * <h3>Why the catalog needed an admin API</h3>
 * The catalog was browse-only: rows existed because {@code DemoDataSeeder} inserted them, and the
 * only way to add a real service was to write SQL by hand. That is fine for a demo and untenable
 * for a system that bills people — an offering that cannot be added through the application is one
 * that gets added inconsistently, or invoiced as free text, which defeats the point of having a
 * catalog at all.
 *
 * <h3>Authorization</h3>
 * Mounted under {@code /admin/**}, so {@code SecurityConfig}'s existing matcher
 * ({@code hasAnyAuthority("UPDATE:USER", "UPDATE:ROLE")}) gates the whole class with <b>no new
 * request matcher</b> — the same approach {@link AnalyticsController} and
 * {@link SecurityDashboardController} take, and the reason is worth repeating: matchers are
 * evaluated top-down, so every new one is a chance to place a rule below a catch-all and silently
 * open a hole. Reusing the existing prefix means the ordering cannot be got wrong.
 * {@link PreAuthorize} repeats the check at the method level (FR-RBAC-2).
 *
 * <p><b>Reading stays public to authenticated users.</b> Browsing the catalog happens through
 * {@code GET /customer/invoice/new}, which every signed-in user can reach — they need it to raise
 * an invoice. This controller's read endpoints exist because administrators need to see
 * <em>retired</em> entries too, which the public path deliberately hides.
 *
 * <h3>Per-organization catalogs (2026-08-28)</h3>
 * {@link Services#getOrganizationId()} lets a catalog entry be either globally shared
 * ({@code null}, the default every pre-existing row and the public catalog use) or privately owned
 * by one organization. The class-level {@code @PreAuthorize} above is unchanged and still gates
 * every method to a holder of {@code UPDATE:USER}/{@code UPDATE:ROLE} — which, per the existing
 * role grants, includes both the unscoped platform-operator tiers <em>and</em> the org-scoped
 * {@code ROLE_ORGANIZATION_ADMIN}/{@code ROLE_HELP_DESK_ADMIN} tiers. Before this, that meant any
 * org-scoped admin could already create, edit or retire <em>any</em> catalog entry — including, once
 * private entries exist, another organization's — because nothing below the method-level authority
 * check distinguished "my organization's catalog" from "the whole catalog". {@link #requireVisible}
 * and {@link #requireManageable} close that gap the same way {@code CustomerController}'s own
 * organization-scope resolution closes it for customers: an unscoped caller may act on anything; a
 * scoped caller may read a globally shared entry but not create/edit/retire one (unscoped-only), and
 * may create/edit/retire an entry only when it is owned by one of its own active organizations.
 */
@RestController
@RequestMapping(path = "/admin/services")
@RequiredArgsConstructor
@Slf4j
public class ServicesCatalogController {

    private final ServicesCatalogService servicesCatalogService;
    private final UserService userService;
    private final OrganizationService organizationService;

    /**
     * Lists the catalog visible to the caller, retired entries included: the whole catalog for an
     * unscoped tier, or the globally shared entries plus the caller's own organizations' entries for
     * a scoped tier.
     *
     * @param user the authenticated (admin) principal, echoed in the envelope
     * @return 200 OK with {@code user} and {@code services}
     */
    @GetMapping("/list")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> listServices(@AuthenticationPrincipal UserDTO user) {
        List<Services> services = RoleType.isOrganizationScoped(user.getRoleName())
                ? servicesCatalogService.getAllServicesForOrganizations(organizationService.findActiveOrganizationIds(user.getId()))
                : servicesCatalogService.getAllServices();
        return ResponseEntity.ok(envelope(user, "services", services,
                "Service catalog retrieved successfully!"));
    }

    /**
     * Retrieves one catalog entry, for an edit form.
     *
     * @param user      the authenticated (admin) principal
     * @param serviceId the id to fetch
     * @return 200 OK with {@code user} and {@code service}
     * @throws AccessDeniedException if the caller is scoped and this entry belongs to another organization
     */
    @GetMapping("/get/{serviceId}")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> getService(@AuthenticationPrincipal UserDTO user,
                                                   @PathVariable Long serviceId) {
        Services service = servicesCatalogService.getService(serviceId);
        requireVisible(user, service);
        return ResponseEntity.ok(envelope(user, "service", service,
                "Service retrieved successfully!"));
    }

    /**
     * Adds a service to the catalog.
     *
     * <p>{@link Services#getOrganizationId()} on the submitted body decides ownership: {@code null}
     * creates a globally shared entry (unscoped tiers only); a non-null id creates an entry private
     * to that organization, and requires the caller to actively belong to it (or be an unscoped
     * tier acting on any organization's behalf).
     *
     * @param user    the authenticated (admin) principal
     * @param service the service to create; any submitted id is ignored
     * @return 201 Created with {@code user} and the persisted {@code service}
     * @throws AccessDeniedException if the caller may not create an entry with this ownership
     */
    @PostMapping("/create")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> createService(@AuthenticationPrincipal UserDTO user,
                                                      @RequestBody @Valid Services service) {
        requireManageable(user, service.getOrganizationId());
        Services created = servicesCatalogService.createService(service);
        return ResponseEntity.status(CREATED).body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("user", userService.getUserByEmail(user.getEmail()), "service", created))
                        .message("Service has been added to the catalog!")
                        .status(CREATED)
                        .statusCode(CREATED.value())
                        .build());
    }

    /**
     * Edits an existing catalog entry.
     *
     * <p>{@code PUT} rather than {@code PATCH}: the service layer replaces name, description and
     * price together, so the request is a full statement of the entry's editable content rather
     * than a partial one. Retirement is excluded from it and has its own endpoint, and ownership
     * ({@code organizationId}) is immutable after creation — see {@code ServicesCatalogServiceImpl
     * #updateService} for why.
     *
     * @param user      the authenticated (admin) principal
     * @param serviceId the id to edit
     * @param service   the submitted values
     * @return 200 OK with {@code user} and the updated {@code service}
     * @throws AccessDeniedException if the caller is scoped and this entry belongs to another organization
     */
    @PutMapping("/update/{serviceId}")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> updateService(@AuthenticationPrincipal UserDTO user,
                                                      @PathVariable Long serviceId,
                                                      @RequestBody @Valid Services service) {
        requireManageable(user, servicesCatalogService.getService(serviceId).getOrganizationId());
        return ResponseEntity.ok(envelope(user, "service", servicesCatalogService.updateService(serviceId, service),
                "Service updated successfully!"));
    }

    /**
     * Retires or reinstates a catalog entry.
     *
     * <p>Deliberately not {@code DELETE}. Beyond the data-preservation argument in
     * {@link Services#getActive()}, {@code DELETE /admin/services/**} would fall under
     * SecurityConfig's {@code DELETE "/customer/delete/**"}-style rules only by accident; keeping
     * retirement a {@code PATCH} means it is governed by the same {@code /admin/**} authority as
     * every other operation here, with nothing to reason about separately.
     *
     * @param user      the authenticated (admin) principal
     * @param serviceId the id to change
     * @param active    true to offer the service, false to retire it
     * @return 200 OK with {@code user} and the updated {@code service}
     * @throws AccessDeniedException if the caller is scoped and this entry belongs to another organization
     */
    @PatchMapping("/{serviceId}/active/{active}")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> setServiceActive(@AuthenticationPrincipal UserDTO user,
                                                         @PathVariable Long serviceId,
                                                         @PathVariable boolean active) {
        requireManageable(user, servicesCatalogService.getService(serviceId).getOrganizationId());
        Services updated = servicesCatalogService.setServiceActive(serviceId, active);
        return ResponseEntity.ok(envelope(user, "service", updated,
                active ? "Service is now available." : "Service has been retired."));
    }

    /**
     * Refuses a read when the caller is org-scoped and the entry is privately owned by an
     * organization the caller does not actively belong to. A globally shared entry ({@code null}
     * ownership) always passes, and an unscoped caller always passes.
     *
     * @param caller  the authenticated principal
     * @param service the entry being read
     * @throws AccessDeniedException if the caller may not see this entry
     */
    private void requireVisible(UserDTO caller, Services service) {
        if (!RoleType.isOrganizationScoped(caller.getRoleName())) return;
        Long organizationId = service.getOrganizationId();
        if (organizationId == null) return;
        Collection<Long> scope = organizationService.findActiveOrganizationIds(caller.getId());
        if (!scope.contains(organizationId)) {
            log.warn("Org-scoped caller '{}' denied reading service id={} owned by organization {}",
                    caller.getEmail(), service.getId(), organizationId);
            throw new AccessDeniedException("This service is outside your organization scope.");
        }
    }

    /**
     * Refuses a create/edit/retire when the caller may not manage an entry with the given ownership:
     * a scoped caller may manage a {@code null}-owned (global) catalog entry not at all, and an
     * organization-owned entry only when it actively belongs to that organization. An unscoped
     * caller may always manage any ownership.
     *
     * @param caller         the authenticated principal
     * @param organizationId the target entry's ownership ({@code null} = global)
     * @throws AccessDeniedException if the caller may not manage an entry with this ownership
     */
    private void requireManageable(UserDTO caller, Long organizationId) {
        if (!RoleType.isOrganizationScoped(caller.getRoleName())) return;
        if (organizationId == null) {
            log.warn("Org-scoped caller '{}' denied managing a global catalog entry", caller.getEmail());
            throw new AccessDeniedException("Only an administrator can manage the shared service catalog.");
        }
        Collection<Long> scope = organizationService.findActiveOrganizationIds(caller.getId());
        if (!scope.contains(organizationId)) {
            log.warn("Org-scoped caller '{}' denied managing a service owned by organization {}",
                    caller.getEmail(), organizationId);
            throw new AccessDeniedException("This service is outside your organization scope.");
        }
    }

    /**
     * Builds the project's standard response envelope with the caller embedded alongside a payload.
     *
     * <p>Factored out because all four success paths here differ only in one key and one sentence,
     * and four near-identical builder chains is where a copy-paste slip goes unnoticed.
     *
     * @param user    the authenticated principal
     * @param key     the data key for the payload
     * @param payload the payload
     * @param message the user-facing message
     * @return the populated envelope
     */
    private HttpResponse envelope(UserDTO user, String key, Object payload, String message) {
        return HttpResponse.builder()
                .timeStamp(now().toString())
                .data(of("user", userService.getUserByEmail(user.getEmail()), key, payload))
                .message(message)
                .status(OK)
                .statusCode(OK.value())
                .build();
    }
}
