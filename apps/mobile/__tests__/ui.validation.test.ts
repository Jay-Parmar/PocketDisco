import {
  normalizeInviteCode,
  validateDisplayName,
  validateInviteCode,
  validateMessage,
  validateRoomName,
  validateServerUrl,
} from '../src/ui/validation';

describe('room form validation', () => {
  test.each(['', '   ', 'a'.repeat(41)])(
    'rejects an invalid name %p',
    value => {
      expect(validateDisplayName(value)).toBeDefined();
    },
  );

  test('accepts a trimmed display name', () => {
    expect(validateDisplayName('  Sam  ')).toBeUndefined();
  });

  test.each(['', '   ', 'a'.repeat(81)])(
    'rejects an invalid room %p',
    value => {
      expect(validateRoomName(value)).toBeDefined();
    },
  );

  test('accepts room names at the length limit', () => {
    expect(validateRoomName('a'.repeat(80))).toBeUndefined();
  });

  test('normalizes a shared invite code', () => {
    expect(normalizeInviteCode(' abcd2345efgh ')).toBe('ABCD2345EFGH');
    expect(validateInviteCode(' abcd2345efgh ')).toBeUndefined();
  });

  test.each(['123', 'ABCDEFGHIJKL', 'ABCD2345EFG!', 'ABCD2345EFGHJ'])(
    'rejects an invalid invite %p',
    value => {
      expect(validateInviteCode(value)).toBeDefined();
    },
  );

  test.each(['http://127.0.0.1:8000', 'https://rooms.example.com/'])(
    'accepts a server origin %p',
    value => {
      expect(validateServerUrl(value)).toBeUndefined();
    },
  );

  test.each([
    '',
    'rooms.example.com',
    'file:///rooms',
    'https://user:password@rooms.example.com',
    'https://rooms.example.com/v1',
    'https://rooms.example.com/?token=test',
    'https://rooms.example.com/#room',
  ])('rejects an invalid server origin %p', value => {
    expect(validateServerUrl(value)).toBeDefined();
  });

  test.each(['', '  ', 'a'.repeat(1001)])(
    'rejects an invalid message %p',
    value => {
      expect(validateMessage(value)).toBeDefined();
    },
  );

  test('accepts trimmed text and the message length limit', () => {
    expect(validateMessage('  hello  ')).toBeUndefined();
    expect(validateMessage('a'.repeat(1000))).toBeUndefined();
  });
});
