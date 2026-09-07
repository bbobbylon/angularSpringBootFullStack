import { Component, input } from '@angular/core';
import { NgModel } from '@angular/forms';
import { TranslocoDirective } from '@jsverse/transloco';

/** Validators this component knows how to explain, checked in this priority order. */
const KNOWN_ERRORS = [
  'required',
  'email',
  'pattern',
  'minlength',
  'maxlength',
  'min',
  'max',
  'url',
] as const;
type KnownError = (typeof KNOWN_ERRORS)[number];

/**
 * The one-line "why this field is invalid" message that FUTURE-ENHANCEMENTS.md §3.7 found
 * missing from every form in the app — a repo-wide check turned up zero uses of Bootstrap's
 * {@code is-invalid}/{@code invalid-feedback} classes, so a failed validator only ever disabled
 * the submit button with no indication of which field was wrong or why.
 *
 * <h3>Usage</h3>
 * ```html
 * <input #email="ngModel" name="email" ngModel required type="email" class="form-control" />
 * <app-field-error [control]="email" />
 * ```
 * That is the whole integration: a template reference on the {@code ngModel} directive, and this
 * component reading it. The red border on the input itself needs no wiring at all — Angular
 * already stamps {@code ng-invalid}/{@code ng-touched}/{@code ng-dirty} onto every
 * {@code ngModel}-bound control, and {@code styles.css} styles {@code .form-control.ng-invalid}
 * (touched or dirty) directly off those, the way the audit's "driven off NgForm's existing
 * per-control validity" sketch described. This component only supplies the half that CSS cannot:
 * naming *which* validator failed, in the user's language.
 *
 * <h3>Why a message is not shown before the user has done anything</h3>
 * A brand-new, empty, {@code required} field is technically invalid the instant the form opens —
 * showing the message immediately would greet the user with a wall of red before they have typed
 * a single character. Visibility is therefore gated on the control being {@code touched}
 * (blurred at least once) or {@code dirty} (edited at least once), matching the same condition
 * the CSS uses for the border, so the message and the highlighted field always appear together.
 *
 * <h3>Why this is Default change detection, not OnPush</h3>
 * Every other component in this app is {@code OnPush} by convention, but this one deliberately is
 * not. The {@link NgModel} instance passed in via {@link control} is a stable object reference for
 * the field's entire lifetime — only its *internal* state (`invalid`, `touched`, `dirty`,
 * `errors`) mutates as the user types or blurs, and an unchanged `@Input` reference gives an
 * `OnPush` view nothing to react to. The sibling `<input>` element lives in the *parent* form
 * component's own template, so the DOM event that mutates the control fires there, not inside
 * this component — marking the parent dirty, not this one. Subscribing to the control's
 * `statusChanges`/`events` to bridge that gap would also miss the plain "just blurred, value
 * unchanged" transition, since blur alone does not change validity status. Default change
 * detection sidesteps all of it: whenever the parent form's view is refreshed (which happens on
 * every keystroke and blur, because that is where the event lives), this component is refreshed
 * right alongside it, unconditionally — the same mechanism that already lets a plain
 * `[disabled]="registerForm.invalid"` binding on the parent stay correct with no extra plumbing.
 *
 * <h3>Only one message at a time</h3>
 * A field can fail several validators at once (e.g. both `required` and `pattern` on a cleared
 * password field cannot happen, but `minlength` and `pattern` can). Showing all of them stacks
 * unrelated red text under one input; {@link KNOWN_ERRORS} is the priority order used to pick a
 * single most-useful one, most-fundamental first.
 */
@Component({
  selector: 'app-field-error',
  standalone: true,
  imports: [TranslocoDirective],
  templateUrl: './field-error.component.html',
})
export class FieldErrorComponent {
  /** The template-driven form control to report on, e.g. `[control]="email"` for `#email="ngModel"`. */
  readonly control = input<NgModel | null>(null);

  /** Whether a message should be shown at all — invalid, and the user has interacted with it. */
  protected get visible(): boolean {
    const c = this.control();
    return !!c && c.invalid === true && (c.touched === true || c.dirty === true);
  }

  /** The i18n key for the single error being reported, or `null` if nothing qualifies. */
  protected get errorKey(): string | null {
    const error = this.firstError();
    return error ? `validation.${error}` : null;
  }

  /** Interpolation params for {@link errorKey} — e.g. `requiredLength` for `minlength`/`maxlength`. */
  protected get errorParams(): Record<string, unknown> {
    const error = this.firstError();
    const detail = error ? this.control()?.errors?.[error] : null;
    if (!detail || typeof detail !== 'object') return {};
    switch (error) {
      case 'minlength':
      case 'maxlength':
        return { requiredLength: detail.requiredLength };
      case 'min':
        return { min: detail.min };
      case 'max':
        return { max: detail.max };
      default:
        return {};
    }
  }

  private firstError(): KnownError | null {
    const errors = this.control()?.errors;
    if (!errors) return null;
    return KNOWN_ERRORS.find((key) => key in errors) ?? null;
  }
}
