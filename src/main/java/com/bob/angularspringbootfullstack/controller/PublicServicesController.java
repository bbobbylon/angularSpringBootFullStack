package com.bob.angularspringbootfullstack.controller;

import com.bob.angularspringbootfullstack.model.HttpResponse;
import com.bob.angularspringbootfullstack.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.OK;

/**
 * Unauthenticated public browsing of the services catalog — {@code GET /services/public}.
 *
 * <h3>Why this is its own controller</h3>
 * {@link ServicesCatalogController} is entirely {@code /admin/**}-gated catalog administration, and
 * {@link CustomerController#newInvoice} is entirely authenticated-staff-only — it also returns the
 * caller's customer list and organization scope, none of which has any meaning for an anonymous
 * visitor. Neither is the right home for "let anyone browsing the site see what we sell." A third,
 * deliberately narrow seam keeps both existing controllers' authorization stories simple rather than
 * carving one {@code permitAll} exception into a class whose every other method is gated.
 *
 * <h3>What it returns, and why it reuses rather than re-queries</h3>
 * Delegates to {@link CustomerService#getServices()} — the same active-only query the authenticated
 * new-invoice form already relies on — so there is exactly one definition of "what's currently for
 * sale" in the codebase, not two that could silently drift apart. The response carries no customer
 * data and no user echo: the caller may not have a principal at all.
 */
@RestController
@RequestMapping(path = "/services")
@RequiredArgsConstructor
public class PublicServicesController {

    private final CustomerService customerService;

    /**
     * Lists the active service catalog for an unauthenticated visitor.
     *
     * @return 200 OK with {@code services}
     */
    @GetMapping("/public")
    public ResponseEntity<HttpResponse> listPublicServices() {
        return ResponseEntity.ok(HttpResponse.builder()
                .timeStamp(now().toString())
                .data(of("services", customerService.getServices()))
                .message("Service catalog retrieved successfully!")
                .status(OK)
                .statusCode(OK.value())
                .build());
    }
}
