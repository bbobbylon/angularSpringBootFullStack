export interface CustomHttpResponseInterface<T> {
  statusCode: number;
  message: string;
  data?: T;
  timestamp: Date;
  reason?: string;
  devMessage?: string;
  status: string;

}
