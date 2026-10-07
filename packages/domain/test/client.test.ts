import assert from 'node:assert/strict';
import test from 'node:test';
import {RoomClient} from '../src/index.ts';
import type {RoomSocket} from '../src/index.ts';
import {messageId, otherRoomId, roomId, session, snapshot, snapshotEvent, userId} from './fixtures.ts';

class FakeSocket implements RoomSocket {
  readyState = 0;
  bufferedAmount = 0;
  onopen: RoomSocket['onopen'] = null;
  onmessage: RoomSocket['onmessage'] = null;
  onclose: RoomSocket['onclose'] = null;
  onerror: RoomSocket['onerror'] = null;
  sent: {type: string; command_id: string; payload: Record<string, unknown>}[] = [];
  closed = false;
  failSend = false;

  open() {
    this.readyState = 1;
    this.onopen?.();
  }

  receive(value: unknown) {
    this.onmessage?.({data: JSON.stringify(value)});
  }

  ack(id = this.sent.at(-1)!.command_id) {
    this.receive({v: 1, type: 'command.ack', payload: {command_id: id}});
  }

  send(value: string) {
    if (this.failSend) throw new Error('send failed');
    this.sent.push(JSON.parse(value));
  }

  close() {
    this.closed = true;
    this.readyState = 3;
    this.onclose?.({code: 1000});
  }

  lose(code = 1006) {
    this.readyState = 3;
    this.onclose?.({code});
  }
}

class FakeTimers {
  now = 0;
  nextId = 0;
  pending = new Map<number, {at: number; callback: () => void}>();

  setTimeout = (callback: () => void, delay: number) => {
    const id = ++this.nextId;
    this.pending.set(id, {at: this.now + delay, callback});
    return id;
  };

  clearTimeout = (id: number) => {this.pending.delete(id);};

  advance(ms: number) {
    const until = this.now + ms;
    while (true) {
      const next = [...this.pending.entries()].filter(([, task]) => task.at <= until).sort((a, b) => a[1].at - b[1].at)[0];
      if (next === undefined) break;
      this.now = next[1].at;
      this.pending.delete(next[0]);
      next[1].callback();
    }
    this.now = until;
  }
}

const settle = async () => {for (let i = 0; i < 30; i += 1) await Promise.resolve();};
const response = (body: unknown, status = 200) => ({ok: status < 400, status, json: async () => body});
const confirm = (socket: FakeSocket, pending: Promise<string>) => {
  socket.ack();
  return pending;
};

function setup(t: {after: (callback: () => void) => void}, override?: (url: string, options: RequestInit) => Promise<ReturnType<typeof response> | undefined>) {
  const sockets: FakeSocket[] = [];
  const socketUrls: string[] = [];
  const calls: {url: string; options: RequestInit}[] = [];
  const timers = new FakeTimers();
  let sequence = 0;
  const client = new RoomClient({
    baseUrl: 'https://example.test',
    timers,
    now: () => timers.now,
    random: () => 0.5,
    createCommandId: () => `55555555-5555-4555-8555-${(++sequence).toString().padStart(12, '0')}`,
    createSocket: url => {
      socketUrls.push(url);
      const socket = new FakeSocket();
      sockets.push(socket);
      return socket;
    },
    fetch: async (url, options) => {
      calls.push({url, options});
      const result = await override?.(url, options);
      if (result) return result;
      if (url.endsWith('/auth/guest')) return response(session());
      if (url.endsWith('/snapshot')) return response(snapshot(3));
      if (url.endsWith('/join')) return response({snapshot: snapshot()});
      if (url.endsWith('/leave')) return response({ok: true});
      if (url.endsWith('/tickets')) return response({ticket: 'one-use&ticket', expires_in: 60});
      return response({snapshot: snapshot(), invite_code: '12ABCDEFGHJK'});
    },
  });
  t.after(() => client.signOut());
  client.restoreSession(session());
  const connect = (revision = 1) => {
    const socket = sockets.at(-1)!;
    socket.open();
    socket.receive({v: 1, type: 'hello', payload: {heartbeat_interval_ms: 15000}});
    socket.receive(snapshotEvent(revision));
    return socket;
  };
  return {client, sockets, socketUrls, calls, timers, connect};
}

