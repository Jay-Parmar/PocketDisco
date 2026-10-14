import React, { useRef, useState } from 'react';
import {
  Keyboard,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import { Brand, Button, ErrorBanner } from './components';
import { DemoPlayer } from './DemoPlayer';
import { MembersCard } from './MembersCard';
import { NearbyAudioCard } from './NearbyAudioCard';
import { ChatComposer, MessageThread } from './RoomChat';
import { colors } from './theme';
import type { ConnectionStatus, RoomDetails } from './types';

export type RoomScreenProps = {
  room: RoomDetails;
  currentUserId: string;
  inviteCode?: string;
  connectionStatus: ConnectionStatus;
  busy?: boolean;
  isSending?: boolean;
  error?: string | null;
  onReadyChange: (ready: boolean) => void;
  onSendMessage: (body: string) => Promise<void>;
  onShareInvite: () => void;
  onLeave: () => void;
  onRetryConnection: () => void;
  onDismissError?: () => void;
};

const connectionLabels: Record<ConnectionStatus, string> = {
  connecting: 'Connecting',
  connected: 'Connected',
  reconnecting: 'Reconnecting',
  offline: 'Offline',
};

type RoomSection = 'room' | 'audio' | 'chat';
const sections: readonly RoomSection[] = ['room', 'audio', 'chat'];

export function RoomScreen({
  room,
  currentUserId,
  inviteCode,
  connectionStatus,
  busy = false,
  isSending = false,
  error,
  onReadyChange,
  onSendMessage,
  onShareInvite,
  onLeave,
  onRetryConnection,
  onDismissError,
}: RoomScreenProps) {
  const connected = connectionStatus === 'connected';
  const you = room.members.find(member => member.user_id === currentUserId);
  const controlsDisabled = busy || !connected || !you;
  const scroll = useRef<React.ElementRef<typeof ScrollView>>(null);
  const followMessages = useRef(false);
  const [section, setSection] = useState<RoomSection>('room');
  const sectionOffsets = useRef({ room: 0, audio: Infinity, chat: Infinity });
  const errorOffset = useRef(0);

  function goToSection(next: RoomSection) {
    Keyboard.dismiss();
    followMessages.current = next === 'chat';
    setSection(next);
    if (next === 'chat') {
      scroll.current?.scrollToEnd({ animated: true });
    } else {
      const offset =
        next === 'room' && error
          ? errorOffset.current
          : sectionOffsets.current[next];
      scroll.current?.scrollTo({
        y: Number.isFinite(offset) ? offset : 0,
        animated: true,
      });
    }
  }

  function showLatestMessages() {
    followMessages.current = true;
    setSection('chat');
    scroll.current?.scrollToEnd({ animated: true });
  }

  return (
    <KeyboardAvoidingView
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      style={styles.screen}
      testID="room-screen"
    >
      <View style={styles.header} testID="room-header">
        <View style={styles.headerContent}>
          <View style={styles.topBar}>
            <View style={styles.brand}>
              <Brand />
              <View style={styles.connection} accessibilityLiveRegion="polite">
                <View
                  style={[
                    styles.connectionDot,
                    connected && styles.connectedDot,
                  ]}
                />
                <Text style={styles.connectionLabel}>
                  {connectionLabels[connectionStatus]}
                </Text>
              </View>
            </View>
            <Button
              label={busy ? 'Please wait...' : 'Leave'}
              tone="outline"
              onPress={onLeave}
              disabled={busy}
              testID="leave-room-button"
              style={styles.leaveButton}
            />
          </View>
          <View style={styles.navigation} accessibilityLabel="Room sections">
            {sections.map(item => (
              <Pressable
                key={item}
                testID={`room-nav-${item}`}
                accessibilityRole="button"
                accessibilityLabel={
                  item === 'room' && error
                    ? `Room, view error. ${error}`
                    : item === 'room'
                    ? 'Room'
                    : item === 'audio'
                    ? 'Audio'
                    : 'Chat'
                }
                accessibilityHint={`Scroll to ${
                  item === 'room' && error
                    ? 'the room error'
                    : item === 'chat'
                    ? 'the latest messages'
                    : `the ${item} section`
                }`}
                accessibilityLiveRegion={item === 'room' ? 'polite' : undefined}
                accessibilityState={{ selected: section === item }}
                onPress={() => goToSection(item)}
                style={({ pressed }) => [
                  styles.navButton,
                  section === item && styles.navSelected,
                  pressed && styles.navPressed,
                ]}
              >
                <Text
                  style={[
                    styles.navLabel,
                    section === item && styles.navSelectedLabel,
                  ]}
                >
                  {item === 'room'
                    ? 'Room'
                    : item === 'audio'
                    ? 'Audio'
                    : 'Chat'}
                </Text>
                {item === 'room' && error && (
                  <Text
                    testID="room-error-shortcut"
                    style={[
                      styles.navErrorHint,
                      section === item && styles.navSelectedLabel,
                    ]}
                  >
                    View error
                  </Text>
                )}
              </Pressable>
            ))}
          </View>
        </View>
      </View>
      <ScrollView
        ref={scroll}
        testID="room-scroll"
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled"
        keyboardDismissMode="on-drag"
        scrollEventThrottle={80}
        onScroll={({ nativeEvent }) => {
          const { contentOffset, layoutMeasurement, contentSize } = nativeEvent;
          followMessages.current =
            contentOffset.y + layoutMeasurement.height >=
            contentSize.height - 80;
          setSection(
            (followMessages.current && contentOffset.y > 0) ||
              contentOffset.y + 24 >= sectionOffsets.current.chat
              ? 'chat'
              : contentOffset.y + 24 >= sectionOffsets.current.audio
              ? 'audio'
              : 'room',
          );
        }}
        onContentSizeChange={() => {
          if (followMessages.current) {
            scroll.current?.scrollToEnd({ animated: true });
          }
        }}
        onLayout={() => {
          if (followMessages.current) {
            scroll.current?.scrollToEnd({ animated: false });
          }
        }}
      >
        <View style={styles.content}>
          <View style={styles.section} testID="room-section-room">
            <View style={styles.roomHeading}>
              <Text style={styles.eyebrow}>YOUR PRIVATE ROOM</Text>
              <Text accessibilityRole="header" style={styles.roomName}>
                {room.name}
              </Text>
              <Text style={styles.body}>
                A place to catch up before the music.
              </Text>
            </View>
            {!connected && (
              <View style={styles.connectionCard} testID="connection-notice">
                <Text accessibilityRole="header" style={styles.noticeTitle}>
                  {connectionStatus === 'connecting'
                    ? 'Joining the room...'
                    : connectionStatus === 'reconnecting'
                    ? 'Getting you back in.'
                    : "You're offline."}
                </Text>
                <Text style={styles.noticeBody}>
                  This is the last known room state. Ready and chat controls
                  will return when you are connected.
                </Text>
                {connectionStatus === 'offline' && (
                  <Button
                    label="Try again"
                    tone="outline"
                    onPress={onRetryConnection}
                    disabled={busy}
                    testID="retry-connection-button"
                  />
                )}
              </View>
            )}
            {error ? (
              <View
                testID="room-error-details"
                onLayout={({ nativeEvent }) => {
                  errorOffset.current = nativeEvent.layout.y;
                }}
              >
                <ErrorBanner message={error} onDismiss={onDismissError} />
              </View>
            ) : null}
            <View style={styles.inviteCard}>
              <Text style={styles.inviteTitle}>Save a spot for a friend.</Text>
              {inviteCode ? (
                <>
                  <Text style={styles.inviteHelp}>
                    Send this room code to a friend.
                  </Text>
                  <Text
                    selectable
                    accessibilityLabel={`Invite code ${inviteCode
                      .split('')
                      .join(' ')}`}
                    style={styles.inviteCode}
                    testID="invite-code"
                  >
                    {inviteCode}
                  </Text>
                  <Button
                    label="Share room invite"
                    tone="dark"
                    onPress={onShareInvite}
                    disabled={busy}
                    testID="share-invite-button"
                  />
                </>
              ) : (
                <Text style={styles.inviteHelp}>
                  Ask the host for the invite code to bring a friend along.
                </Text>
              )}
            </View>
            <View style={styles.readyCard}>
              <Text accessibilityRole="header" style={styles.sectionTitle}>
                {you?.ready ? 'All settled in.' : 'Make yourself at home.'}
              </Text>
              <Text style={styles.body}>
                Let the room know you are ready. This does not start or sync
                audio.
              </Text>
              <Button
                label={you?.ready ? 'Not ready yet' : "I'm ready"}
                tone={you?.ready ? 'outline' : 'lime'}
                onPress={() => {
                  if (!controlsDisabled && you) {
                    onReadyChange(!you.ready);
                  }
                }}
                disabled={controlsDisabled}
                testID="ready-toggle-button"
              />
            </View>
            <MembersCard members={room.members} currentUserId={currentUserId} />
          </View>
          <View
            style={styles.section}
            testID="room-section-audio"
            onLayout={({ nativeEvent }) => {
              sectionOffsets.current.audio = nativeEvent.layout.y;
            }}
          >
            <View style={styles.sectionHeading}>
              <Text style={styles.eyebrow}>A LITTLE SOUND CHECK</Text>
              <Text accessibilityRole="header" style={styles.sectionTitle}>
                Audio corner
              </Text>
              <Text style={styles.body}>
                Try the local demo. Shared room playback is still in
                development.
              </Text>
            </View>
            <DemoPlayer key={room.room_id} enabled={!busy && !!you} />
            <NearbyAudioCard
              key={`nearby-${room.room_id}`}
              roomId={room.room_id}
              canOpen={!controlsDisabled}
            />
          </View>
          <View
            testID="room-section-chat"
            onLayout={({ nativeEvent }) => {
              sectionOffsets.current.chat = nativeEvent.layout.y;
            }}
          >
            <MessageThread
              messages={room.messages}
              currentUserId={currentUserId}
            />
          </View>
        </View>
      </ScrollView>
      <ChatComposer
        disabled={controlsDisabled}
        isSending={isSending}
        unavailableMessage={
          !connected ? 'Chat will be available when connected.' : undefined
        }
        onSendMessage={onSendMessage}
        onFocus={showLatestMessages}
        onSent={showLatestMessages}
      />
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.background },
  header: {
    paddingHorizontal: 20,
    paddingTop: 12,
    paddingBottom: 12,
    borderBottomWidth: 1,
    borderBottomColor: colors.line,
    backgroundColor: colors.background,
  },
  headerContent: { width: '100%', maxWidth: 560, alignSelf: 'center', gap: 12 },
  scrollContent: { paddingHorizontal: 20, paddingVertical: 20 },
  content: { width: '100%', maxWidth: 560, alignSelf: 'center', gap: 30 },
  section: { gap: 20 },
  sectionHeading: { gap: 8 },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    flexWrap: 'wrap',
    gap: 12,
  },
  brand: { flexGrow: 1, flexShrink: 1, gap: 5 },
  leaveButton: { minHeight: 48, paddingHorizontal: 14, paddingVertical: 10 },
  navigation: {
    flexDirection: 'row',
    padding: 4,
    gap: 4,
    borderRadius: 18,
    backgroundColor: '#E9E9DF',
  },
  navButton: {
    flex: 1,
    minHeight: 48,
    paddingHorizontal: 8,
    paddingVertical: 12,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 14,
  },
  navSelected: { backgroundColor: colors.ink },
  navLabel: {
    color: colors.muted,
    fontSize: 14,
    fontWeight: '700',
    textAlign: 'center',
  },
  navSelectedLabel: { color: colors.paper },
  navErrorHint: {
    color: colors.error,
    fontSize: 12,
    lineHeight: 16,
    fontWeight: '600',
    textAlign: 'center',
  },
  navPressed: { opacity: 0.75 },
  roomHeading: { gap: 9 },
  eyebrow: {
    fontSize: 10,
    letterSpacing: 1.2,
    color: colors.muted,
    fontWeight: '700',
  },
  roomName: {
    fontSize: 30,
    lineHeight: 36,
    color: colors.ink,
    fontWeight: '800',
    letterSpacing: -1.1,
  },
  connection: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    paddingLeft: 2,
  },
  connectionDot: {
    width: 7,
    height: 7,
    borderRadius: 4,
    backgroundColor: colors.muted,
  },
  connectedDot: { backgroundColor: colors.green },
  connectionLabel: { fontSize: 12, lineHeight: 18, color: colors.muted },
  connectionCard: {
    padding: 18,
    borderRadius: 20,
    backgroundColor: '#EAE8DF',
    gap: 10,
  },
  noticeTitle: {
    fontSize: 17,
    lineHeight: 24,
    fontWeight: '700',
    color: colors.ink,
  },
  noticeBody: { fontSize: 14, lineHeight: 21, color: colors.muted },
  inviteCard: {
    backgroundColor: colors.lime,
    padding: 20,
    borderRadius: 24,
    gap: 11,
  },
  inviteTitle: {
    fontSize: 21,
    lineHeight: 27,
    fontWeight: '700',
    letterSpacing: -0.8,
    color: colors.ink,
  },
  inviteHelp: { fontSize: 14, lineHeight: 21, color: colors.ink },
  inviteCode: {
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    fontSize: 20,
    lineHeight: 29,
    letterSpacing: 1.2,
    color: colors.ink,
    marginVertical: 3,
  },
  readyCard: {
    gap: 12,
    padding: 20,
    borderRadius: 24,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.paper,
  },
  sectionTitle: {
    fontSize: 21,
    fontWeight: '700',
    letterSpacing: -0.6,
    color: colors.ink,
  },
  body: { fontSize: 14, lineHeight: 21, color: colors.muted },
});
