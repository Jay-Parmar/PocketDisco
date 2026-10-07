import React from 'react';
import { TextInput } from 'react-native';
import ReactTestRenderer, { act } from 'react-test-renderer';

import { ChatComposer, MessageThread } from '../src/ui/RoomChat';

let screen: ReactTestRenderer.ReactTestRenderer;

async function renderComposer(
  overrides: Partial<React.ComponentProps<typeof ChatComposer>> = {},
) {
  const props: React.ComponentProps<typeof ChatComposer> = {
    disabled: false,
    onSendMessage: jest.fn().mockResolvedValue(undefined),
    onFocus: jest.fn(),
    onSent: jest.fn(),
    ...overrides,
  };
  await act(async () => {
    screen = ReactTestRenderer.create(<ChatComposer {...props} />);
  });
  return props;
}

function input() {
  return screen.root.findByType(TextInput);
}

function sendButton() {
  return screen.root.findAll(
    node =>
      node.props.testID === 'send-message-button' &&
      node.props.accessibilityState,
  )[0]!;
}

async function fill(value: string) {
  await act(async () => input().props.onChangeText(value));
}

async function send() {
  await act(async () => sendButton().props.onPress());
}

afterEach(async () => {
  await act(async () => screen?.unmount());
});

test('does not send whitespace', async () => {
  const props = await renderComposer();
  await fill('  ');
  expect(sendButton().props.disabled).toBe(true);
  await send();
  expect(props.onSendMessage).not.toHaveBeenCalled();
});

test('sends trimmed text and clears a confirmed draft', async () => {
  const props = await renderComposer();
  await fill('  Hello from my corner  ');
  await send();
  expect(props.onSendMessage).toHaveBeenCalledWith('Hello from my corner');
  expect(props.onSent).toHaveBeenCalledTimes(1);
  expect(input().props.value).toBe('');
});

test('keeps a failed draft for retry', async () => {
  const props = await renderComposer({
    onSendMessage: jest.fn().mockRejectedValue(new Error('Offline')),
  });
  await fill('Keep this message');
  await send();
  expect(input().props.value).toBe('Keep this message');
  expect(props.onSent).not.toHaveBeenCalled();
  expect(JSON.stringify(screen.toJSON())).toContain('Message not sent.');
});

test('guards duplicate presses while a send is pending', async () => {
  let confirmSend!: () => void;
  const pending = new Promise<void>(resolve => {
    confirmSend = resolve;
  });
  const props = await renderComposer({ onSendMessage: jest.fn(() => pending) });
  await fill('Just one message');
  await act(async () => {
    sendButton().props.onPress();
    sendButton().props.onPress();
  });
  expect(sendButton().props.disabled).toBe(true);
  expect(input().props.editable).toBe(false);
  expect(props.onSendMessage).toHaveBeenCalledTimes(1);
  expect(input().props.value).toBe('Just one message');
  await act(async () => confirmSend());
  expect(input().props.value).toBe('');
});

test('retains a newer draft if it changed before a send completed', async () => {
  let confirmSend!: () => void;
  const props = await renderComposer({
    onSendMessage: jest.fn(
      () =>
        new Promise<void>(resolve => {
          confirmSend = resolve;
        }),
    ),
  });
  await fill('First message');
  await act(async () => {
    sendButton().props.onPress();
  });
  await fill('Next message');
  await act(async () => confirmSend());
  expect(props.onSendMessage).toHaveBeenCalledWith('First message');
  expect(input().props.value).toBe('Next message');
});

test.each([{ disabled: true }, { isSending: true }])(
  'pauses chat controls with %p',
  async overrides => {
    const props = await renderComposer(overrides);
    await fill('Wait for connection');
    expect(sendButton().props.disabled).toBe(true);
    expect(input().props.editable).toBe(false);
    await send();
    expect(props.onSendMessage).not.toHaveBeenCalled();
  },
);

test('rejects an oversized message', async () => {
  const props = await renderComposer();
  await fill('a'.repeat(1001));
  await send();
  expect(props.onSendMessage).not.toHaveBeenCalled();
  expect(JSON.stringify(screen.toJSON())).toContain(
    'Keep your message to 1,000 characters.',
  );
});

test('opens the latest messages when the composer is focused', async () => {
  const props = await renderComposer();
  await act(async () => input().props.onFocus());
  expect(props.onFocus).toHaveBeenCalledTimes(1);
});

test('renders real messages with authors and plain text bodies', async () => {
  await act(async () => {
    screen = ReactTestRenderer.create(
      <MessageThread
        currentUserId="sam"
        messages={[
          {
            id: 'm1',
            user_id: 'sam',
            display_name: 'Sam',
            body: 'Hi everyone',
            created_at_ms: 1791374400000,
          },
          {
            id: 'm2',
            user_id: 'lee',
            display_name: 'Lee',
            body: '<b>Here!</b>',
            created_at_ms: 1791374401000,
          },
        ]}
      />,
    );
  });
  expect(
    screen.root.findByProps({ testID: 'message-m1' }).props.accessibilityLabel,
  ).toMatch(/^You, .+: Hi everyone$/);
  expect(
    screen.root.findByProps({ testID: 'message-m2' }).props.accessibilityLabel,
  ).toMatch(/^Lee, .+: <b>Here!<\/b>$/);
});

test('shows an empty chat without inventing messages', async () => {
  await act(async () => {
    screen = ReactTestRenderer.create(
      <MessageThread messages={[]} currentUserId="sam" />,
    );
  });
  expect(JSON.stringify(screen.toJSON())).toContain(
    'The room starts with a hello.',
  );
});
