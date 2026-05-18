import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { IMAGE_CONFIG } from '@angular/common';
import { provideRouter } from '@angular/router';

import { routes } from './app.routing-module';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { tokenInterceptor } from './interceptor/token.interceptor';
import { cacheInterceptor } from './interceptor/cache.interceptor';

/**
 * Root application configuration for the Angular standalone app.
 *
 * In Angular 15+ standalone apps there is no AppModule — this object replaces it.
 * It is passed to bootstrapApplication() in main.ts and tells Angular which
 * global services and features to make available across the entire application.
 * Every provider registered here is singleton-scoped for the lifetime of the app.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    /**
     * Catches unhandled JavaScript errors that occur outside Angular's change
     * detection (e.g. in setTimeout, Promise rejections, or native browser events)
     * and routes them through Angular's ErrorHandler so they appear in the console
     * and can be intercepted by a custom error handler later.
     */
    provideBrowserGlobalErrorListeners(),

    /**
     * Registers the Angular Router and wires it to the route definitions in
     * app.routing-module.ts. Without this, [routerLink], router.navigate(), and
     * route guards would not function. The routes array maps URL paths to the
     * components that should render at those paths (e.g. /profile → ProfileComponent).
     */
    provideRouter(routes),

    /**
     * Registers Angular's HttpClient so it can be injected anywhere in the app
     * (services, components, etc.) to make HTTP requests to the backend.
     *
     * withInterceptors([cacheInterceptor, tokenInterceptor]) plugs both interceptors
     * into the HTTP pipeline in order. cacheInterceptor runs first — a cache hit
     * returns immediately without ever invoking tokenInterceptor, so no Authorization
     * header is attached to a request that never leaves the browser. On a cache miss,
     * the request flows through to tokenInterceptor, which attaches the JWT header
     * before forwarding to the server.
     */
    provideHttpClient(withInterceptors([cacheInterceptor, tokenInterceptor])),

    { provide: IMAGE_CONFIG, useValue: { disableImageSizeWarning: true } },
  ],
};
