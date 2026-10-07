import React, { useEffect, useRef, useState } from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';

import { Brand, Button, ErrorBanner, Field, RoomArtwork } from './components';
import { colors } from './theme';
import {
  normalizeInviteCode,
  validateDisplayName,
  validateInviteCode,
  validateRoomName,
  validateServerUrl,
} from './validation';

export type WelcomeScreenProps = {
  serverUrl: string;
  showServerSettings: boolean;
  busy: 'creating' | 'joining' | null;
  error?: string | null;
  onCreate: (values: { displayName: string; roomName: string }) => void;
  onJoin: (values: { displayName: string; inviteCode: string }) => void;
  onSaveServerUrl: (url: string) => void;
  onDismissError?: () => void;
};

export function WelcomeScreen({
  serverUrl,
  showServerSettings,
  busy,
  error,
  onCreate,
  onJoin,
  onSaveServerUrl,
  onDismissError,
}: WelcomeScreenProps) {
  const [mode, setMode] = useState<'create' | 'join'>('create');
  const [displayName, setDisplayName] = useState('');
  const [roomName, setRoomName] = useState('');
  const [inviteCode, setInviteCode] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [serverDraft, setServerDraft] = useState(serverUrl);
  const [serverError, setServerError] = useState<string>();
  const [serverSaved, setServerSaved] = useState(false);
  const secondInput = useRef<React.ElementRef<typeof TextInput>>(null);

  useEffect(() => {
    setServerDraft(serverUrl);
  }, [serverUrl]);

  const nameError = submitted ? validateDisplayName(displayName) : undefined;
  const roomError = submitted ? validateRoomName(roomName) : undefined;
  const inviteError = submitted ? validateInviteCode(inviteCode) : undefined;

  function submit() {
    if (busy) {
      return;
    }
    setSubmitted(true);
    if (validateDisplayName(displayName)) {
      return;
    }
    if (mode === 'create' && !validateRoomName(roomName)) {
      onCreate({ displayName: displayName.trim(), roomName: roomName.trim() });
    } else if (mode === 'join' && !validateInviteCode(inviteCode)) {
      onJoin({
        displayName: displayName.trim(),
        inviteCode: normalizeInviteCode(inviteCode),
      });
    }
  }

  function saveServer() {
    const nextError = validateServerUrl(serverDraft);
    setServerError(nextError);
    setServerSaved(false);
    if (!nextError && !busy) {
      onSaveServerUrl(serverDraft.trim().replace(/\/+$/, ''));
      setServerSaved(true);
    }
  }

  return (
    <KeyboardAvoidingView
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      style={styles.screen}
      testID="welcome-screen"
    >
      <ScrollView
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled"
        keyboardDismissMode="on-drag"
      >
        <View style={styles.content}>
          <Brand />
          <View style={styles.hero}>
            <View style={styles.alphaBadge}>
              <Text style={styles.alphaText}>PRIVATE ROOM ALPHA</Text>
            </View>
            <Text accessibilityRole="header" style={styles.title}>
              Good music.{`\n`}Better company.
            </Text>
            <Text style={styles.description}>
              Make a little room for your people.
            </Text>
          </View>
          <RoomArtwork />
          <View style={styles.form}>
            <View style={styles.tabs} accessibilityRole="tablist">
              {(['create', 'join'] as const).map(tab => (
                <Pressable
                  accessibilityRole="tab"
                  accessibilityState={{
                    selected: mode === tab,
                    disabled: !!busy,
                  }}
                  disabled={!!busy}
                  testID={`${tab}-tab`}
                  key={tab}
                  onPress={() => {
                    setMode(tab);
                    setSubmitted(false);
                  }}
                  style={({ pressed }) => [
                    styles.tab,
                    mode === tab && styles.activeTab,
                    pressed && styles.pressed,
                  ]}
                >
                  <Text style={styles.tabText}>
                    {tab === 'create' ? 'Create a room' : 'Join friends'}
                  </Text>
                </Pressable>
              ))}
            </View>
            {error ? (
              <ErrorBanner message={error} onDismiss={onDismissError} />
            ) : null}
            <Field
              label="Your name"
              placeholder="What should we call you?"
              value={displayName}
              onChangeText={setDisplayName}
              maxLength={40}
              autoCapitalize="words"
              autoCorrect={false}
              editable={!busy}
              returnKeyType="next"
              onSubmitEditing={() => secondInput.current?.focus()}
              error={nameError}
              testID="display-name-input"
            />
            {mode === 'create' ? (
              <Field
                label="Room name"
                inputRef={secondInput}
                placeholder="Friday night feels"
                value={roomName}
                onChangeText={setRoomName}
                maxLength={80}
                editable={!busy}
                returnKeyType="go"
                onSubmitEditing={submit}
                error={roomError}
                testID="room-name-input"
              />
            ) : (
              <Field
                label="Invite code"
                inputRef={secondInput}
                placeholder="12-character room code"
                hint="Get a code from the person who created the room."
                value={inviteCode}
                onChangeText={setInviteCode}
                maxLength={12}
                autoCapitalize="characters"
                autoCorrect={false}
                editable={!busy}
                returnKeyType="go"
                onSubmitEditing={submit}
                error={inviteError}
                testID="invite-code-input"
                style={styles.codeInput}
              />
            )}
            <Button
              label={
                busy === 'creating'
                  ? 'Creating your room...'
                  : busy === 'joining'
                  ? 'Joining your friends...'
                  : mode === 'create'
                  ? 'Create my room'
                  : 'Join the room'
              }
              onPress={submit}
              busy={!!busy}
              testID={
                mode === 'create' ? 'create-room-button' : 'join-room-button'
              }
            />
            <Text style={styles.formNote}>
              Invite-only, up to 25 people. Playback is coming next.
            </Text>
          </View>
          {showServerSettings && (
            <View style={styles.serverSection}>
              <Pressable
                accessibilityRole="button"
                accessibilityState={{
                  expanded: settingsOpen,
                  disabled: !!busy,
                }}
                disabled={!!busy}
                onPress={() => setSettingsOpen(!settingsOpen)}
                testID="server-settings-toggle"
                style={styles.serverToggle}
              >
                <Text style={styles.serverTitle}>Development server</Text>
                <Text style={styles.serverAction}>
                  {settingsOpen ? 'Close' : 'Edit'}
                </Text>
              </Pressable>
              {settingsOpen ? (
                <View style={styles.serverForm}>
                  <Field
                    label="Server address"
                    hint="Local testing only. This setting is hidden in release builds."
                    value={serverDraft}
                    onChangeText={value => {
                      setServerDraft(value);
                      setServerSaved(false);
                      setServerError(undefined);
                    }}
                    autoCapitalize="none"
                    autoCorrect={false}
                    keyboardType="url"
                    returnKeyType="done"
                    onSubmitEditing={saveServer}
                    editable={!busy}
                    error={serverError}
                    testID="server-url-input"
                  />
                  <Button
                    label="Save server address"
                    tone="outline"
                    onPress={saveServer}
                    disabled={!!busy}
                    testID="save-server-button"
                  />
                  {serverSaved && (
                    <Text accessibilityLiveRegion="polite" style={styles.saved}>
                      Server address saved.
                    </Text>
                  )}
                </View>
              ) : (
                <Text style={styles.serverAddress}>{serverUrl}</Text>
              )}
            </View>
          )}
          <Text style={styles.footer}>
            A shared room. Your own corner of it.
          </Text>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.background },
  scrollContent: { flexGrow: 1, paddingHorizontal: 22, paddingVertical: 24 },
  content: { width: '100%', maxWidth: 560, alignSelf: 'center', gap: 24 },
  hero: { gap: 12 },
  alphaBadge: {
    alignSelf: 'flex-start',
    borderWidth: 1,
    borderColor: colors.line,
    borderRadius: 20,
    paddingHorizontal: 10,
    paddingVertical: 6,
  },
  alphaText: {
    color: colors.muted,
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 1.1,
  },
  title: {
    color: colors.ink,
    fontSize: 38,
    lineHeight: 43,
    fontWeight: '800',
    letterSpacing: -1.5,
  },
  description: { color: colors.muted, fontSize: 16, lineHeight: 24 },
  form: { gap: 20 },
  tabs: {
    flexDirection: 'row',
    gap: 4,
    backgroundColor: '#E9E9DF',
    borderRadius: 17,
    padding: 4,
  },
  tab: {
    flex: 1,
    minHeight: 48,
    paddingHorizontal: 12,
    paddingVertical: 12,
    borderRadius: 13,
    alignItems: 'center',
    justifyContent: 'center',
  },
  activeTab: { backgroundColor: colors.paper },
  tabText: {
    color: colors.ink,
    fontSize: 15,
    fontWeight: '700',
    textAlign: 'center',
  },
  pressed: { opacity: 0.7 },
  codeInput: { letterSpacing: 1.3 },
  formNote: {
    color: colors.muted,
    fontSize: 13,
    lineHeight: 20,
    textAlign: 'center',
  },
  serverSection: {
    borderTopWidth: 1,
    borderTopColor: colors.line,
    paddingTop: 8,
  },
  serverToggle: {
    minHeight: 48,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
  },
  serverTitle: {
    fontSize: 14,
    color: colors.muted,
    fontWeight: '600',
    flex: 1,
  },
  serverAction: { fontSize: 14, color: colors.ink, fontWeight: '700' },
  serverAddress: { fontSize: 12, color: colors.muted, lineHeight: 18 },
  serverForm: { gap: 14, paddingTop: 8 },
  saved: { fontSize: 13, lineHeight: 19, color: colors.green },
  footer: {
    fontSize: 12,
    color: colors.muted,
    textAlign: 'center',
    lineHeight: 18,
  },
});
