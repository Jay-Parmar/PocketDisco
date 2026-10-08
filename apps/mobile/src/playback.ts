import {
  parsePlaybackState,
  type PlaybackCapabilities,
  type PlaybackProvider,
  type TimedPlaybackState,
} from '../../../packages/domain/src/playback';

export const demoTrack = {
  id: 'generated-pulse',
  title: 'Pocket pulse',
  durationMs: 24000,
};

export const emptyPlayback: TimedPlaybackState = {
  itemId: null,
  status: 'idle',
  positionMs: 0,
  durationMs: 0,
  sampledAtMonotonicMs: 0,
  scheduledStartMonotonicMs: null,
  errorCode: null,
};

type NativePlayer = {
  getCapabilities(): Promise<unknown>;
  prepare(itemId: string, positionMs: number): Promise<unknown>;
  playAt(monotonicTimeMs: number, positionMs: number): Promise<unknown>;
  pause(): Promise<unknown>;
  seek(positionMs: number): Promise<unknown>;
  getTimedState(): Promise<unknown>;
  disconnect(): Promise<void>;
};

const messages: Record<string, string> = {
  playback_failed: 'Could not play the demo. Try again.',
  invalid_position: 'Choose a position within this demo.',
  invalid_schedule: 'Could not schedule playback. Tap play to retry.',
  missed_deadline: 'The start was delayed. Tap play to retry.',
  not_ready: 'The demo is not ready. Tap play to retry.',
  not_foreground: 'Return to PocketDisco to play the demo.',
  prepare_timeout: 'The demo took too long to load. Tap play to retry.',
  audio_focus: 'Audio is in use. Tap play when it is available.',
};

class PlaybackError extends Error {
  constructor(readonly code: string) {
    super(messages[code] ?? messages.playback_failed);
  }
}

function safeError(error: unknown): PlaybackError {
  const code =
    typeof error === 'object' && error !== null && 'code' in error
      ? error.code
      : undefined;
  return new PlaybackError(
    typeof code === 'string' && Object.hasOwn(messages, code)
      ? code
      : 'playback_failed',
  );
}

export function playbackMessage(error: unknown): string {
  return safeError(error).message;
}

function validateTime(value: number, code: string) {
  if (!Number.isFinite(value) || value < 0 || value > Number.MAX_SAFE_INTEGER) {
    throw new PlaybackError(code);
  }
}

export function createPlaybackProvider(native: NativePlayer): PlaybackProvider {
  async function observe(action: () => Promise<unknown>) {
    try {
      return parsePlaybackState(await action());
    } catch (error) {
      throw safeError(error);
    }
  }

  return {
    async getCapabilities() {
      try {
        const value = await native.getCapabilities();
        const keys = [
          'canSchedule',
          'canSeek',
          'canReportPosition',
          'canRateAdjust',
          'canBackground',
        ] as const;
        if (
          typeof value !== 'object' ||
          value === null ||
          !keys.every(
            key =>
              key in value &&
              typeof value[key as keyof typeof value] === 'boolean',
          )
        ) {
          throw new PlaybackError('playback_failed');
        }
        const capabilities = value as PlaybackCapabilities;
        return Object.fromEntries(
          keys.map(key => [key, capabilities[key]]),
        ) as PlaybackCapabilities;
      } catch (error) {
        throw safeError(error);
      }
    },
    async prepare(itemId, positionMs) {
      validateTime(positionMs, 'invalid_position');
      return observe(() => native.prepare(itemId, positionMs));
    },
    async playAt(monotonicTimeMs, positionMs) {
      validateTime(monotonicTimeMs, 'invalid_schedule');
      validateTime(positionMs, 'invalid_position');
      return observe(() => native.playAt(monotonicTimeMs, positionMs));
    },
    pause: () => observe(() => native.pause()),
    async seek(positionMs) {
      validateTime(positionMs, 'invalid_position');
      return observe(() => native.seek(positionMs));
    },
    getTimedState: () => observe(() => native.getTimedState()),
    async disconnect() {
      try {
        await native.disconnect();
      } catch (error) {
        throw safeError(error);
      }
    },
  };
}
