import { Component, inject } from '@angular/core';
import { ChildrenOutletContexts, RouterOutlet } from '@angular/router';
import { routeTransition } from './shared/animations/route-animations';
import { CommandPaletteComponent } from './shared/command-palette/command-palette.component';
import { FooterComponent } from './shared/footer/footer.component';

/**
 * Root shell for the standalone Angular application.
 *
 * <p>Hosts the single router outlet that every feature view renders into, and the always-on
 * chrome pieces that must survive across route changes:
 * <ul>
 *   <li>the {@link routeTransition} animation, bound around the outlet so navigations
 *       cross-fade instead of snapping;</li>
 *   <li>the global {@link CommandPaletteComponent} (⌘/Ctrl+K), which self-gates on
 *       authentication so it is inert on the public auth screens; and</li>
 *   <li>the global {@link FooterComponent}, rendered on every screen — authenticated or
 *       not — via the sticky-footer flex layout in {@code app.component.css}.</li>
 * </ul>
 *
 * <p>Because there is no persistent navbar in this shell — each feature component renders
 * its own {@code <app-navbar>} — {@code AppComponent} is the only correct home for
 * app-wide behaviour like these. Change detection is left at the default strategy so
 * the router-driven {@code getRouteAnimationData()} binding re-evaluates on every
 * navigation without extra plumbing.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, CommandPaletteComponent, FooterComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
  standalone: true,
  animations: [routeTransition],
})
export class AppComponent {
  /** Router outlet registry, used to read the activated route for the animation key. */
  private readonly contexts = inject(ChildrenOutletContexts);

  /**
   * Whether the user has asked the OS for reduced motion. Bound to {@code [@.disabled]}
   * on the animation host so the route cross-fade is skipped entirely for these users.
   * Evaluated once at construction — the preference does not change mid-session in
   * practice, and re-querying per navigation would add no value.
   */
  protected readonly prefersReducedMotion =
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /**
   * Supplies the value the {@link routeTransition} trigger keys off. Returning the
   * activated route's config path means the {@code '* <=> *'} transition fires on every
   * move to a *different* route (and stays quiet when the same route re-activates).
   *
   * @return the current primary-outlet route path, or an empty string for the index route
   */
  protected getRouteAnimationData(): string {
    return this.contexts.getContext('primary')?.route?.snapshot?.routeConfig?.path ?? '';
  }
}
