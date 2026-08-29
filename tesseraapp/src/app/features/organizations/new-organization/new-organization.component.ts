import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, filter, switchMap } from 'rxjs/operators';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { UserService } from '../../../service/user.service';
import { OrganizationService } from '../../../service/organization.service';
import { CustomerService } from '../../../service/customer.service';
import { NotificationsService } from '../../../service/notifications-service';
import { DataState } from '../../../enumeration/datastate.enum';
import { UserInterface } from '../../../interface/user.interface';
import { CustomerInterface } from '../../../interface/customer.interface';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Dedicated "create organization" screen (FUTURE-ENHANCEMENTS.md §3.2), split out of
 * {@code OrganizationsComponent} on 2026-08-29 so the catalog/create surfaces match the app's
 * established pattern — {@code /customer/new} and {@code /invoice/new} are already separate
 * routes from their list pages, and this brings {@code /organizations} in line rather than
 * carrying the create form inline above the card grid.
 *
 * <p>Everything here — the base fields, the collapsed advanced-setup section (profile fields,
 * tenant UUID, MFA policy, feature flags, attached-customer picker, confirmation email), and the
 * single {@code POST /admin/organization} call — is unchanged from what used to live in
 * {@code OrganizationsComponent#createOrganization}. Only the destination changed: it now stays
 * on this page and resets the form on success (matching {@code NewCustomerComponent}), rather than
 * splicing the response into a catalog signal this page no longer holds. A "View organizations"
 * link in the breadcrumb and success area gets the caller back to the list in one click.
 *
 * <h3>Route-level gate is a UX narrowing, not the security boundary</h3>
 * {@code OrganizationController#requireUnscopedTier} refuses this call below {@code ROLE_ADMIN}/
 * {@code ROLE_APPLICATION_ADMIN} regardless of what this component does. The route itself only
 * carries {@code adminGuard} (staff-grade authority), same as {@code /organizations} — a caller
 * who reaches this page without the narrower unscoped-tier role sees a permission notice instead
 * of the form (see {@link isUnscopedTier}), rather than a form that would 403 on submit.
 */
@Component({
  selector: 'app-new-organization',
  standalone: true,
  imports: [FormsModule, RouterLink, NavbarComponent, TranslocoDirective],
  templateUrl: './new-organization.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewOrganizationComponent implements OnInit {
  /** Template access to DataState for skeleton/error rendering. */
  readonly DataState = DataState;
  /** MFA methods offered by the advanced-setup policy checkboxes — see OrganizationsComponent's twin field. */
  readonly MFA_METHODS: readonly string[] = ['TOTP', 'PASSKEY', 'SMS', 'PHONE_CALL'];
  /** Page load state — starts LOADING while the caller's own role is confirmed (see {@link isUnscopedTier}). */
  protected readonly dataState = signal<DataState>(DataState.LOADING);
  /** The signed-in user, used only to gate the form to the unscoped tiers. */
  protected readonly user = signal<UserInterface | undefined>(undefined);
  /** Tracks the in-flight create request so the submit button can disable and show a spinner. */
  protected readonly isMutating = signal(false);
  /** The MFA methods selected in the advanced-setup checkboxes. */
  protected readonly createMfaMethods = signal<Set<string>>(new Set());
  /** Customers chosen to attach in the advanced-setup picker. */
  protected readonly createSelectedCustomers = signal<CustomerInterface[]>([]);
  /** Directory matches for the advanced-setup customer-search input. */
  protected readonly createCustomerCandidates = signal<CustomerInterface[]>([]);

  /**
   * Whether the signed-in user may create an organization at all.
   *
   * <p>Mirrors {@code OrganizationController#requireUnscopedTier} exactly the way
   * {@code OrganizationsComponent#isUnscopedTier} did — by role name, not authority string, since
   * {@code ROLE_ORGANIZATION_ADMIN} also carries {@code UPDATE:ORGANIZATION} but is excluded here.
   */
  protected get isUnscopedTier(): boolean {
    const role = this.user()?.roleName;
    return role === 'ROLE_ADMIN' || role === 'ROLE_APPLICATION_ADMIN';
  }

  private readonly userService = inject(UserService);
  private readonly organizationService = inject(OrganizationService);
  private readonly customerService = inject(CustomerService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly router = inject(Router);

  /** Raw keystrokes from the customer-search input; debounced below before hitting the API. */
  private readonly createCustomerSearchInput$ = new Subject<string>();

  /** Loads the signed-in user, purely to evaluate {@link isUnscopedTier} before rendering the form. */
  ngOnInit(): void {
    this.userService
      .profile$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.user.set(response.data?.user);
          this.dataState.set(DataState.LOADED);
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.dataState.set(DataState.ERROR);
        },
      });

    this.createCustomerSearchInput$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        filter((term) => term.length >= 2),
        switchMap((term) => this.customerService.searchCustomers$(term, 0, 5)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (response) => this.createCustomerCandidates.set(response.data?.page?.content ?? []),
        error: (error: string) => this.notification.onError(error),
      });
  }

  /**
   * Submits the form ({@code POST /admin/organization}), including whatever the collapsed
   * advanced-setup section carries. On success the form resets in place (matching
   * {@code NewCustomerComponent}) rather than navigating away, so creating several organizations
   * in a row needs no back-and-forth to the list.
   *
   * @param form - the NgForm carrying {@code name} and the advanced-setup text/URL/email fields
   */
  protected createOrganization(form: NgForm): void {
    if (!form.valid) return;
    this.isMutating.set(true);
    const featureFlags = this.splitFeatureFlags(form.value.featureFlags);
    const customerIds = this.createSelectedCustomers().map((customer) => customer.id);
    this.organizationService
      .createOrganization$(form.value.name, {
        description: form.value.description || undefined,
        contactEmail: form.value.contactEmail || undefined,
        website: form.value.website || undefined,
        tenantUuid: form.value.tenantUuid || undefined,
        mfaAllowedMethods: this.createMfaMethods().size > 0 ? Array.from(this.createMfaMethods()) : undefined,
        featureFlags: featureFlags.length > 0 ? featureFlags : undefined,
        customerIds: customerIds.length > 0 ? customerIds : undefined,
        sendConfirmationEmail: !!form.value.sendConfirmationEmail,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isMutating.set(false);
          this.notification.onSuccess(response.message ?? 'Organization created successfully');
          form.resetForm();
          this.createMfaMethods.set(new Set());
          this.createSelectedCustomers.set([]);
          this.createCustomerCandidates.set([]);
        },
        error: (error: string) => {
          this.isMutating.set(false);
          this.notification.onError(error);
        },
      });
  }

  /** Toggles one MFA method in the advanced-setup policy checkboxes. */
  protected toggleCreateMfaMethod(method: string): void {
    const next = new Set(this.createMfaMethods());
    if (next.has(method)) next.delete(method);
    else next.add(method);
    this.createMfaMethods.set(next);
  }

  /** Pushes each keystroke from the customer-search input into the debounced pipeline. */
  protected onCreateCustomerSearchInput(term: string): void {
    this.createCustomerSearchInput$.next(term);
  }

  /** Adds a search result to the selected-customers list, if not already chosen. */
  protected addCreateCustomer(candidate: CustomerInterface): void {
    if (this.createSelectedCustomers().some((customer) => customer.id === candidate.id)) return;
    this.createSelectedCustomers.set([...this.createSelectedCustomers(), candidate]);
  }

  /** Removes a customer from the selected-customers list. */
  protected removeCreateCustomer(candidate: CustomerInterface): void {
    this.createSelectedCustomers.set(this.createSelectedCustomers().filter((customer) => customer.id !== candidate.id));
  }

  /**
   * Splits a comma-separated feature-flags field into its trimmed, non-blank labels — the
   * frontend counterpart of the backend's {@code joinFeatureFlags}.
   */
  private splitFeatureFlags(text: string | undefined | null): string[] {
    if (!text) return [];
    return text
      .split(',')
      .map((label) => label.trim())
      .filter((label) => label.length > 0);
  }
}
