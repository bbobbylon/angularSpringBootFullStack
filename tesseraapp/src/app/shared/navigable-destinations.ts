/**
 * Single source of truth for every route the app treats as a "destination" — a place a user can
 * jump to directly, as opposed to an action (theme toggle, logout) that has no URL of its own.
 *
 * <p>Two consumers share this list: {@link CommandPaletteComponent} (⌘/Ctrl+K) builds its
 * navigable entries from it, and the favorites/pinned-destinations bar
 * ({@code FavoritesBarComponent}) resolves a user's pinned {@code destinationId}s against it to
 * render icon/label/route. Before this module existed, the palette's {@code buildCommands()} was
 * the only place this list lived; extracting it here means a new destination (or an icon/route
 * change to an existing one) is added once and both surfaces pick it up, instead of two hardcoded
 * copies silently drifting apart.
 *
 * <p>{@code id} is the same opaque string persisted server-side by
 * {@code POST /user/favorites/{destinationId}} (see {@code FavoriteController} and
 * {@code schema.sql}'s {@code userfavorites} table) — it is never validated against this list on
 * the backend, so a destination removed here in a later change simply stops resolving to
 * anything renderable; {@code FavoritesBarComponent} is expected to skip ids it can't find.
 *
 * <p>Deliberately excludes: {@code home} (fine as a destination but it is the app's own landing
 * route — surfacing it in a "pin your favorites" bar would be circular), pure actions
 * ({@code toggle-theme}, {@code logout}) which have no route, and {@code new-service} (a query-param
 * variant of {@code manage-services} rather than a distinct route — pinning "manage-services"
 * already gets a user there).
 */
export interface DestinationDefinition {
  /** Stable identity — persisted as the favorite's {@code destinationId} and used for palette tracking. */
  readonly id: string;
  /** Router path passed straight to {@code Router.navigate}. */
  readonly path: string;
  /** Bootstrap-icon class (e.g. {@code 'bi-people-fill'}). */
  readonly icon: string;
  /** Transloco key for the primary label. */
  readonly labelKey: string;
  /** Transloco key for the secondary hint/description. */
  readonly hintKey: string;
  /**
   * Authorities under which this destination should be visible, matched with
   * {@code UserService.hasAnyAuthority} (any one is sufficient). Omitted for destinations every
   * authenticated user may reach.
   */
  readonly requiredAuthorities?: readonly string[];
}

/**
 * Every navigable destination in the app, in the same order the command palette has always
 * listed them. See the module-level doc above for what is intentionally excluded.
 */
export const NAVIGABLE_DESTINATIONS: readonly DestinationDefinition[] = [
  { id: 'customers', path: '/customers', icon: 'bi-people-fill', labelKey: 'palette.customers', hintKey: 'palette.customersHint' },
  { id: 'invoices', path: '/invoices', icon: 'bi-receipt-cutoff', labelKey: 'palette.invoices', hintKey: 'palette.invoicesHint' },
  { id: 'services', path: '/services', icon: 'bi-grid-1x2', labelKey: 'palette.services', hintKey: 'palette.servicesHint' },
  { id: 'profile', path: '/profile', icon: 'bi-person-circle', labelKey: 'palette.profile', hintKey: 'palette.profileHint' },
  { id: 'security', path: '/security', icon: 'bi-shield-lock', labelKey: 'palette.security', hintKey: 'palette.securityHint' },
  {
    id: 'new-customer',
    path: '/customer/new',
    icon: 'bi-person-plus-fill',
    labelKey: 'palette.newCustomer',
    hintKey: 'palette.newCustomerHint',
    requiredAuthorities: ['UPDATE:CUSTOMER', 'UPDATE:USER'],
  },
  {
    id: 'new-invoice',
    path: '/invoice/new',
    icon: 'bi-receipt',
    labelKey: 'palette.newInvoice',
    hintKey: 'palette.newInvoiceHint',
    requiredAuthorities: ['UPDATE:CUSTOMER', 'UPDATE:USER'],
  },
  {
    id: 'users',
    path: '/users',
    icon: 'bi-people-fill',
    labelKey: 'palette.users',
    hintKey: 'palette.usersHint',
    requiredAuthorities: ['UPDATE:USER', 'UPDATE:ROLE'],
  },
  {
    id: 'roles',
    path: '/roles',
    icon: 'bi-grid-3x3-gap-fill',
    labelKey: 'palette.roles',
    hintKey: 'palette.rolesHint',
    requiredAuthorities: ['UPDATE:USER', 'UPDATE:ROLE'],
  },
  {
    id: 'billing',
    path: '/billing',
    icon: 'bi-graph-up-arrow',
    labelKey: 'palette.billing',
    hintKey: 'palette.billingHint',
    requiredAuthorities: ['UPDATE:USER', 'UPDATE:ROLE'],
  },
  {
    id: 'analytics',
    path: '/analytics',
    icon: 'bi-bar-chart-line-fill',
    labelKey: 'palette.analytics',
    hintKey: 'palette.analyticsHint',
    requiredAuthorities: ['UPDATE:USER', 'UPDATE:ROLE'],
  },
  {
    id: 'security-overview',
    path: '/security-overview',
    icon: 'bi-shield-exclamation',
    labelKey: 'palette.securityOverview',
    hintKey: 'palette.securityOverviewHint',
    requiredAuthorities: ['UPDATE:USER', 'UPDATE:ROLE'],
  },
  {
    id: 'manage-services',
    path: '/services/manage',
    icon: 'bi-sliders',
    labelKey: 'palette.manageServices',
    hintKey: 'palette.manageServicesHint',
    requiredAuthorities: ['UPDATE:USER', 'UPDATE:ROLE'],
  },
];

/** Looks up a destination by id, or {@code undefined} if it no longer exists. */
export function findDestination(id: string): DestinationDefinition | undefined {
  return NAVIGABLE_DESTINATIONS.find((destination) => destination.id === id);
}
