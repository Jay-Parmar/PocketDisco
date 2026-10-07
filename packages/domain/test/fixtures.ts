export const userId = '11111111-1111-4111-8111-111111111111';
export const roomId = '22222222-2222-4222-8222-222222222222';
export const otherRoomId = '33333333-3333-4333-8333-333333333333';
export const messageId = '44444444-4444-4444-8444-444444444444';

export function snapshot(revision = 1) {
  return {
    room_id: roomId,
    name: 'Evening room',
    revision,
    provider: 'generated_demo' as const,
    host_id: userId,
    members: [{
      user_id: userId, display_name: 'River', role: 'host' as const, ready: false, connected: true,
    }],
    messages: [],
  };
}

export function session(suffix = '') {
  return {
    access_token: `test-access${suffix}`,
    refresh_token: `test-refresh${suffix}`,
    expires_in: 900,
    user: {id: userId, display_name: 'River'},
  };
}

export function snapshotEvent(revision = 1) {
  return {
    v: 1 as const, type: 'room.snapshot' as const,
    room_id: roomId, revision, server_time_ms: 1234, payload: snapshot(revision),
  };
}
