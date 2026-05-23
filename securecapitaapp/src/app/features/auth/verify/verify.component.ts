import { ChangeDetectionStrategy, Component, inject, OnInit, Signal, signal } from '@angular/core';
import { DataState } from '../../../enumeration/datastate.enum';
import { FormsModule, NgForm } from '@angular/forms';
import { catchError, map, of, startWith, switchMap } from 'rxjs';
import { toSignal } from '@angular/core/rxjs-interop';
import { AccountType, VerifyStateInterface } from '../../../interface/appstates.interface';
import { UserInterface } from '../../../interface/user.interface';
import { ActivatedRoute, ParamMap, RouterLink } from '@angular/router';
import { CustomerService } from '../../../service/customer.service';
import { UserService } from '../../../service/user.service';

/**
 * Verification landing view for account and password reset links.
 *
 * Displays the verification result and routes the user to the next step.
 */
@Component({
  selector: 'app-verify',
  imports: [FormsModule, RouterLink],
  templateUrl: './verify.component.html',
  styleUrl: './verify.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VerifyComponent implements OnInit {
  //TODO 05/23 verify which instance of user should be used
  //@Input() user: UserInterface;
  /** Exposes the `DataState` enum to the template for asynchronous data handling. */
  readonly DataState = DataState;
  verifyState: Signal<VerifyStateInterface>;
  protected readonly activatedRoute = inject(ActivatedRoute);
  protected readonly customerService = inject(CustomerService);
  protected isLoading = signal(false);
  private readonly userService = inject(UserService);
  private userSubject = signal<UserInterface>(null);
  user = this.userSubject.asReadonly();
  private readonly ACCOUNT_KEY = 'key';
  /**
   * Wires the home state observable to the combined page/size stream.
   *
   * Uses {@code combineLatest} so that a change to either the current page or the
   * page size triggers a new request. {@code switchMap} automatically cancels any
   * in-flight request when a new emission arrives, preventing stale responses.
   */
  ngOnInit(): void {
    this.verifyState = toSignal(
      this.activatedRoute.paramMap.pipe(
        switchMap((params: ParamMap) => {
          console.log(this.activatedRoute);
          //TODO implement a better way to determine which URL we are on, instead of using window.location.href
          const type: AccountType = this.getAccountType(window.location.href);
          return this.userService.verifyAccount$(params.get(this.ACCOUNT_KEY), type).pipe(
            map((response) => {
              console.log(response);
              if (type === 'password') {
                this.userSubject.set(response.data.user);
              }
              return { type, title: 'Verified :) ', dataState: DataState.LOADED, message: response.message, verifySuccess: true };
            }),
            startWith({
              type,
              title: 'Verifying... ',
              dataState: DataState.LOADING,
              message: 'Please wait while we verify your information',
              verifySuccess: false,
            }), // emit the last cached data with a LOADING state while the request is in-flight so the template can show the spinner without losing the existing data
            catchError((error: string) =>
              of({
                title: 'Verification Failed :(',
                dataState: DataState.ERROR,
                error,
                message: error,
                verifySuccess: false,
              }),
            ),
          );
        }),
      ),
    );
  }

  /**
   * Submits the new password for the user resolved by the prior link-verification step.
   *
   * The {@code userSubject} was populated in {@link ngOnInit} when {@code verifyAccount$}
   * resolved the password reset key, so {@code userSubject.value.id} is the userID
   * that the backend's {@code PUT /user/new/password} endpoint expects. The form field
   * names ({@code newPassword}, {@code confirmPassword}) mirror the backend's
   * {@code NewPasswordForm.java} so {@code @RequestBody @Valid} binding succeeds.
   *
   * The reactive pipeline reuses {@code verifyState$} so the same LOADING / LOADED /
   * ERROR template branches render the in-flight, success, and failure states — no
   * imperative flag-flipping or separate observable needed.
   *
   * @param resetPasswordForm - Angular {@link NgForm} containing newPassword and confirmPassword
   */
  setNewPassword(resetPasswordForm: NgForm): void {
    this.isLoading.set(true);
    const newPasswordState$ = this.userService
      .setNewPassword$({
        userID: this.user().id,
        newPassword: resetPasswordForm.value.newPassword,
        confirmPassword: resetPasswordForm.value.confirmPassword,
      })
      .pipe(
        map((response) => {
          this.isLoading.set(false);
          // type: 'account' selects the template's success-card branch (check icon + login link).
          // The 'password' branch would re-render the empty form, masking the success.
          return {
            type: 'account' as AccountType,
            title: 'Password Updated :) ',
            dataState: DataState.LOADED,
            message: response.message,
            verifySuccess: true,
          };
        }),
        startWith({
          type: 'password' as AccountType,
          title: 'Saving... ',
          dataState: DataState.LOADING,
          message: 'Updating your password. Please wait...',
          verifySuccess: false,
        }), // emit a LOADING state while the request is in-flight so the template shows the spinner
        catchError((error: string) => {
          this.isLoading.set(false);
          return of({
            title: 'Password Update Failed :(',
            dataState: DataState.ERROR,
            error,
            message: error,
            verifySuccess: false,
          });
        }),
      );
    this.verifyState = toSignal(newPasswordState$);
  }

  private getAccountType(url: string): AccountType {
    return url.includes('password') ? 'password' : 'account';
  }
}
