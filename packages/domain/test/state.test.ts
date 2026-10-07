import assert from 'node:assert/strict';
import test from 'node:test';
import {applySnapshot, parseInviteCode, parseServerEvent, parseSession, parseSnapshot, ProtocolError, reduceRoom} from '../src/index.ts';
import {messageId, otherRoomId, session, snapshot, snapshotEvent, userId} from './fixtures.ts';

test('snapshots retain supported data without sharing server-owned objects', () => {
  const wire = {...snapshot(), next_field: 'ignored'};
  const parsed = parseSnapshot(wire);
  assert.deepEqual(parsed, snapshot());
  assert.notEqual(parsed, wire);
  assert.notEqual(parsed.members[0], wire.members[0]);
});

test('older and duplicate revisions leave state unchanged', () => {
  const current = snapshot(5);
  assert.equal(applySnapshot(current, snapshot(4)), current);
  assert.equal(applySnapshot(current, snapshot(5)), current);
  assert.equal(reduceRoom(current, snapshotEvent(3)), current);
});

test('a complete snapshot closes any revision gap', () => {
  const next = snapshotEvent(12);
  assert.equal(reduceRoom(snapshot(2), next), next.payload);
  assert.equal(reduceRoom(null, next), next.payload);
});

test('room events cannot move a member into another room', () => {
  const current = snapshot();
  const event = snapshotEvent(2);
  event.room_id = otherRoomId;
  event.payload.room_id = otherRoomId;
  assert.equal(reduceRoom(current, event), current);
  assert.equal(applySnapshot(current, event.payload), event.payload);
});

test('snapshot envelopes must agree with their payload', () => {
  assert.throws(() => parseServerEvent({...snapshotEvent(), revision: 2}), ProtocolError);
  assert.throws(() => parseServerEvent({...snapshotEvent(), room_id: otherRoomId}), ProtocolError);
});

test('unknown additive events are ignored but protocol versions are checked', () => {
  assert.deepEqual(parseServerEvent({v: 1, type: 'room.future', payload: {}}), {v: 1, type: 'unknown'});
  assert.throws(() => parseServerEvent({...snapshotEvent(), v: 2}), ProtocolError);
});

test('invalid snapshots fail before state changes', () => {
  const cases = [
    {...snapshot(), revision: -1},
    {...snapshot(), revision: 0.5},
    {...snapshot(), revision: Number.MAX_SAFE_INTEGER + 1},
    {...snapshot(), room_id: '../other'},
    {...snapshot(), name: ' '},
    {...snapshot(), provider: 'spotify'},
    {...snapshot(), members: []},
    {...snapshot(), members: Array(26).fill(snapshot().members[0])},
    {...snapshot(), host_id: otherRoomId},
    {...snapshot(), members: [...snapshot().members, ...snapshot().members]},
    {...snapshot(), members: [{...snapshot().members[0], ready: 'true'}]},
    {...snapshot(), members: [{...snapshot().members[0], role: 'moderator'}]},
    {...snapshot(), messages: Array(51).fill({})},
  ];
  for (const value of cases) assert.throws(() => parseSnapshot(value), ProtocolError);
});

test('chat snapshots enforce body bounds and unique IDs', () => {
  const message = {id: messageId, user_id: userId, display_name: 'River', body: 'hello', created_at_ms: 123};
  assert.equal(parseSnapshot({...snapshot(), messages: [message]}).messages.length, 1);
  assert.throws(() => parseSnapshot({...snapshot(), messages: [message, message]}), ProtocolError);
  for (const body of ['', ' ', 'a'.repeat(1001)]) {
    assert.throws(() => parseSnapshot({...snapshot(), messages: [{...message, body}]}), ProtocolError);
  }
  assert.equal(parseSnapshot({...snapshot(), messages: [{...message, body: '🎵'.repeat(1000)}]}).messages.length, 1);
});

test('session validation bounds secrets and validates the user', () => {
  assert.deepEqual(parseSession(session()), session());
  for (const value of [null, {}, {...session(), access_token: ''}, {...session(), expires_in: 0}, {...session(), user: {}}]) {
    assert.throws(() => parseSession(value), ProtocolError);
  }
});

test('invite codes are case-insensitive, trimmed, and path-safe', () => {
  assert.equal(parseInviteCode('  12abcdefghjk  '), '12ABCDEFGHJK');
  for (const code of ['https://example.test/join', '12ABCDEFGHJL', '../room', '', 'A'.repeat(13)]) {
    assert.throws(() => parseInviteCode(code), ProtocolError);
  }
});

test('hello chooses bounded heartbeat intervals', () => {
  assert.deepEqual(parseServerEvent({v: 1, type: 'hello'}), {v: 1, type: 'hello', heartbeat_interval_ms: 15000});
  assert.equal(parseServerEvent({v: 1, type: 'hello', payload: {heartbeat_interval_ms: 5000}}).type, 'hello');
  assert.throws(() => parseServerEvent({v: 1, type: 'hello', payload: {heartbeat_interval_ms: 0}}), ProtocolError);
});
