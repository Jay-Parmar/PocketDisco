import React from 'react';
import { AppState } from 'react-native';
import type { AppStateStatus } from 'react-native';
import ReactTestRenderer, { act } from 'react-test-renderer';

import type {
  ClientState,
  RoomSnapshot,
  Session,
} from '../../../packages/domain/src/types';
import { ClientError } from '../../../packages/domain/src/errors';
import DeviceSession from '../src/native/NativeDeviceSession';
import { useMobileRoom } from '../src/useMobileRoom';

jest.mock('../src/native/NativeDeviceSession', () => ({
  getSettings: jest.fn(),
  randomId: jest.fn(),
  load: jest.fn(),
  save: jest.fn(),
  clear: jest.fn(),
}));

jest.mock('../../../packages/domain/src/client', () => ({
  RoomClient: jest.fn((options: { baseUrl: string }) => {
    const client = new mockRoomClient(options.baseUrl);
    mockClients.push(client);
    return client;
  }),
}));

const session: Session = {
  access_token: 'test-access',
  refresh_token: 'test-refresh',
  expires_in: 900,
  user: { id: '10000000-0000-4000-8000-000000000001', display_name: 'Sam' },
};
const snapshot: RoomSnapshot = {
  room_id: '20000000-0000-4000-8000-000000000001',
  name: 'Night shift',
  revision: 1,
  provider: 'generated_demo',
  host_id: session.user.id,
  members: [
    {
      user_id: session.user.id,
      display_name: 'Sam',
      role: 'host',
      ready: false,
      connected: true,
    },
  ],
  messages: [],
};
const baseUrl = 'http://127.0.0.1:8000';
const inviteCode = '0123456789AB';
const mockClients: mockRoomClient[] = [];
const mockResume = jest.fn<
  Promise<RoomSnapshot>,
  [mockRoomClient, string, string | undefined]
>();

class mockRoomClient {
  private state: ClientState = {
    session: null,
    snapshot: null,
    inviteCode: null,
    connection: 'offline',
    error: null,
  };
  private listeners = new Set<(state: ClientState) => void>();
  constructor(readonly url: string) {}
  getState = () => this.state;
  subscribe = (listener: (state: ClientState) => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };
  emit(change: Partial<ClientState>) {
    this.state = { ...this.state, ...change };
    this.listeners.forEach(listener => listener(this.state));
  }
  disconnect = jest.fn(() => this.emit({ connection: 'offline' }));
  restoreSession = jest.fn((restored: Session) => {
    this.emit({ session: null, snapshot: null, inviteCode: null });
    this.emit({ session: restored, error: null });
  });
  resumeRoom = jest.fn((roomId: string, code?: string) =>
    mockResume(this, roomId, code),
  );
  reconnect = jest.fn(async () => {
    this.emit({ connection: 'connected', error: null });
  });
  signOut = jest.fn(() =>
    this.emit({
      session: null,
      snapshot: null,
      inviteCode: null,
      connection: 'offline',
    }),
  );
  leaveRoom = jest.fn(async () => {
    this.emit({ snapshot: null, inviteCode: null, connection: 'offline' });
  });
  guest = jest.fn(async (displayName: string) => {
    const next = {
      ...session,
      user: { ...session.user, display_name: displayName },
    };
    this.emit({ session: next, snapshot: null, inviteCode: null, error: null });
    return next;
  });
  createRoom = jest.fn(async () => {
    this.emit({ snapshot, inviteCode, connection: 'connected' });
    return snapshot;
  });
  joinRoom = jest.fn(async () => {
    this.emit({ snapshot, inviteCode, connection: 'connected' });
    return snapshot;
  });
  setReady = jest.fn().mockResolvedValue('command-id');
  sendChat = jest.fn().mockResolvedValue('command-id');
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((accept, decline) => {
    resolve = accept;
    reject = decline;
  });
  return { promise, resolve, reject };
}

function saved(roomId: string | null = snapshot.room_id) {
  return JSON.stringify({
    baseUrl,
    session,
    roomId,
    inviteCode: roomId ? inviteCode : null,
  });
}

function succeedResume(client: mockRoomClient, _roomId: string, code?: string) {
  client.emit({
    snapshot,
    inviteCode: code ?? null,
    connection: 'connected',
    error: null,
  });
  return Promise.resolve(snapshot);
}

const native = jest.mocked(DeviceSession);
let room: ReturnType<typeof useMobileRoom>;
let screen: ReactTestRenderer.ReactTestRenderer | undefined;
let appListener: ((status: AppStateStatus) => void) | undefined;

