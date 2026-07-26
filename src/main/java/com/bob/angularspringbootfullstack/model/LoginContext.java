package com.bob.angularspringbootfullstack.model;

/**
 * A single historical sign-in "fingerprint" — the device string and IP address recorded on one
 * successful authentication.
 *
 * <p>This is a read-only projection over the {@code userevents} audit table rather than an entity
 * in its own right: FR-TPF-1 needs no new storage, because every successful login already writes a
 * row carrying exactly these two columns (see
 * {@link com.bob.angularspringbootfullstack.listener.NewUserEventListener}, which stamps them from
 * {@link com.bob.angularspringbootfullstack.utils.RequestUtils}). Modelling it as a {@code record}
 * — rather than a Lombok-builder model like {@link User} — reflects that: it is an immutable value
 * read out of a query, never persisted or mutated.
 *
 * <p>Both fields may be {@code null} or carry the sentinel {@code "Unknown IP"} that
 * {@code RequestUtils.getIpAddress} returns when no address can be determined;
 * {@link com.bob.angularspringbootfullstack.service.serviceimpl.LoginRiskServiceImpl} filters those
 * out rather than treating them as a distinct device or network.
 *
 * @param device    the {@code "OS - Browser - Device"} string parsed from the User-Agent header
 * @param ipAddress the originating IP address recorded for that sign-in
 */
public record LoginContext(String device, String ipAddress) {
}
