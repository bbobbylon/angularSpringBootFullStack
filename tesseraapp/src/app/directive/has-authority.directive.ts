import { Directive, ElementRef, inject, Input, OnInit, Renderer2, TemplateRef, ViewContainerRef } from '@angular/core';
import { UserService } from '../service/user.service';

/**
 * Normalizes the several shapes an authority input may take into a flat list.
 *
 * Templates are allowed to write a single authority (`*appHasAuthority="'UPDATE:CUSTOMER'"`), an
 * array (`*appHasAuthority="['UPDATE:USER', 'UPDATE:CUSTOMER']"`), or a comma-separated string
 * (`*appHasAuthority="'UPDATE:USER, UPDATE:CUSTOMER'"`) — the last of which matches how the backend
 * itself stores a role's permissions in the `roles.permission` column, so a developer copying a
 * string out of `schema.sql` gets the behavior they expect rather than a silent never-match.
 *
 * @param value - the raw binding value
 * @returns the authorities as trimmed, non-empty strings
 */
function toAuthorities(value: string | string[] | null | undefined): string[] {
  if (!value) return [];
  const parts = Array.isArray(value) ? value : value.split(',');
  return parts.map((part) => part.trim()).filter((part) => part.length > 0);
}

/**
 * Structural directive that renders its content only when the signed-in user holds at least one
 * of the given authorities (ROADMAP §2 — capability-level RBAC gating).
 *
 * <h3>The gap this closes</h3>
 * {@link adminGuard} gates whole *routes*, which is the right control for a page that would be
 * entirely useless without the authority. It says nothing about the inside of a page a user is
 * legitimately allowed to open. A `ROLE_USER` account holds `READ:CUSTOMER` but not
 * `UPDATE:CUSTOMER`, so it can open a customer's detail page — and then sees an editable form and
 * a **Save** button that will only ever answer 403. The failure is not a security one (the backend
 * refuses correctly); it is that the refusal arrives *after* the user has typed a change they now
 * lose, at a moment when the system looks broken rather than restricted.
 *
 * <h3>Usage</h3>
 * ```html
 * <button *appHasAuthority="'UPDATE:CUSTOMER'" type="submit">Save</button>
 *
 * <!-- with a read-only substitute rather than a hole in the layout -->
 * <button *appHasAuthority="'UPDATE:CUSTOMER'; else readOnlyNotice" type="submit">Save</button>
 * <ng-template #readOnlyNotice>
 *   <p class="text-muted">Read-only — contact your administrator to request edit access.</p>
 * </ng-template>
 * ```
 *
 * <h3>Hide, or disable?</h3>
 * Removing a control is right when its presence is pure noise (a **Delete** button a viewer can
 * never use). It is wrong when absence would be *confusing* — a form whose submit button simply
 * isn't there reads as a rendering bug. For that case use {@link RequiresAuthorityDirective},
 * which leaves the control visible but inert and explains itself on hover. Choosing between them
 * is a per-control judgment, which is why this file ships both rather than picking one globally.
 *
 * <h3>Not a security boundary (NFR-SEC-4)</h3>
 * The authorities are read from the `authorities` claim of the access token in `localStorage`, a
 * value the user physically controls. Editing it changes what this directive renders and nothing
 * else: the backend re-derives authorities from the database on every request and enforces them at
 * both the URL level (`SecurityConfig`) and the method level (`@PreAuthorize`). This is a
 * usability aid, and treating it as anything more would be the actual vulnerability.
 *
 * <h3>Non-enumerating</h3>
 * The directive reveals only what *this* user may do. It never renders a count, a name, or any
 * hint about other accounts, other organizations, or records that exist but are out of scope —
 * consistent with the app-wide rule that the UI must not become an enumeration channel.
 *
 * <h3>Evaluation timing</h3>
 * The check runs when the binding is first set and again whenever it changes, not continuously.
 * That matches the token's own lifecycle: authorities are fixed for the life of an access token,
 * and the two events that can change them — signing in as someone else, or an administrator
 * reassigning a role — both replace the rendered view anyway (a fresh navigation, or a re-login).
 * A polling or signal-driven check would add reactivity to a value that does not move.
 */
@Directive({
  selector: '[appHasAuthority]',
  standalone: true,
})
export class HasAuthorityDirective {
  private readonly templateRef: TemplateRef<unknown> = inject(TemplateRef);
  private readonly viewContainer = inject(ViewContainerRef);
  private readonly userService = inject(UserService);

  private authorities: string[] = [];
  private elseTemplate: TemplateRef<unknown> | null = null;
  /** Guards against tearing down and rebuilding an identical view on every change detection. */
  private rendered: 'main' | 'else' | 'none' = 'none';

  /**
   * The authority (or authorities) that gate the content. Any one of them is enough — this is an
   * OR, mirroring the backend's `hasAnyAuthority(...)` rather than inventing a stricter rule the
   * server would not agree with.
   */
  @Input()
  set appHasAuthority(value: string | string[] | null | undefined) {
    this.authorities = toAuthorities(value);
    this.render();
  }

