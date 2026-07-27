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
 * The command palette's labels and hints, mirrored from {@code public/assets/i18n/en.json}.
 *
 * <p>The palette resolves every command label through Transloco now, so a spec that asserts on
 * rendered text needs these to come back in English. They are duplicated here deliberately: a spec
 * that imported the live dictionary would keep passing if a translation were accidentally blanked,
 * because the assertion and the value under test would move together.
 */
export const EN_PALETTE: Record<string, string> = {
  'palette.analytics': "Analytics Hub",
  'palette.analyticsHint': "Admin · trends & stats",
  'palette.billing': "Billing Overview",
  'palette.billingHint': "Admin · revenue analytics",
  'palette.customers': "All Customers",
  'palette.customersHint': "Browse customer directory",
  'palette.home': "Home",
  'palette.homeHint': "Dashboard overview",
  'palette.invoices': "All Invoices",
  'palette.invoicesHint': "Browse invoices",
  'palette.logout': "Log Out",
  'palette.logoutHint': "End your session",
  'palette.manageServices': "Manage Services",
  'palette.manageServicesHint': "Admin · catalog CRUD",
  'palette.newCustomer': "New Customer",
  'palette.newCustomerHint': "Create a customer",
  'palette.newInvoice': "New Invoice",
  'palette.newInvoiceHint': "Create an invoice",
  'palette.profile': "Profile",
  'palette.profileHint': "Your account",
  'palette.roles': "Roles & Permissions",
  'palette.rolesHint': "Admin · RBAC matrix",
  'palette.sectionActions': "Actions",
  'palette.sectionNavigate': "Navigate",
  'palette.security': "Security Center",
  'palette.securityHint': "MFA & active sessions",
  'palette.securityOverview': "Security Overview",
  'palette.securityOverviewHint': "Admin · anomalies & MFA coverage",
  'palette.services': "Service Catalog",
  'palette.servicesHint': "Browse services & apps",
  'palette.toggleTheme': "Toggle Theme",
  'palette.toggleThemeHint': "Switch dark / light",
  'palette.users': "User Directory",
  'palette.usersHint': "Admin · manage users",
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
      const template = EN_STRINGS[key] ?? EN_PALETTE[key];
      if (template === undefined) return key;
      return template.replace(/\{\{(\w+)}}/g, (_, name: string) => String(params?.[name] ?? ''));
    }),
  };
}
