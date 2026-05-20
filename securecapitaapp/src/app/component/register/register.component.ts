import { Component, inject } from '@angular/core';
import { RegisterStateInterface } from '../../interface/appstates.interface';
import { DataState } from '../../enumeration/datastate.enum';
import { map, Observable, of, startWith } from 'rxjs';
import { UserService } from '../../service/user.service';
import { FormsModule, NgForm } from '@angular/forms';
import { catchError } from 'rxjs/operators';
import { RouterLink } from '@angular/router';
import { AsyncPipe } from '@angular/common';

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
})
export class RegisterComponent {
  registerState$: Observable<RegisterStateInterface> = of({ dataState: DataState.LOADED });
  readonly DataState = DataState;
  protected readonly userService = inject(UserService);

  register(registerForm: NgForm): void {
    this.registerState$ = this.userService.register$(registerForm.value).pipe(
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
  }

  /** Resets the view back to the blank registration form. */
  createAccountForm(): void {
    this.registerState$ = of({ dataState: DataState.LOADED, registerSuccess: false });
  }
}
