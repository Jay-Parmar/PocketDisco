import {
  parseSession,
  parseInviteCode,
} from '../../../packages/domain/src/validation';
import type { Session } from '../../../packages/domain/src/types';

export type SavedSession = {
  baseUrl: string;
  session: Session | null;
  roomId: string | null;
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
  const session = record.session === null ? null : parseSession(record.session);
  if (
    session === null &&
    (record.roomId != null || record.inviteCode != null)
  ) {
    throw new Error('Invalid saved room.');
  }
  return {
    baseUrl: record.baseUrl,
    session,
    roomId:
      typeof record.roomId === 'string' &&
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
        record.roomId,
      )
        ? record.roomId
        : null,
    inviteCode:
      session === null || record.inviteCode === null
        ? null
        : parseInviteCode(record.inviteCode),
  };
}
