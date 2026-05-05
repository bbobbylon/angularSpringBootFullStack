import { DataState } from '../enumeration/datastate.enum';
import { User } from './user.interface';

// Define the possible states of data fetching
export interface LoginState {
  dataState: DataState;
  loginSuccess?: boolean;
  error?: string;
  message?: string;
  isUsingMfa?: boolean;
  phone?: string;
}
export interface Profile {
  user?: User;
  access_token: string;
  refresh_token: string;
}
