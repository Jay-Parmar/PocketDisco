import { createPlaybackProvider, playbackMessage } from '../src/playback';

const observation = {
  itemId: 'generated-pulse',
  status: 'ready',
  positionMs: 0,
  durationMs: 24000,
  sampledAtMonotonicMs: 5000,
  scheduledStartMonotonicMs: null,
  errorCode: null,
};

function bridge() {
  return {
    getCapabilities: jest.fn().mockResolvedValue({
      canSchedule: true,
      canSeek: true,
      canReportPosition: true,
      canRateAdjust: false,
      canBackground: false,
    }),
    prepare: jest.fn().mockResolvedValue(observation),
    playAt: jest.fn().mockResolvedValue({
      ...observation,
      status: 'scheduled',
      scheduledStartMonotonicMs: 5500,
    }),
    pause: jest.fn().mockResolvedValue({ ...observation, status: 'paused' }),
    seek: jest
      .fn()
      .mockResolvedValue({
        ...observation,
        status: 'paused',
        positionMs: 5000,
      }),
    getTimedState: jest.fn().mockResolvedValue(observation),
    disconnect: jest.fn().mockResolvedValue(undefined),
  };
}

test('passes playback intents to native without a JavaScript start timer', async () => {
  const native = bridge();
  const player = createPlaybackProvider(native);
  expect(await player.prepare('generated-pulse', 0)).toEqual(observation);
  expect((await player.playAt(5500, 0)).status).toBe('scheduled');
  expect(native.playAt).toHaveBeenCalledWith(5500, 0);
  expect((await player.seek(5000)).positionMs).toBe(5000);
  expect((await player.pause()).status).toBe('paused');
  expect(await player.getTimedState()).toEqual(observation);
  await player.disconnect();
  expect(native.disconnect).toHaveBeenCalledTimes(1);
});

test('does not advertise unavailable background or rate controls', async () => {
  const player = createPlaybackProvider(bridge());
  expect(await player.getCapabilities()).toEqual({
    canSchedule: true,
    canSeek: true,
    canReportPosition: true,
    canRateAdjust: false,
    canBackground: false,
  });
});

test('fails closed on a malformed native observation', async () => {
  const native = bridge();
  native.getTimedState.mockResolvedValue({ ...observation, positionMs: NaN });
  await expect(
    createPlaybackProvider(native).getTimedState(),
  ).rejects.toMatchObject({ code: 'playback_failed' });
});

test('rejects malformed capabilities', async () => {
  const native = bridge();
  native.getCapabilities.mockResolvedValue({ canSchedule: true });
  await expect(
    createPlaybackProvider(native).getCapabilities(),
  ).rejects.toMatchObject({ code: 'playback_failed' });
});

test('does not surface raw native errors in the UI', async () => {
  const native = bridge();
  native.prepare.mockRejectedValue(
    new Error('secret test path /private/media'),
  );
  let failure: unknown;
  try {
    await createPlaybackProvider(native).prepare('generated-pulse', 0);
  } catch (error) {
    failure = error;
  }
  expect(playbackMessage(failure)).toBe('Could not play the demo. Try again.');
  expect(String(failure)).not.toContain('/private/media');
});

test.each([NaN, Infinity, -1])(
  'rejects invalid playback time %s before native',
  async value => {
    const native = bridge();
    const player = createPlaybackProvider(native);
    await expect(player.seek(value)).rejects.toMatchObject({
      code: 'invalid_position',
    });
    await expect(
      player.prepare('generated-pulse', value),
    ).rejects.toMatchObject({ code: 'invalid_position' });
    await expect(player.playAt(value, 0)).rejects.toMatchObject({
      code: 'invalid_schedule',
    });
    expect(native.seek).not.toHaveBeenCalled();
    expect(native.prepare).not.toHaveBeenCalled();
    expect(native.playAt).not.toHaveBeenCalled();
  },
);
