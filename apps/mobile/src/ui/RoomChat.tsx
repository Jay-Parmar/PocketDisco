import React, { useRef, useState } from 'react';
import { StyleSheet, Text, TextInput, View } from 'react-native';

import { Button } from './components';
import { colors } from './theme';
import type { RoomMessage } from './types';
import { validateMessage } from './validation';

export function MessageThread({
  messages,
  currentUserId,
}: {
  messages: readonly RoomMessage[];
  currentUserId: string;
}) {
  return (
    <View style={styles.thread} testID="message-thread">
      <Text accessibilityRole="header" style={styles.title}>
        Room chat
      </Text>
      {messages.length === 0 ? (
        <View style={styles.emptyChat}>
          <Text style={styles.emptyTitle}>The room starts with a hello.</Text>
          <Text style={styles.emptyBody}>
            Break the ice. Only people in this room can see the conversation.
          </Text>
        </View>
      ) : (
        <View style={styles.messages}>
          {messages.map(message => {
            const own = message.user_id === currentUserId;
            const time = new Date(message.created_at_ms).toLocaleTimeString(
              undefined,
              { hour: '2-digit', minute: '2-digit' },
            );
            return (
              <View
                key={message.id}
                testID={`message-${message.id}`}
                accessible
                accessibilityLabel={`${
                  own ? 'You' : message.display_name
                }, ${time}: ${message.body}`}
                style={[styles.message, own && styles.ownMessage]}
              >
                <View style={[styles.messageMeta, own && styles.ownMeta]}>
                  <Text style={styles.author}>
                    {own ? 'You' : message.display_name}
                  </Text>
                  <Text style={styles.time}>{time}</Text>
                </View>
                <View style={[styles.bubble, own && styles.ownBubble]}>
                  <Text
                    selectable
                    style={[styles.messageBody, own && styles.ownBody]}
                  >
                    {message.body}
                  </Text>
                </View>
              </View>
            );
          })}
        </View>
      )}
    </View>
  );
}

type ChatComposerProps = {
  disabled: boolean;
  isSending?: boolean;
  unavailableMessage?: string;
  onSendMessage: (body: string) => Promise<void>;
  onFocus: () => void;
  onSent: () => void;
};

export function ChatComposer({
  disabled,
  isSending = false,
  unavailableMessage,
  onSendMessage,
  onFocus,
  onSent,
}: ChatComposerProps) {
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string>();
  const sendInFlight = useRef(false);
  const busy = sending || isSending;

  async function send() {
    if (disabled || isSending || sendInFlight.current) {
      return;
    }
    const nextError = validateMessage(draft);
    setError(nextError);
    if (nextError) {
      return;
    }
    const body = draft.trim();
    sendInFlight.current = true;
    setSending(true);
    try {
      await onSendMessage(body);
      setDraft(current => (current.trim() === body ? '' : current));
      onSent();
    } catch {
      setError('Message not sent. Check your connection and try again.');
    } finally {
      sendInFlight.current = false;
      setSending(false);
    }
  }

  return (
    <View style={styles.composerOuter}>
      <View style={styles.composerContent}>
        {unavailableMessage && (
          <Text accessibilityLiveRegion="polite" style={styles.unavailable}>
            {unavailableMessage}
          </Text>
        )}
        <View style={styles.composerRow}>
          <TextInput
            testID="chat-message-input"
            accessibilityLabel="Message to the room"
            accessibilityHint={error}
            placeholder="Say something good..."
            placeholderTextColor={colors.muted}
            value={draft}
            onChangeText={value => {
              setDraft(value);
              setError(undefined);
            }}
            onFocus={onFocus}
            editable={!disabled && !busy}
            multiline
            maxLength={1000}
            selectionColor={colors.green}
            style={[styles.composerInput, !!error && styles.inputError]}
          />
          <Button
            label={busy ? 'Sending...' : 'Send'}
            onPress={send}
            disabled={disabled || !draft.trim()}
            busy={busy}
            testID="send-message-button"
            style={styles.sendButton}
          />
        </View>
        {error ? (
          <Text accessibilityLiveRegion="polite" style={styles.error}>
            {error}
          </Text>
        ) : draft.length > 850 ? (
          <Text style={styles.counter}>{draft.length}/1,000</Text>
        ) : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  thread: { gap: 16 },
  title: {
    fontSize: 21,
    fontWeight: '700',
    letterSpacing: -0.6,
    color: colors.ink,
  },
  emptyChat: {
    padding: 20,
    borderRadius: 20,
    borderWidth: 1,
    borderStyle: 'dashed',
    borderColor: colors.line,
    gap: 6,
  },
  emptyTitle: {
    fontSize: 16,
    lineHeight: 23,
    fontWeight: '700',
    color: colors.ink,
  },
  emptyBody: { fontSize: 14, lineHeight: 21, color: colors.muted },
  messages: { gap: 17 },
  message: { alignSelf: 'flex-start', maxWidth: '94%', gap: 6 },
  ownMessage: { alignSelf: 'flex-end' },
  messageMeta: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 3,
  },
  ownMeta: { justifyContent: 'flex-end' },
  author: {
    fontSize: 12,
    fontWeight: '700',
    color: colors.muted,
    flexShrink: 1,
  },
  time: { fontSize: 11, color: colors.muted },
  bubble: {
    borderRadius: 18,
    borderTopLeftRadius: 5,
    backgroundColor: colors.paper,
    paddingHorizontal: 15,
    paddingVertical: 12,
    borderWidth: 1,
    borderColor: colors.line,
  },
  ownBubble: {
    backgroundColor: colors.ink,
    borderColor: colors.ink,
    borderTopLeftRadius: 18,
    borderTopRightRadius: 5,
  },
  messageBody: { fontSize: 15, lineHeight: 22, color: colors.ink },
  ownBody: { color: colors.paper },
  composerOuter: {
    borderTopWidth: 1,
    borderTopColor: colors.line,
    backgroundColor: colors.background,
    paddingHorizontal: 22,
    paddingVertical: 12,
  },
  composerContent: {
    width: '100%',
    maxWidth: 560,
    alignSelf: 'center',
    gap: 7,
  },
  composerRow: { flexDirection: 'row', alignItems: 'flex-end', gap: 9 },
  composerInput: {
    flex: 1,
    minHeight: 52,
    maxHeight: 126,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.paper,
    paddingHorizontal: 14,
    paddingTop: 14,
    paddingBottom: 14,
    textAlignVertical: 'top',
    fontSize: 15,
    lineHeight: 21,
    color: colors.ink,
  },
  sendButton: { paddingHorizontal: 15, maxWidth: '40%' },
  unavailable: { fontSize: 12, lineHeight: 18, color: colors.muted },
  inputError: { borderColor: colors.error },
  error: { fontSize: 12, lineHeight: 18, color: colors.error },
  counter: {
    fontSize: 11,
    lineHeight: 17,
    color: colors.muted,
    textAlign: 'right',
  },
});
