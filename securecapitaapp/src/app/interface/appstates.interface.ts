import { DataState } from '../enumeration/datastate.enum';
import { UserInterface } from './user.interface';
import { UserEventsInterface } from './user-events.interface';
import { RolesInterface } from './roles.interface';

// Define the possible states of data fetching
export interface LoginStateInterface {
  dataState: DataState;
  loginSuccess?: boolean;
  error?: string;
  message?: string;
  isUsingMfa?: boolean;
  phone?: string;
}
export interface ProfileInterface {
  user?: UserInterface;
  access_token: string;
  refresh_token: string;
  events?: UserEventsInterface[];
  roles?: RolesInterface[];
}
