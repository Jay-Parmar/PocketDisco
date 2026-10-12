import React from 'react';
import ReactTestRenderer, { act } from 'react-test-renderer';

import NearbyAudio from '../src/native/NativeNearbyAudio';
import { NearbyAudioCard } from '../src/ui/NearbyAudioCard';

const native = jest.mocked(NearbyAudio);
let screen: ReactTestRenderer.ReactTestRenderer | undefined;

async function render(canOpen = true) {
  await act(async () => {
    screen = ReactTestRenderer.create(
      <NearbyAudioCard roomId="room-1" canOpen={canOpen} />,
    );
  });
}

function button() {
  return screen!.root.findAll(
    node =>
      node.props.testID === 'nearby-open' && node.props.accessibilityState,
  )[0]!;
}

async function press() {
  await act(async () => {
    button().props.onPress();
  });
}

async function unmount() {
  await act(async () => screen?.unmount());
  screen = undefined;
}

beforeEach(() => {
  native.getAvailability.mockReset().mockReturnValue({
    enabled: true,
    supported: true,
  });
  native.open.mockReset().mockResolvedValue(undefined);
  native.stop.mockReset().mockResolvedValue(undefined);
});

afterEach(unmount);

test('release builds have no nearby controls or native session calls', async () => {
  native.getAvailability.mockReturnValue({ enabled: false, supported: false });
  await render();
  expect(screen!.toJSON()).toBeNull();
  await unmount();
  expect(native.open).not.toHaveBeenCalled();
  expect(native.stop).not.toHaveBeenCalled();
});

test('Android versions below 10 cannot open the test', async () => {
  native.getAvailability.mockReturnValue({ enabled: true, supported: false });
  await render();
  expect(
    screen!.root.findByProps({ testID: 'nearby-unsupported' }),
  ).toBeDefined();
  expect(screen!.root.findAllByProps({ testID: 'nearby-open' })).toHaveLength(
    0,
  );
  expect(native.open).not.toHaveBeenCalled();
});

test('states the nearby boundary without automatically opening capture', async () => {
  await render();
  const content = JSON.stringify(screen!.toJSON());
  expect(content).toContain('Nearby audio (test)');
  expect(content).toContain('128-bit code and paired Bluetooth');
  expect(content).toContain('Not internet room playback.');
  expect(content).toContain('not room membership');
  expect(content).toContain('permission to share');
  expect(native.open).not.toHaveBeenCalled();
});

test('explicit entry passes its owning room to the native handoff', async () => {
  await render();
  await press();
  expect(native.open).toHaveBeenCalledTimes(1);
  expect(native.open).toHaveBeenCalledWith('room-1');
});

test('unavailable room controls cannot open the native activity', async () => {
  await render(false);
  expect(button().props.disabled).toBe(true);
  await press();
  expect(native.open).not.toHaveBeenCalled();
});

test('repeated taps cannot overlap activity launches', async () => {
  let finish!: () => void;
  native.open.mockReturnValueOnce(
    new Promise(resolve => {
      finish = resolve;
    }),
  );
  await render();
  await press();
  expect(button().props.disabled).toBe(true);
  await press();
  expect(native.open).toHaveBeenCalledTimes(1);
  await act(async () => finish());
  expect(button().props.disabled).toBe(false);
});

test('leaving the room stops the nearby session', async () => {
  await render();
  await press();
  await unmount();
  expect(native.stop).toHaveBeenCalledTimes(1);
});

test('the handoff background disconnect does not end nearby audio', async () => {
  await render();
  await press();
  await act(async () => {
    screen!.update(<NearbyAudioCard roomId="room-1" canOpen={false} />);
  });
  expect(native.stop).not.toHaveBeenCalled();
  expect(button().props.disabled).toBe(true);
});

test('switching rooms stops the old session and ignores its late failure', async () => {
  let fail!: (error: Error) => void;
  native.open.mockReturnValueOnce(
    new Promise((_, reject) => {
      fail = reject;
    }),
  );
  await render();
  await press();
  await act(async () => {
    screen!.update(<NearbyAudioCard roomId="room-2" canOpen />);
  });
  expect(native.stop).toHaveBeenCalledTimes(1);
  await act(async () => fail(new Error('private native details')));
  expect(screen!.root.findAllByProps({ testID: 'nearby-error' })).toHaveLength(
    0,
  );
  await press();
  expect(native.open).toHaveBeenLastCalledWith('room-2');
});

test('native failures show a safe retry message', async () => {
  native.open.mockRejectedValueOnce(new Error('private native details'));
  await render();
  await press();
  const message = screen!.root.findByProps({ testID: 'nearby-error' }).props
    .children;
  expect(message).toBe(
    'Could not open nearby audio. Return to the room and try again.',
  );
  expect(JSON.stringify(screen!.toJSON())).not.toContain(
    'private native details',
  );
  expect(button().props.disabled).toBe(false);
});

test('a late native failure after unmount cannot restore the card', async () => {
  let fail!: (error: Error) => void;
  native.open.mockReturnValueOnce(
    new Promise((_, reject) => {
      fail = reject;
    }),
  );
  await render();
  await press();
  await unmount();
  await act(async () => fail(new Error('activity ended')));
  expect(native.stop).toHaveBeenCalledTimes(1);
});