function Probe() {
  room = useMobileRoom();
  return null;
}
async function render() {
  await act(async () => {
    screen = ReactTestRenderer.create(<Probe />);
  });
}
async function changeState(status: AppStateStatus) {
  await act(async () => {
    AppState.currentState = status;
    appListener?.(status);
  });
}
function currentClient() {
  return mockClients[mockClients.length - 1]!;
}
function lastRecord(fallback = saved()) {
  const calls = native.save.mock.calls;
  return JSON.parse(calls[calls.length - 1]?.[0] ?? fallback) as {
    baseUrl: string;
    session: Session;
    roomId: string | null;
    inviteCode: string | null;
  };
}

beforeEach(() => {
  jest.clearAllMocks();
  mockClients.length = 0;
  mockResume.mockReset().mockImplementation(succeedResume);
  native.getSettings.mockReturnValue({
    apiUrl: baseUrl,
    allowLocalServer: true,
  });
  native.load.mockResolvedValue(saved());
  native.save.mockResolvedValue(undefined);
  native.clear.mockResolvedValue(undefined);
  AppState.currentState = 'active';
  jest
    .spyOn(AppState, 'addEventListener')
    .mockImplementation((_type, listener) => {
      appListener = listener;
      return {
        remove: () => {
          appListener = undefined;
        },
      };
    });
});

afterEach(async () => {
  await act(async () => screen?.unmount());
  screen = undefined;
  jest.restoreAllMocks();
});

test('restores a saved room and its invite', async () => {
  await render();
  expect(room.booting).toBe(false);
  expect(room.state.snapshot).toEqual(snapshot);
  expect(room.state.inviteCode).toBe(inviteCode);
  expect(mockResume).toHaveBeenCalledWith(
    currentClient(),
    snapshot.room_id,
    inviteCode,
  );
  expect(room.canResume).toBe(false);
});

test('defers a load completed in the background until active', async () => {
  const load = deferred<string | null>();
  native.load.mockReturnValue(load.promise);
  await render();
  await changeState('background');
  await act(async () => load.resolve(saved()));
  expect(room.booting).toBe(false);
  expect(room.canResume).toBe(true);
  expect(mockResume).not.toHaveBeenCalled();
  await changeState('active');
  expect(mockResume).toHaveBeenCalledTimes(1);
  expect(room.state.connection).toBe('connected');
});

test('does not hydrate or open a connection after unmount', async () => {
  const load = deferred<string | null>();
  native.load.mockReturnValue(load.promise);
  await render();
  await act(async () => screen!.unmount());
  screen = undefined;
  await act(async () => load.resolve(saved()));
  expect(mockClients).toHaveLength(1);
  expect(mockResume).not.toHaveBeenCalled();
  expect(native.save).not.toHaveBeenCalled();
});

test('persists rotated tokens before a room resume finishes', async () => {
  const resume = deferred<RoomSnapshot>();
  const rotated = {
    ...session,
    access_token: 'rotated-access',
    refresh_token: 'rotated-refresh',
  };
  mockResume.mockImplementation(async client => {
    client.emit({ session: rotated });
    return resume.promise;
  });
  await render();
  expect(room.booting).toBe(true);
  expect(lastRecord()).toEqual({
    baseUrl,
    session: rotated,
    roomId: snapshot.room_id,
    inviteCode,
  });
  await act(async () => screen!.unmount());
  screen = undefined;
  await act(async () => resume.resolve(snapshot));
  expect(lastRecord().session.refresh_token).toBe('rotated-refresh');
});

test('retains room and invite after a failed resume and can retry', async () => {
  mockResume.mockImplementationOnce(async client => {
    client.emit({ error: new ClientError('request_timeout', 'Offline', true) });
    throw new Error('Offline');
  });
  await render();
  expect(room.state.snapshot).toBeNull();
  expect(room.canResume).toBe(true);
  expect(lastRecord().roomId).toBe(snapshot.room_id);
  expect(lastRecord().inviteCode).toBe(inviteCode);
  await act(async () => room.retry());
  expect(mockResume).toHaveBeenCalledTimes(2);
  expect(room.state.snapshot).toEqual(snapshot);
  expect(room.state.inviteCode).toBe(inviteCode);
});

test('reconciles a background and foreground change during resume', async () => {
  const resume = deferred<RoomSnapshot>();
  mockResume.mockImplementation(async (client, _id, code) => {
    await resume.promise;
    client.emit({ snapshot, inviteCode: code ?? null, connection: 'offline' });
    return snapshot;
  });
  await render();
  const client = currentClient();
  await changeState('background');
  await changeState('active');
  await act(async () => resume.resolve(snapshot));
  expect(mockResume).toHaveBeenCalledTimes(1);
  expect(client.reconnect).toHaveBeenCalledTimes(1);
  expect(room.state.connection).toBe('connected');
});

