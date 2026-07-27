package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.model.Services;
import com.bob.angularspringbootfullstack.service.ServicesCatalogService;
import com.bob.angularspringbootfullstack.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
 */
@RestController
@RequestMapping(path = "/admin/services")
@RequiredArgsConstructor
public class ServicesCatalogController {

    private final ServicesCatalogService servicesCatalogService;
    private final UserService userService;

    /**
     * Lists the entire catalog, retired entries included.
     *
     * @param user the authenticated (admin) principal, echoed in the envelope
     * @return 200 OK with {@code user} and {@code services}
     */
    @GetMapping("/list")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> listServices(@AuthenticationPrincipal UserDTO user) {
        return ResponseEntity.ok(envelope(user, "services", servicesCatalogService.getAllServices(),
                "Service catalog retrieved successfully!"));
    }

    /**
     * Retrieves one catalog entry, for an edit form.
     *
     * @param user      the authenticated (admin) principal
     * @param serviceId the id to fetch
     * @return 200 OK with {@code user} and {@code service}
     */
    @GetMapping("/get/{serviceId}")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> getService(@AuthenticationPrincipal UserDTO user,
                                                   @PathVariable Long serviceId) {
        return ResponseEntity.ok(envelope(user, "service", servicesCatalogService.getService(serviceId),
                "Service retrieved successfully!"));
    }

    /**
     * Adds a service to the catalog.
     *
     * @param user    the authenticated (admin) principal
     * @param service the service to create; any submitted id is ignored
     * @return 201 Created with {@code user} and the persisted {@code service}
     */
    @PostMapping("/create")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> createService(@AuthenticationPrincipal UserDTO user,
                                                      @RequestBody @Valid Services service) {
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
     * than a partial one. Retirement is excluded from it and has its own endpoint.
     *
     * @param user      the authenticated (admin) principal
     * @param serviceId the id to edit
     * @param service   the submitted values
     * @return 200 OK with {@code user} and the updated {@code service}
     */
    @PutMapping("/update/{serviceId}")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> updateService(@AuthenticationPrincipal UserDTO user,
                                                      @PathVariable Long serviceId,
                                                      @RequestBody @Valid Services service) {
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
     */
    @PatchMapping("/{serviceId}/active/{active}")
    @PreAuthorize("hasAnyAuthority('UPDATE:USER', 'UPDATE:ROLE')")
    public ResponseEntity<HttpResponse> setServiceActive(@AuthenticationPrincipal UserDTO user,
                                                         @PathVariable Long serviceId,
                                                         @PathVariable boolean active) {
        Services updated = servicesCatalogService.setServiceActive(serviceId, active);
        return ResponseEntity.ok(envelope(user, "service", updated,
                active ? "Service is now available." : "Service has been retired."));
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
