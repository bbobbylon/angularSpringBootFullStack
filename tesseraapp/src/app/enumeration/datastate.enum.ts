/**
 * The three states of any async operation rendered by this app's "RxJS trio" pattern
 * (see documentation/GUIDE.md §6.4): a service exposes an `Observable` whose emitted value
 * always carries one of these, so a component's template can switch on `dataState` alone rather
 * than juggling separate loading/error booleans. Embedded in every `*StateInterface`
 * (`interface/appstates.interface.ts`) and in {@link GlobalStateInterface}.
 */
export enum DataState {
  LOADING = 'LOADING_STATE',
  LOADED = 'LOADED_STATE',
  ERROR = 'ERROR_STATE',
}
