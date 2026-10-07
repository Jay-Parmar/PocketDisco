import React from 'react';
import { TextInput } from 'react-native';
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
    onSaveServerUrl: jest.fn(),
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
  expect(tree).toContain('Playback is coming next.');
  expect(tree).not.toContain('server-settings-toggle');
});

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
