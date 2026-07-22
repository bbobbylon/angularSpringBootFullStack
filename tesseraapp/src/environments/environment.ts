/**
 * Development environment configuration.
 *
 * Loaded by default during `ng serve` and `ng build --configuration development`.
 * The production build swaps this file for environment.production.ts via the
 * `fileReplacements` entry in angular.json, so services import a single symbol
 * (`environment.apiUrl`) with no runtime branching needed.
 */
export const environment = {
  production: false,
  apiUrl: 'http://localhost:8080',
};
