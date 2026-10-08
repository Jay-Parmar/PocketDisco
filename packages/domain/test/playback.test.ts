import assert from 'node:assert/strict';
import test from 'node:test';

import {parsePlaybackState} from '../src/playback.ts';

const state = {
  itemId: 'generated-pulse',
  status: 'ready',
  positionMs: 0,
  durationMs: 24000,
  sampledAtMonotonicMs: 5000,
  scheduledStartMonotonicMs: null,
  errorCode: null,
};

test('reads a timed player observation without treating it as audible sync', () => {
  assert.deepEqual(parsePlaybackState(state), state);
});

test('accepts an empty player and a pending native start', () => {
  assert.equal(parsePlaybackState({...state, itemId: null, status: 'idle', durationMs: 0}).status, 'idle');
  assert.equal(parsePlaybackState({...state, status: 'scheduled', scheduledStartMonotonicMs: 5500}).scheduledStartMonotonicMs, 5500);
});

for (const status of ['preparing', 'playing', 'paused', 'ended', 'error']) {
  test(`accepts the ${status} observation`, () => {
    assert.equal(parsePlaybackState({...state, status}).status, status);
  });
}

for (const [field, value] of [
  ['status', 'synchronized'],
  ['positionMs', -1],
  ['positionMs', 24001],
  ['durationMs', Infinity],
  ['sampledAtMonotonicMs', NaN],
  ['sampledAtMonotonicMs', -1],
  ['scheduledStartMonotonicMs', -1],
  ['scheduledStartMonotonicMs', 'soon'],
  ['itemId', 42],
  ['errorCode', {}],
] as const) {
  test(`rejects malformed ${field}: ${String(value)}`, () => {
    assert.throws(() => parsePlaybackState({...state, [field]: value}), /Invalid playback state/);
  });
}

test('rejects a scheduled state without a deadline', () => {
  assert.throws(() => parsePlaybackState({...state, status: 'scheduled'}), /Invalid playback state/);
});

test('rejects non-object responses', () => {
  for (const value of [null, [], 'ready', 1]) {
    assert.throws(() => parsePlaybackState(value), /Invalid playback state/);
  }
});

test('copies only known fields from native responses', () => {
  assert.deepEqual(parsePlaybackState({...state, extra: 'ignore'}), state);
});
