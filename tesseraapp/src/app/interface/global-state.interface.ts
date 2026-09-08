import { DataState } from '../enumeration/datastate.enum';

/**
 * The generic reactive-state envelope for the app's "RxJS trio" pattern
 * (documentation/GUIDE.md §6.4): a service's `Observable` emits this shape so a component can
 * switch on {@link DataState} to drive its loading spinner / error alert / loaded view, with
 * `appData` holding the endpoint-specific payload once loaded. Several features define a more
 * specific sibling interface instead of using this one directly (e.g. `LoginStateInterface`,
 * `RegisterStateInterface` in `interface/appstates.interface.ts`) when they need named fields
 * beyond a single `appData` blob; this generic form is used where a single typed payload is
 * enough.
 */
export interface GlobalStateInterface<T> {
  dataState: DataState;
  appData?: T;
  error?: string;
}
