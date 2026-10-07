export type ConnectionStatus =
  | 'connecting'
  | 'connected'
  | 'reconnecting'
  | 'offline';

export type RoomMember = {
  user_id: string;
  display_name: string;
  role: 'host' | 'listener';
  ready: boolean;
  connected: boolean;
};

export type RoomMessage = {
  id: string;
  user_id: string;
  display_name: string;
  body: string;
  created_at_ms: number;
};

export type RoomDetails = {
  room_id: string;
  name: string;
  host_id: string;
  members: readonly RoomMember[];
  messages: readonly RoomMessage[];
};
