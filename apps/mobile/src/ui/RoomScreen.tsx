import React, { useRef } from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import { Brand, Button, ErrorBanner } from './components';
import { MembersCard } from './MembersCard';
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

  function showLatestMessages() {
    followMessages.current = true;
    scroll.current?.scrollToEnd({ animated: true });
  }

  return (
    <KeyboardAvoidingView
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      style={styles.screen}
      testID="room-screen"
    >
      <ScrollView
        ref={scroll}
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled"
        keyboardDismissMode="on-drag"
        scrollEventThrottle={80}
        onScroll={({ nativeEvent }) => {
          const { contentOffset, layoutMeasurement, contentSize } = nativeEvent;
          followMessages.current =
            contentOffset.y + layoutMeasurement.height >=
            contentSize.height - 80;
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
          <View style={styles.topBar}>
            <View style={styles.brand}>
              <Brand />
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
          <View style={styles.roomHeading}>
            <Text style={styles.eyebrow}>PRIVATE ROOM ALPHA</Text>
            <Text accessibilityRole="header" style={styles.roomName}>
              {room.name}
            </Text>
            <View style={styles.connection} accessibilityLiveRegion="polite">
              <View
                style={[styles.connectionDot, connected && styles.connectedDot]}
              />
              <Text style={styles.connectionLabel}>
                {connectionLabels[connectionStatus]}
              </Text>
            </View>
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
                This is the last known room state. Ready and chat controls will
                return when you are connected.
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
            <ErrorBanner message={error} onDismiss={onDismissError} />
          ) : null}
          <View style={styles.inviteCard}>
            <Text style={styles.inviteTitle}>Better with your people.</Text>
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
          <MembersCard members={room.members} currentUserId={currentUserId} />
          <View style={styles.readyCard}>
            <Text accessibilityRole="header" style={styles.sectionTitle}>
              {you?.ready ? 'All settled in.' : 'Make yourself at home.'}
            </Text>
            <Text style={styles.body}>
              Let the room know you are ready. This does not start audio yet.
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
          <View style={styles.playbackNote}>
            <Text style={styles.playbackTitle}>
              Listening together is on the way.
            </Text>
            <Text style={styles.playbackBody}>
              Playback is coming next. For now, gather your people and say
              hello.
            </Text>
          </View>
          <MessageThread
            messages={room.messages}
            currentUserId={currentUserId}
          />
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
  scrollContent: { paddingHorizontal: 22, paddingVertical: 22 },
  content: { width: '100%', maxWidth: 560, alignSelf: 'center', gap: 22 },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    flexWrap: 'wrap',
    gap: 12,
  },
  brand: { flexGrow: 1 },
  leaveButton: { minHeight: 44, paddingHorizontal: 14, paddingVertical: 10 },
  roomHeading: { gap: 9 },
  eyebrow: {
    fontSize: 10,
    letterSpacing: 1.2,
    color: colors.muted,
    fontWeight: '700',
  },
  roomName: {
    fontSize: 34,
    lineHeight: 40,
    color: colors.ink,
    fontWeight: '800',
    letterSpacing: -1.1,
  },
  connection: { flexDirection: 'row', alignItems: 'center', gap: 7 },
  connectionDot: {
    width: 7,
    height: 7,
    borderRadius: 4,
    backgroundColor: colors.muted,
  },
  connectedDot: { backgroundColor: colors.green },
  connectionLabel: { fontSize: 13, lineHeight: 19, color: colors.muted },
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
    fontSize: 23,
    lineHeight: 29,
    fontWeight: '700',
    letterSpacing: -0.8,
    color: colors.ink,
  },
  inviteHelp: { fontSize: 14, lineHeight: 21, color: colors.ink },
  inviteCode: {
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    fontSize: 21,
    lineHeight: 30,
    letterSpacing: 1.9,
    color: colors.ink,
    marginVertical: 3,
  },
  readyCard: { gap: 12 },
  sectionTitle: {
    fontSize: 21,
    fontWeight: '700',
    letterSpacing: -0.6,
    color: colors.ink,
  },
  body: { fontSize: 14, lineHeight: 21, color: colors.muted },
  playbackNote: {
    borderTopWidth: 1,
    borderTopColor: colors.line,
    paddingTop: 20,
    gap: 6,
  },
  playbackTitle: {
    fontSize: 14,
    lineHeight: 21,
    fontWeight: '700',
    color: colors.ink,
  },
  playbackBody: { fontSize: 13, lineHeight: 20, color: colors.muted },
});