test('forgets a saved room when the server rejects membership', async () => {
  mockResume.mockImplementationOnce(async client => {
    const failure = new ClientError(
      'not_member',
      'No longer a member.',
      false,
      403,
    );
    client.emit({ error: failure });
    throw failure;
  });
  await render();
  expect(room.canResume).toBe(false);
  expect(lastRecord().roomId).toBeNull();
  expect(lastRecord().inviteCode).toBeNull();
});

test('clears saved room references when a live room ends', async () => {
  await render();
  await act(async () =>
    currentClient().emit({
      snapshot: null,
      inviteCode: null,
      connection: 'offline',
      error: new ClientError('room_closed', 'Room closed.'),
    }),
  );
  expect(lastRecord().roomId).toBeNull();
  expect(lastRecord().inviteCode).toBeNull();
  expect(room.canResume).toBe(false);
});

test('waits for successful leave to be saved before clearing busy state', async () => {
  await render();
  const write = deferred<void>();
  native.save.mockReturnValueOnce(write.promise);
  let leaving!: Promise<void>;
  await act(async () => {
    leaving = room.leave();
  });
  expect(room.busy).toBe('room');
  expect(lastRecord().roomId).toBeNull();
  expect(lastRecord().inviteCode).toBeNull();
  await act(async () => {
    write.resolve(undefined);
    await leaving;
  });
  expect(room.busy).toBeNull();
  expect(room.canResume).toBe(false);
});

test('clears credentials and room references on authentication expiry', async () => {
  await render();
  await act(async () =>
    currentClient().emit({
      session: null,
      snapshot: null,
      inviteCode: null,
      error: new ClientError('session_expired', 'Choose a name again.'),
    }),
  );
  expect(native.clear).toHaveBeenCalledTimes(1);
  expect(room.canResume).toBe(false);
});

test('clears malformed saved data instead of failing every launch', async () => {
  native.load.mockResolvedValue('{not-json');
  await render();
  expect(native.clear).toHaveBeenCalledTimes(1);
  expect(room.booting).toBe(false);
  expect(room.state.session).toBeNull();
});

test('does not attach a new server until old credentials are cleared', async () => {
  native.load.mockResolvedValue(saved(null));
  await render();
  const clear = deferred<void>();
  native.clear.mockReturnValueOnce(clear.promise);
  let switching!: Promise<boolean>;
  await act(async () => {
    switching = room.saveServer('http://192.0.2.10:8000');
  });
  expect(room.baseUrl).toBe(baseUrl);
  expect(room.state.session).toBeNull();
  expect(currentClient().restoreSession).not.toHaveBeenCalled();
  await act(async () => {
    clear.resolve(undefined);
    await switching;
  });
  expect(room.baseUrl).toBe('http://192.0.2.10:8000');
  expect(room.state.session).toBeNull();
  expect(currentClient().restoreSession).not.toHaveBeenCalled();
});

test('keeps the old origin on a failed credential clear and supports retry', async () => {
  native.load.mockResolvedValue(saved(null));
  await render();
  native.clear.mockRejectedValueOnce(new Error('Storage unavailable'));
  await act(async () => {
    expect(await room.saveServer('http://192.0.2.10:8000')).toBe(false);
  });
  expect(room.baseUrl).toBe(baseUrl);
  expect(room.state.session).toBeNull();
  await act(async () => {
    expect(await room.saveServer('http://192.0.2.10:8000')).toBe(true);
  });
  expect(native.clear).toHaveBeenCalledTimes(2);
  expect(room.baseUrl).toBe('http://192.0.2.10:8000');
});

test('does not restore another production origin', async () => {
  native.getSettings.mockReturnValue({
    apiUrl: 'https://rooms.example.com',
    allowLocalServer: false,
  });
  await render();
  expect(mockResume).not.toHaveBeenCalled();
  expect(native.clear).toHaveBeenCalledTimes(1);
  expect(room.state.session).toBeNull();
});

test('accepts an unchanged server without clearing the current session', async () => {
  await render();
  await act(async () => {
    expect(await room.saveServer(baseUrl)).toBe(true);
  });
  expect(native.clear).not.toHaveBeenCalled();
  expect(room.state.session).toEqual(session);
});

test('rejects a server change while booting', async () => {
  const load = deferred<string | null>();
  native.load.mockReturnValue(load.promise);
  await render();
  await act(async () => {
    expect(await room.saveServer('http://192.0.2.10:8000')).toBe(false);
  });
  expect(native.clear).not.toHaveBeenCalled();
  await act(async () => load.resolve(null));
});

test('dismisses an existing client error but shows a new error', async () => {
  await render();
  await act(async () =>
    currentClient().emit({ error: new ClientError('slow', 'Please retry.') }),
  );
  expect(room.error).toBe('Please retry.');
  await act(async () => room.dismissError());
  expect(room.error).toBeNull();
  await act(async () =>
    currentClient().emit({
      error: new ClientError('offline', 'No connection.'),
    }),
  );
  expect(room.error).toBe('No connection.');
});
