import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * Always-mounted global footer, rendered once by {@code AppComponent} below the router outlet
 * so it appears on every screen — authenticated or not — without every feature component having
 * to remember to include it.
 *
 * <p>Links to {@code /privacy}, {@code /terms} and {@code /contact}, the app's public,
 * unauthenticated legal/contact routes, plus a copyright line. Deliberately not run through
 * Transloco: {@link PrivacyPolicyComponent} and {@link TermsComponent} — the two pages this
 * footer links to most — are themselves static English content with no {@code *transloco}
 * scope, so translating only the footer's own three words around them would be a half
 * measure. See `documentation/FUTURE-ENHANCEMENTS.md` §3.5 for the design this replaced (a
 * placeholder note that a footer was coming).
 */
@Component({
  selector: 'app-footer',
  imports: [RouterLink],
  templateUrl: './footer.component.html',
  styleUrl: './footer.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FooterComponent {
  /** The year shown in the copyright line. Read once — a footer does not need to roll over live. */
  protected readonly year = new Date().getFullYear();
}
