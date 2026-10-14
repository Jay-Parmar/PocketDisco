import React from 'react';
import { AppState, ScrollView, StyleSheet, TextInput } from 'react-native';
import ReactTestRenderer, { act } from 'react-test-renderer';

import AudioPlayback from '../src/native/NativeAudioPlayback';
import NearbyAudio from '../src/native/NativeNearbyAudio';
import { RoomScreen } from '../src/ui/RoomScreen';
import type { RoomScreenProps } from '../src/ui/RoomScreen';
import type { RoomDetails } from '../src/ui/types';

const room: RoomDetails = {
  room_id: 'room-1',
  name: 'Night shift',
  host_id: 'sam',
  members: [
    {
      user_id: 'sam',
      display_name: 'Sam',
      role: 'host',
      ready: false,
      connected: true,
    },
    {
      user_id: 'lee',
      display_name: 'Lee',
      role: 'listener',
      ready: true,
      connected: true,
    },
    {
      user_id: 'rae',
      display_name: 'Rae',
      role: 'listener',
      ready: true,
      connected: false,
    },
  ],
  messages: [],
};

let screen: ReactTestRenderer.ReactTestRenderer;

async function render(overrides: Partial<RoomScreenProps> = {}) {
  const props: RoomScreenProps = {
    room,
    currentUserId: 'sam',
    inviteCode: 'ABCD2345EFGH',
    connectionStatus: 'connected',
    onReadyChange: jest.fn(),
    onSendMessage: jest.fn().mockResolvedValue(undefined),
    onShareInvite: jest.fn(),
    onLeave: jest.fn(),
    onRetryConnection: jest.fn(),
    ...overrides,
  };
  await act(async () => {
    screen = ReactTestRenderer.create(<RoomScreen {...props} />);
  });
  return props;
}

function button(id: string) {
  return screen.root.findAll(
    node => node.props.testID === id && node.props.accessibilityState,
  )[0]!;
}

async function press(id: string) {
  await act(async () => button(id).props.onPress());
}

afterEach(async () => {
  await act(async () => screen?.unmount());
  jest.mocked(NearbyAudio).getAvailability.mockReturnValue({
    enabled: false,
    supported: false,
  });
});

test('shows live members, roles, and their actual ready states', async () => {
  await render();
  expect(
    screen.root.findByProps({ testID: 'member-sam' }).props.accessibilityLabel,
  ).toBe('Sam, you, host, Here');
  expect(
    screen.root.findByProps({ testID: 'member-lee' }).props.accessibilityLabel,
  ).toBe('Lee, listener, Ready');
  expect(
    screen.root.findByProps({ testID: 'member-rae' }).props.accessibilityLabel,
  ).toBe('Rae, listener, Away');
  expect(JSON.stringify(screen.toJSON())).toContain(
    'Room playback is not connected yet.',
  );
});

test('keeps connection, room shortcuts and leaving outside the scrolled content', async () => {
  await render();
  const header = screen.root.findByProps({ testID: 'room-header' });
  expect(header.findAllByType(ScrollView)).toHaveLength(0);
  expect(
    header.findAllByProps({ testID: 'leave-room-button' }).length,
  ).toBeGreaterThan(0);
  for (const section of ['room', 'audio', 'chat']) {
    const shortcut = button(`room-nav-${section}`);
    expect(shortcut.props.accessibilityRole).toBe('button');
    const style =
      typeof shortcut.props.style === 'function'
        ? shortcut.props.style({ pressed: false })
        : shortcut.props.style;
    expect(StyleSheet.flatten(style).minHeight).toBeGreaterThanOrEqual(48);
  }
});

test('section shortcuts scroll without unmounting audio or discarding a chat draft', async () => {
  const native = jest.mocked(AudioPlayback);
  native.prepare.mockClear();
  native.disconnect.mockClear();
  await render();
  const scrolling = screen.root.findByType(ScrollView).instance;
  const scrollTo = jest.spyOn(scrolling, 'scrollTo');
  const scrollToEnd = jest.spyOn(scrolling, 'scrollToEnd');
  const composer = screen.root.findByType(TextInput);
  await act(async () => {
    composer.props.onChangeText('Keep my draft');
    screen.root.findByProps({ testID: 'room-section-audio' }).props.onLayout({
      nativeEvent: { layout: { y: 800 } },
    });
  });
  await press('room-nav-audio');
  expect(scrollTo).toHaveBeenLastCalledWith({ y: 800, animated: true });
  await press('room-nav-chat');
  expect(scrollToEnd).toHaveBeenLastCalledWith({ animated: true });
  await press('room-nav-room');
  expect(scrollTo).toHaveBeenLastCalledWith({ y: 0, animated: true });
  expect(composer.props.value).toBe('Keep my draft');
  expect(
    screen.root.findAllByProps({ testID: 'demo-player' }),
  ).not.toHaveLength(0);
  expect(native.prepare).not.toHaveBeenCalled();
  expect(native.disconnect).not.toHaveBeenCalled();
});

test('scrolling updates the selected shortcut including long chat threads', async () => {
  await render();
  await act(async () => {
    screen.root.findByProps({ testID: 'room-section-audio' }).props.onLayout({
      nativeEvent: { layout: { y: 800 } },
    });
    screen.root.findByProps({ testID: 'room-section-chat' }).props.onLayout({
      nativeEvent: { layout: { y: 1600 } },
    });
  });
  for (const [y, section] of [
    [0, 'room'],
    [800, 'audio'],
    [1700, 'chat'],
  ] as const) {
    await act(async () => {
      screen.root.findByType(ScrollView).props.onScroll({
        nativeEvent: {
          contentOffset: { y },
          layoutMeasurement: { height: 500 },
          contentSize: { height: 3000 },
        },
      });
    });
    expect(
      button(`room-nav-${section}`).props.accessibilityState.selected,
    ).toBe(true);
  }
});

