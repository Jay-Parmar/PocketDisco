import { useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { RoomClient } from '../../../packages/domain/src/client';
import type { ClientState } from '../../../packages/domain/src/types';
import DeviceSession from './native/NativeDeviceSession';
import { readSavedSession } from './session';

function message(error: unknown) {
  return error instanceof Error
    ? error.message
    : 'Something went wrong. Please try again.';
}

function roomUnavailable(error: ClientState['error']) {
  return (
    error !== null &&
    (('status' in error &&
      typeof error.status === 'number' &&
      [403, 404, 410].includes(error.status)) ||
      [
        'room_unavailable',
        'not_member',
        'room_closed',
        'room_not_found',
        'forbidden',
      ].includes(error.code))
  );
}

export function useMobileRoom() {
  const [settings] = useState(() => DeviceSession.getSettings());
  const createClient = (url: string) =>
    new RoomClient({
      baseUrl: url,
      allowInsecureHttp: settings.allowLocalServer,
      createCommandId: () => DeviceSession.randomId(),
    });
  const clientRef = useRef<RoomClient | null>(null);
  if (clientRef.current === null)
    clientRef.current = createClient(settings.apiUrl);
  const [state, setState] = useState(clientRef.current.getState());
  const [baseUrl, setBaseUrl] = useState(settings.apiUrl);
  const [booting, setBooting] = useState(true);
  const [busy, setBusy] = useState<'creating' | 'joining' | 'room' | null>(
    null,
  );
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dismissedError, setDismissedError] =
    useState<ClientState['error']>(null);
  const unsubscribe = useRef(() => {});
  const active = useRef(true);
  const effectGeneration = useRef(0);
  const foreground = useRef(AppState.currentState === 'active');
  const foregroundGeneration = useRef(0);
  const hydrating = useRef(true);
  const readyToResume = useRef(false);
  const lastQueued = useRef<string | null | undefined>(undefined);
  const writes = useRef<Promise<void>>(Promise.resolve());
  const savedRoomId = useRef<string | null>(null);
  const savedInvite = useRef<string | null>(null);
  const hadSnapshot = useRef(false);
  const currentUrl = useRef(settings.apiUrl);
  const actionPending = useRef(false);
  const resumeFlight = useRef<{
    client: RoomClient;
    promise: Promise<void>;
  } | null>(null);

  function clearSavedRoom() {
    savedRoomId.current = null;
    savedInvite.current = null;
    hadSnapshot.current = false;
  }

  function persist(next: ClientState): Promise<void> {
    if (hydrating.current) return Promise.resolve();
    if (next.session === null || roomUnavailable(next.error)) {
      clearSavedRoom();
    } else if (next.snapshot) {
      savedRoomId.current = next.snapshot.room_id;
      savedInvite.current = next.inviteCode;
      hadSnapshot.current = true;
    } else if (hadSnapshot.current) {
      clearSavedRoom();
    }
    const record = next.session
      ? JSON.stringify({
          baseUrl: currentUrl.current,
          session: next.session,
          roomId: savedRoomId.current,
          inviteCode: savedInvite.current,
        })
      : null;
    if (record === lastQueued.current) return writes.current;
    lastQueued.current = record;
    const operation = writes.current
      .catch(() => {})
      .then(() =>
        record === null ? DeviceSession.clear() : DeviceSession.save(record),
      );
    writes.current = operation;
    operation.catch(() => {
      if (lastQueued.current === record) lastQueued.current = undefined;
      if (active.current)
        setError(
          'Could not save your session securely. Keep the app open and retry.',
        );
    });
    return operation;
  }

  function attach(client: RoomClient, url: string) {
    unsubscribe.current();
    clientRef.current?.disconnect();
    clientRef.current = client;
    currentUrl.current = url;
    setBaseUrl(url);
    setState(client.getState());
    unsubscribe.current = client.subscribe(next => {
      if (!active.current || client !== clientRef.current) return;
      persist(next).catch(() => {});
      setState(next);
    });
  }

  async function connectCurrent(client: RoomClient): Promise<void> {
    if (!foreground.current || !readyToResume.current) return;
    if (resumeFlight.current?.client === client)
      return resumeFlight.current.promise;
    const generation = foregroundGeneration.current;
    const task = (async () => {
      if (client.getState().snapshot) {
        await client.reconnect();
      } else if (savedRoomId.current && client.getState().session) {
        await client.resumeRoom(
          savedRoomId.current,
          savedInvite.current ?? undefined,
        );
      } else {
        return;
      }
      if (
        !active.current ||
        client !== clientRef.current ||
        !foreground.current
      ) {
        client.disconnect();
      } else if (
        generation !== foregroundGeneration.current &&
        client.getState().snapshot &&
        client.getState().connection === 'offline'
      ) {
        await client.reconnect();
      }
    })();
    const flight = { client, promise: task };
    resumeFlight.current = flight;
    try {
      await task;
    } finally {
      if (resumeFlight.current === flight) resumeFlight.current = null;
    }
  }

  useEffect(() => {
    const generation = ++effectGeneration.current;
    const isCurrent = () =>
      active.current && effectGeneration.current === generation;
    active.current = true;
    hydrating.current = true;
    readyToResume.current = false;
    foreground.current = AppState.currentState === 'active';
    attach(clientRef.current!, currentUrl.current);
    const lifecycle = AppState.addEventListener('change', status => {
      foreground.current = status === 'active';
      foregroundGeneration.current += 1;
      const client = clientRef.current!;
      if (!foreground.current) {
        client.disconnect();
      } else {
        connectCurrent(client).catch(failure => {
          if (isCurrent() && client === clientRef.current)
            setError(message(failure));
        });
      }
    });
    DeviceSession.load()
      .then(async stored => {
        if (!isCurrent()) return;
        lastQueued.current = stored;
        if (stored) {
          const restored = readSavedSession(
            stored,
            settings.apiUrl,
            settings.allowLocalServer,
          );
          const client = createClient(restored.baseUrl);
          savedRoomId.current = restored.roomId;
          savedInvite.current = restored.inviteCode;
          hadSnapshot.current = false;
          attach(client, restored.baseUrl);
          client.restoreSession(restored.session);
        }
        hydrating.current = false;
        await persist(clientRef.current!.getState());
        if (!isCurrent()) return;
        readyToResume.current = true;
        await connectCurrent(clientRef.current!);
      })
      .catch(() => {
        if (isCurrent())
          setError(
            'Could not reopen your last session. Check your connection or join again.',
          );
      })
      .finally(async () => {
        if (!isCurrent()) return;
        hydrating.current = false;
        await persist(clientRef.current!.getState()).catch(() => {});
        if (!isCurrent()) return;
        readyToResume.current = true;
        setBooting(false);
      });
    return () => {
      active.current = false;
      unsubscribe.current();
      lifecycle.remove();
      clientRef.current!.disconnect();
    };
    // Native configuration stays fixed for this app instance.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function run<T>(
    kind: 'creating' | 'joining' | 'room',
    task: () => Promise<T>,
  ): Promise<T | undefined> {
    if (actionPending.current || booting) return;
    actionPending.current = true;
    setBusy(kind);
    setError(null);
    try {
      return await task();
    } catch (failure) {
      if (active.current) setError(message(failure));
    } finally {
      actionPending.current = false;
      if (active.current) setBusy(null);
    }
  }

  async function identify(displayName: string) {
    const client = clientRef.current!;
    if (client.getState().session?.user.display_name !== displayName) {
      clearSavedRoom();
      await client.guest(displayName);
      await persist(client.getState());
    }
    return client;
  }

  return {
    state,
    baseUrl,
    booting,
    busy,
    sending,
    canResume:
      savedRoomId.current !== null &&
      state.session !== null &&
      state.snapshot === null,
    error:
      error ??
      (state.error === dismissedError ? null : state.error?.message) ??
      null,
    showServerSettings: settings.allowLocalServer,
    dismissError: () => {
      setError(null);
      setDismissedError(state.error);
    },
    create: (displayName: string, roomName: string) =>
      run('creating', async () => {
        clearSavedRoom();
        const client = await identify(displayName);
        await client.createRoom(roomName);
        await persist(client.getState());
      }),
    join: (displayName: string, inviteCode: string) =>
      run('joining', async () => {
        clearSavedRoom();
        const client = await identify(displayName);
        await client.joinRoom(inviteCode);
        await persist(client.getState());
      }),
    leave: () =>
      run('room', async () => {
        await clientRef.current!.leaveRoom();
        clearSavedRoom();
        await persist(clientRef.current!.getState());
      }),
    setReady: (ready: boolean) =>
      run('room', () => clientRef.current!.setReady(ready)),
    retry: () => run('room', () => connectCurrent(clientRef.current!)),
    send: async (body: string) => {
      setSending(true);
      setError(null);
      try {
        await clientRef.current!.sendChat(body);
      } catch (failure) {
        if (active.current) setError(message(failure));
        throw failure;
      } finally {
        if (active.current) setSending(false);
      }
    },
    saveServer: async (url: string) =>
      (await run('room', async () => {
        if (url === currentUrl.current) return true;
        if (!settings.allowLocalServer) return false;
        const nextClient = createClient(url);
        clientRef.current!.signOut();
        clearSavedRoom();
        await persist(clientRef.current!.getState());
        attach(nextClient, url);
        return true;
      })) === true,
  };
}
