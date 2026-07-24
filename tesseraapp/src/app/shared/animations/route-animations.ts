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
 * <p>The effect is intentionally restrained — a brief cross-fade where the leaving view
 * fades out first and the entering view fades up from a small offset. Both views are
 * pinned {@code position: absolute} for the duration so they overlap without the page
 * height jumping. Durations/easing mirror the {@code --dur}/{@code --ease-out} design
 * tokens in {@code styles.css}. Users who prefer reduced motion never see this: the
 * parent element carries {@code [@.disabled]}, which {@code AppComponent} sets from the
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
    // Entering view starts hidden and slightly below its resting position.
    query(':enter', [style({ opacity: 0, transform: 'translateY(10px)' })], { optional: true }),
    // Fade the old view out quickly...
    query(':leave', [animate('110ms ease', style({ opacity: 0 }))], { optional: true }),
    // ...then ease the new view up into place.
    query(
      ':enter',
      [animate('220ms cubic-bezier(0.16, 1, 0.3, 1)', style({ opacity: 1, transform: 'translateY(0)' }))],
      { optional: true },
    ),
  ]),
]);
