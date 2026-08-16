/**
 * Builders for the JWTs the client-side token inspection code is asked to read.
 *
 * <p>Nothing here signs anything, and nothing needs to. The browser never verifies a token's
 * signature — {@code JwtHelperService} base64-decodes the payload and reads claims, and the
 * backend is the only party that checks the signature (NFR-SEC-4). So the third segment is a
 * fixed piece of filler, and these builders can produce token shapes a real
 * {@code TokenProvider} would never emit: no {@code exp}, no {@code authorities}, truncated.
 * Those are precisely the shapes worth testing, because they are what a corrupted or
 * hand-edited {@code localStorage} entry actually looks like.
 *
 * <p>Used by {@code authentication.guard.spec.ts} and {@code user.service.authority.spec.ts}.
 */

/** Encodes a value as the unpadded base64url a JWT segment uses. */
const segment = (value: unknown): string =>
  btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/** Options describing the token to build; every field has a sensible default. */
export interface JwtOptions {
  /**
   * Lifetime relative to now, in seconds. Negative values produce an already-expired token.
   * Ignored when {@link omitExp} is set. Defaults to one hour.
   */
  expiresInSeconds?: number;
  /** Extra payload claims, e.g. {@code { authorities: ['UPDATE:USER'] }}. */
  claims?: Record<string, unknown>;
  /**
   * Omits the {@code exp} claim entirely. The helper library reports such a token as *not*
   * expired, which is a behavior worth pinning rather than discovering in production.
   */
  omitExp?: boolean;
}

/**
 * Builds a decodable, unsigned JWT string.
 *
 * @param options - see {@link JwtOptions}
 * @returns a three-segment token suitable for {@code localStorage}
 */
export function jwtWith({ expiresInSeconds = 3600, claims = {}, omitExp = false }: JwtOptions = {}): string {
  const payload: Record<string, unknown> = {
    sub: 'spec@tessera.test',
    iss: 'TesseraApp',
    ...claims,
    ...(omitExp ? {} : { exp: Math.floor(Date.now() / 1000) + expiresInSeconds }),
  };

  // The signature segment is filler: valid base64url so the string is well-formed, and never read.
  return `${segment({ alg: 'HS512', typ: 'JWT' })}.${segment(payload)}.c2lnbmF0dXJl`;
}

/**
 * Builds a token carrying exactly the given authorities, in the claim the backend's
 * {@code TokenProvider} writes and Spring Security enforces against.
 *
 * @param authorities - authority strings such as {@code 'UPDATE:ROLE'}
 * @param expiresInSeconds - lifetime relative to now; negative for an expired token
 */
export const jwtGranting = (authorities: string[], expiresInSeconds = 3600): string =>
  jwtWith({ claims: { authorities }, expiresInSeconds });
