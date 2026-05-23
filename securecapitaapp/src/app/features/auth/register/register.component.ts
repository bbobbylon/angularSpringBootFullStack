import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RegisterStateInterface } from '../../../interface/appstates.interface';
import { DataState } from '../../../enumeration/datastate.enum';
import { map, Observable, of, startWith } from 'rxjs';
import { UserService } from '../../../service/user.service';
import { FormsModule, NgForm } from '@angular/forms';
import { catchError } from 'rxjs/operators';
import { RouterLink } from '@angular/router';
import { AsyncPipe } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';

/**
 * Registration view for creating new user accounts.
 *
 * The template wires up the registration form and will
 * submit to the backend registration endpoint.
 */
@Component({
  selector: 'app-register',
  imports: [FormsModule, RouterLink, AsyncPipe],
  templateUrl: './register.component.html',
  styleUrl: './register.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegisterComponent {
  registerState = signal<RegisterStateInterface>({ dataState: DataState.LOADED });
  readonly DataState = DataState;
  protected readonly userService = inject(UserService);

  /**
   * Submits the registration form to the backend and drives the component state.
   *
   * Uses {@code startWith} to immediately enter LOADING state, maps a successful response
   * to the success screen (resetting the form in the process), and catches errors to display
   * them inline without breaking the observable chain.
   *
   * @param registerForm - the template-driven form reference containing firstName, lastName,
   *                       email, and password field values
   */
  register(registerForm: NgForm): void {
    const register$ = this.userService.register$(registerForm.value).pipe(
      map((response) => {
        console.log(response);
        registerForm.reset();
        return { dataState: DataState.LOADED, registerSuccess: true, message: response.message };
      }),
      startWith({ dataState: DataState.LOADING, registerSuccess: false }),
      catchError((error: string) => {
        return of({ dataState: DataState.ERROR, registerError: true, error });
      }),
    );
    this.registerState.set(toSignal(register$)());
  }

  /** Resets the view back to the blank registration form. */
  createAccountForm(): void {
    this.registerState.set({ dataState: DataState.LOADED, registerSuccess: false });
  }
}