  /**
   * Optional fallback template shown when the user lacks every listed authority — the
   * `*appHasAuthority="'X'; else someTemplate"` form. Angular derives this setter name by
   * concatenating the selector with the microsyntax keyword, which is why it must be
   * `appHasAuthorityElse` exactly.
   */
  @Input()
  set appHasAuthorityElse(template: TemplateRef<unknown> | null) {
    this.elseTemplate = template;
    this.render();
  }

  /**
   * Renders the main template, the else template, or nothing, according to the current authorities.
   *
   * Short-circuits when the correct view is already on screen so that re-binding does not destroy
   * and recreate DOM — which would reset focus and any in-progress form state inside the gated
   * block.
   */
  private render(): void {
    const permitted = this.authorities.length === 0 || this.userService.hasAnyAuthority(...this.authorities);
    const target: 'main' | 'else' | 'none' = permitted ? 'main' : this.elseTemplate ? 'else' : 'none';
    if (target === this.rendered) return;

    this.viewContainer.clear();
    if (target === 'main') {
      this.viewContainer.createEmbeddedView(this.templateRef);
    } else if (target === 'else' && this.elseTemplate) {
      this.viewContainer.createEmbeddedView(this.elseTemplate);
    }
    this.rendered = target;
  }
}

/**
 * Attribute directive that leaves a control visible but inert when the user lacks the authority to
 * use it — the "disable, don't hide" half of capability-level gating.
 *
 * <h3>Why a disabled control is sometimes better than a missing one</h3>
 * Hiding teaches the user nothing. A greyed-out **Save** with a tooltip reading "You don't have
 * permission to update customers — contact your administrator" tells them the feature exists, that
 * their account is the reason it is unavailable, and precisely what to ask for. That is the
 * difference between a product that feels restricted and one that feels broken. Hiding remains the
 * better choice for destructive or administrative actions where the mere advertisement of the
 * capability is clutter — see {@link HasAuthorityDirective}.
 *
 * <h3>Usage</h3>
 * ```html
 * <button [appRequiresAuthority]="'UPDATE:CUSTOMER'" deniedAction="update customers" type="submit">
 *   Save
 * </button>
 * ```
 *
 * <h3>What it actually does</h3>
 * Three things, because no single one is sufficient on its own:
 * - sets the `disabled` **property** on elements that have one (`button`, `input`, `select`,
 *   `textarea`), which is what actually stops the click;
 * - sets `aria-disabled="true"` and, for elements without a `disabled` property (an `<a>` styled
 *   as a button, for instance), removes it from the tab order — otherwise a keyboard user reaches
 *   a control a mouse user cannot, which is the classic way "disabled" states fail accessibility;
 * - adds the `is-restricted` class and a `title`, so the state is visible and its *reason* is
 *   discoverable rather than mysterious.
 *
 * <h3>Interaction with other disabled states</h3>
 * This directive only ever *adds* the disabled state; it never clears one. A submit button already
 * disabled while a request is in flight stays disabled for that reason too — the two conditions
 * are independent, and a directive that owned the property outright would fight the component for
 * it. When the user does hold the authority, this directive touches nothing at all.
 *
 * Same NFR-SEC-4 caveat as {@link HasAuthorityDirective}: cosmetic only, the server is the boundary.
 */
@Directive({
  selector: '[appRequiresAuthority]',
  standalone: true,
})
export class RequiresAuthorityDirective implements OnInit {
  private readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly renderer = inject(Renderer2);
  private readonly userService = inject(UserService);

  /** The authority (or authorities) required to use this control; any one suffices. */
  @Input({ required: true }) appRequiresAuthority: string | string[] = [];

  /**
   * The capability named in the tooltip, phrased as a verb phrase to slot into the sentence
   * "You don't have permission to ___ — contact your administrator." Uses the same wording
   * convention as the route guard's `data.deniedAction`, so a user who meets the restriction at
   * the route level and at the control level is told the same thing twice rather than two
   * different things once.
   */
  @Input() deniedAction = 'perform this action';

  /**
   * Applies the restricted state once, on init.
   *
   * Deliberately not re-evaluated on every change detection: the authorities behind it are fixed
   * for the life of an access token, and a control that flickers between usable and not would be
   * worse than either state.
   */
  ngOnInit(): void {
    const authorities = toAuthorities(this.appRequiresAuthority);
    if (authorities.length === 0 || this.userService.hasAnyAuthority(...authorities)) return;

    const element = this.elementRef.nativeElement;
    const message = `You don't have permission to ${this.deniedAction} — contact your administrator.`;

    if ('disabled' in element) {
      this.renderer.setProperty(element, 'disabled', true);
    } else {
      // No disabled property to set (an anchor, a div acting as a control): take it out of the
      // tab order so keyboard users are restricted exactly as far as pointer users are.
      this.renderer.setAttribute(element, 'tabindex', '-1');
    }
    this.renderer.setAttribute(element, 'aria-disabled', 'true');
    this.renderer.setAttribute(element, 'title', message);
    this.renderer.addClass(element, 'is-restricted');
  }
}
