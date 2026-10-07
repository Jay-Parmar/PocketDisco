import React from 'react';
import ReactTestRenderer, { act } from 'react-test-renderer';

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
  expect(JSON.stringify(screen.toJSON())).toContain('Playback is coming next.');
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
