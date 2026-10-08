import React from 'react';
import { AppState, type AppStateStatus } from 'react-native';
import ReactTestRenderer, { act } from 'react-test-renderer';
import type {
  PlaybackProvider,
  TimedPlaybackState,
} from '../../../packages/domain/src/playback';
import { emptyPlayback } from '../src/playback';
import { useDemoPlayback } from '../src/useDemoPlayback';

const ready: TimedPlaybackState = {
  ...emptyPlayback,
  itemId: 'generated-pulse',
  status: 'ready',
  durationMs: 24000,
  sampledAtMonotonicMs: 5000,
};
let provider: jest.Mocked<PlaybackProvider>;
let controls: ReturnType<typeof useDemoPlayback>;
let screen: ReactTestRenderer.ReactTestRenderer | undefined;
let listener: ((status: AppStateStatus) => void) | undefined;

function Probe({ enabled = true }: { enabled?: boolean }) {
  controls = useDemoPlayback(provider, enabled);
  return null;
}

async function render(enabled = true) {
  await act(async () => {
    screen = ReactTestRenderer.create(<Probe enabled={enabled} />);
  });
}

async function lifecycle(status: AppStateStatus) {
  await act(async () => {
    AppState.currentState = status;
    listener?.(status);
  });
}

beforeEach(() => {
  jest.useFakeTimers();
  AppState.currentState = 'active';
  jest
    .spyOn(AppState, 'addEventListener')
    .mockImplementation((_event, callback) => {
      listener = callback;
      return { remove: jest.fn() };
    });
  provider = {
    getCapabilities: jest.fn().mockResolvedValue({
      canSchedule: true,
      canSeek: true,
      canReportPosition: true,
      canRateAdjust: false,
      canBackground: false,
    }),
    prepare: jest.fn().mockResolvedValue(ready),
    playAt: jest
      .fn()
      .mockResolvedValue({
        ...ready,
        status: 'scheduled',
        scheduledStartMonotonicMs: 5500,
      }),
    pause: jest.fn().mockResolvedValue({ ...ready, status: 'paused' }),
    seek: jest
      .fn()
      .mockResolvedValue({ ...ready, status: 'paused', positionMs: 5000 }),
    getTimedState: jest.fn().mockResolvedValue(emptyPlayback),
    disconnect: jest.fn().mockResolvedValue(undefined),
  };
});

afterEach(async () => {
  await act(async () => screen?.unmount());
  screen = undefined;
  listener = undefined;
  jest.restoreAllMocks();
  jest.useRealTimers();
});

test('mounting a room never prepares or starts audio', async () => {
  await render();
  expect(provider.prepare).not.toHaveBeenCalled();
  expect(provider.playAt).not.toHaveBeenCalled();
  expect(controls.state.status).toBe('idle');
});

test('an explicit tap prepares before requesting a native deadline', async () => {
  await render();
  await act(async () => controls.play());
  expect(provider.prepare).toHaveBeenCalledWith('generated-pulse', 0);
  expect(provider.playAt).toHaveBeenCalledWith(5500, 0);
  expect(controls.state.status).toBe('scheduled');
  expect(controls.busy).toBe(false);
});

test('resumes a paused item without reloading or losing its position', async () => {
  provider.getTimedState.mockResolvedValue({
    ...ready,
    status: 'paused',
    positionMs: 7000,
  });
  await render();
  await act(async () => controls.play());
  expect(provider.prepare).not.toHaveBeenCalled();
  expect(provider.playAt).toHaveBeenCalledWith(5500, 7000);
});

test('replays an ended item from its beginning', async () => {
  provider.getTimedState.mockResolvedValue({
    ...ready,
    status: 'ended',
    positionMs: 24000,
  });
  await render();
  await act(async () => controls.play());
  expect(provider.prepare).toHaveBeenCalledWith('generated-pulse', 0);
});

test('duplicate taps cannot prepare or schedule twice', async () => {
  await render();
  await act(async () => {
    await Promise.all([controls.play(), controls.play()]);
  });
  expect(provider.prepare).toHaveBeenCalledTimes(1);
  expect(provider.playAt).toHaveBeenCalledTimes(1);
});

test('backgrounding cancels playback and foregrounding does not resume it', async () => {
  await render();
  await act(async () => controls.play());
  await lifecycle('background');
  expect(provider.pause).toHaveBeenCalledTimes(1);
  expect(controls.available).toBe(false);
  await lifecycle('active');
  expect(provider.playAt).toHaveBeenCalledTimes(1);
  expect(controls.available).toBe(true);
});

