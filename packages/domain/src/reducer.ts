import type {RoomSnapshot, SnapshotEvent} from './types.ts';

export function applySnapshot(
  current: RoomSnapshot | null,
  incoming: RoomSnapshot,
): RoomSnapshot {
  if (current !== null && current.room_id === incoming.room_id
    && incoming.revision <= current.revision) return current;
  return incoming;
}

export function reduceRoom(
  current: RoomSnapshot | null,
  event: SnapshotEvent,
): RoomSnapshot | null {
  if (current !== null && event.room_id !== current.room_id) return current;
  return applySnapshot(current, event.payload);
}