test('create waits for the authoritative socket snapshot before enabling controls', async t => {
  const {client, sockets, socketUrls, calls, connect} = setup(t);
  const result = await client.createRoom('  Evening room  ');
  assert.deepEqual(result, snapshot());
  assert.equal(client.getState().connection, 'connecting');
  await assert.rejects(client.sendChat('hello'), {code: 'not_connected'});
  assert.deepEqual(JSON.parse(calls[0].options.body), {name: 'Evening room'});
  assert.deepEqual(JSON.parse(calls[1].options.body), {room_id: roomId});
  assert.equal(socketUrls[0], 'wss://example.test/v1/realtime?ticket=one-use%26ticket');
  assert.equal(socketUrls[0].includes('test-access'), false);
  connect();
  assert.equal(client.getState().connection, 'connected');
  assert.equal(sockets[0].sent[0].type, 'sync.request');
});

test('join normalizes the invite and retains it for sharing', async t => {
  const {client, calls} = setup(t);
  await client.joinRoom('  12abcdefghjk  ');
  assert.equal(calls[0].url, 'https://example.test/v1/rooms/12ABCDEFGHJK/join');
  assert.equal(client.getState().inviteCode, '12ABCDEFGHJK');
  await assert.rejects(client.joinRoom('12ABCDEFGHJK'), {code: 'room_active'});
  assert.equal(calls.length, 2);
});

test('invalid invite, room name, and display name never reach the network', async t => {
  const {client, calls} = setup(t);
  await assert.rejects(client.joinRoom('../bad'), {code: 'invalid_invite'});
  await assert.rejects(client.createRoom(' '), {code: 'invalid_input'});
  await assert.rejects(client.guest('a'.repeat(41)), {code: 'invalid_input'});
  assert.equal(calls.length, 0);
});

test('readiness and chat wait for server state and have distinct command IDs', async t => {
  const {client, connect} = setup(t);
  await client.createRoom('Evening room');
  const socket = connect();
  const readyId = await confirm(socket, client.setReady(true));
  const chatId = await confirm(socket, client.sendChat('  Hello friends  '));
  assert.notEqual(readyId, chatId);
  assert.deepEqual(socket.sent.at(-2).payload, {ready: true});
  assert.deepEqual(socket.sent.at(-1).payload, {body: 'Hello friends'});
  assert.equal(client.getState().snapshot!.members[0].ready, false);
  assert.equal(client.getState().snapshot!.messages.length, 0);
  const event = snapshotEvent(2);
  event.payload.members[0].ready = true;
  event.payload.messages.push({id: messageId, user_id: userId, display_name: 'River', body: 'Hello friends', created_at_ms: 1234});
  socket.receive(event);
  assert.equal(client.getState().snapshot!.members[0].ready, true);
  assert.equal(client.getState().snapshot!.messages.length, 1);
});

test('stale revisions cannot overwrite the latest room', async t => {
  const {client, connect} = setup(t);
  await client.createRoom('Evening room');
  const socket = connect();
  socket.receive(snapshotEvent(8));
  const latest = client.getState().snapshot;
  socket.receive(snapshotEvent(2));
  socket.receive(snapshotEvent(8));
  assert.equal(client.getState().snapshot, latest);
  await client.refreshSnapshot();
  assert.equal(client.getState().snapshot, latest);
});

test('reconnect fetches current state and a fresh ticket without replaying chat', async t => {
  const {client, connect, timers, calls, sockets} = setup(t);
  await client.createRoom('Evening room');
  const first = connect();
  await confirm(first, client.sendChat('sent once'));
  first.lose();
  assert.equal(client.getState().connection, 'reconnecting');
  await assert.rejects(client.sendChat('do not queue me'), {code: 'not_connected'});
  timers.advance(500);
  await settle();
  assert.equal(sockets.length, 2);
  assert.equal(calls[2].url.endsWith('/snapshot'), true);
  assert.equal(calls[3].url.endsWith('/tickets'), true);
  connect(3);
  assert.equal(client.getState().connection, 'connected');
  assert.equal(first.sent.filter(item => item.type === 'chat.send').length, 1);
  assert.deepEqual(sockets[1].sent.map(item => item.type), ['sync.request']);
});

