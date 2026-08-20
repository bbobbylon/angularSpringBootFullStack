package com.bob.angularspringbootfullstack.service.serviceimpl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, single-use tickets that carry "user X asked to link provider Y" across the OAuth2
 * round trip (ROADMAP §1.4 — federated account linking).
 *
 * <h3>Why a ticket exists at all</h3>
 * Linking has to happen on behalf of the <em>already signed-in</em> user, but the browser leaves the
 * application entirely during the OAuth handshake. The signed-in identity lives in a JWT, and a JWT
 * cannot ride a top-level navigation — you cannot attach an {@code Authorization} header to
 * {@code window.location.assign(...)}. So the identity has to be handed over some other way.
 *
 * <p>The obvious alternative is to have the SPA set a cookie-backed session with an authenticated
 * XHR before navigating. That works when the SPA and API share an origin, and is awkward when they
 * do not — in development they are {@code :4200} and {@code :8080}, so it drags in CORS credentials
 * and SameSite behavior for one call. A ticket sidesteps that: it is minted over an ordinary
 * authenticated XHR, travels in the URL of a top-level navigation, and is exchanged server-side.
 *
 * <h3>Why it is safe to put in a URL</h3>
 * A ticket is an opaque random UUID that is <b>single-use</b>, <b>expires in five minutes</b>, and
 * <b>grants nothing on its own</b> — redeeming it does not authenticate anybody. All it does is tell
 * the OAuth callback which local account a successfully-verified provider identity should be
 * attached to. An attacker who steals a ticket can only cause their <em>own</em> provider identity
 * to be linked to the victim's account, which still has to pass the "already linked elsewhere"
 * refusal, and which the victim sees in their audit log and can undo from the Security Center.
 *
 * <h3>Known limitation</h3>
 * The store is in-memory, so a ticket minted on one instance cannot be redeemed on another. That is
 * consistent with the brute-force counter, which is also per-instance, and acceptable because a
 * ticket lives for five minutes and both legs of the exchange come from the same browser within
 * seconds. Behind a load balancer without sticky sessions this needs to move to the database or a
 * shared cache — the interface would not change.
 */
@Service
@Slf4j
public class ProviderLinkTicketService {

    /** How long a ticket remains redeemable. Long enough to finish a consent screen, short enough not to linger. */
    private static final Duration TICKET_TTL = Duration.ofMinutes(5);

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    /**
     * A pending link intent.
     *
     * @param userId    the account the provider should be attached to
     * @param provider  the registration id the ticket was minted for
     * @param expiresAt when the ticket stops being redeemable
     */
    private record Ticket(Long userId, String provider, Instant expiresAt) {
    }

    /**
     * Mints a ticket for an authenticated user.
     *
     * @param userId   the signed-in account, taken from the JWT principal — never from a request body
     * @param provider the registration id the user chose
     * @return the opaque ticket to put in the link URL
     */
    public String mint(Long userId, String provider) {
        purgeExpired();
        String ticket = UUID.randomUUID().toString();
        tickets.put(ticket, new Ticket(userId, provider, Instant.now().plus(TICKET_TTL)));
        log.debug("[FEDERATION] Minted link ticket for userId={} provider={}", userId, provider);
        return ticket;
    }

    /**
     * Redeems a ticket, consuming it.
     *
     * <p>The provider is checked as well as the ticket itself, so a ticket minted for one provider
     * cannot be replayed against another — otherwise a user who started a GitHub link could be
     * steered into attaching a Google identity instead.
     *
     * @param ticket   the value from the link URL
     * @param provider the registration id the callback is actually completing
     * @return the user id to link to, or empty when the ticket is unknown, expired, or for a
     *         different provider
     */
    public Optional<Long> redeem(String ticket, String provider) {
        if (ticket == null || ticket.isBlank()) return Optional.empty();

        Ticket found = tickets.get(ticket);
        if (found == null) {
            log.debug("[FEDERATION] Link ticket not found or already used.");
            return Optional.empty();
        }
        if (Instant.now().isAfter(found.expiresAt())) {
            tickets.remove(ticket, found);
            log.debug("[FEDERATION] Link ticket expired for userId={}", found.userId());
            return Optional.empty();
        }
        if (!found.provider().equals(provider)) {
            // Deliberately does NOT consume. Checking before removing matters: consuming on a
            // mismatch would let anyone who learns a ticket cancel somebody else's pending link
            // just by presenting it at the wrong provider — a small denial of service that costs
            // nothing to prevent.
            log.warn("[FEDERATION] Link ticket provider mismatch: minted for '{}', redeemed at '{}'",
                    found.provider(), provider);
            return Optional.empty();
        }

        // Atomic consume: remove(key, value) succeeds for exactly one caller, so two concurrent
        // redemptions of the same ticket cannot both be handed the user id.
        if (!tickets.remove(ticket, found)) {
            log.debug("[FEDERATION] Link ticket was consumed concurrently.");
            return Optional.empty();
        }
        return Optional.of(found.userId());
    }

    /**
     * Drops expired entries so an unused ticket cannot accumulate indefinitely.
     *
     * <p>Called on mint rather than on a schedule: the map only grows when tickets are minted, so
     * that is exactly when it is worth tidying, and it avoids a timer for a map that is normally
     * empty.
     */
    private void purgeExpired() {
        Instant now = Instant.now();
        tickets.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
    }
}
