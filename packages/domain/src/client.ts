import {ClientError, clientError, inputText} from './errors.ts';
import {defaultTimers, SessionTransport} from './http.ts';
import type {HttpOptions, TimerApi} from './http.ts';
import {applySnapshot} from './reducer.ts';
import type {ClientState, RoomSnapshot, Session} from './types.ts';
import {integer, object, parseInviteCode, parseServerEvent, parseSnapshot, parseUuid, ProtocolError, text} from './validation.ts';

export type RoomSocket = {
  readyState: number;
  bufferedAmount?: number;
  onopen: (() => void) | null;
  onmessage: ((event: {data: unknown}) => void) | null;
  onclose: ((event: {code?: number}) => void) | null;
  onerror: (() => void) | null;
  send(data: string): void;
  close(): void;
};

export type RoomClientOptions = HttpOptions & {
  createSocket?: (url: string) => RoomSocket;
  createCommandId?: () => string;
  onSessionChanged?: (session: Session | null) => void;
  now?: () => number;
  random?: () => number;
  handshakeTimeoutMs?: number;
  commandAckTimeoutMs?: number;
  reconnectDelaysMs?: readonly number[];
};

type Timer = ReturnType<typeof setTimeout>;
type PendingCommand = {timer: Timer; resolve: (id: string) => void; reject: (issue: ClientError) => void};

const initialState = (): ClientState => ({
  session: null, snapshot: null, inviteCode: null, connection: 'offline', error: null,
});

function commandId(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID();
  if (typeof globalThis.crypto?.getRandomValues === 'function') {
    const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6]! & 15) | 64;
    bytes[8] = (bytes[8]! & 63) | 128;
    const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  throw new ClientError('command_id_unavailable', 'This build is missing secure command ID support.');
}

export class RoomClient {
  private state: ClientState = initialState();
  private readonly listeners = new Set<(state: ClientState) => void>();
  private readonly http: SessionTransport;
  private readonly createSocket: (url: string) => RoomSocket;
  private readonly createCommandId: () => string;
  private readonly timers: TimerApi;
  private readonly now: () => number;
  private readonly random: () => number;
  private readonly handshakeTimeoutMs: number;
  private readonly commandAckTimeoutMs: number;
  private readonly pendingCommands = new Map<string, PendingCommand>();
  private readonly reconnectDelays: readonly number[];
  private socket: RoomSocket | null = null;
  private reconnectTimer: Timer | null = null;
  private heartbeatTimer: Timer | null = null;
  private handshakeTimer: Timer | null = null;
  private generation = 0;
  private roomOperation = 0;
  private roomOperationActive = false;
  private wantedConnection = false;
  private reconnectAttempt = 0;
  private everConnected = false;
  private lastReceivedAt = 0;
  private heartbeatIntervalMs = 15_000;

  constructor(options: RoomClientOptions) {
    this.timers = options.timers ?? defaultTimers;
    this.now = options.now ?? Date.now;
    this.random = options.random ?? Math.random;
    this.handshakeTimeoutMs = options.handshakeTimeoutMs ?? 10_000;
    this.commandAckTimeoutMs = options.commandAckTimeoutMs ?? 10_000;
    this.reconnectDelays = options.reconnectDelaysMs ?? [500, 1000, 2000, 4000, 8000, 15_000, 30_000];
    if (this.handshakeTimeoutMs < 1 || this.handshakeTimeoutMs > 60_000 || !Number.isFinite(this.handshakeTimeoutMs)
      || this.commandAckTimeoutMs < 1 || this.commandAckTimeoutMs > 60_000 || !Number.isFinite(this.commandAckTimeoutMs)
      || this.reconnectDelays.length === 0
      || this.reconnectDelays.some(delay => !Number.isFinite(delay) || delay < 1 || delay > 60_000)) {
      throw new ClientError('invalid_options', 'Connection delays must be positive and bounded.');
    }
    this.createSocket = options.createSocket ?? (url => new WebSocket(url) as unknown as RoomSocket);
    this.createCommandId = options.createCommandId ?? commandId;
    this.http = new SessionTransport(options, session => {
      if (session === null) this.clearRoom();
      this.update({session});
      options.onSessionChanged?.(session);
    });
  }

