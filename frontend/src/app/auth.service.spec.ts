import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { AuthService } from './auth.service';

const TOKEN_KEY = 'road_assistant_token';

describe('AuthService.updateProfile', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.setItem(TOKEN_KEY, 'old-token');

    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('stores the fresh token that PUT /api/users/me returns, not just the user', async () => {
    service
      .updateProfile({
        fullName: 'New Name',
        email: 'new@example.com',
        phone: '12345678',
        address: 'Somewhere 1'
      })
      .subscribe();

    // BackendUrlService probes /api/health before the real call.
    httpMock.expectOne((request) => request.url.endsWith('/api/health')).flush({ status: 'UP' });
    // ensureConnected() resolves a promise before the real call is issued.
    await new Promise((resolve) => setTimeout(resolve, 0));

    const request = httpMock.expectOne((candidate) => candidate.url.endsWith('/api/users/me'));
    expect(request.request.method).toBe('PUT');

    request.flush({
      token: 'fresh-token',
      user: {
        id: 1,
        fullName: 'New Name',
        email: 'new@example.com',
        phone: '12345678',
        address: 'Somewhere 1'
      }
    });

    expect(service.getToken()).toBe('fresh-token');
    expect(localStorage.getItem(TOKEN_KEY)).toBe('fresh-token');
    expect(service.user()?.fullName).toBe('New Name');
  });
});
