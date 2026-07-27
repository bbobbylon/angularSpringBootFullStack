import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { RegisterStateInterface } from '../../../interface/appstates.interface';
import { DataState } from '../../../enumeration/datastate.enum';
import { UserService } from '../../../service/user.service';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NotificationsService } from '../../../service/notifications-service';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Registration view for creating new user accounts.
 *
 * The template wires up the registration form and submits to the backend
 * registration endpoint. Component state is held in a writable signal
 * ({@link registerState}) so the template's `OnPush` change detection
 * picks up state transitions without re-creating subscriptions per submit.
 */
@Component({
  selector: 'app-register',
  imports: [FormsModule, RouterLink, TranslocoDirective],
  templateUrl: './register.component.html',
  styleUrl: './register.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegisterComponent {
  /** Single source of truth for the template's loading/success/error rendering. */
  registerState = signal<RegisterStateInterface>({ dataState: DataState.LOADED });
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;
  protected readonly userService = inject(UserService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notification = inject(NotificationsService);

  /**
   * Submits the registration form to the backend and drives component state.
   *
   * Synchronously sets {@link registerState} to LOADING so the spinner shows
   * on the next change-detection tick, then subscribes to the create call.
   * On success the form is reset and the success card is rendered; on failure
   * the error message is surfaced via the ERROR branch.
   *
   * {@code takeUntilDestroyed} ties the subscription to the component lifecycle
   * so an unmount mid-flight cannot leak the HTTP callback.
   *
   * @param registerForm - the template-driven form with firstName, lastName,
   *                       email, and password
   */
  register(registerForm: NgForm): void {
    this.registerState.set({ dataState: DataState.LOADING, registerSuccess: false });
    this.userService.register$(registerForm.value)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          // console.log(response);
          registerForm.reset();
          this.notification.onSuccess(response.message);
          this.registerState.set({ dataState: DataState.LOADED, registerSuccess: true, message: response.message });
        },
        error: (error: string) => {
          this.notification.onError(error);
          this.registerState.set({ dataState: DataState.ERROR, registerError: true, error });
        },
      });
  }

  /** Resets the view back to the blank registration form. */
  createAccountForm(): void {
    this.registerState.set({ dataState: DataState.LOADED, registerSuccess: false });
  }
}
