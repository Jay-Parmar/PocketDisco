import React, { useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import NearbyAudio from '../native/NativeNearbyAudio';
import { Button } from './components';
import { colors } from './theme';

type Props = { roomId: string; canOpen: boolean };

export function NearbyAudioCard({ roomId, canOpen }: Props) {
  const [availability] = useState(() => NearbyAudio.getAvailability());
  const [opening, setOpening] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const session = useRef({ roomId, active: true, opening: false });

  useEffect(() => {
    const current = { roomId, active: true, opening: false };
    session.current = current;
    setOpening(false);
    setError(null);
    return () => {
      current.active = false;
      if (availability.enabled && availability.supported) {
        NearbyAudio.stop().catch(() => undefined);
      }
    };
  }, [roomId, availability]);

  async function open() {
    const current = session.current;
    if (
      !availability.supported ||
      !canOpen ||
      !current.active ||
      current.roomId !== roomId ||
      current.opening
    ) {
      return;
    }
    current.opening = true;
    setOpening(true);
    setError(null);
    try {
      await NearbyAudio.open(roomId);
    } catch {
      if (current.active) {
        setError(
          'Could not open nearby audio. Return to the room and try again.',
        );
      }
    } finally {
      current.opening = false;
      if (current.active) setOpening(false);
    }
  }

  if (!availability.enabled) return null;

  return (
    <View style={styles.card} testID="nearby-audio-card">
      <Text style={styles.eyebrow}>INTERNAL TEST</Text>
      <Text accessibilityRole="header" style={styles.title}>
        Nearby audio (test)
      </Text>
      <Text style={styles.body}>
        A separate nearby session with its own 128-bit code and paired Bluetooth
        phones. Not internet room playback.
      </Text>
      <Text style={styles.body}>
        The nearby code, not room membership, controls who can connect. Share
        only audio you have permission to share. Some apps block capture.
      </Text>
      {availability.supported ? (
        <>
          <Button
            label="Open nearby audio"
            tone="dark"
            disabled={!canOpen || opening}
            busy={opening}
            onPress={open}
            hint="Opens the nearby test. Returning to this room stops nearby audio."
            testID="nearby-open"
          />
          <Text style={styles.footnote}>
            Returning here ends nearby audio. Room chat reconnects on return.
          </Text>
        </>
      ) : (
        <Text style={styles.footnote} testID="nearby-unsupported">
          Nearby audio requires Android 10 or newer.
        </Text>
      )}
      {error && (
        <Text
          style={styles.error}
          accessibilityLiveRegion="polite"
          testID="nearby-error"
        >
          {error}
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    padding: 20,
    borderRadius: 24,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.greenWash,
    gap: 12,
  },
  eyebrow: {
    color: colors.green,
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 1,
  },
  title: {
    color: colors.ink,
    fontSize: 23,
    lineHeight: 29,
    fontWeight: '700',
    letterSpacing: -0.6,
  },
  body: { color: colors.muted, fontSize: 14, lineHeight: 21 },
  footnote: { color: colors.muted, fontSize: 12, lineHeight: 18 },
  error: { color: colors.error, fontSize: 14, lineHeight: 21 },
});
