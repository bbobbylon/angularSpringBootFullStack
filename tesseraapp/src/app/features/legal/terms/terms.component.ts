import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * Public, unauthenticated Terms & Conditions page.
 *
 * Pairs with {@link PrivacyPolicyComponent} as the second stable, publicly
 * reachable URL (`/terms`) required by third parties before they'll enable a
 * feature — here, Twilio's A2P 10DLC campaign registration for SMS 2FA, which
 * asks for a link documenting the messaging program (opt-in method, message
 * frequency, opt-out instructions). Content is static and reflects the app's
 * actual SMS flow (see `NotificationServiceImpl#sendTwoFactorCode` and
 * `SecurityCenterComponent`'s phone-number opt-in form).
 */
@Component({
  selector: 'app-terms',
  imports: [RouterLink],
  templateUrl: './terms.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TermsComponent {}
