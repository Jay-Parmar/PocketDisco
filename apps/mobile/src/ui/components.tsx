import React from 'react';
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import type { TextInputProps, ViewStyle } from 'react-native';

import { colors } from './theme';

type ButtonProps = {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  busy?: boolean;
  tone?: 'dark' | 'lime' | 'outline' | 'quiet';
  testID?: string;
  hint?: string;
  style?: ViewStyle;
};

export function Button({
  label,
  onPress,
  disabled = false,
  busy = false,
  tone = 'dark',
  testID,
  hint,
  style,
}: ButtonProps) {
  const unavailable = disabled || busy;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      accessibilityState={{ disabled: unavailable, busy }}
      disabled={unavailable}
      onPress={onPress}
      testID={testID}
      style={({ pressed }) => [
        styles.button,
        styles[tone],
        style,
        unavailable && styles.disabled,
        pressed && styles.pressed,
      ]}
    >
      {busy && (
        <ActivityIndicator
          color={tone === 'dark' ? colors.paper : colors.ink}
          size="small"
          accessibilityElementsHidden
          importantForAccessibility="no"
        />
      )}
      <Text style={[styles.buttonText, tone === 'dark' && styles.lightText]}>
        {label}
      </Text>
    </Pressable>
  );
}

type FieldProps = TextInputProps & {
  label: string;
  error?: string;
  hint?: string;
  inputRef?: React.Ref<React.ElementRef<typeof TextInput>>;
};

export function Field({
  label,
  error,
  hint,
  inputRef,
  style,
  ...props
}: FieldProps) {
  return (
    <View style={styles.field}>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        ref={inputRef}
        accessibilityLabel={label}
        accessibilityHint={error || hint}
        placeholderTextColor={colors.muted}
        selectionColor={colors.green}
        {...props}
        style={[styles.input, error && styles.invalidInput, style]}
      />
      {error ? (
        <Text accessibilityLiveRegion="polite" style={styles.fieldError}>
          {error}
        </Text>
      ) : hint ? (
        <Text style={styles.hint}>{hint}</Text>
      ) : null}
    </View>
  );
}

export function ErrorBanner({
  message,
  onDismiss,
}: {
  message: string;
  onDismiss?: () => void;
}) {
  return (
    <View
      accessibilityLiveRegion="polite"
      style={styles.errorBanner}
      testID="error-banner"
    >
      <Text style={styles.errorText}>{message}</Text>
      {onDismiss && (
        <Button
          label="Dismiss"
          tone="quiet"
          onPress={onDismiss}
          style={styles.dismiss}
        />
      )}
    </View>
  );
}

export function Brand() {
  return (
    <View style={styles.brand} accessibilityLabel="PocketDisco">
      <View
        style={styles.brandMark}
        accessibilityElementsHidden
        importantForAccessibility="no-hide-descendants"
      >
        <View style={styles.brandCenter} />
      </View>
      <Text style={styles.brandName}>pocketdisco</Text>
    </View>
  );
}

export function RoomArtwork() {
  return (
    <View
      style={styles.artwork}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      pointerEvents="none"
    >
      <View style={styles.artworkCaption}>
        <Text style={styles.artworkEyebrow}>YOURS, TOGETHER</Text>
        <Text style={styles.artworkTitle}>
          A little room.{`\n`}A lot of feeling.
        </Text>
      </View>
      <View style={styles.record}>
        <View style={styles.recordGroove}>
          <View style={styles.recordGrooveInner}>
            <View style={styles.recordLabel}>
              <View style={styles.recordHole} />
            </View>
          </View>
        </View>
      </View>
      <View style={styles.tonearm} />
    </View>
  );
}

const styles = StyleSheet.create({
  button: {
    minHeight: 52,
    paddingHorizontal: 18,
    paddingVertical: 13,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    flexDirection: 'row',
    gap: 10,
  },
  dark: { backgroundColor: colors.ink },
  lime: { backgroundColor: colors.lime },
  outline: { borderWidth: 1, borderColor: colors.line },
  quiet: { backgroundColor: 'transparent' },
  disabled: { opacity: 0.48 },
  pressed: { opacity: 0.75 },
  buttonText: {
    fontSize: 16,
    fontWeight: '700',
    color: colors.ink,
    textAlign: 'center',
    flexShrink: 1,
  },
  lightText: { color: colors.paper },
  field: { gap: 8 },
  label: { fontSize: 14, fontWeight: '700', color: colors.ink },
  input: {
    minHeight: 54,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: colors.line,
    paddingHorizontal: 15,
    paddingVertical: 12,
    color: colors.ink,
    backgroundColor: colors.paper,
    fontSize: 16,
  },
  invalidInput: { borderColor: colors.error },
  fieldError: { fontSize: 13, lineHeight: 19, color: colors.error },
  hint: { fontSize: 13, lineHeight: 19, color: colors.muted },
  errorBanner: {
    padding: 16,
    borderRadius: 16,
    backgroundColor: colors.errorWash,
    gap: 4,
  },
  errorText: { fontSize: 14, lineHeight: 21, color: colors.error },
  dismiss: { alignSelf: 'flex-start', minHeight: 44, paddingHorizontal: 0 },
  brand: { flexDirection: 'row', alignItems: 'center', gap: 9 },
  brandMark: {
    width: 29,
    height: 29,
    borderRadius: 15,
    backgroundColor: colors.ink,
    alignItems: 'center',
    justifyContent: 'center',
  },
  brandCenter: {
    width: 9,
    height: 9,
    borderRadius: 5,
    backgroundColor: colors.lime,
  },
  brandName: {
    fontSize: 22,
    fontWeight: '800',
    letterSpacing: -0.8,
    color: colors.ink,
  },
  artwork: {
    minHeight: 142,
    borderRadius: 23,
    backgroundColor: colors.coral,
    padding: 19,
    overflow: 'hidden',
    justifyContent: 'center',
  },
  artworkCaption: { maxWidth: '63%', zIndex: 1 },
  artworkEyebrow: {
    fontSize: 9,
    letterSpacing: 1.2,
    fontWeight: '800',
    color: colors.ink,
    marginBottom: 11,
  },
  artworkTitle: {
    fontSize: 21,
    lineHeight: 26,
    fontWeight: '700',
    letterSpacing: -0.7,
    color: colors.ink,
  },
  record: {
    width: 138,
    height: 138,
    borderRadius: 69,
    backgroundColor: colors.ink,
    alignItems: 'center',
    justifyContent: 'center',
    position: 'absolute',
    right: -12,
    bottom: -17,
    transform: [{ rotate: '-18deg' }],
  },
  recordGroove: {
    width: 113,
    height: 113,
    borderRadius: 57,
    borderWidth: 1,
    borderColor: '#52564D',
    alignItems: 'center',
    justifyContent: 'center',
  },
  recordGrooveInner: {
    width: 90,
    height: 90,
    borderRadius: 45,
    borderWidth: 1,
    borderColor: '#52564D',
    alignItems: 'center',
    justifyContent: 'center',
  },
  recordLabel: {
    width: 43,
    height: 43,
    borderRadius: 22,
    backgroundColor: colors.lime,
    alignItems: 'center',
    justifyContent: 'center',
  },
  recordHole: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: colors.ink,
  },
  tonearm: {
    width: 5,
    height: 68,
    backgroundColor: colors.paper,
    position: 'absolute',
    right: 30,
    top: -6,
    borderRadius: 3,
    transform: [{ rotate: '-23deg' }],
  },
});