test('disconnect keeps the room but cancels timers and ignores old callbacks', async t => {
  const {client, connect, timers, sockets} = setup(t);
  await client.createRoom('Evening room');
  const first = connect();
  const oldMessage = first.onmessage!;
  client.disconnect();
  assert.equal(client.getState().connection, 'offline');
  assert.equal(client.getState().snapshot?.room_id, roomId);
  assert.equal(timers.pending.size, 0);
  oldMessage({data: JSON.stringify(snapshotEvent(100))});
  assert.equal(client.getState().snapshot?.revision, 1);
  timers.advance(60_000);
  assert.equal(sockets.length, 1);
  await client.reconnect();
  connect(3);
  assert.equal(client.getState().connection, 'connected');
});

test('leave clears membership only after the server accepts', async t => {
  const {client, connect, timers, calls} = setup(t);
  await client.createRoom('Evening room');
  const socket = connect();
  await client.leaveRoom();
  assert.equal(calls.at(-1).url, `https://example.test/v1/rooms/${roomId}/leave`);
  assert.equal(client.getState().snapshot, null);
  assert.equal(client.getState().inviteCode, null);
  assert.equal(client.getState().connection, 'offline');
  assert.equal(socket.closed, true);
  assert.equal(timers.pending.size, 0);
  assert.deepEqual(client.getState().session, session());
});

test('a failed leave preserves the room for retry', async t => {
  const {client, connect} = setup(t, async url => url.endsWith('/leave')
    ? response({detail: {code: 'unavailable', message: 'Try again later.'}}, 503) : undefined);
  await client.createRoom('Evening room');
  connect();
  await assert.rejects(client.leaveRoom(), {code: 'unavailable'});
  assert.equal(client.getState().snapshot?.room_id, roomId);
  assert.equal(client.getState().connection, 'connected');
});

test('closed membership stops recovery and returns to the room picker', async t => {
  const {client, connect, timers, sockets} = setup(t, async url => url.endsWith('/snapshot')
    ? response({detail: {code: 'not_member', message: 'Room closed.'}}, 403) : undefined);
  await client.createRoom('Evening room');
  connect().lose();
  timers.advance(500);
  await settle();
  assert.equal(client.getState().snapshot, null);
  assert.equal(client.getState().error?.code, 'room_unavailable');
  assert.equal(timers.pending.size, 0);
  assert.equal(sockets.length, 1);
});

test('protocol mismatch stops retrying without discarding the room', async t => {
  const {client, sockets, timers} = setup(t);
  await client.createRoom('Evening room');
  sockets[0].open();
  sockets[0].receive({v: 2, type: 'hello'});
  assert.equal(client.getState().connection, 'offline');
  assert.equal(client.getState().error?.code, 'invalid_response');
  assert.equal(client.getState().snapshot?.room_id, roomId);
  assert.equal(timers.pending.size, 0);
});

test('socket snapshots before hello or for another room are rejected', async t => {
  const {client, sockets, connect} = setup(t);
  await client.createRoom('Evening room');
  sockets[0].open();
  sockets[0].receive(snapshotEvent());
  assert.equal(client.getState().connection, 'offline');
  await client.reconnect();
  const socket = connect(3);
  const event = snapshotEvent(4);
  event.room_id = otherRoomId;
  event.payload.room_id = otherRoomId;
  socket.receive(event);
  assert.equal(client.getState().connection, 'offline');
  assert.equal(client.getState().snapshot?.room_id, roomId);
});

test('slow sockets reject new messages rather than building an unbounded queue', async t => {
  const {client, connect} = setup(t);
  await client.createRoom('Evening room');
  const socket = connect();
  socket.bufferedAmount = 65_000;
  await assert.rejects(client.sendChat('a'.repeat(1000)), {code: 'connection_busy'});
  assert.equal(socket.sent.length, 1);
  socket.bufferedAmount = 0;
  await confirm(socket, client.sendChat('works now'));
  assert.equal(socket.sent.length, 2);
});

