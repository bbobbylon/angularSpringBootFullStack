import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';

/**
 * Lets anything in the application open the command palette.
 *
 * <h3>Why a service rather than a direct call</h3>
 * The palette is mounted once in {@code AppComponent}, as a sibling of the {@code <router-outlet>}.
 * The navbar lives *inside* whichever feature component the outlet rendered, so the two are in
 * different branches of the component tree with no parent/child relationship — a template
 * reference or an {@code @Output} cannot bridge them.
 *
 * <p>The obvious shortcut is to dispatch a synthetic {@code Ctrl+K} keydown at {@code document},
 * since the palette already listens for one. That was rejected: it couples the navbar to a
 * keybinding rather than to an intent, it breaks silently the day the hotkey changes, and
 * synthesising trusted-looking input events to drive your own UI is the kind of thing that reads
 * as a workaround forever after. A one-line subject states the intent directly.
 *
 * <p>Deliberately a {@link Subject} and not a signal: opening is an *event*, not a state. A signal
 * would need a counter or a reset to distinguish "open again" from "still open", and the palette
 * already owns its own open/closed state.
 */
@Injectable({ providedIn: 'root' })
export class CommandPaletteService {
  private readonly openRequests = new Subject<void>();

  /** Emits each time something asks for the palette. Subscribed by the palette component. */
  readonly openRequested$: Observable<void> = this.openRequests.asObservable();

  /**
   * Requests that the palette open.
   *
   * <p>The palette still applies its own authentication gate, so this cannot surface it on the
   * public login or verification screens.
   */
  open(): void {
    this.openRequests.next();
  }
}
