import { vi } from 'vitest';

/**
 * Shared test doubles for translation-aware code.
 *
 * <h3>Why this lives outside the spec files</h3>
 * The stub below started life exported from {@code admin.guard.spec.ts}, which
 * {@code capability.guard.spec.ts} then imported. That works, but it silently runs every test in
 * {@code admin.guard.spec.ts} <em>twice</em>: Vitest collects the file once as a spec of its own,
 * and again as part of the importing spec's module graph. The visible symptom was a suite
 * reporting 42 tests against 35 {@code it()} blocks — harmless while everything passes, and
 * actively misleading the moment it does not, since one failure is then reported from two places
 * and the duplicated run shares no state with the original.
 *
 * <p>Test helpers therefore live in {@code src/app/testing/}, which contains no {@code describe}
 * blocks and can be imported freely.
 */

/**
 * The subset of {@code public/assets/i18n/en.json} that the route guards read.
 *
 * <p>Duplicated here rather than imported from the real dictionary so a spec failure points at a
 * behaviour change in the guard rather than at an unrelated copy edit, and so the assertions can
 * state the expected sentence in full — which is what a reader of the spec actually wants to see.
 */
export const EN_STRINGS: Record<string, string> = {
  'permissions.denied': "You don't have permission to {{action}} — contact your administrator.",
  'permissions.actions.manageUsers': 'manage users',
  'permissions.actions.viewBilling': 'view billing',
  'permissions.actions.createCustomers': 'create customers',
  'permissions.actions.generic': 'access this area',
};

/**
 * A {@code TranslocoService} double that reproduces the two behaviours the guards depend on.
 *
 * <p>It interpolates {@code {{param}}} placeholders, and — importantly — it returns the **key
 * itself** when a translation is missing, exactly as Transloco does. That second behaviour is what
 * the guards' fallback logic tests for, so a stub returning {@code undefined} or an empty string
 * would let a broken fallback pass and ship a UI that shows users raw keys.
 *
 * @returns an object exposing a spied {@code translate}
 */
export function translocoStub(): { translate: ReturnType<typeof vi.fn> } {
  return {
    translate: vi.fn((key: string, params?: Record<string, unknown>) => {
      const template = EN_STRINGS[key];
      if (template === undefined) return key;
      return template.replace(/\{\{(\w+)}}/g, (_, name: string) => String(params?.[name] ?? ''));
    }),
  };
}
