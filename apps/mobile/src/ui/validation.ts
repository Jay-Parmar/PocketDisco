export function validateDisplayName(value: string): string | undefined {
  const name = value.trim();
  if (!name) {
    return 'Add a name so your friends know it is you.';
  }
  if (name.length > 40) {
    return 'Keep your name to 40 characters.';
  }
  return undefined;
}

export function validateRoomName(value: string): string | undefined {
  const name = value.trim();
  if (!name) {
    return 'Give your room a name.';
  }
  if (name.length > 80) {
    return 'Keep the room name to 80 characters.';
  }
  return undefined;
}

export function normalizeInviteCode(value: string): string {
  return value.trim().toUpperCase();
}

export function validateInviteCode(value: string): string | undefined {
  if (!/^[0-9A-HJKMNP-TV-Z]{12}$/.test(normalizeInviteCode(value))) {
    return 'Enter the 12-character code from your invite.';
  }
  return undefined;
}

export function validateServerUrl(value: string): string | undefined {
  try {
    const url = new URL(value.trim());
    if (
      !['http:', 'https:'].includes(url.protocol) ||
      !url.hostname ||
      url.username ||
      url.password ||
      url.search ||
      url.hash ||
      (url.pathname !== '/' && url.pathname !== '')
    ) {
      return 'Use a server address such as http://127.0.0.1:8000.';
    }
  } catch {
    return 'Use a server address such as http://127.0.0.1:8000.';
  }
  return undefined;
}

export function validateMessage(value: string): string | undefined {
  const message = value.trim();
  if (!message) {
    return 'Write a message first.';
  }
  if (message.length > 1000) {
    return 'Keep your message to 1,000 characters.';
  }
  return undefined;
}
