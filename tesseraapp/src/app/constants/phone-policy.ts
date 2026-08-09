/**
 * The frontend mirror of the backend's `PhonePolicy` (`constants/PhonePolicy.java`) — same
 * pattern, same US-only 10-digit shape. Kept as a single exported constant for the same reason
 * as `password-policy.ts`: two independent copies of a validation regex drift, and drift is
 * exactly how a 7-digit non-number like `"1234567"` used to pass the old, much looser
 * `^\+?[0-9. ()-]{7,25}$` pattern that lived directly on the HTML input.
 *
 * UX improvement only — the backend's `UpdateForm.phoneNumber` re-validates with the identical
 * pattern regardless of what the client sends.
 */
export const PHONE_PATTERN = '^(\\+?1[-.\\s]?)?\\(?[0-9]{3}\\)?[-.\\s]?[0-9]{3}[-.\\s]?[0-9]{4}$';

export const PHONE_HINT = 'A 10-digit US number, e.g. (808) 482-4518.';
