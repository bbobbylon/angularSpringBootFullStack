import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { ResetPasswordStateInterface } from '../../../interface/appstates.interface';
import { UserService } from '../../../service/user.service';
import { FormsModule, NgForm } from '@angular/forms';
import { DataState } from '../../../enumeration/datastate.enum';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NotificationsService } from '../../../service/notifications-service';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Password reset view used after a reset link is verified.
 *
 * Provides the form for entering and confirming a new password. State is held
 * in {@link resetPasswordState}, a writable signal mutated via {@code .set()}
 * so the template re-renders cleanly under {@code OnPush}.
 */
@Component({
  selector: 'app-resetpassword',
  imports: [RouterLink, FormsModule, TranslocoDirective],
  templateUrl: './resetpassword.component.html',
  styleUrl: './resetpassword.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResetPasswordComponent {
  /** Drives the template's loading/success/error rendering. */
  resetPasswordState = signal<ResetPasswordStateInterface>({ dataState: DataState.LOADED });

  protected readonly userService = inject(UserService);
  protected readonly DataState = DataState;
  private readonly destroyRef = inject(DestroyRef);
  private readonly notification = inject(NotificationsService);

  /**
   * Submits the password reset request and drives component state.
   *
   * Extracts the email from the {@code resetPasswordEmail} form field and passes
   * it to {@link UserService#requestPasswordReset$}. The form is cleared on success
   * and the success screen is shown; on failure the inline error alert renders.
   *
   * @param resetPasswordForm - template-driven form with the resetPasswordEmail field
   */
  resetPassword(resetPasswordForm: NgForm): void {
    this.resetPasswordState.set({ dataState: DataState.LOADING, resetPasswordSuccess: false });
    this.userService.requestPasswordReset$(resetPasswordForm.value.resetPasswordEmail)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          console.log(response);
          resetPasswordForm.reset();
          this.notification.onSuccess(response.message);
          this.resetPasswordState.set({ dataState: DataState.LOADED, resetPasswordSuccess: true, message: response.message });
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.resetPasswordState.set({ dataState: DataState.ERROR, resetPasswordError: true, error });
        },
      });
  }
}
