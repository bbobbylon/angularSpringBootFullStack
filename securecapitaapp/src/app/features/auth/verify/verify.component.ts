import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { DataState } from '../../../enumeration/datastate.enum';
import { FormsModule, NgForm } from '@angular/forms';
import { catchError, map, of, startWith, switchMap } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AccountType, VerifyStateInterface } from '../../../interface/appstates.interface';
import { UserInterface } from '../../../interface/user.interface';
import { ActivatedRoute, ParamMap, RouterLink } from '@angular/router';
import { CustomerService } from '../../../service/customer.service';
import { UserService } from '../../../service/user.service';
import { NotificationsService } from '../../../service/notifications-service';

/**
 * Verification landing view for account and password reset links.
 *
 * Displays the verification result and routes the user to the next step.
 * State is held in {@link verifyState}, a writable signal that the
 * `activatedRoute.paramMap` subscription and the {@link setNewPassword}
 * event handler both feed into.
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
  /** Exposes the `DataState` enum to the template for asynchronous data handling. */
  readonly DataState = DataState;
  /**
   * Drives the template's loading/success/error rendering. Single writable signal,
   * mutated from both the route-param subscription (initial verification) and the
   * password-set event handler.
   */
  verifyState = signal<VerifyStateInterface>({
    type: 'account' as AccountType,
    title: 'Verifying... ',
    dataState: DataState.LOADING,
    message: 'Please wait while we verify your information',
    verifySuccess: false,
  });
  protected readonly activatedRoute = inject(ActivatedRoute);
  protected readonly customerService = inject(CustomerService);
  protected isLoading = signal(false);
  private readonly userService = inject(UserService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notification = inject(NotificationsService);
  private userSubject = signal<UserInterface>(null);
  user = this.userSubject.asReadonly();
  private readonly ACCOUNT_KEY = 'key';

  /**
   * Subscribes to the route's paramMap so each navigation re-runs the verification call.
   *
   * {@code switchMap} cancels any in-flight request when a new param emission arrives,
   * preventing stale responses from overwriting newer ones. The inner pipe's
   * {@code startWith} re-emits the LOADING state on each switchMap cycle, so the
   * template shows the spinner during every re-verification — not just the first.
   */
  ngOnInit(): void {
    this.activatedRoute.paramMap
      .pipe(
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
              return { type, title: 'Verified :) ', dataState: DataState.LOADED, message: response.message, verifySuccess: true } as VerifyStateInterface;
            }),
            startWith({
              type,
              title: 'Verifying... ',
              dataState: DataState.LOADING,
              message: 'Please wait while we verify your information',
              verifySuccess: false,
            } as VerifyStateInterface),
            catchError((error: string) =>
              of({
                title: 'Verification Failed :(',
                dataState: DataState.ERROR,
                error,
                message: error,
                verifySuccess: false,
              } as VerifyStateInterface),
            ),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((state) => this.verifyState.set(state));
  }

  /**
   * Submits the new password for the user resolved by the prior link-verification step.
   *
   * The {@code userSubject} was populated in {@link ngOnInit} when {@code verifyAccount$}
   * resolved the password reset key, so {@code user().id} is the userID the backend's
   * {@code PUT /user/new/password} endpoint expects. Field names mirror
   * {@code NewPasswordForm.java} so {@code @RequestBody @Valid} binding succeeds.
   *
   * Sets {@link verifyState} to LOADING synchronously, then `.set()`s LOADED or ERROR
   * from the subscribe callbacks. {@code takeUntilDestroyed} guarantees cleanup if the
   * component unmounts mid-flight.
   *
   * @param resetPasswordForm - Angular {@link NgForm} with newPassword and confirmPassword
   */
  setNewPassword(resetPasswordForm: NgForm): void {
    this.isLoading.set(true);
    this.verifyState.set({
      type: 'password' as AccountType,
      title: 'Saving... ',
      dataState: DataState.LOADING,
      message: 'Updating your password. Please wait...',
      verifySuccess: false,
    });
    this.userService
      .setNewPassword$({
        userID: this.user().id,
        newPassword: resetPasswordForm.value.newPassword,
        confirmPassword: resetPasswordForm.value.confirmPassword,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.isLoading.set(false);
          // type: 'account' selects the template's success-card branch (check icon + login link).
          // The 'password' branch would re-render the empty form, masking the success.
          this.notification.onSuccess(response.message);
          this.verifyState.set({
            type: 'account' as AccountType,
            title: 'Password Updated :) ',
            dataState: DataState.LOADED,
            message: response.message,
            verifySuccess: true,
          });
        },
        error: (error: string) => {
          this.isLoading.set(false);
          this.notification.onError(error);
          this.verifyState.set({
            title: 'Password Update Failed :(',
            dataState: DataState.ERROR,
            error,
            message: error,
            verifySuccess: false,
          });
        },
      });
  }

  private getAccountType(url: string): AccountType {
    return url.includes('password') ? 'password' : 'account';
  }
}
