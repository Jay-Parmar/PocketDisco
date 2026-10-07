import {ClientError, clientError, inputText} from './errors.ts';
import type {Session} from './types.ts';
import {object, parseSession, ProtocolError, text} from './validation.ts';

export type FetchResponse = Pick<Response, 'ok' | 'status' | 'json'>;
export type FetchFunction = (url: string, options: RequestInit) => Promise<FetchResponse>;

export type TimerApi = {
  setTimeout(callback: () => void, delayMs: number): ReturnType<typeof setTimeout>;
  clearTimeout(timer: ReturnType<typeof setTimeout>): void;
};

export const defaultTimers: TimerApi = {
  setTimeout: (callback, delay) => setTimeout(callback, delay),
  clearTimeout: timer => clearTimeout(timer),
};

export type HttpOptions = {
  baseUrl: string;
  allowInsecureHttp?: boolean;
  fetch?: FetchFunction;
  requestTimeoutMs?: number;
  timers?: TimerApi;
};

export class SessionTransport {
  readonly baseUrl: string;
  private readonly fetch: FetchFunction;
  private readonly timeoutMs: number;
  private readonly timers: TimerApi;
  private readonly onSession: (session: Session | null) => void;
  private session: Session | null = null;
  private identity = 0;
  private refreshFlight: {identity: number; promise: Promise<void>} | null = null;
  private readonly requests = new Map<AbortController, () => void>();

  constructor(options: HttpOptions, onSession: (session: Session | null) => void) {
    let url: URL;
    try {
      url = new URL(options.baseUrl);
    } catch {
      throw new ClientError('invalid_url', 'Enter a valid PocketDisco server address.');
    }
    if ((url.protocol !== 'https:' && !(options.allowInsecureHttp && url.protocol === 'http:'))
      || url.username || url.password || url.search || url.hash) {
      throw new ClientError('invalid_url', 'Use an HTTPS server address without credentials or query parameters.');
    }
    this.baseUrl = url.toString().replace(/\/+$/, '');
    this.fetch = options.fetch ?? ((input, init) => globalThis.fetch(input, init));
    this.timeoutMs = options.requestTimeoutMs ?? 10_000;
    if (!Number.isFinite(this.timeoutMs) || this.timeoutMs < 1 || this.timeoutMs > 60_000) {
      throw new ClientError('invalid_options', 'Request timeout must be between 1 and 60000 ms.');
    }
    this.timers = options.timers ?? defaultTimers;
    this.onSession = onSession;
  }

  getSession(): Session | null {
    return this.session;
  }

  restoreSession(value: Session): void {
    const session = parseSession(value);
    this.clear();
    this.setSession(session);
  }

  async guest(displayName: string): Promise<Session> {
    const name = inputText(displayName, 40, 'Your name');
    this.clear();
    const identity = this.identity;
    const data = await this.perform('POST', '/v1/auth/guest', {display_name: name});
    this.assertIdentity(identity);
    const session = parseSession(data);
    this.setSession(session);
    return session;
  }

  clear(): void {
    this.identity += 1;
    for (const cancel of this.requests.values()) cancel();
    this.requests.clear();
    this.refreshFlight = null;
    this.setSession(null);
  }

  async request(method: string, path: string, body?: unknown): Promise<unknown> {
    const identity = this.identity;
    const session = this.session;
    if (session === null) throw new ClientError('not_authenticated', 'Choose a name to continue.');
    let token = session.access_token;
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        const response = await this.perform(method, path, body, token);
        this.assertIdentity(identity);
        return response;
      } catch (error) {
        this.assertIdentity(identity);
        const issue = clientError(error);
        if (issue.status !== 401) throw issue;
        if (attempt === 1) throw this.expired();
        await this.refresh(token, identity);
        this.assertIdentity(identity);
        token = this.session!.access_token;
      }
    }
    throw this.expired();
  }

  private setSession(session: Session | null): void {
    this.session = session;
    this.onSession(session);
  }

  private assertIdentity(identity: number): void {
    if (identity !== this.identity) {
      throw new ClientError('cancelled', 'This request is no longer active.');
    }
  }

  private expired(): ClientError {
    this.clear();
    return new ClientError('session_expired', 'Your session expired. Choose a name to sign in again.');
  }

  private async refresh(previousToken: string, identity: number): Promise<void> {
    if (this.session?.access_token !== previousToken) return;
    if (this.refreshFlight?.identity === identity) return this.refreshFlight.promise;
    const refreshToken = this.session.refresh_token;
    const promise = (async () => {
      try {
        const data = await this.perform('POST', '/v1/auth/refresh', {refresh_token: refreshToken});
        this.assertIdentity(identity);
        const next = parseSession(data);
        if (next.user.id !== this.session?.user.id) throw new ProtocolError();
        this.setSession(next);
      } catch (error) {
        this.assertIdentity(identity);
        const issue = clientError(error);
        if (issue.status === 401 || issue.status === 403) throw this.expired();
        throw issue;
      }
    })();
    const flight = {identity, promise};
    this.refreshFlight = flight;
    try {
      await promise;
    } finally {
      if (this.refreshFlight === flight) this.refreshFlight = null;
    }
  }

  private async perform(method: string, path: string, body?: unknown, token?: string): Promise<unknown> {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const stopped = new Promise<never>((_, reject) => {
      const stop = (error: ClientError) => {
        reject(error);
        controller.abort();
      };
      this.requests.set(controller, () => stop(new ClientError('cancelled', 'This request is no longer active.')));
      timer = this.timers.setTimeout(
        () => stop(new ClientError('request_timeout', 'The server took too long to respond. Try again.', true)),
        this.timeoutMs,
      );
    });
    const headers: Record<string, string> = {Accept: 'application/json'};
    if (token !== undefined) headers.Authorization = `Bearer ${token}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const options: RequestInit = {method, headers, signal: controller.signal};
    if (body !== undefined) options.body = JSON.stringify(body);
    try {
      const response = await Promise.race([this.fetch(`${this.baseUrl}${path}`, options), stopped]);
      const data = await Promise.race([response.json().catch(() => null), stopped]);
      if (!response.ok) {
        let code = 'request_failed';
        let message = 'The request could not be completed. Try again.';
        try {
          const detail = object(object(data).detail);
          code = text(detail.code, 80);
          message = text(detail.message, 300);
        } catch {
          // Keep a safe message for non-JSON error pages.
        }
        throw new ClientError(code, message, response.status === 429 || response.status >= 500, response.status);
      }
      if (data === null) throw new ProtocolError();
      return data;
    } catch (error) {
      throw clientError(error);
    } finally {
      if (timer !== undefined) this.timers.clearTimeout(timer);
      this.requests.delete(controller);
    }
  }
}
