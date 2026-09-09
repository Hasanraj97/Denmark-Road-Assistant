import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

const TOKEN_KEY = 'road_assistant_token';
const USER_KEY = 'road_assistant_user';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let router: Router;
  let auth: AuthService;

  beforeEach(() => {
    localStorage.setItem(TOKEN_KEY, 'test-token');
    localStorage.setItem(
      USER_KEY,
      JSON.stringify({ id: 1, fullName: 'Test', email: 't@example.com', phone: '1', address: 'a' })
    );

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([])
      ]
    });

    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('attaches the token to this app\'s own backend', () => {
    http.get('http://localhost:8080/api/history').subscribe();

    const request = httpMock.expectOne('http://localhost:8080/api/history');
    expect(request.request.headers.get('Authorization')).toBe('Bearer test-token');
    request.flush([]);
  });

  it('does not send the token to Open-Meteo', () => {
    http.get('https://api.open-meteo.com/v1/forecast?latitude=55&longitude=12').subscribe();

    const request = httpMock.expectOne((candidate) => candidate.url.includes('open-meteo.com'));
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('does not send the token to Nominatim', () => {
    http.get('https://nominatim.openstreetmap.org/reverse?lat=55&lon=12').subscribe();

    const request = httpMock.expectOne((candidate) => candidate.url.includes('nominatim'));
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('is not fooled by a third-party URL that merely mentions the backend', () => {
    http.get('https://evil.example.com/collect?next=http://localhost:8080/api').subscribe();

    const request = httpMock.expectOne((candidate) => candidate.url.includes('evil.example.com'));
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('does not send the token to the login endpoint', () => {
    http.post('http://localhost:8080/api/auth/login', {}).subscribe();

    const request = httpMock.expectOne('http://localhost:8080/api/auth/login');
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('clears the session and routes to login on a 401 from the backend', async () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    http.get('http://localhost:8080/api/history').subscribe({ error: () => undefined });
    httpMock
      .expectOne('http://localhost:8080/api/history')
      .flush({ message: 'expired' }, { status: 401, statusText: 'Unauthorized' });

    expect(auth.isLoggedIn()).toBe(false);
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    // router.url is '/' in a test with no active navigation; in the app it is
    // whichever protected page the user was looking at.
    expect(navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { reason: 'session-expired', returnUrl: router.url }
    });
  });

  it('keeps the session on a 401 from login, which only means bad credentials', () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    http.post('http://localhost:8080/api/auth/login', {}).subscribe({ error: () => undefined });
    httpMock
      .expectOne('http://localhost:8080/api/auth/login')
      .flush({ message: 'bad credentials' }, { status: 401, statusText: 'Unauthorized' });

    expect(auth.isLoggedIn()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('leaves the session alone when a third party answers 401', () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    http.get('https://api.open-meteo.com/v1/forecast').subscribe({ error: () => undefined });
    httpMock
      .expectOne((candidate) => candidate.url.includes('open-meteo.com'))
      .flush('nope', { status: 401, statusText: 'Unauthorized' });

    expect(auth.isLoggedIn()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });
});
