package com.bob.angularspringbootfullstack.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Multi-factor enrollment across the in-scope account population (SRS FR-TPF-2).
 *
 * <p>This is the dashboard's only *posture* metric — everything else on the screen reports what
 * has happened, while this reports how exposed the population is to what happens next. It is the
 * number that makes FR-TPF-1's step-up story legible: an emailed code is the fallback for exactly
 * the {@link #singleFactorUsers} group, so that count is simultaneously a measure of risk and a
 * measure of how much load the weakest step-up path is carrying.
 *
 * <p>The three groups are mutually exclusive and sum to {@link #totalUsers} by construction — they
 * come from conditional sums over a single scan, with {@code using_totp} taking precedence so an
 * account holding both factors is reported at its strongest rather than counted twice. Percentages
 * derived from these are therefore guaranteed to total 100%, which separately-issued counts could
 * not promise.
 *
 * @param totalUsers        accounts in scope
 * @param totpUsers         accounts with a confirmed authenticator app (the strongest factor here)
 * @param smsUsers          accounts using SMS MFA and no authenticator
 * @param singleFactorUsers accounts protected by a password alone
 */
public record MfaAdoption(long totalUsers, long totpUsers, long smsUsers, long singleFactorUsers) {

    /**
     * The share of in-scope accounts holding any second factor, as a percentage rounded to one
     * decimal place.
     *
     * <p>Returns {@code 0} rather than dividing by zero for an empty population — which is a real
     * case, not a defensive flourish: an organization admin whose memberships have all lapsed sees
     * a scope of nobody, and that screen must render zeros rather than fail.
     *
     * <p>{@code @JsonProperty} is required rather than decorative: Jackson serializes a record
     * from its component accessors only, so a derived method would otherwise be invisible to the
     * client and the SPA would silently show nothing where the coverage figure belongs.
     *
     * @return the percentage of accounts with MFA of any kind, 0 when there are no accounts
     */
    @JsonProperty("mfaCoveragePercent")
    public double mfaCoveragePercent() {
        if (totalUsers == 0) return 0;
        return Math.round(((totpUsers + smsUsers) * 1000.0) / totalUsers) / 10.0;
    }
}
