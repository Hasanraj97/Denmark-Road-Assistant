import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, from, switchMap, tap } from 'rxjs';

import { BackendUrlService } from './backend-url.service';

export interface UserProfile {
  id: number;
  fullName: string;
  email: string;
  phone: string;
  address: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface SignupPayload {
  fullName: string;
  email: string;
  phone: string;
  address: string;
  password: string;
}

export interface LoginPayload {
  email: string;
  password: string;
}

export interface UpdateProfilePayload {
  fullName: string;
  email: string;
  phone: string;
  address: string;
}

interface AuthResponse {
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

  updateProfile(payload: UpdateProfilePayload): Observable<UserProfile> {
    return from(this.backendUrl.ensureConnected()).pipe(
      switchMap(() => this.http.put<UserProfile>(this.backendUrl.apiUrl('/users/me'), payload)),
      tap((user) => this.storeUser(user))
    );
  }

  bootstrapSession(): void {
    if (!this.getToken()) {
      return;
    }

    this.getCurrentUser().subscribe({
      error: () => this.logout(true)
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
