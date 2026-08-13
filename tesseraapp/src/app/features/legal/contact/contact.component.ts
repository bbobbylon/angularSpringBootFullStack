import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ContactService } from '../../../service/contact.service';
import { NotificationsService } from '../../../service/notifications-service';

/**
 * Public, unauthenticated Contact Us page — the third leg of the pre-signup legal/support surface
 * alongside {@link TermsComponent} and {@link PrivacyPolicyComponent}, linked from the global
 * footer ({@code FooterComponent}).
 *
 * <p>Deliberately not run through Transloco, for the same reason those two pages aren't: they are
 * static/simple English content with no {@code *transloco} scope, so translating only this page's
 * own strings around them would be a half measure.
 *
 * <p>State is a single {@link submitted} flag rather than the app's usual {@code DataState}
 * machinery — there is no data to load, only a form to submit once, so LOADING/LOADED/ERROR would
 * be three states doing the work two booleans already cover.
 */
@Component({
  selector: 'app-contact',
  imports: [FormsModule, RouterLink],
  templateUrl: './contact.component.html',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContactComponent {
  private readonly contactService = inject(ContactService);
  private readonly notification = inject(NotificationsService);
  private readonly destroyRef = inject(DestroyRef);

  /** True while the submission is in flight — disables the form and shows a spinner. */
  protected readonly isLoading = signal(false);
  /** True once a submission has succeeded — swaps the form for a thank-you message. */
  protected readonly submitted = signal(false);

  /**
   * Submits the form. On success, replaces the form with a confirmation message rather than
   * routing away — a visitor who came to ask a question should see it was received, not be sent
   * back to a login screen they may not even have an account for.
   *
   * @param contactForm the template-driven form with name, email, subject, and message
   */
  protected submit(contactForm: NgForm): void {
    this.isLoading.set(true);
    this.contactService
      .submit$(contactForm.value)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isLoading.set(false);
          this.submitted.set(true);
          this.notification.onSuccess(response.message);
        },
        error: (error: string) => {
          this.isLoading.set(false);
          this.notification.onError(error);
        },
      });
  }
}
