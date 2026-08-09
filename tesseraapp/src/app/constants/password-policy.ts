/**
 * The frontend mirror of the backend's `PasswordPolicy` (`constants/PasswordPolicy.java`) —
 * same pattern, same minimum length, same requirements. Kept as a single exported constant
 * rather than copied into each of the three password forms (register, reset, change) so a
 * future policy change can't update two of the three and silently miss the third, which is
 * exactly the bug this class exists to prevent on the backend side.
 *
 * This is a UX improvement only, not the security boundary — the backend re-validates with the
 * identical pattern on every one of the three password endpoints regardless of what the client
 * sends. What this closes is the confusing case where a weak password looked accepted (passed
 * a lax client-side check) and then failed with a 400 the user had no warning was coming.
 */
export const PASSWORD_MIN_LENGTH = 8;

/** Must match `PasswordPolicy.PATTERN` in the backend exactly. */
export const PASSWORD_PATTERN = '^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)\\S{8,}$';

export const PASSWORD_HINT =
  'At least 8 characters, with an uppercase letter, a lowercase letter, and a number. No spaces.';