test('a prepare reply after backgrounding cannot schedule playback', async () => {
  let finish!: (value: TimedPlaybackState) => void;
  provider.prepare.mockImplementation(
    () =>
      new Promise(resolve => {
        finish = resolve;
      }),
  );
  await render();
  let playing!: Promise<void>;
  await act(async () => {
    playing = controls.play();
  });
  await lifecycle('background');
  await act(async () => {
    finish(ready);
    await playing;
  });
  expect(provider.playAt).not.toHaveBeenCalled();
  expect(controls.busy).toBe(false);
});

test('leaving releases native resources and ignores a pending prepare', async () => {
  let finish!: (value: TimedPlaybackState) => void;
  provider.prepare.mockImplementation(
    () =>
      new Promise(resolve => {
        finish = resolve;
      }),
  );
  await render();
  let playing!: Promise<void>;
  await act(async () => {
    playing = controls.play();
  });
  await act(async () => {
    screen?.unmount();
    screen = undefined;
  });
  await act(async () => {
    finish(ready);
    await playing;
  });
  expect(provider.disconnect).toHaveBeenCalledTimes(1);
  expect(provider.playAt).not.toHaveBeenCalled();
  const reads = provider.getTimedState.mock.calls.length;
  await act(async () => {
    jest.advanceTimersByTime(5000);
  });
  expect(provider.getTimedState).toHaveBeenCalledTimes(reads);
});

test('disabling controls releases the player and blocks new starts', async () => {
  await render();
  await act(async () => {
    screen?.update(<Probe enabled={false} />);
  });
  await act(async () => controls.play());
  expect(provider.disconnect).toHaveBeenCalledTimes(1);
  expect(provider.playAt).not.toHaveBeenCalled();
  expect(controls.available).toBe(false);
});

test('seeking clamps to the item and never resumes audio automatically', async () => {
  provider.getTimedState.mockResolvedValue({
    ...ready,
    status: 'playing',
    positionMs: 20000,
  });
  await render();
  await act(async () => controls.seekBy(10000));
  expect(provider.seek).toHaveBeenCalledWith(24000);
  expect(provider.playAt).not.toHaveBeenCalled();
  await act(async () => controls.seekBy(-50000));
  expect(provider.seek).toHaveBeenLastCalledWith(0);
});

test('a stale poll cannot overwrite a newer command result', async () => {
  await render();
  let finish!: (value: TimedPlaybackState) => void;
  provider.getTimedState.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        finish = resolve;
      }),
  );
  await act(async () => {
    jest.advanceTimersByTime(500);
  });
  await act(async () => controls.play());
  await act(async () => finish(emptyPlayback));
  expect(controls.state.status).toBe('scheduled');
});

test('does not show raw native failure details', async () => {
  provider.prepare.mockRejectedValue(new Error('/private/sensitive/path'));
  await render();
  await act(async () => controls.play());
  expect(controls.error).toBe('Could not play the demo. Try again.');
});

test('uses observed playhead values without estimating audible position', async () => {
  await render();
  provider.getTimedState.mockResolvedValue({
    ...ready,
    status: 'playing',
    positionMs: 1234,
  });
  await act(async () => {
    jest.advanceTimersByTime(500);
  });
  expect(controls.state.positionMs).toBe(1234);
});

test('a slow poll does not create parallel polling loops after foregrounding', async () => {
  let finish!: (value: TimedPlaybackState) => void;
  provider.getTimedState.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        finish = resolve;
      }),
  );
  await render();
  await lifecycle('background');
  await lifecycle('active');
  expect(provider.getTimedState).toHaveBeenCalledTimes(1);
  await act(async () => finish(ready));
  await act(async () => {
    jest.advanceTimersByTime(500);
  });
  expect(provider.getTimedState).toHaveBeenCalledTimes(2);
});

test('a missing scheduling capability cannot fall back to JavaScript timing', async () => {
  provider.getCapabilities.mockResolvedValue({
    canSchedule: false,
    canSeek: true,
    canReportPosition: true,
    canRateAdjust: false,
    canBackground: false,
  });
  await render();
  await act(async () => controls.play());
  expect(provider.prepare).not.toHaveBeenCalled();
  expect(provider.playAt).not.toHaveBeenCalled();
  expect(controls.error).toBe('Could not play the demo. Try again.');
});
