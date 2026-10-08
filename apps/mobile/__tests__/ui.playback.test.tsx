import React from 'react';
import ReactTestRenderer, { act } from 'react-test-renderer';
import { DemoPlayerCard } from '../src/ui/DemoPlayer';
import { emptyPlayback } from '../src/playback';
import type { useDemoPlayback } from '../src/useDemoPlayback';

let screen: ReactTestRenderer.ReactTestRenderer;
type Controls = ReturnType<typeof useDemoPlayback>;

async function render(overrides: Partial<Controls> = {}) {
  const playback: Controls = {
    state: emptyPlayback,
    busy: false,
    error: null,
    available: true,
    play: jest.fn().mockResolvedValue(undefined),
    pause: jest.fn().mockResolvedValue(undefined),
    seekBy: jest.fn().mockResolvedValue(undefined),
    ...overrides,
  };
  await act(async () => {
    screen = ReactTestRenderer.create(<DemoPlayerCard playback={playback} />);
  });
  return playback;
}

function button(id: string) {
  return screen.root.findAll(
    node => node.props.testID === id && node.props.accessibilityState,
  )[0]!;
}

afterEach(async () => {
  await act(async () => screen?.unmount());
});

test('labels local demo audio without promising room synchronization', async () => {
  const controls = await render();
  const text = JSON.stringify(screen.toJSON());
  expect(text).toContain('Pocket pulse');
  expect(text).toContain('Only this phone will play.');
  expect(text).toContain('Room playback is not connected yet.');
  expect(controls.play).not.toHaveBeenCalled();
  await act(async () => button('demo-play-pause').props.onPress());
  expect(controls.play).toHaveBeenCalledTimes(1);
});

test.each(['playing', 'scheduled'] as const)(
  'offers pause while %s',
  async status => {
    const playback = await render({
      state: {
        ...emptyPlayback,
        itemId: 'generated-pulse',
        status,
        durationMs: 24000,
        positionMs: 6000,
        scheduledStartMonotonicMs: status === 'scheduled' ? 2000 : null,
      },
    });
    expect(button('demo-play-pause').props.accessibilityLabel).toBe(
      'Pause demo',
    );
    await act(async () => button('demo-play-pause').props.onPress());
    expect(playback.pause).toHaveBeenCalledTimes(1);
    expect(playback.play).not.toHaveBeenCalled();
  },
);

test('shows sampled progress and accessible seek controls', async () => {
  const playback = await render({
    state: {
      ...emptyPlayback,
      itemId: 'generated-pulse',
      status: 'paused',
      durationMs: 24000,
      positionMs: 6000,
    },
  });
  const progress = screen.root.findByProps({ testID: 'demo-progress' });
  expect(progress.props.accessibilityValue).toMatchObject({
    min: 0,
    max: 24000,
    now: 6000,
  });
  await act(async () => button('demo-seek-back').props.onPress());
  await act(async () => button('demo-seek-forward').props.onPress());
  expect(playback.seekBy).toHaveBeenNthCalledWith(1, -5000);
  expect(playback.seekBy).toHaveBeenNthCalledWith(2, 5000);
});

test('disables seeks until an item is loaded', async () => {
  await render();
  expect(button('demo-seek-back').props.disabled).toBe(true);
  expect(button('demo-seek-forward').props.disabled).toBe(true);
});

test.each([{ busy: true }, { available: false }])(
  'disables unavailable controls: %o',
  async options => {
    await render(options);
    expect(button('demo-play-pause').props.disabled).toBe(true);
    expect(button('demo-seek-back').props.disabled).toBe(true);
    expect(button('demo-seek-forward').props.disabled).toBe(true);
  },
);

test('offers replay and announces a safe error', async () => {
  await render({
    state: { ...emptyPlayback, status: 'ended' },
    error: 'Could not play the demo. Try again.',
  });
  expect(button('demo-play-pause').props.accessibilityLabel).toBe(
    'Replay demo',
  );
  expect(
    screen.root.findByProps({ testID: 'demo-error' }).props
      .accessibilityLiveRegion,
  ).toBe('polite');
});
