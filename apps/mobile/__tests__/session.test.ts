import { readSavedSession } from '../src/session';

const session = {
  access_token: 'test-access',
  refresh_token: 'test-refresh',
  expires_in: 900,
  user: { id: '10000000-0000-4000-8000-000000000001', display_name: 'Host' },
};

function saved(baseUrl: string, inviteCode: string | null = null) {
  return JSON.stringify({ baseUrl, session, inviteCode });
}

test('restores the configured secure server', () => {
  expect(
    readSavedSession(
      saved('https://example.com'),
      'https://example.com',
      false,
    ),
  ).toEqual({
    baseUrl: 'https://example.com',
    session,
    inviteCode: null,
    roomId: null,
  });
});

test('allows a local server only in internal builds', () => {
  const record = saved('http://127.0.0.1:8000', '0123456789AB');
  expect(readSavedSession(record, 'https://example.com', true).inviteCode).toBe(
    '0123456789AB',
  );
  expect(() =>
    readSavedSession(record, 'https://example.com', false),
  ).toThrow();
});

test('does not send a stored session to a changed production server', () => {
  expect(() =>
    readSavedSession(
      saved('https://other.example'),
      'https://example.com',
      false,
    ),
  ).toThrow();
});

test('retains the room and invite needed for recovery', () => {
  const roomId = '20000000-0000-4000-8000-000000000001';
  const record = JSON.stringify({
    baseUrl: 'https://example.com',
    session,
    roomId,
    inviteCode: '0123456789AB',
  });
  expect(readSavedSession(record, 'https://example.com', false)).toEqual({
    baseUrl: 'https://example.com',
    session,
    roomId,
    inviteCode: '0123456789AB',
  });
});

test.each([
  'https://example.com/?key=test',
  'https://example.com/#room',
  'file:///rooms',
])('rejects a saved URL with unsafe origin details %p', baseUrl => {
  expect(() =>
    readSavedSession(saved(baseUrl), 'https://example.com', true),
  ).toThrow();
});

test.each([
  'not-json',
  '{}',
  'null',
  saved('https://user:secret@example.com'),
  saved('https://example.com/path'),
])('rejects malformed saved data', value => {
  expect(() => readSavedSession(value, 'https://example.com', true)).toThrow();
});
