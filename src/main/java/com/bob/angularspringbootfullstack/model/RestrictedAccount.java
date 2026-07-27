package com.bob.angularspringbootfullstack.model;

import java.time.LocalDateTime;

/**
 * An account that currently cannot sign in — locked by brute-force protection, or not yet enabled
 * (SRS FR-TPF-2, security dashboard).
 *
 * <p>The two states are deliberately carried in one type because they are indistinguishable from
 * the user's side ("I can't get in") while having different remedies: a locked account needs an
 * administrative unlock, an unenabled one needs its verification email completing or an
 * administrative enable. Splitting them into two lists would force an administrator handling a
 * support call to check two places to answer one question.
 *
 * <p>{@code lastFailureAt} is nullable and its absence is informative rather than missing data: an
 * account that has never recorded a failed sign-in but is nonetheless restricted was almost
 * certainly disabled administratively or never verified, not locked out by password guessing. The
 * dashboard sorts nulls last for that reason — they are the least time-critical rows.
 *
 * @param userId        the restricted account's id, for linking to its admin detail page
 * @param firstName     the account holder's first name
 * @param lastName      the account holder's last name
 * @param email         the account's email address
 * @param nonLocked     false when brute-force protection has locked the account
 * @param enabled       false when the account has not completed verification or was disabled
 * @param lastFailureAt the most recent failed sign-in, or null if there has never been one
 */
public record RestrictedAccount(Long userId,
                                String firstName,
                                String lastName,
                                String email,
                                boolean nonLocked,
                                boolean enabled,
                                LocalDateTime lastFailureAt) {
}
