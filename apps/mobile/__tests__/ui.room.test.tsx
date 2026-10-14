import React from 'react';
import {
  AppState,
  DeviceEventEmitter,
  Platform,
  ScrollView,
  StyleSheet,
  TextInput,
} from 'react-native';
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
  expect(scrollTo).toHaveBeenLastCalledWith({ y: 800, animated: false });
  await press('room-nav-chat');
  expect(scrollToEnd).toHaveBeenLastCalledWith({ animated: true });
  await press('room-nav-room');
  expect(scrollTo).toHaveBeenLastCalledWith({ y: 0, animated: false });
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
  expect(scrollTo).toHaveBeenLastCalledWith({ y: 0, animated: false });
});

test('keeps a failed room action visible from chat and jumps to its details', async () => {
  const props = await render();
  const scrollTo = jest.spyOn(
    screen.root.findByType(ScrollView).instance,
    'scrollTo',
  );
  await press('room-nav-chat');
  await act(async () => {
    screen.update(
      <RoomScreen {...props} error="Could not leave the room. Try again." />,
    );
  });
  expect(
    screen.root
      .findByProps({ testID: 'room-header' })
      .findByProps({ testID: 'room-error-shortcut' }).props.children,
  ).toBe('View error');
  expect(button('room-nav-room').props.accessibilityLabel).toContain(
    'Could not leave the room.',
  );
  await act(async () => {
    screen.root.findByProps({ testID: 'room-error-details' }).props.onLayout({
      nativeEvent: { layout: { y: 290 } },
    });
  });
  await press('room-nav-room');
  expect(scrollTo).toHaveBeenLastCalledWith({ y: 290, animated: false });
  await act(async () => screen.update(<RoomScreen {...props} error={null} />));
  expect(
    screen.root.findAllByProps({ testID: 'room-error-shortcut' }),
  ).toHaveLength(0);
  await press('room-nav-room');
  expect(scrollTo).toHaveBeenLastCalledWith({ y: 0, animated: false });
});

function scrollEvent(y: number) {
  return {
    nativeEvent: {
      contentOffset: { y },
      layoutMeasurement: { height: 600 },
      contentSize: { height: 3000 },
    },
  };
}

function viewportLayout(height: number) {
  return { nativeEvent: { layout: { width: 360, height, x: 0, y: 0 } } };
}

test.each([0, 2])(
  'gives a short chat with %i messages its own viewport',
  async count => {
    await render({
      room: {
        ...room,
        messages: Array.from({ length: count }, (_, index) => ({
          id: `short-${index}`,
          user_id: 'sam',
          display_name: 'Sam',
          body: `Message ${index + 1}`,
          created_at_ms: 1791374400000 + index,
        })),
      },
    });
    const scrolling = screen.root.findByType(ScrollView);
    await act(async () => scrolling.props.onLayout(viewportLayout(600)));
    const chatStyle = StyleSheet.flatten(
      screen.root.findByProps({ testID: 'room-section-chat' }).props.style,
    );
    const padding = StyleSheet.flatten(
      scrolling.props.contentContainerStyle,
    ).paddingVertical;
    expect(chatStyle.minHeight + 2 * padding).toBe(600);
    expect(chatStyle.height).toBeUndefined();
    const scrollToEnd = jest.spyOn(scrolling.instance, 'scrollToEnd');
    await press('room-nav-chat');
    expect(scrollToEnd).toHaveBeenLastCalledWith({ animated: true });
  },
);

