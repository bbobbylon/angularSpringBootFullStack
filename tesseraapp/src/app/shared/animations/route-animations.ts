import { animate, query, style, transition, trigger } from '@angular/animations';

/**
 * Global route-change transition for the standalone router outlet.
 *
 * <p>Bound as {@code [@routeTransition]} on the element that wraps the app's single
 * {@code <router-outlet>} (see {@code app.component.html}). The bound value is the
 * activated route's config path (supplied by {@code AppComponent#getRouteAnimationData});
 * because the {@code '* <=> *'} transition fires whenever that value changes, every
 * navigation to a *different* route animates, while re-activating the same route does not.
 *
 * <p>The leaving view fades and settles back slightly while the entering view rises up
 * and scales in from just below full size — a "zoom+slide" combination that reads as
 * more alive than a flat cross-fade while staying quick enough not to feel sluggish.
 * Both views are pinned {@code position: absolute} for the duration so they overlap
 * without the page height jumping. Durations/easing mirror the {@code --dur}/
 * {@code --dur-slow}/{@code --ease-out} design tokens in {@code styles.css} (the
 * animation DSL needs literal ms strings, so the values are copied rather than
 * referenced). Users who prefer reduced motion never see this: the parent element
 * carries {@code [@.disabled]}, which {@code AppComponent} sets from the
 * {@code prefers-reduced-motion} media query.
 */
export const routeTransition = trigger('routeTransition', [
  transition('* <=> *', [
    // Anchor the container so the two overlapping views position against it.
    style({ position: 'relative' }),
    // Stack both the entering and leaving views on top of each other.
    query(
      ':enter, :leave',
      [style({ position: 'absolute', top: 0, left: 0, right: 0 })],
      { optional: true },
    ),
    // Entering view starts hidden, slightly below its resting position, and a touch smaller.
    query(
      ':enter',
      [style({ opacity: 0, transform: 'translateY(16px) scale(0.98)' })],
      { optional: true },
    ),
    // Fade the old view out while it settles back a hair, reinforcing the entering view's "lift".
    query(
      ':leave',
      [animate('140ms ease', style({ opacity: 0, transform: 'scale(0.99)' }))],
      { optional: true },
    ),
    // ...then ease the new view up and scale it to full size.
    query(
      ':enter',
      [
        animate(
          '320ms cubic-bezier(0.16, 1, 0.3, 1)',
          style({ opacity: 1, transform: 'translateY(0) scale(1)' }),
        ),
      ],
      { optional: true },
    ),
  ]),
]);
