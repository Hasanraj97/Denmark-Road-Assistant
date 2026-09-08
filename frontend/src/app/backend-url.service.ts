import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { environment } from '../environments/environment';

@Injectable({ providedIn: 'root' })
export class BackendUrlService {
  private readonly http = inject(HttpClient);
  private readonly resolvedBaseUrl = signal(environment.apiBaseUrl);
  private readonly candidates = environment.apiBaseCandidates ?? [environment.apiBaseUrl];
  private connected = false;

  currentBaseUrl(): string {
    return this.resolvedBaseUrl();
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
