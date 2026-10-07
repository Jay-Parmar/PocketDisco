import {
  parseSession,
  parseInviteCode,
} from '../../../packages/domain/src/validation';
import type { Session } from '../../../packages/domain/src/types';

export type SavedSession = {
  baseUrl: string;
  session: Session;
  inviteCode: string | null;
};

export function readSavedSession(
  value: string,
  configuredUrl: string,
  allowLocalServer: boolean,
): SavedSession {
  const saved: unknown = JSON.parse(value);
  if (typeof saved !== 'object' || saved === null) {
    throw new Error('Invalid saved session.');
  }
  const record = saved as Record<string, unknown>;
  if (typeof record.baseUrl !== 'string') {
    throw new Error('Invalid saved server.');
  }
  const url = new URL(record.baseUrl);
  if (
    (!allowLocalServer && record.baseUrl !== configuredUrl) ||
    (!allowLocalServer && url.protocol !== 'https:') ||
    !['http:', 'https:'].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname !== '/'
  ) {
    throw new Error('Invalid saved server.');
  }
  return {
    baseUrl: record.baseUrl,
    session: parseSession(record.session),
    inviteCode:
      record.inviteCode === null ? null : parseInviteCode(record.inviteCode),
  };
}
