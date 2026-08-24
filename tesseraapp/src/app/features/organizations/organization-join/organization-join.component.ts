import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavbarComponent } from '../../../shared/navbar/navbar.component';
import { OrganizationService } from '../../../service/organization.service';
import { NotificationsService } from '../../../service/notifications-service';
import { DataState } from '../../../enumeration/datastate.enum';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * The landing page a shared organization invite link opens to
 * ({@code /organizations/join/:code}), completing the invite flow
 * {@code OrganizationsComponent}'s Invites tab starts by creating and copying the link.
 *
 * <h3>Why a confirmation step, not an immediate redeem</h3>
 * The link is often opened cold — forwarded in an email or chat, clicked before the recipient
 * necessarily remembers who sent it or why. {@code GET /user/organization/invite/:code} (a
 * read-only preview, no state change) resolves the code to just the organization's name so this
 * page can ask "Join {name}?" before anything happens; only clicking through calls
 * {@code POST /user/organization/invite/:code/redeem}, which actually adds the membership and
 * consumes the invite.
 *
 * <h3>Reachable by any authenticated user, not just admins</h3>
 * Unlike every other route under {@code /organizations}, this one carries only
 * {@code authenticationGuard} — see {@code app.routes.ts}'s comment on this route. The person
 * opening a shared invite link is, by definition, not yet a member of (and often has no
 * administrative role in) the organization they are joining, and the backend's
 * {@code OrganizationInviteController} only ever requires {@code .authenticated()} — see that
 * controller's Javadoc.
 *
 * <h3>Unknown, expired, and already-redeemed codes look identical</h3>
 * {@code OrganizationService#previewInvite$}'s Javadoc explains why: the backend deletes an
 * invite row on redemption exactly like {@code resetpasswordverifications}, so there is no way to
 * distinguish "never existed," "expired," and "already used" without leaking information
 * (NFR-SEC-7). This page shows one generic error state for all three.
 */
@Component({
  selector: 'app-organization-join',
  standalone: true,
  imports: [NavbarComponent, TranslocoDirective],
  templateUrl: './organization-join.component.html',
  styleUrl: './organization-join.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrganizationJoinComponent implements OnInit {
  /** Template access to DataState for the preview/redeem state machine. */
  readonly DataState = DataState;
  /** {@code 'preview'} while resolving the code, {@code 'joined'} once redeemed, else {@code DataState}. */
  protected readonly state = signal<DataState | 'joined'>(DataState.LOADING);
  /** The invite's organization name, once the preview resolves. */
  protected readonly organizationName = signal<string | undefined>(undefined);
  /** True while the redeem request is in flight, so the "Join" button can disable and spin. */
  protected readonly isJoining = signal(false);

  private readonly organizationService = inject(OrganizationService);
  private readonly notification = inject(NotificationsService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  /** The invite code from the route, captured once for both the preview and redeem calls. */
  private code = '';

  /** Resolves the invite code from the route and previews its organization's name. */
  ngOnInit(): void {
    this.code = this.route.snapshot.paramMap.get('code') ?? '';
    if (!this.code) {
      this.state.set(DataState.ERROR);
      return;
    }
    this.organizationService
      .previewInvite$(this.code)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.organizationName.set(response.data?.organizationName);
          this.state.set(DataState.LOADED);
        },
        error: () => this.state.set(DataState.ERROR),
      });
  }

  /** Redeems the invite, then returns to the home dashboard after a brief confirmation. */
  protected join(): void {
    if (this.isJoining()) return;
    this.isJoining.set(true);
    this.organizationService
      .redeemInvite$(this.code)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isJoining.set(false);
          this.state.set('joined');
          this.notification.onSuccess(response.message ?? 'You have joined the organization');
        },
        error: (error: string) => {
          this.isJoining.set(false);
          this.notification.onError(error);
          this.state.set(DataState.ERROR);
        },
      });
  }

  /** Returns to the home dashboard, used by both the success state and the "Cancel" link. */
  protected goHome(): void {
    this.router.navigate(['/']);
  }
}
