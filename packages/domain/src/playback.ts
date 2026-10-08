export type PlaybackStatus =
  | 'idle'
  | 'preparing'
  | 'ready'
  | 'scheduled'
  | 'playing'
  | 'paused'
  | 'ended'
  | 'error';

export type PlaybackCapabilities = {
  canSchedule: boolean;
  canSeek: boolean;
  canReportPosition: boolean;
  canRateAdjust: boolean;
  canBackground: boolean;
};

export type TimedPlaybackState = {
  itemId: string | null;
  status: PlaybackStatus;
  positionMs: number;
  durationMs: number;
  sampledAtMonotonicMs: number;
  scheduledStartMonotonicMs: number | null;
  errorCode: string | null;
};

export interface PlaybackProvider {
  getCapabilities(): Promise<PlaybackCapabilities>;
  prepare(itemId: string, positionMs: number): Promise<TimedPlaybackState>;
  // Deadlines use the provider's monotonic clock, not Unix time.
  playAt(monotonicTimeMs: number, positionMs: number): Promise<TimedPlaybackState>;
  pause(): Promise<TimedPlaybackState>;
  seek(positionMs: number): Promise<TimedPlaybackState>;
  getTimedState(): Promise<TimedPlaybackState>;
  disconnect(): Promise<void>;
}

const statuses = new Set<string>([
  'idle', 'preparing', 'ready', 'scheduled', 'playing', 'paused', 'ended', 'error',
]);

function milliseconds(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
    && value >= 0 && value <= Number.MAX_SAFE_INTEGER;
}

export function parsePlaybackState(value: unknown): TimedPlaybackState {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error('Invalid playback state');
  }
  const data = value as Record<string, unknown>;
  const {itemId, status, positionMs, durationMs, sampledAtMonotonicMs,
    scheduledStartMonotonicMs, errorCode} = data;
  if (
    !(itemId === null || (typeof itemId === 'string' && itemId.length > 0 && itemId.length <= 128))
    || typeof status !== 'string' || !statuses.has(status)
    || !milliseconds(positionMs) || !milliseconds(durationMs) || positionMs > durationMs
    || !milliseconds(sampledAtMonotonicMs)
    || !(scheduledStartMonotonicMs === null || milliseconds(scheduledStartMonotonicMs))
    || (status === 'scheduled' && scheduledStartMonotonicMs === null)
    || !(errorCode === null || (typeof errorCode === 'string' && errorCode.length <= 80))
  ) {
    throw new Error('Invalid playback state');
  }
  return {
    itemId, status: status as PlaybackStatus, positionMs, durationMs,
    sampledAtMonotonicMs, scheduledStartMonotonicMs, errorCode,
  };
}
