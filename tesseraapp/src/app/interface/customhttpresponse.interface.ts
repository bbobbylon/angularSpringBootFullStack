/**
 * Mirrors the backend's response envelope, {@code HttpResponse.java} — every controller returns
 * `ResponseEntity<HttpResponse>`, so every HTTP call this app's services make resolves to this
 * shape. `data` carries the endpoint-specific payload (typed more specifically per-call via `T`,
 * e.g. {@link CustomerListDataInterface}); `reason`/`devMessage` surface the backend's
 * non-enumerating error text, consumed by the global error interceptor and toast service.
 */
export interface CustomHttpResponseInterface<T> {
  statusCode: number;
  message: string;
  data?: T;
  timestamp: Date;
  reason?: string;
  devMessage?: string;
  status: string;

}
