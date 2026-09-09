import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { environment } from '../environments/environment';

function originOf(url: string): string | null {
  try {
    return new URL(url).origin.toLowerCase();
  } catch {
    return null;
  }
}

@Injectable({ providedIn: 'root' })
export class BackendUrlService {
  private readonly http = inject(HttpClient);
  private readonly resolvedBaseUrl = signal(environment.apiBaseUrl);
  private readonly candidates = environment.apiBaseCandidates ?? [environment.apiBaseUrl];
  private connected = false;

  currentBaseUrl(): string {
    return this.resolvedBaseUrl();
  }

  /**
   * True only for requests aimed at this app's own backend.
   *
   * <p>The auth interceptor uses this to decide where the session token may go.
   * It used to attach the token to every request that was not
   * {@code /api/auth/}, which included the direct Open-Meteo and Nominatim
   * calls this app makes - so the user's bearer token was handed to two
   * unrelated public services on every analysis.</p>
   *
   * <p>The comparison is on origin, not on a substring of the URL, because
   * {@code url.includes(baseUrl)} would also match a third-party address that
   * merely mentions ours in a query parameter.</p>
   */
  isBackendUrl(url: string): boolean {
    // A relative URL is served from the app's own origin.
    if (!/^[a-z][a-z0-9+.-]*:/i.test(url)) {
      return url.startsWith('/api');
    }

    const origin = originOf(url);
    return origin !== null && this.backendOrigins().has(origin);
  }

  private backendOrigins(): Set<string> {
    const origins = new Set<string>();
    for (const candidate of [...this.candidates, environment.apiBaseUrl, this.resolvedBaseUrl()]) {
      const origin = originOf(candidate);
      if (origin) {
        origins.add(origin);
      }
    }
    return origins;
  }

  apiUrl(path: string): string {
    const normalizedPath = path.startsWith('/api') ? path : `/api${path}`;
    return `${this.resolvedBaseUrl()}${normalizedPath}`;
  }

  async ensureConnected(force = false): Promise<string> {
    if (this.connected && !force) {
      return this.resolvedBaseUrl();
    }

    let lastError: unknown;
    for (const candidate of this.candidates) {
      try {
        await firstValueFrom(this.http.get(`${candidate}/api/health`));
        this.resolvedBaseUrl.set(candidate);
        this.connected = true;
        return candidate;
      } catch (error) {
        lastError = error;
      }
    }

    this.connected = false;
    throw lastError;
  }
}
