/* eslint-env jest */

jest.mock(
  'react-native-safe-area-context',
  () => require('react-native-safe-area-context/jest/mock').default,
);

jest.mock('./src/native/NativeDeviceSession', () => ({
  __esModule: true,
  default: {
    getSettings: () => ({
      apiUrl: 'http://127.0.0.1:8000',
      allowLocalServer: true,
    }),
    randomId: () => '10000000-0000-4000-8000-000000000001',
    load: jest.fn(async () => null),
    save: jest.fn(async () => undefined),
    clear: jest.fn(async () => undefined),
  },
}));