test('uncertain delivery is surfaced and never resent automatically', async t => {
  const {client, connect, timers, sockets} = setup(t);
  await client.createRoom('Evening room');
  const socket = connect();
  socket.failSend = true;
  await assert.rejects(client.sendChat('maybe delivered'), {code: 'delivery_uncertain'});
  assert.equal(client.getState().connection, 'reconnecting');
  timers.advance(500);
  await settle();
  connect(3);
  assert.deepEqual(sockets[1].sent.map(item => item.type), ['sync.request']);
});

test('heartbeat detects half-open connections and ping uses no persisted queue', async t => {
  const {client, connect, timers} = setup(t);
  await client.createRoom('Evening room');
  const socket = connect();
  timers.advance(15_000);
  assert.deepEqual(socket.sent.at(-1).payload, {client_time_ms: 15000});
  socket.receive({v: 1, type: 'pong', server_time_ms: 15001});
  timers.advance(15_000);
  assert.equal(client.getState().connection, 'connected');
  timers.advance(15_000);
  assert.equal(client.getState().connection, 'reconnecting');
  assert.equal(socket.closed, true);
});

test('initial snapshot timeout reconnects instead of enabling stale controls', async t => {
  const {client, sockets, timers} = setup(t);
  await client.createRoom('Evening room');
  sockets[0].open();
  sockets[0].receive({v: 1, type: 'hello'});
  timers.advance(10_000);
  assert.equal(client.getState().connection, 'reconnecting');
  assert.equal(client.getState().error?.code, 'connection_timeout');
});

test('server errors are visible and subscription teardown stops notifications', async t => {
  const {client, connect} = setup(t);
  let updates = 0;
  const unsubscribe = client.subscribe(() => {updates += 1;});
  await client.createRoom('Evening room');
  const socket = connect();
  socket.receive({v: 1, type: 'error', payload: {code: 'rate_limited', message: 'Please wait.'}});
  assert.equal(client.getState().error?.message, 'Please wait.');
  unsubscribe();
  const before = updates;
  client.disconnect();
  assert.equal(updates, before);
});

test('sign out clears all local session and room state', async t => {
  const {client, connect, timers} = setup(t);
  await client.createRoom('Evening room');
  connect();
  client.signOut();
  assert.deepEqual(client.getState(), {session: null, snapshot: null, inviteCode: null, connection: 'offline', error: null});
  assert.equal(timers.pending.size, 0);
  await assert.rejects(client.createRoom('Evening room'), {code: 'not_authenticated'});
});

test('terminal socket close codes stop retrying', async t => {
  const {client, connect, timers} = setup(t);
  await client.createRoom('Evening room');
  connect().lose(1008);
  assert.equal(client.getState().connection, 'offline');
  assert.equal(timers.pending.size, 0);
  await client.reconnect();
  connect(3).lose(4403);
  assert.equal(client.getState().snapshot, null);
  assert.equal(timers.pending.size, 0);
});

test('chat completes only after the server confirms its commit', async t => {
  const {client, connect} = setup(t);
  await client.createRoom('Evening room');
  const socket = connect();
  let confirmed = false;
  const pending = client.sendChat('Keep this draft until confirmed').then(id => {confirmed = true; return id;});
  await settle();
  assert.equal(confirmed, false);
  socket.ack('99999999-9999-4999-8999-999999999999');
  await settle();
  assert.equal(confirmed, false);
  const id = socket.sent.at(-1).command_id;
  socket.ack(id);
  assert.equal(await pending, id);
  assert.equal(confirmed, true);
  socket.ack(id);
  assert.equal(client.getState().connection, 'connected');
});

test('correlated server errors reject the correct pending command', async t => {
  const {client, connect} = setup(t);
  await client.createRoom('Evening room');
  const socket = connect();
  const rejected = client.sendChat('too soon');
  const firstId = socket.sent.at(-1).command_id;
  const accepted = client.setReady(true);
  const secondId = socket.sent.at(-1).command_id;
  socket.receive({v: 1, type: 'error', payload: {code: 'rate_limited', message: 'Please wait.', command_id: firstId}});
  await assert.rejects(rejected, {code: 'rate_limited'});
  socket.ack(secondId);
  assert.equal(await accepted, secondId);
});

