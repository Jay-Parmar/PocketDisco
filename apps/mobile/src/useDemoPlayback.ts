import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import type {
  PlaybackProvider,
  TimedPlaybackState,
} from '../../../packages/domain/src/playback';
import { demoTrack, emptyPlayback, playbackMessage } from './playback';

export function useDemoPlayback(provider: PlaybackProvider, enabled = true) {
  const [state, setState] = useState(emptyPlayback);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [available, setAvailable] = useState(
    enabled && AppState.currentState === 'active',
  );
  const active = useRef(false);
  const mounted = useRef(false);
  const running = useRef(false);
  const generation = useRef(0);

  const record = useCallback((value: TimedPlaybackState) => {
    setState(value);
    if (value.errorCode) setError(playbackMessage({ code: value.errorCode }));
  }, []);

  useEffect(() => {
    let alive = true;
    let polling = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    mounted.current = true;
    active.current = enabled && AppState.currentState === 'active';
    setAvailable(active.current);
    running.current = false;
    setBusy(false);

    async function poll() {
      const version = generation.current;
      if (!alive || !active.current || polling) return;
      polling = true;
      try {
        if (!running.current) {
          const next = await provider.getTimedState();
          if (alive && active.current && version === generation.current)
            record(next);
        }
      } catch (failure) {
        if (alive && active.current && version === generation.current) {
          setError(playbackMessage(failure));
        }
      } finally {
        polling = false;
        if (alive && active.current) timer = setTimeout(poll, 500);
      }
    }

    poll();
    const subscription = AppState.addEventListener('change', status => {
      const nextActive = enabled && status === 'active';
      if (nextActive === active.current) return;
      active.current = nextActive;
      setAvailable(nextActive);
      const version = ++generation.current;
      running.current = false;
      setBusy(false);
      clearTimeout(timer);
      if (nextActive) {
        poll();
      } else {
        provider
          .pause()
          .then(next => {
            if (alive && version === generation.current) record(next);
          })
          .catch(failure => {
            if (alive && version === generation.current)
              setError(playbackMessage(failure));
          });
      }
    });

    return () => {
      alive = false;
      mounted.current = false;
      active.current = false;
      generation.current += 1;
      clearTimeout(timer);
      subscription.remove();
      provider.disconnect().catch(() => {});
    };
  }, [enabled, provider, record]);

  async function run(
    action: (current: () => boolean) => Promise<TimedPlaybackState | null>,
  ) {
    if (!active.current || running.current) return;
    running.current = true;
    setBusy(true);
    setError(null);
    const version = ++generation.current;
    const current = () =>
      mounted.current && active.current && version === generation.current;
    try {
      const next = await action(current);
      if (next && current()) record(next);
    } catch (failure) {
      if (current()) setError(playbackMessage(failure));
    } finally {
      if (current()) {
        running.current = false;
        setBusy(false);
      }
    }
  }

  function play() {
    return run(async current => {
      const capabilities = await provider.getCapabilities();
      if (!current()) return null;
      if (!capabilities.canSchedule || !capabilities.canReportPosition) {
        throw new Error('Playback is unavailable');
      }
      let next = await provider.getTimedState();
      if (!current()) return null;
      if (
        next.itemId !== demoTrack.id ||
        ['idle', 'preparing', 'error', 'ended'].includes(next.status) ||
        next.positionMs >= next.durationMs
      ) {
        next = await provider.prepare(demoTrack.id, 0);
      }
      if (!current()) return null;
      if (next.status === 'playing' || next.status === 'scheduled') return next;
      return provider.playAt(next.sampledAtMonotonicMs + 500, next.positionMs);
    });
  }

  function pause() {
    return run(() => provider.pause());
  }

  function seekBy(deltaMs: number) {
    return run(async current => {
      const next = await provider.getTimedState();
      if (!current() || !next.itemId || !Number.isFinite(deltaMs)) return null;
      return provider.seek(
        Math.min(next.durationMs, Math.max(0, next.positionMs + deltaMs)),
      );
    });
  }

  return { state, busy, error, available, play, pause, seekBy };
}