test('resizes short chat with the keyboard without losing a draft or remounting audio', async () => {
  await render();
  const native = jest.mocked(AudioPlayback);
  native.prepare.mockClear();
  native.disconnect.mockClear();
  const scrolling = screen.root.findByType(ScrollView);
  const scrollToEnd = jest.spyOn(scrolling.instance, 'scrollToEnd');
  const composer = screen.root.findByType(TextInput);
  await act(async () => composer.props.onChangeText('Keep this draft'));
  await press('room-nav-chat');
  for (const height of [700, 320, 700]) {
    await act(async () => scrolling.props.onLayout(viewportLayout(height)));
    const chatStyle = StyleSheet.flatten(
      screen.root.findByProps({ testID: 'room-section-chat' }).props.style,
    );
    expect(chatStyle.minHeight).toBe(height - 40);
    await act(async () => scrolling.props.onContentSizeChange());
    expect(scrollToEnd).toHaveBeenLastCalledWith({ animated: true });
  }
  expect(composer.props.value).toBe('Keep this draft');
  expect(native.prepare).not.toHaveBeenCalled();
  expect(native.disconnect).not.toHaveBeenCalled();
});

test.each([
  { height: 752, overlap: 256 },
  { height: 496, overlap: 0 },
])(
  'Android avoids the measured keyboard overlap without double padding: %o',
  async ({ height, overlap }) => {
    const platform = jest.replaceProperty(Platform, 'OS', 'android');
    try {
      await render();
      const avoidingView = () =>
        screen.root.findAll(
          node =>
            node.props.testID === 'room-screen' &&
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
    } finally {
      await act(async () => DeviceEventEmitter.emit('keyboardDidHide', {}));
      platform.restore();
    }
  },
);

test('keyboard resize and stale bottom events cannot hijack a room jump', async () => {
  await render();
  const scrolling = screen.root.findByType(ScrollView);
  const scrollTo = jest.spyOn(scrolling.instance, 'scrollTo');
  const scrollToEnd = jest.spyOn(scrolling.instance, 'scrollToEnd');
  await press('room-nav-chat');
  scrollToEnd.mockClear();
  await press('room-nav-room');
  await act(async () => {
    scrolling.props.onScroll(scrollEvent(2400));
    scrolling.props.onLayout(viewportLayout(600));
    scrolling.props.onContentSizeChange();
  });
  expect(scrollToEnd).not.toHaveBeenCalled();
  expect(scrollTo).toHaveBeenLastCalledWith({ y: 0, animated: false });
  expect(button('room-nav-room').props.accessibilityState.selected).toBe(true);
  await act(async () => {
    scrolling.props.onScroll(scrollEvent(0));
    scrolling.props.onLayout(viewportLayout(600));
  });
  expect(scrollToEnd).not.toHaveBeenCalled();
});

test('a real drag cancels a pending jump and follows the visible section', async () => {
  await render();
  const scrolling = screen.root.findByType(ScrollView);
  await press('room-nav-room');
  await act(async () => {
    scrolling.props.onScrollBeginDrag();
    scrolling.props.onScroll(scrollEvent(2400));
  });
  expect(button('room-nav-chat').props.accessibilityState.selected).toBe(true);
});

test('an unobserved jump expires instead of suppressing future accessibility scrolling', async () => {
  const now = jest.spyOn(Date, 'now').mockReturnValue(1000);
  try {
    await render();
    await press('room-nav-room');
    now.mockReturnValue(2001);
    await act(async () => {
      screen.root.findByType(ScrollView).props.onScroll(scrollEvent(2400));
    });
    expect(button('room-nav-chat').props.accessibilityState.selected).toBe(
      true,
    );
  } finally {
    now.mockRestore();
  }
});

test('focusing chat cancels a pending jump and follows the resized keyboard viewport', async () => {
  await render();
  const scrolling = screen.root.findByType(ScrollView);
  const scrollToEnd = jest.spyOn(scrolling.instance, 'scrollToEnd');
  await press('room-nav-audio');
  await act(async () => {
    screen.root.findByType(TextInput).props.onFocus();
    scrolling.props.onLayout(viewportLayout(600));
  });
  expect(scrollToEnd).toHaveBeenLastCalledWith({ animated: false });
  expect(button('room-nav-chat').props.accessibilityState.selected).toBe(true);
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
