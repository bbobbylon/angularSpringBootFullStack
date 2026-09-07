import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnDestroy,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { RegisterStateInterface } from '../../../interface/appstates.interface';
import { DataState } from '../../../enumeration/datastate.enum';
import { UserService } from '../../../service/user.service';
import { FormsModule, NgForm } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NotificationsService } from '../../../service/notifications-service';
import { TranslocoDirective } from '@jsverse/transloco';
import { PASSWORD_HINT, PASSWORD_MIN_LENGTH, PASSWORD_PATTERN } from '../../../constants/password-policy';
import { FieldErrorComponent } from '../../../shared/field-error/field-error.component';
import { environment } from '../../../../environments/environment';

/**
 * Minimal shape of the `window.turnstile` global the Cloudflare Turnstile script
 * (loaded in `index.html`) attaches once it finishes loading. Declared locally rather
 * than as a shared ambient type — this is the only component that touches it.
 */
interface TurnstileApi {
  render(container: HTMLElement, options: { sitekey: string; callback: (token: string) => void }): string;
  reset(widgetId: string): void;
  remove(widgetId: string): void;
}

declare global {
  interface Window {
    turnstile?: TurnstileApi;
  }
}

/**
 * Registration view for creating new user accounts.
 *
 * The template wires up the registration form and submits to the backend
 * registration endpoint. Component state is held in a writable signal
 * ({@link registerState}) so the template's `OnPush` change detection
 * picks up state transitions without re-creating subscriptions per submit.
 *
 * <p>Also hosts the Cloudflare Turnstile CAPTCHA widget (FUTURE-ENHANCEMENTS.md §3.1,
 * backend {@code TurnstileUtils}). Rendered explicitly via {@link Window.turnstile.render}
 * rather than the widget's implicit `data-sitekey` auto-render — this component can be
 * destroyed and recreated by router navigation, and explicit rendering gives a widget id
 * {@link ngOnDestroy} can clean up, which the implicit form has no handle for. The
 * container is queried via a {@link ViewChild} setter rather than {@code ngAfterViewInit}
 * specifically because the template destroys and recreates the container element when
 * "Create another" brings the form back after a successful registration — a lifecycle hook
 * only fires once per component instance, but the setter re-fires every time the queried
 * element (re)appears. Skipped entirely when {@link environment.turnstileSiteKey} is empty
 * (no Cloudflare account configured yet) or the script has not finished loading — the
 * submit button never depends on a token being present, mirroring the backend's own
 * {@code TurnstileUtils.isConfigured()} graceful-degradation gate.
 */
@Component({
  selector: 'app-register',
  imports: [FormsModule, RouterLink, TranslocoDirective, FieldErrorComponent],
  templateUrl: './register.component.html',
  styleUrl: './register.component.css',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegisterComponent implements OnDestroy {
  /** Single source of truth for the template's loading/success/error rendering. */
  registerState = signal<RegisterStateInterface>({ dataState: DataState.LOADED });
  /** Exposes {@link DataState} to the template for switch-case rendering. */
  readonly DataState = DataState;
  /** Password requirements — mirrors the backend's `PasswordPolicy` exactly; see that constant's doc. */
  protected readonly PASSWORD_MIN_LENGTH = PASSWORD_MIN_LENGTH;
  protected readonly PASSWORD_PATTERN = PASSWORD_PATTERN;
  protected readonly PASSWORD_HINT = PASSWORD_HINT;
  protected readonly userService = inject(UserService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notification = inject(NotificationsService);

  /** Public Turnstile site key — safe to expose, unlike the backend's secret key. Empty means unconfigured. */
  protected readonly turnstileSiteKey = environment.turnstileSiteKey;
  private turnstileWidgetId?: string;
  /** The most recent token the widget produced; attached to the next submit, then cleared. */
  private captchaToken?: string;

  /**
   * Fires every time the container element (re)appears in the view — including after "Create
   * another" tears down and recreates the registration form's `@if` block following a successful
   * signup — not just once like `ngAfterViewInit` would. Renders the widget unless it is
   * unconfigured (no site key) or the script from `index.html` has not attached `window.turnstile`
   * yet; both are treated as "no CAPTCHA available" rather than an error, matching the backend's
   * own graceful degradation for an unconfigured deployment. Fires with `undefined` when the
   * container is torn down, so the old widget is released before a new one can be rendered.
   */
  @ViewChild('turnstileContainer')
  private set turnstileContainer(ref: ElementRef<HTMLDivElement> | undefined) {
    this.destroyTurnstile();
    if (!ref || !this.turnstileSiteKey || !window.turnstile) {
      return;
    }
    this.turnstileWidgetId = window.turnstile.render(ref.nativeElement, {
      sitekey: this.turnstileSiteKey,
      callback: (token) => (this.captchaToken = token),
    });
  }

  /** Releases the widget's DOM/JS resources — router navigation destroys this component on every visit away. */
  ngOnDestroy(): void {
    this.destroyTurnstile();
  }

  private destroyTurnstile(): void {
    if (this.turnstileWidgetId) {
      window.turnstile?.remove(this.turnstileWidgetId);
      this.turnstileWidgetId = undefined;
    }
  }

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
    this.userService.register$(registerForm.value, this.captchaToken)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          // console.log(response);
          registerForm.reset();
          this.notification.onSuccess(response.message);
          this.registerState.set({ dataState: DataState.LOADED, registerSuccess: true, message: response.message });
        },
        error: (error: string) => {
          // A Turnstile token is single-use: whether registration failed because of the CAPTCHA
          // check or for any other reason, the consumed/stale token can never succeed on a
          // retry, so the widget must re-challenge before the next submit.
          this.resetTurnstile();
          this.notification.onError(error);
          this.registerState.set({ dataState: DataState.ERROR, registerError: true, error });
        },
      });
  }

  /** Resets the view back to the blank registration form. */
  createAccountForm(): void {
    this.registerState.set({ dataState: DataState.LOADED, registerSuccess: false });
  }

  private resetTurnstile(): void {
    this.captchaToken = undefined;
    if (this.turnstileWidgetId) {
      window.turnstile?.reset(this.turnstileWidgetId);
    }
  }
}
