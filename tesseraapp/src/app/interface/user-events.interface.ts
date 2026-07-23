import { EventType } from '../enumeration/event-type.enum';

export interface UserEventsInterface {
  id: number;
  type: EventType;
  description: string;
  createdAt: Date;
  device: string;
  ipAddress: string;
}
