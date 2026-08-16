package com.bob.angularspringbootfullstack.configuration;

import com.bob.angularspringbootfullstack.utils.RequestUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes the deployment's reverse-proxy depth into {@link RequestUtils} at startup.
 *
 * <p>This tiny bean exists to bridge two conventions that do not otherwise meet.
 * {@link RequestUtils} is a static utility — it is called from the audit listener, the session
 * service, the rate-limit filter, and the login-anomaly detector, none of which would benefit from
 * holding an injected collaborator just to read one integer. But the value it needs is a
 * <em>deployment</em> fact, and this project's rule is that deployment facts come from
 * configuration rather than constants. Reading the property here and pushing it into the utility
 * keeps the call sites simple while keeping the value externalized.
 *
 * <p>The startup log line is deliberate: how many proxies the application believes are in front of
 * it determines whether {@code X-Forwarded-For} is honored at all, and a mismatch between this
 * number and the real topology is silent but consequential. Too low and genuine client addresses
 * are replaced by the load balancer's, which collapses every user into one apparent network — the
 * rate limiter then throttles all users as if they were one caller, and the anomaly detector's
 * {@code NEW_NETWORK} signal can never fire. Too high and an attacker-supplied header entry is
 * treated as trustworthy, which is the vulnerability the whole mechanism exists to prevent.
 * Printing the effective value makes that misconfiguration visible in the boot log instead of
 * leaving it to be inferred from strange audit data months later.
 *
 * @see RequestUtils#getIpAddress(jakarta.servlet.http.HttpServletRequest)
 */
@Component
@Slf4j
public class TrustedProxyConfigurer {

    /**
     * Number of reverse proxies between the internet and this application.
     *
     * <p>Set it to the number of hops that append to {@code X-Forwarded-For} on the way in —
     * typically {@code 1} for a single ALB / Cloud Run front end, {@code 2} when a CDN sits in
     * front of that. The default of {@code 0} means "no proxy", which is correct for local
     * development and for any direct-to-container deployment, and which causes the header to be
     * ignored entirely.
     */
    @Value("${app.security.trusted-proxy-count:0}")
    private int trustedProxyCount;

    /**
     * Pushes the configured value into the static utility before any request is served.
     */
    @PostConstruct
    public void publishTrustedProxyCount() {
        RequestUtils.configureTrustedProxyCount(trustedProxyCount);
        if (trustedProxyCount == 0) {
            log.info("[NET] trusted-proxy-count=0 — X-Forwarded-For is IGNORED and the transport peer " +
                    "address is used. Correct for local runs and direct-to-container deployments; set " +
                    "app.security.trusted-proxy-count (TRUSTED_PROXY_COUNT) to the real hop count when " +
                    "running behind a load balancer, or every client will look like the proxy.");
        } else {
            log.info("[NET] trusted-proxy-count={} — the client address is read from X-Forwarded-For at " +
                    "position (length - {}); entries further left are treated as caller-supplied and " +
                    "ignored.", trustedProxyCount, trustedProxyCount);
        }
    }
}
