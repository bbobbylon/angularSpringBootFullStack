import { InvoiceInterface } from './invoice.interface';

export interface CustomerInterface {
  id: number;
  name: string;
  email: string;
  address: string;
  type: string;
  status: string;
  imageUrl: string;
  phoneNumber: string;
  createdAt: Date;
  invoices?: InvoiceInterface[];
}
