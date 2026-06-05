import { Injectable, signal } from '@angular/core';

/** The two color modes the application supports. */
export type Theme = 'dark' | 'light';

/** localStorage key under which the user's chosen theme is persisted. */
const STORAGE_KEY = 'sc-theme';

/**
 * Owns the application's color-mode (dark/light) state.
 *
 * <p>The theme is expressed as a single attribute, {@code data-bs-theme}, on the
 * document's root element. That attribute is read by both Bootstrap 5.3's native
 * color-mode system and the custom design-token layer in {@code styles.css}, so
 * setting it here re-skins stock Bootstrap components and bespoke styling together.
 *
 * <p>The initial value is established before first paint by the inline script in
 * {@code index.html} (which prevents a flash of the wrong theme); this service then
 * mirrors that value into a signal so components can react to it and toggle it at
 * runtime. The choice is persisted to {@code localStorage} and falls back to the
 * operating-system preference ({@code prefers-color-scheme}) for first-time visitors.
 *
 * <p>Provided in {@code root}, so a single instance is shared app-wide. It is first
 * constructed when something injects it (currently the navbar's theme toggle).
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  /** Backing signal holding the active theme; mutated only through {@link set}. */
  private readonly _theme = signal<Theme>(this.readInitial());

  /** Read-only view of the active theme for templates and effects to consume. */
  readonly theme = this._theme.asReadonly();

  constructor() {
    // Re-assert the attribute so Bootstrap and the token layer match the signal,
    // even if the inline boot script was somehow skipped.
    this.apply(this._theme());
  }

  /**
   * Flips between dark and light and persists the result.
   * Wired to the navbar toggle button.
   */
  toggle(): void {
    this.set(this._theme() === 'dark' ? 'light' : 'dark');
  }

  /**
   * Sets an explicit theme, applies it to the DOM, and persists it.
   *
   * @param theme the color mode to activate
   */
  set(theme: Theme): void {
    this._theme.set(theme);
    this.apply(theme);
    try {
      localStorage.setItem(STORAGE_KEY, theme);
    } catch {
      /* Storage may be unavailable (private mode); the in-memory signal still works. */
    }
  }

  /**
   * Writes the theme to the document root and updates the browser UI theme-color.
   *
   * @param theme the color mode to reflect in the DOM
   */
  private apply(theme: Theme): void {
    const root = document.documentElement;
    root.setAttribute('data-bs-theme', theme);
    document
      .querySelector('meta[name="theme-color"]')
      ?.setAttribute('content', theme === 'dark' ? '#0a0c12' : '#f5f6fb');
  }

  /**
   * Resolves the theme to use on startup: a previously saved choice if present,
   * otherwise the OS-level {@code prefers-color-scheme}, defaulting to dark.
   *
   * @return the initial theme
   */
  private readInitial(): Theme {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved === 'dark' || saved === 'light') {
        return saved;
      }
      return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
    } catch {
      return 'dark';
    }
  }
}
