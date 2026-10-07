import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import App from '../App';

test('renders the app entry screen', async () => {
  let screen: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    screen = ReactTestRenderer.create(<App />);
  });
  expect(JSON.stringify(screen!.toJSON())).toContain('PocketDisco');
  await ReactTestRenderer.act(async () => screen!.unmount());
});
