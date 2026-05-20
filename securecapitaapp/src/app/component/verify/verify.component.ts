import { Component } from '@angular/core';
import { DataState } from '../../enumeration/datastate.enum';
import { FormsModule, NgForm } from '@angular/forms';
import { Subject } from 'rxjs';
import { RegisterStateInterface } from '../../interface/appstates.interface';

/**
 * Verification landing view for account and password reset links.
 *
 * Displays the verification result and routes the user to the next step.
 */
@Component({
  selector: 'app-verify',
  imports: [FormsModule],
  templateUrl: './verify.component.html',
  styleUrl: './verify.component.css',
  standalone: true,
})
export class VerifyComponent {
  protected readonly DataState = DataState;
  protected readonly isLoadingSubject: Subject<boolean> = new Subject<boolean>();
  protected isLoading$ = this.isLoadingSubject.asObservable();
  protected verifyState$: Subject<RegisterStateInterface> = new Subject<RegisterStateInterface>();
  protected verificationMessage = '';
  private _resetPasswordForm: NgForm;

  protected renewPassword(resetPasswordForm: NgForm) {
    this._resetPasswordForm = resetPasswordForm;
    this.isLoadingSubject.next(true);
  }
}
