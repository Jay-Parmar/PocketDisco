export type User = {
  id: string;
  display_name: string;
};

export type Session = {
  access_token: string;
  refresh_token: string;
  expires_in: number;
  user: User;
};

export type RoomMember = {
  user_id: string;
  display_name: string;
  role: 'host' | 'listener';
  ready: boolean;
  connected: boolean;
};

export type ChatMessage = {
  id: string;
  user_id: string;
  display_name: string;
  body: string;
  created_at_ms: number;
};

export type RoomSnapshot = {
  room_id: string;
  name: string;
  revision: number;
  provider: 'generated_demo';
  host_id: string;
  members: RoomMember[];
  messages: ChatMessage[];
};

export type SnapshotEvent = {
  v: 1;
  type: 'room.snapshot';
  room_id: string;
  revision: number;
  server_time_ms: number;
  payload: RoomSnapshot;
};

export type ServerEvent = SnapshotEvent | {
  v: 1;
  type: 'hello';
  heartbeat_interval_ms: number;
} | {
  v: 1;
  type: 'pong';
  server_time_ms: number;
} | {
  v: 1;
  type: 'command.ack';
  payload: {command_id: string};
} | {
  v: 1;
  type: 'error';
  payload: {code: string; message: string; command_id?: string};
} | {
  v: 1;
  type: 'unknown';
};

export type ConnectionState = 'offline' | 'connecting' | 'connected' | 'reconnecting';

export type ClientIssue = {
  code: string;
  message: string;
  retryable: boolean;
};

export type ClientState = {
  session: Session | null;
  snapshot: RoomSnapshot | null;
  inviteCode: string | null;
  connection: ConnectionState;
  error: ClientIssue | null;
};
