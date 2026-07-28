import { vi } from 'vitest';

/**
 * An in-memory Web Storage implementation for specs that exercise token storage.
 *
 * <p><b>Why this is needed at all.</b> The {@code @angular/build:unit-test} environment provides a
 * {@code window}, but not Web Storage: its {@code localStorage} global is an inert placeholder
 * object with no {@code getItem}, {@code setItem} or {@code clear}. Any spec that drives
 * {@link UserService} or {@code tokenInterceptor} — both of which read and write tokens through
 * the bare {@code localStorage} global — therefore fails on the first call with a
 * "not a function" {@code TypeError} that has nothing to do with the code under test.
 *
 * <p>Installing over the *global* is what makes this work rather than passing a double into the
 * service: the production code closes over the global directly, and in this environment
 * {@code window.localStorage} and {@code globalThis.localStorage} are the same reference, so one
 * stub covers both spellings.
 *
 * <p>The implementation is deliberately faithful to the DOM contract in the ways that matter for
 * these specs — values are coerced to strings, a missing key yields {@code null} rather than
 * {@code undefined}, and {@code length}/{@code key} enumerate. Storing a token and reading back
 * something that is not a string would quietly change what the JWT decoder is handed.
 */
class MemoryStorage implements Storage {
  private readonly entries = new Map<string, string>();

  get length(): number {
    return this.entries.size;
  }

  clear(): void {
    this.entries.clear();
  }

  /** Returns null for an absent key, as the DOM does — not undefined. */
  getItem(key: string): string | null {
    return this.entries.has(key) ? this.entries.get(key)! : null;
  }

  key(index: number): string | null {
    return [...this.entries.keys()][index] ?? null;
  }

  removeItem(key: string): void {
    this.entries.delete(key);
  }

  /** Coerces to string, as the DOM does; a token stored as a non-string would decode differently. */
  setItem(key: string, value: string): void {
    this.entries.set(String(key), String(value));
  }

  [name: string]: unknown;
}

/**
 * Installs a fresh in-memory {@code localStorage} over the global for the current spec.
 *
 * <p>Call from {@code beforeEach}, and pair with {@link restoreLocalStorage} in {@code afterEach}
 * so the stub does not leak into specs that expect the environment's own placeholder.
 *
 * @returns the installed storage, for tests that want to inspect it directly
 */
export function installMemoryLocalStorage(): Storage {
  const storage = new MemoryStorage();
  vi.stubGlobal('localStorage', storage);
  return storage;
}

/** Removes the stub installed by {@link installMemoryLocalStorage}. */
export function restoreLocalStorage(): void {
  vi.unstubAllGlobals();
}
