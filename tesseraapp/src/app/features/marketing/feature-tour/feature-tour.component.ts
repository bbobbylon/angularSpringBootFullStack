import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * Public, unauthenticated "what TesseraApp can do" page ({@code /features}) — the third leg of
 * the pre-signup public-facing surface (FUTURE-ENHANCEMENTS.md §3.5), alongside the legal pages
 * and Contact Us.
 *
 * <p>Deliberately built as static copy/icon cards, not live authenticated components rendered in
 * a public context — the backlog entry this replaces called that out explicitly as the wrong
 * shape, since any data-bound route risks slipping past {@code capability.guard.ts} the moment
 * someone edits it without noticing where it's mounted. No screenshots yet either: producing real
 * ones needs a running, logged-in instance to capture against, which is a separate pass:
 * this page describes real, currently-shipped capabilities, not aspirational ones.
 *
 * <p>Static English content, same reasoning as {@link TermsComponent}/{@link PrivacyPolicyComponent}:
 * not run through Transloco, since translating this page's own strings while the rest of the copy
 * stays English would be a half measure.
 */
@Component({
  selector: 'app-feature-tour',
  imports: [RouterLink],
  templateUrl: './feature-tour.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FeatureTourComponent {}
