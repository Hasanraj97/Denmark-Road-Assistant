import { HttpErrorResponse } from '@angular/common/http';

import { describeError, statusOf } from './error-message';

describe('describeError', () => {
  it('reads the message out of the backend JSON body', () => {
    const error = new HttpErrorResponse({
      status: 404,
      statusText: 'Not Found',
      error: { message: 'Could not match "Xyz" to a place in Denmark.', timestamp: '2026-01-01T00:00:00Z' }
    });

    expect(describeError(error, 'fallback')).toBe('Could not match "Xyz" to a place in Denmark.');
  });

  it('keeps a plain-string body instead of dropping it', () => {
    const error = new HttpErrorResponse({
      status: 413,
      statusText: 'Payload Too Large',
      error: 'Request entity too large'
    });

    expect(describeError(error, 'fallback')).toBe('Request entity too large');
  });

  it('parses a JSON body that arrived with the wrong content type', () => {
    const error = new HttpErrorResponse({
      status: 500,
      error: '{"message":"Boom","timestamp":"2026-01-01T00:00:00Z"}'
    });

    expect(describeError(error, 'fallback')).toBe('Boom');
  });

  it('explains a total network failure rather than blaming the user', () => {
    const error = new HttpErrorResponse({
      status: 0,
      statusText: 'Unknown Error',
      error: new ProgressEvent('error')
    });

    expect(describeError(error, 'Login failed. Please check your credentials.')).toContain(
      'Could not reach the server'
    );
  });

  it('keeps the status when the body says nothing useful', () => {
    const error = new HttpErrorResponse({ status: 403, statusText: 'Forbidden', error: {} });

    expect(describeError(error, 'History could not be loaded.')).toBe(
      'History could not be loaded. (HTTP 403 Forbidden)'
    );
  });

  it('passes through a plain Error, such as a denied geolocation prompt', () => {
    expect(describeError(new Error('Location access denied. Please allow permission.'))).toBe(
      'Location access denied. Please allow permission.'
    );
  });

  it('falls back for something it cannot read', () => {
    expect(describeError(undefined, 'fallback')).toBe('fallback');
  });

  it('reports the HTTP status behind a failure', () => {
    expect(statusOf(new HttpErrorResponse({ status: 404 }))).toBe(404);
    expect(statusOf(new Error('nope'))).toBe(0);
  });
});
