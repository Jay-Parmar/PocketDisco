import React from 'react';
import {
  DeviceEventEmitter,
  Platform,
  StyleSheet,
  TextInput,
} from 'react-native';
import ReactTestRenderer, { act } from 'react-test-renderer';

import { WelcomeScreen } from '../src/ui/WelcomeScreen';
import type { WelcomeScreenProps } from '../src/ui/WelcomeScreen';

let screen: ReactTestRenderer.ReactTestRenderer;

async function render(overrides: Partial<WelcomeScreenProps> = {}) {
  const props: WelcomeScreenProps = {
    serverUrl: 'http://127.0.0.1:8000',
    showServerSettings: true,
    busy: null,
    onCreate: jest.fn(),
    onJoin: jest.fn(),
    onSaveServerUrl: jest.fn().mockResolvedValue(true),
    ...overrides,
  };
  await act(async () => {
    screen = ReactTestRenderer.create(<WelcomeScreen {...props} />);
  });
  return props;
}

function button(id: string) {
  return screen.root.findAll(
    node => node.props.testID === id && node.props.accessibilityState,
  )[0]!;
}

function input(id: string) {
  return screen.root
    .findAllByType(TextInput)
    .find(node => node.props.testID === id)!;
}

async function press(id: string) {
  await act(async () => button(id).props.onPress());
}

async function fill(id: string, value: string) {
  await act(async () => input(id).props.onChangeText(value));
}

afterEach(async () => {
  await act(async () => screen?.unmount());
});

test('shows the alpha boundary and hides development settings in release', async () => {
  await render({ showServerSettings: false });
  const tree = JSON.stringify(screen.toJSON());
  expect(tree).toContain('PRIVATE ROOM ALPHA');
  expect(tree).toContain('Chat together, try audio locally.');
  expect(tree).not.toContain('server-settings-toggle');
  expect(tree).not.toContain('server-connection-help');
});

test('explains the private USB route without claiming the server is reachable', async () => {
  await render();
  const content = JSON.stringify(screen.toJSON());
  expect(content).toContain('USB test route.');
  expect(content).toContain('server tunnel running.');
  expect(content).not.toContain('Server address saved.');
});

test('does not give USB advice for a remote server', async () => {
  await render({ serverUrl: 'https://rooms.example.com' });
  expect(
    screen.root.findAllByProps({ testID: 'server-connection-help' }),
  ).toHaveLength(0);
});

test.each([
  { height: 752, overlap: 256 },
  { height: 496, overlap: 0 },
])(
  'Android keeps form fields above the measured keyboard overlap: %o',
  async ({ height, overlap }) => {
    const platform = jest.replaceProperty(Platform, 'OS', 'android');
    try {
      await render();
      await fill('display-name-input', 'Sam');
      const avoidingView = () =>
        screen.root.findAll(
          node =>
            node.props.testID === 'welcome-screen' &&
            typeof node.props.onLayout === 'function',
        )[0]!;
      await act(async () => {
        await avoidingView().props.onLayout({
          persist: jest.fn(),
          nativeEvent: { layout: { x: 0, y: 24, width: 360, height } },
        });
        DeviceEventEmitter.emit('keyboardDidShow', {
          duration: 0,
          easing: 'keyboard',
          endCoordinates: { screenX: 0, screenY: 520, width: 360, height: 280 },
        });
      });
      expect(StyleSheet.flatten(avoidingView().props.style).paddingBottom).toBe(
        overlap,
      );
      await act(async () => DeviceEventEmitter.emit('keyboardDidHide', {}));
      expect(StyleSheet.flatten(avoidingView().props.style).paddingBottom).toBe(
        0,
      );
      expect(input('display-name-input').props.value).toBe('Sam');
    } finally {
      await act(async () => DeviceEventEmitter.emit('keyboardDidHide', {}));
      platform.restore();
    }
  },
);

test('explains missing fields without creating a room', async () => {
  const props = await render();
  await press('create-room-button');
  expect(props.onCreate).not.toHaveBeenCalled();
  expect(JSON.stringify(screen.toJSON())).toContain('Give your room a name.');
  expect(JSON.stringify(screen.toJSON())).toContain('Add a name');
});