  getState(): ClientState {
    return this.state;
  }

  subscribe(listener: (state: ClientState) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  async guest(displayName: string): Promise<Session> {
    return this.action(() => this.http.guest(displayName));
  }

  restoreSession(session: Session): void {
    this.http.restoreSession(session);
    this.update({error: null});
  }

  signOut(): void {
    this.http.clear();
    this.update({error: null});
  }

  async createRoom(name: string): Promise<RoomSnapshot> {
    return this.enterRoom(async () => {
      const data = object(await this.http.request('POST', '/v1/rooms', {name: inputText(name, 80, 'Room name')}));
      return {snapshot: parseSnapshot(data.snapshot), inviteCode: parseInviteCode(data.invite_code)};
    });
  }

  async joinRoom(inviteCode: string): Promise<RoomSnapshot> {
    return this.enterRoom(async () => {
      let code: string;
      try {
        code = parseInviteCode(inviteCode);
      } catch {
        throw new ClientError('invalid_invite', 'Enter the 12-character room invite code.');
      }
      const data = object(await this.http.request('POST', `/v1/rooms/${code}/join`, {}));
      return {snapshot: parseSnapshot(data.snapshot), inviteCode: code};
    });
  }

  async resumeRoom(roomId: string, inviteCode?: string): Promise<RoomSnapshot> {
    return this.enterRoom(async () => {
      let id: string;
      let code: string | null;
      try {
        id = parseUuid(roomId);
        code = inviteCode === undefined ? null : parseInviteCode(inviteCode);
      } catch {
        throw new ClientError('invalid_room', 'The saved room could not be restored. Join with a new invite.');
      }
      const snapshot = parseSnapshot(await this.http.request('GET', `/v1/rooms/${id}/snapshot`));
      if (snapshot.room_id !== id) throw new ProtocolError();
      return {snapshot, inviteCode: code};
    });
  }

  async leaveRoom(): Promise<void> {
    return this.action(async () => {
      const room = this.requireRoom();
      if (this.roomOperationActive) throw new ClientError('request_in_progress', 'Wait for the current room request.');
      const operation = ++this.roomOperation;
      this.roomOperationActive = true;
      try {
        const data = object(await this.http.request('POST', `/v1/rooms/${room.room_id}/leave`, {}));
        if (data.ok !== true) throw new ProtocolError();
        if (this.roomOperation === operation) this.clearRoom();
      } finally {
        if (this.roomOperation === operation) this.roomOperationActive = false;
      }
    });
  }

  async refreshSnapshot(): Promise<RoomSnapshot> {
    return this.action(async () => {
      const room = this.requireRoom();
      const operation = this.roomOperation;
      const snapshot = parseSnapshot(await this.http.request('GET', `/v1/rooms/${room.room_id}/snapshot`));
      if (operation !== this.roomOperation) throw new ClientError('cancelled', 'This request is no longer active.');
      this.acceptSnapshot(snapshot, room.room_id);
      return this.state.snapshot!;
    });
  }

  async setReady(ready: boolean): Promise<string> {
    return this.action(async () => {
      if (typeof ready !== 'boolean') throw new ClientError('invalid_input', 'Readiness must be true or false.');
      return this.sendConfirmed('member.ready', {ready});
    });
  }

  async sendChat(body: string): Promise<string> {
    return this.action(async () => this.sendConfirmed('chat.send', {body: inputText(body, 1000, 'Message')}));
  }

  disconnect(): void {
    this.wantedConnection = false;
    this.closeSocket();
    this.update({connection: 'offline'});
  }

  async reconnect(): Promise<void> {
    this.requireRoom();
    this.wantedConnection = true;
    this.reconnectAttempt = 0;
    this.closeSocket();
    await this.connectSocket(true);
  }

  private async enterRoom(load: () => Promise<{snapshot: RoomSnapshot; inviteCode: string | null}>): Promise<RoomSnapshot> {
    return this.action(async () => {
      if (this.state.snapshot !== null) throw new ClientError('room_active', 'Leave your current room before joining another.');
      if (this.roomOperationActive) throw new ClientError('request_in_progress', 'Wait for the current room request.');
      const operation = ++this.roomOperation;
      const generation = this.generation;
      this.roomOperationActive = true;
      try {
        const room = await load();
        if (operation !== this.roomOperation) throw new ClientError('cancelled', 'This request is no longer active.');
        this.validateMembership(room.snapshot);
        this.everConnected = false;
        this.update({...room, error: null});
        if (generation === this.generation) {
          this.wantedConnection = true;
          await this.connectSocket(false);
        }
        return room.snapshot;
      } finally {
        if (this.roomOperation === operation) this.roomOperationActive = false;
      }
    });
  }

  private requireRoom(): RoomSnapshot {
    if (this.state.session === null) throw new ClientError('not_authenticated', 'Choose a name to continue.');
    if (this.state.snapshot === null) throw new ClientError('not_in_room', 'Join a room to continue.');
    return this.state.snapshot;
  }

  private validateMembership(snapshot: RoomSnapshot): void {
    if (!snapshot.members.some(member => member.user_id === this.state.session?.user.id)) {
      throw new ClientError('room_unavailable', 'You are no longer a member of this room.');
    }
  }

  private acceptSnapshot(snapshot: RoomSnapshot, roomId: string): void {
    if (snapshot.room_id !== roomId) throw new ProtocolError();
    const current = this.state.snapshot;
    if (current !== null && snapshot.revision < current.revision) return;
    this.validateMembership(snapshot);
    this.update({snapshot: applySnapshot(current, snapshot)});
  }

  private async connectSocket(recover: boolean): Promise<void> {
    if (!this.wantedConnection || this.state.snapshot === null || this.state.session === null) return;
    const roomId = this.state.snapshot.room_id;
    const generation = this.generation;
    this.update({connection: this.everConnected || recover ? 'reconnecting' : 'connecting'});
    try {
      if (recover) {
        const snapshot = parseSnapshot(await this.http.request('GET', `/v1/rooms/${roomId}/snapshot`));
        if (generation !== this.generation || !this.wantedConnection) return;
        this.acceptSnapshot(snapshot, roomId);
      }
      const data = object(await this.http.request('POST', '/v1/realtime/tickets', {room_id: roomId}));
      if (generation !== this.generation || !this.wantedConnection) return;
      const ticket = text(data.ticket, 2048);
      if (integer(data.expires_in) === 0) throw new ProtocolError();
      const url = `${this.http.baseUrl.replace(/^http/, 'ws')}/v1/realtime?ticket=${encodeURIComponent(ticket)}`;
      const socket = this.createSocket(url);
      this.socket = socket;
      let hello = false;
      this.handshakeTimer = this.timers.setTimeout(() => {
        this.connectionLost(generation, new ClientError('connection_timeout', 'The room did not respond. Reconnecting.', true));
      }, this.handshakeTimeoutMs);
      socket.onopen = () => {
        if (generation === this.generation) this.lastReceivedAt = this.now();
      };
      socket.onmessage = event => {
        if (generation !== this.generation) return;
        try {
          if (typeof event.data !== 'string' || event.data.length > 262_144) throw new ProtocolError();
          const message = parseServerEvent(JSON.parse(event.data));
          if (message.type === 'unknown') return;
          this.lastReceivedAt = this.now();
          if (message.type === 'hello') {
            if (hello) throw new ProtocolError();
            hello = true;
            this.heartbeatIntervalMs = message.heartbeat_interval_ms;
            this.scheduleHeartbeat(generation);
            this.send('sync.request', {}, true);
          } else if (message.type === 'room.snapshot') {
            if (!hello || message.room_id !== roomId) throw new ProtocolError();
            if (message.revision < (this.state.snapshot?.revision ?? 0)) return;
            this.acceptSnapshot(message.payload, roomId);
            if (this.handshakeTimer !== null) this.timers.clearTimeout(this.handshakeTimer);
            this.handshakeTimer = null;
            this.everConnected = true;
            this.reconnectAttempt = 0;
            this.update({connection: 'connected', error: null});
          } else if (message.type === 'command.ack') {
            this.finishCommand(message.payload.command_id);
          } else if (message.type === 'error') {
            const issue = new ClientError(message.payload.code, message.payload.message);
            if (message.payload.command_id !== undefined) this.finishCommand(message.payload.command_id, issue);
            if (['not_member', 'room_closed', 'room_not_found', 'forbidden'].includes(issue.code)) {
              this.clearRoom();
            }
            this.update({error: issue});
          }
        } catch (error) {
          const issue = error instanceof SyntaxError ? new ClientError('invalid_response', 'The server sent an unsupported response.') : clientError(error);
          this.connectionLost(generation, issue);
        }
      };
      socket.onclose = event => {
        if (event.code === 4403) {
          this.connectionLost(generation, new ClientError('room_unavailable', 'You are no longer a member of this room.'));
        } else if (event.code === 1008 || event.code === 1009) {
          this.connectionLost(generation, new ClientError('invalid_response', 'This app cannot use the room protocol. Check for an update.'));
        } else {
          this.connectionLost(generation, new ClientError('connection_lost', 'Connection lost. Reconnecting to your room.', true));
        }
      };
      socket.onerror = () => this.connectionLost(generation, new ClientError('connection_lost', 'Connection lost. Reconnecting to your room.', true));
    } catch (error) {
      this.connectionLost(generation, clientError(error));
    }
  }

  private sendConfirmed(type: string, payload: unknown): Promise<string> {
    if (this.pendingCommands.size >= 8) {
      throw new ClientError('connection_busy', 'Wait for your previous messages to arrive.', true);
    }
    const id = this.createCommandId();
    if (this.pendingCommands.has(id)) throw new ClientError('command_id_unavailable', 'Command IDs must be unique.');
    return new Promise((resolve, reject) => {
      const timer = this.timers.setTimeout(() => {
        this.finishCommand(id, new ClientError('delivery_uncertain', 'Delivery could not be confirmed. Check the room before sending again.', true));
      }, this.commandAckTimeoutMs);
      this.pendingCommands.set(id, {timer, resolve, reject});
      try {
        this.send(type, payload, false, id);
      } catch (error) {
        this.finishCommand(id, clientError(error));
      }
    });
  }

  private finishCommand(id: string, issue?: ClientError): void {
    const pending = this.pendingCommands.get(id);
    if (pending === undefined) return;
    this.pendingCommands.delete(id);
    this.timers.clearTimeout(pending.timer);
    if (issue === undefined) pending.resolve(id);
    else pending.reject(issue);
  }

  private send(type: string, payload: unknown, duringHandshake = false, id = this.createCommandId()): string {
    const socket = this.socket;
    if (socket === null || socket.readyState !== 1 || (!duringHandshake && this.state.connection !== 'connected')) {
      throw new ClientError('not_connected', 'Reconnect to your room before sending.', true);
    }
    if (!duringHandshake && this.roomOperationActive) {
      throw new ClientError('request_in_progress', 'Wait for the current room request.');
    }
    if (!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(id)) {
      throw new ClientError('command_id_unavailable', 'This build is missing secure command ID support.');
    }
    const data = JSON.stringify({v: 1, type, command_id: id, payload});
    if ((socket.bufferedAmount ?? 0) + data.length * 3 > 65_536) {
      throw new ClientError('connection_busy', 'Your connection is catching up. Try again shortly.', true);
    }
    try {
      socket.send(data);
      return id;
    } catch {
      const issue = new ClientError('delivery_uncertain', 'Delivery could not be confirmed. Check the room before sending again.', true);
      this.connectionLost(this.generation, issue);
      throw issue;
    }
  }

  private scheduleHeartbeat(generation: number): void {
    if (this.heartbeatTimer !== null) this.timers.clearTimeout(this.heartbeatTimer);
    this.heartbeatTimer = this.timers.setTimeout(() => {
      this.heartbeatTimer = null;
      if (generation !== this.generation) return;
      if (this.now() - this.lastReceivedAt >= this.heartbeatIntervalMs * 2) {
        this.connectionLost(generation, new ClientError('connection_timeout', 'The room stopped responding. Reconnecting.', true));
        return;
      }
      try {
        this.send('ping', {client_time_ms: this.now()}, true);
        this.scheduleHeartbeat(generation);
      } catch (error) {
        this.connectionLost(generation, clientError(error));
      }
    }, this.heartbeatIntervalMs);
  }

  private connectionLost(generation: number, issue: ClientError): void {
    if (generation !== this.generation || !this.wantedConnection) return;
    this.closeSocket();
    if (issue.status === 403 || issue.status === 404 || issue.status === 410 || issue.code === 'room_unavailable') {
      this.clearRoom();
      this.update({error: new ClientError('room_unavailable', 'This room is no longer available. Create or join another room.')});
      return;
    }
    if (!issue.retryable || this.state.session === null || this.state.snapshot === null) {
      this.wantedConnection = false;
      this.update({connection: 'offline', error: issue});
      return;
    }
    this.update({connection: 'reconnecting', error: issue});
    const base = this.reconnectDelays[Math.min(this.reconnectAttempt, this.reconnectDelays.length - 1)]!;
    this.reconnectAttempt += 1;
    const jitter = Math.max(0, Math.min(1, this.random()));
    const delay = Math.round(base * (0.8 + jitter * 0.4));
    this.reconnectTimer = this.timers.setTimeout(() => {
      this.reconnectTimer = null;
      void this.connectSocket(true);
    }, delay);
  }

  private closeSocket(pendingIssue = new ClientError('delivery_uncertain', 'Delivery could not be confirmed. Check the room before sending again.', true)): void {
    this.generation += 1;
    for (const id of this.pendingCommands.keys()) this.finishCommand(id, pendingIssue);
    for (const timer of [this.reconnectTimer, this.heartbeatTimer, this.handshakeTimer]) {
      if (timer !== null) this.timers.clearTimeout(timer);
    }
    this.reconnectTimer = null;
    this.heartbeatTimer = null;
    this.handshakeTimer = null;
    const socket = this.socket;
    this.socket = null;
    if (socket !== null) {
      socket.onopen = null;
      socket.onmessage = null;
      socket.onclose = null;
      socket.onerror = null;
      socket.close();
    }
  }

  private clearRoom(): void {
    this.wantedConnection = false;
    this.closeSocket(new ClientError('cancelled', 'This request is no longer active.'));
    this.roomOperation += 1;
    this.roomOperationActive = false;
    this.everConnected = false;
    this.reconnectAttempt = 0;
    this.update({snapshot: null, inviteCode: null, connection: 'offline'});
  }

  private update(change: Partial<ClientState>): void {
    this.state = {...this.state, ...change};
    for (const listener of this.listeners) listener(this.state);
  }

  private async action<T>(run: () => Promise<T>): Promise<T> {
    this.update({error: null});
    try {
      return await run();
    } catch (error) {
      const issue = clientError(error);
      if (issue.code !== 'cancelled') this.update({error: issue});
      throw issue;
    }
  }
}
