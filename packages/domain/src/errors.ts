import type {ClientIssue} from './types.ts';
import {ProtocolError} from './validation.ts';

export class ClientError extends Error implements ClientIssue {
  readonly code: string;
  readonly retryable: boolean;
  readonly status: number | undefined;

  constructor(code: string, message: string, retryable = false, status?: number) {
    super(message);
    this.name = 'ClientError';
    this.code = code;
    this.retryable = retryable;
    this.status = status;
  }
}

export function clientError(error: unknown): ClientError {
  if (error instanceof ClientError) return error;
  if (error instanceof ProtocolError) return new ClientError('invalid_response', error.message);
  return new ClientError('network_error', 'Could not reach PocketDisco. Check your connection.', true);
}

export function inputText(value: string, max: number, label: string): string {
  const result = value.trim();
  if (result.length === 0 || [...result].length > max) {
    throw new ClientError('invalid_input', `${label} must have 1 to ${max} characters.`);
  }
  return result;
}
