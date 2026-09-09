import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { AuthService } from './auth.service';
import { BackendUrlService } from './backend-url.service';

/**
 * Attaches the session token, and only to this app's own backend.
 *
 * <p>Two things were wrong here.</p>
 *
 * <p><strong>The token leaked to third parties.</strong> The interceptor added
 * {@code Authorization: Bearer ...} to every outgoing request except
 * {@code /api/auth/}. This app calls two public services directly from the
 * browser - Open-Meteo for the hourly forecast and Nominatim for reverse
 * geocoding - so every route analysis and every "Use My Location" tap sent the
 * user's signed session token to servers that have no business holding it, and
 * that log request headers. The check is now on the request's origin, matched
 * against the configured backend origins.</p>
 *
 * <p><strong>Nothing noticed an expired session.</strong> There was no error
 * handling at all, anywhere in the app. Once a token expired, every backend call
 * failed with 401 while the header still showed the user's name and the route
 * guard still let them into /history and /profile, because the guard only checks
 * that a token <em>string</em> exists. The session is now cleared on the first
 * 401 from the backend and the user is sent to the login page with a reason and
 * a return URL.</p>
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const authService = inject(AuthService);
  const backendUrl = inject(BackendUrlService);
  const router = inject(Router);

  const isBackendRequest = backendUrl.isBackendUrl(request.url);
  const isAuthEndpoint = isBackendRequest && request.url.includes('/api/auth/');
  const token = authService.getToken();

  const outgoing =
    token && isBackendRequest && !isAuthEndpoint
      ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : request;

  return next(outgoing).pipe(
    catchError((error: unknown) => {
      // A 401 from /api/auth/login means "wrong password", not "session over",
      // so signing the user out there would be nonsense. A 401 from anywhere
      // else on our backend means the token is gone, expired or no longer valid.
      const isDeadSession =
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        isBackendRequest &&
        !isAuthEndpoint;

      if (isDeadSession && authService.isLoggedIn()) {
        authService.logout(true);

        const returnUrl = router.url.startsWith('/login') ? '/analysis' : router.url;
        void router.navigate(['/login'], {
          queryParams: { reason: 'session-expired', returnUrl }
        });
      }

      return throwError(() => error);
    })
  );
};
