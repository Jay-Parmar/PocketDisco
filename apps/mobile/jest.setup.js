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

jest.mock('./src/native/NativeAudioPlayback', () => {
  const idle = {
    itemId: null,
    status: 'idle',
    positionMs: 0,
    durationMs: 0,
    sampledAtMonotonicMs: 1000,
    scheduledStartMonotonicMs: null,
    errorCode: null,
  };
  const ready = {
    ...idle,
    itemId: 'generated-pulse',
    status: 'ready',
    durationMs: 24000,
  };
  return {
    __esModule: true,
    default: {
      getCapabilities: jest.fn(async () => ({
        canSchedule: true,
        canSeek: true,
        canReportPosition: true,
        canRateAdjust: false,
        canBackground: false,
      })),
      prepare: jest.fn(async () => ready),
      playAt: jest.fn(async deadline => ({
        ...ready,
        status: 'scheduled',
        scheduledStartMonotonicMs: deadline,
      })),
      pause: jest.fn(async () => ({ ...ready, status: 'paused' })),
      seek: jest.fn(async positionMs => ({
        ...ready,
        status: 'paused',
        positionMs,
      })),
      getTimedState: jest.fn(async () => idle),
      disconnect: jest.fn(async () => undefined),
    },
  };
});
