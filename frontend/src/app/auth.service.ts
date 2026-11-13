import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, Subscription, from, switchMap, tap } from 'rxjs';

import { BackendUrlService } from './backend-url.service';

export interface UserProfile {
  id: number;
  fullName: string;
  email: string;
  phone: string | null;
  address: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface SignupPayload {
  fullName: string;
  email: string;
  phone: string | null;
  address: string | null;
  password: string;
}

export interface LoginPayload {
  email: string;
  password: string;
}

export interface UpdateProfilePayload {
  fullName: string;
  email: string;
  phone: string | null;
  address: string | null;
}

export interface AuthResponse {
  token: string;
  user: UserProfile;
}

const TOKEN_KEY = 'road_assistant_token';
const USER_KEY = 'road_assistant_user';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly backendUrl = inject(BackendUrlService);
  private readonly token = signal<string | null>(this.readToken());

  readonly user = signal<UserProfile | null>(this.readUser());

  signup(payload: SignupPayload): Observable<AuthResponse> {
    return from(this.backendUrl.ensureConnected()).pipe(
      switchMap(() => this.http.post<AuthResponse>(this.backendUrl.apiUrl('/auth/signup'), payload)),
      tap((response) => this.storeSession(response))
    );
  }

  login(payload: LoginPayload): Observable<AuthResponse> {
    return from(this.backendUrl.ensureConnected()).pipe(
      switchMap(() => this.http.post<AuthResponse>(this.backendUrl.apiUrl('/auth/login'), payload)),
      tap((response) => this.storeSession(response))
    );
  }

  getCurrentUser(): Observable<UserProfile> {
    return from(this.backendUrl.ensureConnected()).pipe(
      switchMap(() => this.http.get<UserProfile>(this.backendUrl.apiUrl('/users/me'))),
      tap((user) => this.storeUser(user))
    );
  }

  /**
   * Saves the profile and adopts the token that comes back with it.
   *
   * <p>{@code PUT /api/users/me} answers with the same shape as login -
   * {@code {token, user}} - because changing the email address invalidates the
   * claims in the token the caller is holding. This method used to be typed as
   * returning a bare {@code UserProfile} and called {@code storeUser}, keeping
   * the stale token, so the header kept showing the previous name until the
   * next full sign-in.</p>
   */
  updateProfile(payload: UpdateProfilePayload): Observable<AuthResponse> {
    return from(this.backendUrl.ensureConnected()).pipe(
      switchMap(() => this.http.put<AuthResponse>(this.backendUrl.apiUrl('/users/me'), payload)),
      tap((response) => this.storeSession(response))
    );
  }

  /** Downloads everything the app stores about the signed-in user. */
  exportMyData(): Observable<unknown> {
    return from(this.backendUrl.ensureConnected()).pipe(
      switchMap(() => this.http.get<unknown>(this.backendUrl.apiUrl('/users/me/export')))
    );
  }

  /** Deletes the account and all saved analyses, then signs out. */
  deleteAccount(): Observable<void> {
    return from(this.backendUrl.ensureConnected()).pipe(
      switchMap(() => this.http.delete<void>(this.backendUrl.apiUrl('/users/me'))),
      tap(() => this.logout(true))
    );
  }

  /**
   * Confirms on startup that the stored token still works.
   *
   * <p>This no longer signs the user out on any error. A 401 - the one answer
   * that really does mean the session is over - is handled once, in the auth
   * interceptor. Signing out here as well meant that a backend that was simply
   * not running yet, or a flaky connection on a phone, silently threw away a
   * perfectly valid session.</p>
   */
  bootstrapSession(): Subscription | null {
    if (!this.getToken()) {
      return null;
    }

    return this.getCurrentUser().subscribe({
      error: () => undefined
    });
  }

  logout(silent = false): void {
    this.token.set(null);
    this.user.set(null);

    if (typeof window !== 'undefined') {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(USER_KEY);
    }

    if (!silent) {
      this.backendUrl.ensureConnected().catch(() => undefined);
    }
  }

  isLoggedIn(): boolean {
    return !!this.token();
  }

  getToken(): string | null {
    return this.token();
  }

  private storeSession(response: AuthResponse): void {
    this.token.set(response.token);
    this.storeUser(response.user);

    if (typeof window !== 'undefined') {
      localStorage.setItem(TOKEN_KEY, response.token);
    }
  }

  private storeUser(user: UserProfile): void {
    this.user.set(user);

    if (typeof window !== 'undefined') {
      localStorage.setItem(USER_KEY, JSON.stringify(user));
    }
  }

  private readToken(): string | null {
    return typeof window !== 'undefined' ? localStorage.getItem(TOKEN_KEY) : null;
  }

  private readUser(): UserProfile | null {
    if (typeof window === 'undefined') {
      return null;
    }

    const raw = localStorage.getItem(USER_KEY);
    if (!raw) {
      return null;
    }

    try {
      return JSON.parse(raw) as UserProfile;
    } catch {
      return null;
    }
  }
}
