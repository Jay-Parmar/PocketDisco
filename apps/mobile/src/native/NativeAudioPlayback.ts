import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type NativePlaybackCapabilities = {
  canSchedule: boolean;
  canSeek: boolean;
  canReportPosition: boolean;
  canRateAdjust: boolean;
  canBackground: boolean;
};

export type NativePlaybackState = {
  itemId: string | null;
  status: string;
  positionMs: number;
  durationMs: number;
  sampledAtMonotonicMs: number;
  scheduledStartMonotonicMs: number | null;
  errorCode: string | null;
};

export interface Spec extends TurboModule {
  getCapabilities(): Promise<NativePlaybackCapabilities>;
  prepare(itemId: string, positionMs: number): Promise<NativePlaybackState>;
  playAt(monotonicTimeMs: number, positionMs: number): Promise<NativePlaybackState>;
  pause(): Promise<NativePlaybackState>;
  seek(positionMs: number): Promise<NativePlaybackState>;
  getTimedState(): Promise<NativePlaybackState>;
  disconnect(): Promise<void>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('AudioPlayback');
