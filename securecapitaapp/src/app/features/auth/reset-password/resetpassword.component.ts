import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { map, Observable, of, startWith } from 'rxjs';
import { ResetPasswordStateInterface } from '../../../interface/appstates.interface';
import { UserService } from '../../../service/user.service';
import { FormsModule, NgForm } from '@angular/forms';
import { catchError } from 'rxjs/operators';
import { DataState } from '../../../enumeration/datastate.enum';
import { RouterLink } from '@angular/router';
import { AsyncPipe } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';

/**
 * Password reset view used after a reset link is verified.
 *
 * Provides the form for entering and confirming a new password.
 */
@Component({
  selector: 'app-resetpassword',
  imports: [RouterLink, FormsModule, AsyncPipe],
  templateUrl: './resetpassword.component.html',
  styleUrl: './resetpassword.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResetPasswordComponent {
  resetPasswordState = signal<ResetPasswordStateInterface>({ dataState: DataState.LOADED });

  protected readonly userService = inject(UserService);
  protected readonly DataState = DataState;

  /**
   * Submits the password reset request and drives the component state.
   *
   * Extracts the email from the {@code resetPasswordEmail} form field and passes it to
   * {@link UserService#requestPasswordReset$}. On success the form is cleared and the
   * success screen is shown; on failure the error alert renders inline.
   *
   * @param resetPasswordForm - the template-driven form reference containing the resetPasswordEmail field
   */
  resetPassword(resetPasswordForm: NgForm): void {
    const resetPassword$ = this.userService.requestPasswordReset$(resetPasswordForm.value.resetPasswordEmail).pipe(
      map((response) => {
        console.log(response);
        resetPasswordForm.reset();
        return { dataState: DataState.LOADED, resetPasswordSuccess: true, message: response.message };
      }),
      startWith({ dataState: DataState.LOADING, resetPasswordSuccess: false }),
      catchError((error: string) => {
        return of({ dataState: DataState.ERROR, resetPasswordError: true, error });
      }),
    );
    this.resetPasswordState.set(toSignal(resetPassword$)());
  }
}
