import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { PlaybackStatus } from '../../../../packages/domain/src/playback';
import AudioPlayback from '../native/NativeAudioPlayback';
import { createPlaybackProvider, demoTrack } from '../playback';
import { useDemoPlayback } from '../useDemoPlayback';
import { Button } from './components';
import { colors } from './theme';

const provider = createPlaybackProvider(AudioPlayback);
const statusLabels: Record<PlaybackStatus, string> = {
  idle: 'Ready for a sound check',
  preparing: 'Loading the demo',
  ready: 'Ready to play',
  scheduled: 'Starting on this phone',
  playing: 'Playing on this phone',
  paused: 'Paused',
  ended: 'Demo finished',
  error: 'Playback stopped',
};

function timeLabel(milliseconds: number) {
  const seconds = Math.floor(milliseconds / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

export function DemoPlayer({ enabled }: { enabled: boolean }) {
  const playback = useDemoPlayback(provider, enabled);
  return <DemoPlayerCard playback={playback} />;
}

export function DemoPlayerCard({
  playback,
}: {
  playback: ReturnType<typeof useDemoPlayback>;
}) {
  const { state, busy, error, available } = playback;
  const playing = state.status === 'playing' || state.status === 'scheduled';
  const disabled = busy || !available;
  const seekDisabled =
    disabled ||
    !state.itemId ||
    state.status === 'preparing' ||
    state.status === 'error';
  const duration = state.durationMs || demoTrack.durationMs;
  const position = Math.min(state.positionMs, duration);
  const width: `${number}%` = `${(position / duration) * 100}%`;

  return (
    <View style={styles.card} testID="demo-player">
      <View style={styles.heading}>
        <View
          style={styles.disc}
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
        >
          <View style={styles.discCenter} />
        </View>
        <View style={styles.track}>
          <Text style={styles.eyebrow}>LOCAL AUDIO PREVIEW</Text>
          <Text style={styles.title} accessibilityRole="header">
            {demoTrack.title}
          </Text>
          <Text style={styles.subtitle}>Generated test audio. 24 seconds.</Text>
        </View>
      </View>
      <Text style={styles.notice}>
        Only this phone will play. Room playback is not connected yet.
      </Text>
      <View style={styles.progressBlock}>
        <View
          style={styles.progress}
          accessibilityRole="progressbar"
          accessibilityLabel="Demo playhead"
          accessibilityValue={{
            min: 0,
            max: duration,
            now: position,
            text: `${timeLabel(position)} of ${timeLabel(duration)}`,
          }}
          testID="demo-progress"
        >
          <View style={[styles.progressFill, { width }]} />
        </View>
        <View style={styles.progressLabels}>
          <Text style={styles.timestamp}>{timeLabel(position)}</Text>
          <Text style={styles.timestamp}>{timeLabel(duration)}</Text>
        </View>
      </View>
      <Text
        style={styles.status}
        accessibilityLiveRegion="polite"
        testID="demo-status"
      >
        {busy ? 'Updating the player...' : statusLabels[state.status]}
      </Text>
      <Button
        label={
          playing
            ? 'Pause demo'
            : state.status === 'ended'
            ? 'Replay demo'
            : 'Play demo'
        }
        tone="dark"
        busy={busy}
        disabled={disabled}
        onPress={() => {
          if (!disabled) {
            if (playing) playback.pause();
            else playback.play();
          }
        }}
        hint="Controls this phone only"
        testID="demo-play-pause"
      />
      <View style={styles.seekRow}>
        <Button
          label="Back 5 seconds"
          tone="outline"
          disabled={seekDisabled}
          onPress={() => {
            if (!seekDisabled) playback.seekBy(-5000);
          }}
          style={styles.seekButton}
          testID="demo-seek-back"
        />
        <Button
          label="Forward 5 seconds"
          tone="outline"
          disabled={seekDisabled}
          onPress={() => {
            if (!seekDisabled) playback.seekBy(5000);
          }}
          style={styles.seekButton}
          testID="demo-seek-forward"
        />
      </View>
      {error && (
        <Text
          style={styles.error}
          accessibilityLiveRegion="polite"
          testID="demo-error"
        >
          {error}
        </Text>
      )}
      <Text style={styles.footnote}>
        Pauses in the background. Seeking pauses playback.
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    padding: 20,
    borderWidth: 1,
    borderColor: colors.line,
    borderRadius: 24,
    backgroundColor: colors.paper,
    gap: 14,
  },
  heading: {
    flexDirection: 'row',
    gap: 14,
    alignItems: 'center',
    flexWrap: 'wrap',
  },
  track: { flex: 1, minWidth: 160, gap: 5 },
  disc: {
    width: 58,
    height: 58,
    borderRadius: 29,
    backgroundColor: colors.green,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 8,
    borderColor: colors.greenWash,
  },
  discCenter: {
    width: 12,
    height: 12,
    borderRadius: 6,
    backgroundColor: colors.lime,
  },
  eyebrow: {
    color: colors.green,
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 1,
  },
  title: {
    color: colors.ink,
    fontSize: 24,
    fontWeight: '800',
    letterSpacing: -0.6,
  },
  subtitle: { color: colors.muted, fontSize: 12, lineHeight: 18 },
  notice: { color: colors.muted, fontSize: 14, lineHeight: 21 },
  progressBlock: { gap: 7 },
  progress: {
    height: 6,
    borderRadius: 3,
    backgroundColor: colors.line,
    overflow: 'hidden',
  },
  progressFill: { height: 6, borderRadius: 3, backgroundColor: colors.green },
  progressLabels: { flexDirection: 'row', justifyContent: 'space-between' },
  timestamp: {
    color: colors.muted,
    fontSize: 12,
    fontVariant: ['tabular-nums'],
  },
  status: { color: colors.ink, fontSize: 13, lineHeight: 19 },
  seekRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  seekButton: { flexGrow: 1, minWidth: 110, paddingHorizontal: 10 },
  error: { color: colors.error, fontSize: 14, lineHeight: 21 },
  footnote: { color: colors.muted, fontSize: 12, lineHeight: 18 },
});
