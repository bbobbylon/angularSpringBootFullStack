import { vi } from 'vitest';

/**
 * Installs browser-shaped stand-ins for the WebAuthn Level 3 primitives that
 * {@code utils/webauthn.utils.ts} calls directly ({@code PublicKeyCredential.parse*OptionsFromJSON},
 * {@code navigator.credentials.create}/{@code .get}), so a spec can drive a real passkey ceremony.
 *
 * <p>{@code startRegistration}/{@code startAuthentication} are plain functions, not injectable
 * services — there is nothing for {@code TestBed} to override, and Angular's
 * {@code @angular/build:unit-test} runner refuses {@code vi.mock} on relative imports ("Please use
 * Angular TestBed for mocking dependencies"). Stubbing at the actual browser boundary those two
 * functions wrap is the only way to drive a ceremony in a spec at all — and it is also the more
 * faithful double: it exercises the real {@code startRegistration}/{@code startAuthentication}
 * code end to end, with only the genuinely un-fakeable native API underneath standing in.
 *
 * <p>Call from a setup function BEFORE {@code TestBed.createComponent()} — every component that
 * uses this reads {@code isWebAuthnSupported()} once, at field-initializer time.
 *
 * @param supported false hides the passkey UI entirely (mirrors a non-WebAuthn browser) and
 *                  installs nothing; true installs working stand-ins for both ceremony halves
 * @returns the two spies, for tests that want to control what the "platform prompt" resolves or
 *          rejects with — undefined when {@code supported} is false
 */
export function stubWebAuthn(supported: boolean): { create: ReturnType<typeof vi.fn>; get: ReturnType<typeof vi.fn> } | undefined {
  if (!supported) {
    vi.stubGlobal('PublicKeyCredential', undefined);
    return undefined;
  }

  const create = vi.fn();
  const get = vi.fn();

  vi.stubGlobal('PublicKeyCredential', {
    // The real browser API converts base64url fields to ArrayBuffers on the way in and back on
    // the way out; these specs never inspect that shape, only that the object handed to them
    // flows through to navigator.credentials.*, so an identity pass-through stands in for both —
    // exactly the JSON-in/JSON-out contract utils/webauthn.utils.ts's own doc comment describes.
    parseCreationOptionsFromJSON: vi.fn((json: unknown) => json),
    parseRequestOptionsFromJSON: vi.fn((json: unknown) => json),
  });
  Object.defineProperty(navigator, 'credentials', {
    value: { create, get },
    configurable: true,
    writable: true,
  });

  return { create, get };
}
