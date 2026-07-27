package com.bob.angularspringbootfullstack.model;

import java.time.LocalDateTime;

/**
 * One anomaly-flagged sign-in, as shown in the security dashboard's activity table (SRS FR-TPF-2).
 *
 * <p>A read-only projection over the {@code SUSPICIOUS_LOGIN} rows FR-TPF-1 writes into
 * {@code userevents}, joined to the account they belong to. Modelled as a {@code record} for the
 * same reason as {@link LoginContext}: it is a value read out of a query and rendered, never
 * persisted, never mutated.
 *
 * <p>The {@code detail} field is the one that carries the analytical weight. FR-TPF-1 records
 * which signals fired and which step-up was applied — {@code "a new device → step-up: EMAIL_CODE"}
 * — so a reader can tell an account that was challenged with an authenticator (already well
 * protected) from one that fell back to an emailed code (single-factor, and the case worth acting
 * on). Without it, every row would say only "something was noticed", which is a notification, not
 * evidence.
 *
 * <p>Nulls are expected and must be rendered, not filtered: {@code device} and {@code ipAddress}
 * are null on rows written before FR-TPF-1's request-context capture, and {@code detail} is null
 * for any event type that carries no extra context. A row with a missing device is still a real
 * flagged sign-in.
 *
 * @param userId    the flagged account's id, so the UI can link straight to its admin detail page
 * @param firstName the account holder's first name
 * @param lastName  the account holder's last name
 * @param email     the account's email address — the identifier an administrator will search by
 * @param device    the {@code "OS - Browser - Device"} string parsed from the User-Agent, or null
 * @param ipAddress the originating address recorded for the attempt, or null
 * @param detail    which signals fired and which step-up was applied, or null
 * @param createdAt when the flagged sign-in occurred
 */
public record SuspiciousLoginEntry(Long userId,
                                   String firstName,
                                   String lastName,
                                   String email,
                                   String device,
                                   String ipAddress,
                                   String detail,
                                   LocalDateTime createdAt) {
}
