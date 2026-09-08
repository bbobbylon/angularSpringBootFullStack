import { EventType } from '../enumeration/event-type.enum';

/**
 * One row of a user's audit trail, mirroring the backend's `userevents` table
 * (`EventRepoImpl`/`EventRowMapper`). Returned embedded in {@link ProfileInterface#events} after
 * login and by the admin user-detail activity panel; rendered via `event-display.utils.ts`,
 * which maps {@link EventType} to an icon/i18n label pair.
 */
export interface UserEventsInterface {
  id: number;
  type: EventType;
  description: string;
  createdAt: Date;
  device: string;
  ipAddress: string;
}
