import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * Public, unauthenticated Privacy Policy page.
 *
 * Exists primarily so the app has a stable, publicly reachable URL
 * (`/privacy`) to hand to third parties that require one — e.g. Twilio's
 * A2P 10DLC campaign registration, which will not approve SMS sending
 * without a privacy policy link that documents how phone numbers and
 * text-messaging consent are handled. Content is static and reflects the
 * app's actual data practices; it carries no application state.
 */
@Component({
  selector: 'app-privacy-policy',
  imports: [RouterLink],
  templateUrl: './privacy-policy.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PrivacyPolicyComponent {}
