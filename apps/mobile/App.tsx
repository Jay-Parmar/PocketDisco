import React from 'react';
import {
  ActivityIndicator,
  Alert,
  Share,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { colors, RoomScreen, WelcomeScreen } from './src/ui';
import { useMobileRoom } from './src/useMobileRoom';

export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="dark-content" />
      <SafeAreaView style={styles.screen}>
        <RoomApp />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

function RoomApp() {
  const app = useMobileRoom();
  if (app.booting) {
    return (
      <View style={styles.loading} testID="session-loading">
        <Text style={styles.brand}>PocketDisco</Text>
        <ActivityIndicator
          color={colors.green}
          accessibilityLabel="Opening your session"
        />
      </View>
    );
  }
  const { snapshot, session, inviteCode } = app.state;
  if (!snapshot || !session) {
    return (
      <WelcomeScreen
        serverUrl={app.baseUrl}
        showServerSettings={app.showServerSettings}
        busy={app.busy === 'room' ? 'working' : app.busy}
        canResume={app.canResume}
        onResume={() => {
          app.retry();
        }}
        error={app.error}
        onCreate={({ displayName, roomName }) => {
          app.create(displayName, roomName);
        }}
        onJoin={({ displayName, inviteCode: code }) => {
          app.join(displayName, code);
        }}
        onSaveServerUrl={app.saveServer}
        onDismissError={app.dismissError}
      />
    );
  }
  return (
    <RoomScreen
      room={snapshot}
      currentUserId={session.user.id}
      inviteCode={inviteCode ?? undefined}
      connectionStatus={app.state.connection}
      busy={app.busy !== null}
      isSending={app.sending}
      error={app.error}
      onReadyChange={ready => {
        app.setReady(ready);
      }}
      onSendMessage={app.send}
      onShareInvite={() => {
        if (!inviteCode) return;
        Share.share({
          message: `Join ${snapshot.name} on PocketDisco. Room code: ${inviteCode}`,
        }).catch(() =>
          Alert.alert(
            'Could not share',
            'You can read the room code to your friend.',
          ),
        );
      }}
      onLeave={() =>
        Alert.alert('Leave this room?', 'You can return with the room code.', [
          { text: 'Stay', style: 'cancel' },
          {
            text: 'Leave room',
            style: 'destructive',
            onPress: () => {
              app.leave();
            },
          },
        ])
      }
      onRetryConnection={() => {
        app.retry();
      }}
      onDismissError={app.dismissError}
    />
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.background },
  loading: { flex: 1, justifyContent: 'center', alignItems: 'center', gap: 24 },
  brand: { fontSize: 32, fontWeight: '800', color: colors.ink },
});