test('submits a trimmed name and room', async () => {
  const props = await render();
  await fill('display-name-input', '  Sam  ');
  await fill('room-name-input', '  Night shift  ');
  await press('create-room-button');
  expect(props.onCreate).toHaveBeenCalledWith({
    displayName: 'Sam',
    roomName: 'Night shift',
  });
});

test('retains the name when switching modes and validates the invite', async () => {
  const props = await render();
  await fill('display-name-input', 'Sam');
  await press('join-tab');
  expect(input('display-name-input').props.value).toBe('Sam');
  await fill('invite-code-input', '123');
  await press('join-room-button');
  expect(props.onJoin).not.toHaveBeenCalled();
  expect(JSON.stringify(screen.toJSON())).toContain(
    'Enter the 12-character code',
  );
  await fill('invite-code-input', 'abcd2345efgh');
  await press('join-room-button');
  expect(props.onJoin).toHaveBeenCalledWith({
    displayName: 'Sam',
    inviteCode: 'ABCD2345EFGH',
  });
});

test('disables room edits and duplicate submits while joining', async () => {
  const props = await render({ busy: 'joining' });
  expect(button('create-room-button').props.disabled).toBe(true);
  expect(button('join-tab').props.disabled).toBe(true);
  expect(input('display-name-input').props.editable).toBe(false);
  await press('create-room-button');
  expect(props.onCreate).not.toHaveBeenCalled();
  expect(props.onJoin).not.toHaveBeenCalled();
  expect(JSON.stringify(screen.toJSON())).toContain('Joining your friends...');
});

test('rejects invalid server URLs and saves an origin', async () => {
  const props = await render();
  await press('server-settings-toggle');
  await fill('server-url-input', 'rooms.example.com');
  await press('save-server-button');
  expect(props.onSaveServerUrl).not.toHaveBeenCalled();
  await fill('server-url-input', ' https://rooms.example.com/ ');
  await press('save-server-button');
  expect(props.onSaveServerUrl).toHaveBeenCalledWith(
    'https://rooms.example.com',
  );
  expect(JSON.stringify(screen.toJSON())).toContain('Server address saved.');
});

test('shows service errors and a dismiss action', async () => {
  const dismiss = jest.fn();
  await render({ error: 'This invite has expired.', onDismissError: dismiss });
  expect(JSON.stringify(screen.toJSON())).toContain('This invite has expired.');
  const dismissButton = screen.root
    .findAll(node => node.props.accessibilityRole === 'button')
    .find(node => node.props.accessibilityLabel === 'Dismiss')!;
  await act(async () => dismissButton.props.onPress());
  expect(dismiss).toHaveBeenCalledTimes(1);
});

test('retries a saved room without asking for its invite again', async () => {
  const resume = jest.fn();
  await render({ canResume: true, onResume: resume });
  await press('resume-room-button');
  expect(resume).toHaveBeenCalledTimes(1);
});

test('does not confirm an address before secure storage succeeds', async () => {
  let finish!: (saved: boolean) => void;
  await render({
    onSaveServerUrl: () =>
      new Promise(resolve => {
        finish = resolve;
      }),
  });
  await press('server-settings-toggle');
  await act(async () => {
    button('save-server-button').props.onPress();
  });
  expect(JSON.stringify(screen.toJSON())).not.toContain(
    'Server address saved.',
  );
  expect(button('save-server-button').props.disabled).toBe(true);
  await act(async () => {
    finish(false);
  });
  expect(JSON.stringify(screen.toJSON())).not.toContain(
    'Server address saved.',
  );
  expect(JSON.stringify(screen.toJSON())).toContain(
    'Could not save this address.',
  );
});

test('shows a rejected address save as a retryable error', async () => {
  await render({
    onSaveServerUrl: jest.fn().mockRejectedValue(new Error('storage')),
  });
  await press('server-settings-toggle');
  await press('save-server-button');
  expect(JSON.stringify(screen.toJSON())).not.toContain(
    'Server address saved.',
  );
  expect(JSON.stringify(screen.toJSON())).toContain(
    'Could not save this address.',
  );
});
