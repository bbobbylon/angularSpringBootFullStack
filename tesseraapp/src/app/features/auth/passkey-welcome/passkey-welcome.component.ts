import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { UserService } from '../../../service/user.service';
import { NotificationsService } from '../../../service/notifications-service';
import { dismissPasskeyPrompt, startRegistration } from '../../../utils/webauthn.utils';
import { TranslocoService } from '@jsverse/transloco';

/**
 * One-time, skippable interstitial shown immediately after a successful login when the
 * account has no passkey yet (`shouldPromptForPasskey`, `webauthn.utils.ts`) — the "option 1"
 * onboarding hook: passkeys can't be created during registration itself (that account is
 * disabled/unverified and holds no authenticated session yet — see
 * `documentation/flows/13-passkeys.md`), so the first authenticated moment after registration
 * completes is the earliest point a WebAuthn ceremony can actually run.
 *
 * <p>Reuses the exact same enroll flow as the Security Center's "Add a passkey" card
 * ({@link startRegistration}, `webauthnEnrollOptions$`/`webauthnEnrollComplete$}) — this page is
 * purely a better-timed entry point, not a second implementation. "Maybe later" and a successful
 * add both call {@link dismissPasskeyPrompt}, so a user who declines is never asked again on this
 * device (deliberately per-device, not per-account — see that function's doc for why).
 */
@Component({
  selector: 'app-passkey-welcome',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './passkey-welcome.component.html',
  styleUrl: './passkey-welcome.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PasskeyWelcomeComponent {
  /** True while a registration ceremony is in flight. */
  protected readonly adding = signal(false);

  private readonly userService = inject(UserService);
  private readonly router = inject(Router);
  private readonly notification = inject(NotificationsService);
  private readonly transloco = inject(TranslocoService);

  /**
   * Runs the same enroll ceremony as the Security Center card, then returns home. A cancelled
   * platform prompt throws a {@link DOMException} before any backend call — treated as the user
   * simply changing their mind, not an error.
   */
  protected async addPasskey(nameForm: NgForm): Promise<void> {
    const deviceName = (nameForm.value.deviceName as string) || 'Passkey';
    this.adding.set(true);
    try {
      const options = await firstValueFrom(this.userService.webauthnEnrollOptions$());
      const credential = await startRegistration(options.data!.publicKey as PublicKeyCredentialCreationOptionsJSON);
      await firstValueFrom(this.userService.webauthnEnrollComplete$(deviceName, credential));
      dismissPasskeyPrompt();
      this.notification.onSuccess(this.transloco.translate('toasts.passkeyAdded'));
      await this.router.navigate(['/']);
    } catch (error) {
      if (!(error instanceof DOMException)) {
        this.notification.onError(error instanceof Error ? error.message : String(error));
      }
    } finally {
      this.adding.set(false);
    }
  }

  /** Declines the prompt for good on this device and continues to the app. */
  protected skip(): void {
    dismissPasskeyPrompt();
    this.router.navigate(['/']);
  }
}