test('missing acknowledgements reject with uncertain delivery and no replay', async t => {
  const {client, connect, timers} = setup(t);
  await client.createRoom('Evening room');
  const socket = connect();
  const pending = client.sendChat('awaiting confirmation');
  const rejected = assert.rejects(pending, {code: 'delivery_uncertain'});
  timers.advance(10_000);
  await rejected;
  assert.equal(socket.sent.filter(item => item.type === 'chat.send').length, 1);
  assert.equal(client.getState().connection, 'connected');
});

test('disconnect rejects pending acknowledgements and removes their timers', async t => {
  const {client, connect, timers} = setup(t);
  await client.createRoom('Evening room');
  connect();
  const pending = client.sendChat('maybe received');
  client.disconnect();
  await assert.rejects(pending, {code: 'delivery_uncertain'});
  assert.equal(timers.pending.size, 0);
});

test('pending acknowledgements are bounded to eight commands', async t => {
  const {client, connect} = setup(t);
  await client.createRoom('Evening room');
  const socket = connect();
  const pending = Array.from({length: 8}, (_, i) => client.sendChat(`Message ${i}`));
  await assert.rejects(client.sendChat('one too many'), {code: 'connection_busy'});
  assert.equal(socket.sent.filter(item => item.type === 'chat.send').length, 8);
  for (const command of socket.sent.filter(item => item.type === 'chat.send')) socket.ack(command.command_id);
  assert.equal((await Promise.all(pending)).length, 8);
});

test('a late create response does not open a socket after background disconnect', async t => {
  let release: (() => void) | undefined;
  const barrier = new Promise<void>(resolve => {release = resolve;});
  const {client, sockets} = setup(t, async url => {
    if (url.endsWith('/v1/rooms')) await barrier;
    return undefined;
  });
  const creating = client.createRoom('Evening room');
  client.disconnect();
  release!();
  await creating;
  assert.equal(client.getState().snapshot?.room_id, roomId);
  assert.equal(client.getState().connection, 'offline');
  assert.equal(sockets.length, 0);
});

test('process recovery uses membership rather than an expired invite', async t => {
  const {client, connect, calls} = setup(t);
  const restored = await client.resumeRoom(roomId);
  assert.equal(restored.revision, 3);
  assert.equal(calls[0].url, `https://example.test/v1/rooms/${roomId}/snapshot`);
  assert.equal(calls[1].url, 'https://example.test/v1/realtime/tickets');
  assert.equal(calls.some(call => call.url.endsWith('/join')), false);
  assert.equal(client.getState().inviteCode, null);
  connect(3);
  assert.equal(client.getState().connection, 'connected');
});

test('process recovery retains a saved invite only for sharing', async t => {
  const {client, calls} = setup(t);
  await client.resumeRoom(roomId, '12abcdefghjk');
  assert.equal(client.getState().inviteCode, '12ABCDEFGHJK');
  assert.equal(calls.some(call => call.url.includes('12ABCDEFGHJK')), false);
});

test('saved room identifiers cannot change the REST path', async t => {
  const {client, calls} = setup(t);
  await assert.rejects(client.resumeRoom('../other'), {code: 'invalid_room'});
  await assert.rejects(client.resumeRoom(roomId, 'not-an-invite'), {code: 'invalid_room'});
  assert.equal(calls.length, 0);
});

test('recovery rejects a snapshot belonging to a different room', async t => {
  const {client, sockets} = setup(t, async url => url.endsWith('/snapshot')
    ? response({...snapshot(), room_id: otherRoomId}) : undefined);
  await assert.rejects(client.resumeRoom(roomId), {code: 'invalid_response'});
  assert.equal(client.getState().snapshot, null);
  assert.equal(sockets.length, 0);
});

test('recovery keeps a removed member out of the room', async t => {
  const {client, sockets} = setup(t, async url => url.endsWith('/snapshot')
    ? response({detail: {code: 'not_member', message: 'This membership has ended.'}}, 403) : undefined);
  await assert.rejects(client.resumeRoom(roomId), {code: 'not_member'});
  assert.equal(client.getState().snapshot, null);
  assert.equal(sockets.length, 0);
});
