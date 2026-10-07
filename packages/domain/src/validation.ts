import type {ChatMessage, RoomMember, RoomSnapshot, ServerEvent, Session} from './types.ts';

const uuidPattern = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;

export class ProtocolError extends Error {
  constructor(message = 'The server sent an unsupported response.') {
    super(message);
    this.name = 'ProtocolError';
  }
}

export function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new ProtocolError();
  }
  return value as Record<string, unknown>;
}

export function text(value: unknown, max: number): string {
  if (typeof value !== 'string' || value.trim().length === 0 || [...value].length > max) {
    throw new ProtocolError();
  }
  return value;
}

export function integer(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw new ProtocolError();
  }
  return value;
}

export function parseUuid(value: unknown): string {
  const result = text(value, 36);
  if (!uuidPattern.test(result)) throw new ProtocolError();
  return result;
}

function boolean(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new ProtocolError();
  return value;
}

function member(value: unknown): RoomMember {
  const data = object(value);
  if (data.role !== 'host' && data.role !== 'listener') throw new ProtocolError();
  return {
    user_id: parseUuid(data.user_id),
    display_name: text(data.display_name, 40),
    role: data.role,
    ready: boolean(data.ready),
    connected: boolean(data.connected),
  };
}

function message(value: unknown): ChatMessage {
  const data = object(value);
  return {
    id: parseUuid(data.id),
    user_id: parseUuid(data.user_id),
    display_name: text(data.display_name, 40),
    body: text(data.body, 1000),
    created_at_ms: integer(data.created_at_ms),
  };
}

export function parseSession(value: unknown): Session {
  const data = object(value);
  const user = object(data.user);
  const expires = integer(data.expires_in);
  if (expires === 0) throw new ProtocolError();
  return {
    access_token: text(data.access_token, 8192),
    refresh_token: text(data.refresh_token, 8192),
    expires_in: expires,
    user: {id: parseUuid(user.id), display_name: text(user.display_name, 40)},
  };
}

export function parseSnapshot(value: unknown): RoomSnapshot {
  const data = object(value);
  if (data.provider !== 'generated_demo'
    || !Array.isArray(data.members) || data.members.length === 0 || data.members.length > 25
    || !Array.isArray(data.messages) || data.messages.length > 50) {
    throw new ProtocolError();
  }
  const members = data.members.map(member);
  const messages = data.messages.map(message);
  const host = parseUuid(data.host_id);
  if (new Set(members.map(item => item.user_id)).size !== members.length
    || new Set(messages.map(item => item.id)).size !== messages.length
    || members.filter(item => item.role === 'host').length !== 1
    || !members.some(item => item.user_id === host && item.role === 'host')) {
    throw new ProtocolError();
  }
  return {
    room_id: parseUuid(data.room_id),
    name: text(data.name, 80),
    revision: integer(data.revision),
    provider: data.provider,
    host_id: host,
    members,
    messages,
  };
}

export function parseServerEvent(value: unknown): ServerEvent {
  const data = object(value);
  if (data.v !== 1) throw new ProtocolError('This app needs a newer version to connect.');
  switch (data.type) {
    case 'room.snapshot': {
      const payload = parseSnapshot(data.payload);
      if (data.room_id !== payload.room_id || data.revision !== payload.revision) {
        throw new ProtocolError();
      }
      return {
        v: 1,
        type: 'room.snapshot',
        room_id: payload.room_id,
        revision: payload.revision,
        server_time_ms: integer(data.server_time_ms),
        payload,
      };
    }
    case 'hello': {
      const payload = data.payload === undefined ? {} : object(data.payload);
      const interval = payload.heartbeat_interval_ms === undefined
        ? 15_000 : integer(payload.heartbeat_interval_ms);
      if (interval < 1000 || interval > 60_000) throw new ProtocolError();
      return {v: 1, type: 'hello', heartbeat_interval_ms: interval};
    }
    case 'pong':
      return {v: 1, type: 'pong', server_time_ms: integer(data.server_time_ms)};
    case 'command.ack':
      return {v: 1, type: 'command.ack', payload: {command_id: parseUuid(object(data.payload).command_id)}};
    case 'error': {
      const payload = object(data.payload);
      return {v: 1, type: 'error', payload: {
        code: text(payload.code, 80), message: text(payload.message, 300),
        ...(payload.command_id === undefined ? {} : {command_id: parseUuid(payload.command_id)}),
      }};
    }
    default:
      text(data.type, 80);
      return {v: 1, type: 'unknown'};
  }
}

export function parseInviteCode(value: unknown): string {
  if (typeof value !== 'string') throw new ProtocolError();
  const code = value.trim().toUpperCase();
  if (!/^[0-9A-HJKMNP-TV-Z]{12}$/.test(code)) throw new ProtocolError();
  return code;
}
