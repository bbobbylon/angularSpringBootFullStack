import { TranslocoTestingModule } from '@jsverse/transloco';

/**
 * Real {@code TranslocoService}, wired for specs that render a component using the
 * {@code *transloco="let t"} structural directive ({@code TranslocoDirective}).
 *
 * <p>{@link translocoStub} (a plain {@code { translate: vi.fn() }} double, in
 * {@code transloco-stub.ts}) is enough for code that calls {@code TranslocoService#translate()}
 * directly — the command palette, the route guards. {@code TranslocoDirective} reaches much
 * deeper into the service (its {@code config}, {@code langChanges$}, scope resolution), machinery
 * a hand-rolled double cannot cheaply reproduce; a spec that tries throws deep inside the
 * directive's {@code ngOnInit} ({@code Cannot read properties of undefined
 * (reading 'reRenderOnLangChange')}) rather than at the point a real misconfiguration would show up.
 *
 * <p>{@code TranslocoTestingModule} is {@code @jsverse/transloco}'s own answer to that: the real
 * service, wired to an in-memory loader instead of the app's HTTP one. No translations are loaded
 * for {@code en}, so every key resolves to itself — the same fallback the real dictionaries fall
 * back to for a genuinely missing key — which keeps specs asserting on rendered copy independent of
 * whatever is currently in {@code public/assets/i18n/en.json}.
 */
export const TRANSLOCO_TESTING_IMPORTS = [
  TranslocoTestingModule.forRoot({
    langs: { en: {} },
    preloadLangs: true,
    translocoConfig: {
      availableLangs: ['en'],
      defaultLang: 'en',
      reRenderOnLangChange: false,
      missingHandler: { logMissingKey: false },
    },
  }),
];
