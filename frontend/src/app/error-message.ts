import { HttpErrorResponse } from '@angular/common/http';

/**
 * One place that turns anything a failed request can throw into a sentence a
 * user can read.
 *
 * <p>Every page used to write its own {@code error?.error?.message} chain. That
 * expression silently produced {@code undefined} in three very common cases, and
 * the page then fell back to a vague sentence that hid what actually went
 * wrong:</p>
 *
 * <ul>
 *   <li>The backend was not running at all. Angular reports that as status 0
 *       with a {@code ProgressEvent} in {@code error.error}, so
 *       {@code .message} is missing and the user was told "Login failed. Please
 *       check your credentials." when their credentials were never sent.</li>
 *   <li>The body was a plain string. Anything that answers before Spring's
 *       {@code @RestControllerAdvice} - a proxy, a 413 from a container, a
 *       gateway - sends text, not {@code {message}}, and it was dropped.</li>
 *   <li>The body was JSON but arrived with a non-JSON content type, so Angular
 *       left it as an unparsed string.</li>
 * </ul>
 *
 * <p>The HTTP status is also kept when there is nothing better to say, because
 * "403" and "500" send a developer to very different places.</p>
 */
export const GENERIC_ERROR = 'Something went wrong. Please try again.';

const OFFLINE_MESSAGE =
  'Could not reach the server. Check that the backend is running and that you are online.';

export function describeError(error: unknown, fallback: string = GENERIC_ERROR): string {
  if (error instanceof HttpErrorResponse) {
    // Status 0 means no HTTP response ever arrived: DNS failure, refused
    // connection, CORS rejection, aeroplane mode. There is no body to read.
    if (error.status === 0) {
      return OFFLINE_MESSAGE;
    }

    const fromBody = messageFromBody(error.error);
    if (fromBody) {
      return fromBody;
    }

    const statusText = error.statusText && error.statusText !== 'OK' ? ` ${error.statusText}` : '';
    return `${fallback} (HTTP ${error.status}${statusText})`;
  }

  if (error instanceof Error && error.message.trim()) {
    return error.message.trim();
  }

  if (typeof error === 'string' && error.trim()) {
    return error.trim();
  }

  return fallback;
}

/** The HTTP status behind a failure, or 0 when the request never reached a server. */
export function statusOf(error: unknown): number {
  return error instanceof HttpErrorResponse ? error.status : 0;
}

function messageFromBody(body: unknown): string {
  if (typeof body === 'string') {
    const text = body.trim();
    if (!text) {
      return '';
    }

    // A JSON body that arrived with the wrong content type is still JSON.
    if (text.startsWith('{')) {
      try {
        const parsed: unknown = JSON.parse(text);
        const nested = messageFromBody(parsed);
        if (nested) {
          return nested;
        }
      } catch {
        // Not JSON after all; fall through and show the raw text.
      }
    }

    return truncate(text);
  }

  // A network-level failure surfaces the DOM event rather than a server body.
  if (typeof ProgressEvent !== 'undefined' && body instanceof ProgressEvent) {
    return OFFLINE_MESSAGE;
  }

  if (body instanceof Error) {
    return body.message.trim() || OFFLINE_MESSAGE;
  }

  if (body && typeof body === 'object') {
    const record = body as Record<string, unknown>;
    for (const key of ['message', 'error', 'detail']) {
      const value = record[key];
      if (typeof value === 'string' && value.trim()) {
        return truncate(value.trim());
      }
    }
  }

  return '';
}

function truncate(text: string): string {
  const collapsed = text.replace(/\s+/g, ' ').trim();
  return collapsed.length > 300 ? `${collapsed.slice(0, 297)}...` : collapsed;
}
