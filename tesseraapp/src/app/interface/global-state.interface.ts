import { DataState } from '../enumeration/datastate.enum';

// this will define the general state of the application
export interface GlobalStateInterface<T> {
  dataState: DataState;
  appData?: T;
  error?: string;
}