test('an early audio shortcut never passes an unmeasured offset to native scroll', async () => {
  await render();
  const scrollTo = jest.spyOn(
    screen.root.findByType(ScrollView).instance,
    'scrollTo',
  );
  await press('room-nav-audio');
  expect(scrollTo).toHaveBeenLastCalledWith({ y: 0, animated: true });
});

test('sends readiness intent without pretending the server accepted it', async () => {
  const props = await render();
  await press('ready-toggle-button');
  expect(props.onReadyChange).toHaveBeenCalledWith(true);
  expect(button('ready-toggle-button').props.accessibilityLabel).toBe(
    "I'm ready",
  );
});

test('allows a ready member to opt out', async () => {
  const props = await render({ currentUserId: 'lee' });
  await press('ready-toggle-button');
  expect(props.onReadyChange).toHaveBeenCalledWith(false);
});

test.each(['connecting', 'reconnecting', 'offline'] as const)(
  'pauses readiness while %s',
  async connectionStatus => {
    const props = await render({ connectionStatus });
    expect(button('ready-toggle-button').props.disabled).toBe(true);
    expect(
      screen.root.findByProps({ testID: 'connection-notice' }),
    ).toBeDefined();
    await press('ready-toggle-button');
    expect(props.onReadyChange).not.toHaveBeenCalled();
  },
);

test('offers a retry while offline and keeps leaving available', async () => {
  const props = await render({ connectionStatus: 'offline' });
  await press('retry-connection-button');
  await press('leave-room-button');
  expect(props.onRetryConnection).toHaveBeenCalledTimes(1);
  expect(props.onLeave).toHaveBeenCalledTimes(1);
});

test('does not let an unknown member change readiness', async () => {
  const props = await render({ currentUserId: 'unknown' });
  expect(button('ready-toggle-button').props.disabled).toBe(true);
  await press('ready-toggle-button');
  expect(props.onReadyChange).not.toHaveBeenCalled();
});

test('shares only a real room invite', async () => {
  const props = await render();
  await press('share-invite-button');
  expect(props.onShareInvite).toHaveBeenCalledTimes(1);
  expect(
    screen.root.findByProps({ testID: 'invite-code' }).props.children,
  ).toBe('ABCD2345EFGH');
  await act(async () => {
    screen.update(<RoomScreen {...props} inviteCode={undefined} />);
  });
  expect(
    screen.root.findAllByProps({ testID: 'share-invite-button' }),
  ).toHaveLength(0);
});

test('expands larger member lists', async () => {
  const extraMembers = Array.from({ length: 4 }, (_, index) => ({
    user_id: `extra-${index}`,
    display_name: `Friend ${index + 1}`,
    role: 'listener' as const,
    ready: false,
    connected: true,
  }));
  await render({
    room: { ...room, members: [...room.members, ...extraMembers] },
  });
  expect(screen.root.findAllByProps({ testID: 'member-extra-3' })).toHaveLength(
    0,
  );
  await press('expand-members-button');
  expect(screen.root.findByProps({ testID: 'member-extra-3' })).toBeDefined();
  await press('expand-members-button');
  expect(screen.root.findAllByProps({ testID: 'member-extra-3' })).toHaveLength(
    0,
  );
});

test('disables member actions while leaving', async () => {
  await render({ busy: true });
  expect(button('leave-room-button').props.disabled).toBe(true);
  expect(button('ready-toggle-button').props.disabled).toBe(true);
  expect(button('share-invite-button').props.disabled).toBe(true);
});

test('local preview uses the native player without changing room readiness', async () => {
  const previous = AppState.currentState;
  AppState.currentState = 'active';
  const native = jest.mocked(AudioPlayback);
  native.prepare.mockClear();
  native.playAt.mockClear();
  native.disconnect.mockClear();
  try {
    const props = await render();
    await press('demo-play-pause');
    expect(native.prepare).toHaveBeenCalledWith('generated-pulse', 0);
    expect(native.playAt).toHaveBeenCalledWith(1500, 0);
    expect(props.onReadyChange).not.toHaveBeenCalled();
    expect(props.onSendMessage).not.toHaveBeenCalled();
    await act(async () => screen.update(<RoomScreen {...props} busy />));
    expect(native.disconnect).toHaveBeenCalledTimes(1);
    expect(button('demo-play-pause').props.disabled).toBe(true);
  } finally {
    AppState.currentState = previous;
  }
});

test('nearby entry opens a separate session without changing room intent', async () => {
  const native = jest.mocked(NearbyAudio);
  native.getAvailability.mockReturnValue({ enabled: true, supported: true });
  native.open.mockClear();
  native.stop.mockClear();
  const props = await render();
  await press('nearby-open');
  expect(native.open).toHaveBeenCalledWith('room-1');
  expect(props.onReadyChange).not.toHaveBeenCalled();
  expect(props.onSendMessage).not.toHaveBeenCalled();
  await act(async () => {
    screen.update(<RoomScreen {...props} connectionStatus="offline" />);
  });
  expect(native.stop).not.toHaveBeenCalled();
});

test.each([
  { connectionStatus: 'offline' as const },
  { currentUserId: 'unknown' },
  { busy: true },
])('nearby entry respects unavailable room controls: %o', async overrides => {
  const native = jest.mocked(NearbyAudio);
  native.getAvailability.mockReturnValue({ enabled: true, supported: true });
  native.open.mockClear();
  await render(overrides);
  expect(button('nearby-open').props.disabled).toBe(true);
  await press('nearby-open');
  expect(native.open).not.toHaveBeenCalled();
});
