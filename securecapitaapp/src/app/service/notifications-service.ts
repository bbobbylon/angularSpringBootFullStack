import { inject, Injectable } from '@angular/core';
import { ToastrService } from 'ngx-toastr';

/**
 * Application-wide notification façade over {@link ToastrService}.
 *
 * All components inject this service rather than ToastrService directly so that
 * the toast library can be swapped out in one place without touching every callsite.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly toastr = inject(ToastrService);

  onSuccess(message: string): void {
    this.toastr.success(message);
  }

  onError(message: string): void {
    this.toastr.error(message);
  }

  onInfo(message: string): void {
    this.toastr.info(message);
  }

  onWarning(message: string): void {
    this.toastr.warning(message);
  }
}
