/**
 * Production environment configuration.
 *
 * Swapped in at build time by angular.json `fileReplacements` when building with
 * `--configuration production`.  In a reverse-proxy deployment (nginx in front of
 * both the SPA and the Spring Boot API), an empty string makes every API call a
 * same-origin relative URL; update to the fully-qualified backend URL if the
 * frontend and backend are served from different origins.
 */
export const environment = {
  production: true,
  apiUrl: '',
};
