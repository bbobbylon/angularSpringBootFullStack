export interface InvoiceInterface {
  id: number;
  invoiceNumber: string;
  services: string;
  status: string;
  total: number;
  createdAt: Date;
}
