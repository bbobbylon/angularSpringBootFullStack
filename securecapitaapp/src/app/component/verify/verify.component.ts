import { Component, inject, Input, OnInit } from '@angular/core';
import { DataState } from '../../enumeration/datastate.enum';
import { FormsModule, NgForm } from '@angular/forms';
import { BehaviorSubject, catchError, map, Observable, of, startWith, switchMap } from 'rxjs';
import { AccountType, VerifyStateInterface } from '../../interface/appstates.interface';
import { UserInterface } from '../../interface/user.interface';
import { ActivatedRoute, ParamMap, RouterLink } from '@angular/router';
import { CustomerService } from '../../service/customer.service';
import { UserService } from '../../service/user.service';
import { AsyncPipe } from '@angular/common';

/**
 * Verification landing view for account and password reset links.
 *
 * Displays the verification result and routes the user to the next step.
 */
@Component({
  selector: 'app-verify',
  imports: [FormsModule, AsyncPipe, RouterLink],
  templateUrl: './verify.component.html',
  styleUrl: './verify.component.css',
  standalone: true,
})
export class VerifyComponent implements OnInit {
  @Input() user: UserInterface;
  /** Exposes the `DataState` enum to the template for asynchronous data handling. */
  readonly DataState = DataState;
  verifyState$: Observable<VerifyStateInterface>;
  protected readonly activatedRoute = inject(ActivatedRoute);
  protected readonly customerService = inject(CustomerService);
  private readonly userService = inject(UserService);
  private userSubject = new BehaviorSubject<UserInterface>(null);
  user$ = this.userSubject.asObservable();
  private isLoadingSubject = new BehaviorSubject<boolean>(false);
  protected isLoading$ = this.isLoadingSubject.asObservable();
  private _resetPasswordForm: NgForm;
  private readonly ACCOUNT_KEY = 'key';
  /**
   * Wires the home state observable to the combined page/size stream.
   *
   * Uses {@code combineLatest} so that a change to either the current page or the
   * page size triggers a new request. {@code switchMap} automatically cancels any
   * in-flight request when a new emission arrives, preventing stale responses.
   */
  ngOnInit(): void {
    this.verifyState$ = this.activatedRoute.paramMap.pipe(
      switchMap((params: ParamMap) => {
        console.log(this.activatedRoute);
        //TODO implement a better way to determine which URL we are on, instead of using window.location.href
        const type: AccountType = this.getAccountType(window.location.href);
        return this.userService.verifyAccount$(params.get(this.ACCOUNT_KEY), type).pipe(
          map((response) => {
            console.log(response);
            if (type === 'password') {
              this.userSubject.next(response.data.user);
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
              title: error,
              dataState: DataState.ERROR,
              error,
              message: error,
              verifySuccess: false,
            }),
          ),
        );
      }),
    );
  }

  protected renewPassword(resetPasswordForm: NgForm) {
    this._resetPasswordForm = resetPasswordForm;
    this.isLoadingSubject.next(true);
  }

  private getAccountType(url: string): AccountType {
    return url.includes('password') ? 'password' : 'account';
  }
}
