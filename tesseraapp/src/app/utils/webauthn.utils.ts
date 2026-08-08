/**
 * Thin wrappers around the browser's native WebAuthn Level 3 JSON serialization API
 * (`PublicKeyCredential.parseCreationOptionsFromJSON` / `.parseRequestOptionsFromJSON` /
 * `credential.toJSON()`), hand-rolled rather than pulling in a passkey npm package —
 * consistent with this app's existing "implement the auth primitive in-house" pattern
 * (the backend's `TotpUtils` hand-rolls RFC 6238 the same way).
 *
 * The browser's own JSON methods handle every base64url ArrayBuffer conversion, so this
 * file's only job is: feature-detect, call the right browser API, and surface a clean
 * `credential` object ready to POST straight to the backend (which parses it with
 * webauthn4j's own `parseRegistrationResponseJSON`/`parseAuthenticationResponseJSON` —
 * both sides speak the same WebAuthn spec JSON shape, so nothing in between needs to
 * re-encode anything).
 */

/** Whether this browser supports WebAuthn at all — gates every passkey UI element. */
export function isWebAuthnSupported(): boolean {
  return typeof window !== 'undefined' && !!window.PublicKeyCredential;
}

/**
 * Runs a registration ceremony: converts the server's JSON creation options into the
 * browser's native option shape, prompts the platform authenticator via
 * `navigator.credentials.create()`, and returns the resulting credential re-serialized
 * to JSON for the backend's verify endpoint.
 *
 * @param publicKey the `publicKey` object from `POST /user/webauthn/register/options`
 * @returns the credential response, ready to POST to `/user/webauthn/register/verify`
 * @throws whatever `navigator.credentials.create()` throws — most commonly `NotAllowedError`
 *         when the user cancels or dismisses the platform prompt
 */
export async function startRegistration(publicKey: PublicKeyCredentialCreationOptionsJSON): Promise<unknown> {
  const options = PublicKeyCredential.parseCreationOptionsFromJSON(publicKey);
  const credential = (await navigator.credentials.create({ publicKey: options })) as PublicKeyCredential;
  return credential.toJSON();
}

/**
 * Runs a usernameless (discoverable-credential) authentication ceremony: converts the
 * server's JSON request options into the browser's native shape, prompts the user to pick
 * a passkey via `navigator.credentials.get()`, and returns the resulting assertion
 * re-serialized to JSON for the backend's verify endpoint.
 *
 * @param publicKey the `publicKey` object from `POST /user/verify/webauthn/options`
 * @returns the assertion response, ready to POST to `/user/verify/webauthn`
 * @throws whatever `navigator.credentials.get()` throws — most commonly `NotAllowedError`
 *         when the user cancels, or when no passkey for this relying party exists on device
 */
export async function startAuthentication(publicKey: PublicKeyCredentialRequestOptionsJSON): Promise<unknown> {
  const options = PublicKeyCredential.parseRequestOptionsFromJSON(publicKey);
  const credential = (await navigator.credentials.get({ publicKey: options })) as PublicKeyCredential;
  return credential.toJSON();
}
