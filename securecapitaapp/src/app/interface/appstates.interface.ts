import { DataState } from '../enumeration/datastate.enum';
import { UserInterface } from './user.interface';
import { UserEventsInterface } from './user-events.interface';
import { RolesInterface } from './roles.interface';
import { CustomerInterface } from './customer.interface';
import { StatsInterface } from './stats.interface';

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

// Spring Boot 3.3+ serializes Page<T> with pagination metadata nested under a "page" sub-object
export interface PageInterface {
  content: CustomerInterface[];
  page: {
    size: number;
    number: number;
    totalElements: number;
    totalPages: number;
  };
}

export interface CustomerListData {
  user: UserInterface;
  page?: PageInterface;
  stats?: StatsInterface;
  statsData?: StatsData;
}

export interface StatsData {
  user: UserInterface;
  stats: StatsInterface;
}
