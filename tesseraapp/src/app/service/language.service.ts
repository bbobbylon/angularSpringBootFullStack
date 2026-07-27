import { inject, Injectable, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

/** A language the application ships translations for. */
export interface LanguageOption {
  /** ISO 639-1 code, matching the dictionary filename under {@code public/assets/i18n}. */
  code: string;
  /** The language's name **in that language** — see {@link LanguageService.available}. */
  label: string;
  /** Short code for the compact navbar trigger. */
  short: string;
}

/** localStorage key under which the user's chosen language is persisted. */
const STORAGE_KEY = 'sc-lang';

/**
 * Owns the application's active language, mirroring {@code ThemeService} in shape and intent.
 *
 * <p>The two services are deliberately parallel: both hold one user preference, both expose it as a
 * signal, both persist to {@code localStorage}, and both fall back to a browser-level default for a
 * first-time visitor. Anyone who has read one can read the other, and the navbar hosts both
 * controls side by side.
 *
 * <h3>Why a service rather than calling TranslocoService directly</h3>
 * Transloco already tracks the active language, so this could have been a thin wrapper — but it is
 * the wrapper that owns three things Transloco has no opinion about: which languages this
 * application actually ships, that the choice survives a reload, and that the {@code <html lang>}
 * attribute follows it. That last one is not cosmetic: screen readers select a pronunciation voice
 * from it, and a Spanish page announced with an English voice is close to unusable.
 */
@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly transloco = inject(TranslocoService);

  /**
   * The languages on offer.
   *
   * <p>Each is labelled in its own language ("Español", not "Spanish"). A user who has landed in a
   * language they cannot read needs to find their way out, and the one string they are certain to
   * recognise is the name of their own language — labelling the list in the *current* interface
   * language would hide the exit behind the very problem it solves.
   */
  readonly available: readonly LanguageOption[] = [
    { code: 'en', label: 'English', short: 'EN' },
    { code: 'es', label: 'Español', short: 'ES' },
    { code: 'fr', label: 'Français', short: 'FR' },
    { code: 'de', label: 'Deutsch', short: 'DE' },
    { code: 'pt', label: 'Português', short: 'PT' },
    { code: 'zh', label: '中文', short: '中' },
  ];

  private readonly _current = signal<string>('en');

  /** Read-only view of the active language code, for templates. */
  readonly current = this._current.asReadonly();

  constructor() {
    this.set(this.readInitial(), false);
  }

  /**
   * Activates a language, persists it, and reflects it on the document.
   *
   * @param code - a language code from {@link available}; anything else is ignored rather than
   *               activated, since an unknown code would leave Transloco fetching a dictionary
   *               that does not exist and every string rendered as its raw key
   * @param persist - whether to remember the choice; false during construction, where the value
   *                  has just been read back out of storage and rewriting it would be pointless
   */
  set(code: string, persist = true): void {
    if (!this.available.some((option) => option.code === code)) return;

    this._current.set(code);
    this.transloco.setActiveLang(code);
    document.documentElement.setAttribute('lang', code);

    if (!persist) return;
    try {
      localStorage.setItem(STORAGE_KEY, code);
    } catch {
      /* Storage may be unavailable (private mode); the in-memory signal still works. */
    }
  }

  /**
   * The label of the active language, for the navbar trigger.
   *
   * @returns the matching option, falling back to the first available language
   */
  currentOption(): LanguageOption {
    return this.available.find((option) => option.code === this._current()) ?? this.available[0];
  }

  /**
   * Resolves the language to use on startup: a saved choice if present, otherwise the browser's
   * preferred language when the application ships it, otherwise English.
   *
   * <p>Only the primary subtag is compared, so {@code es-MX} and {@code es-ES} both resolve to the
   * Spanish dictionary. Regional variants differ in vocabulary and formatting, and shipping one
   * translation for both is a compromise — but it is a far better one than showing a Mexican
   * Spanish speaker an English interface because the exact tag was not on the list.
   *
   * @returns the initial language code
   */
  private readInitial(): string {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved && this.available.some((option) => option.code === saved)) return saved;

      const browser = (navigator.language ?? 'en').split('-')[0].toLowerCase();
      if (this.available.some((option) => option.code === browser)) return browser;
      return 'en';
    } catch {
      return 'en';
    }
  }
}
