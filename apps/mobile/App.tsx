import React from 'react';
import {StatusBar, StyleSheet, Text, View} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';

export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="dark-content" />
      <SafeAreaView style={styles.screen}>
        <View accessibilityRole="header">
          <Text style={styles.title}>PocketDisco</Text>
          <Text style={styles.subtitle}>Your people. One room.</Text>
        </View>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1, backgroundColor: '#F7F4EC', padding: 24},
  title: {fontSize: 36, fontWeight: '800', color: '#20231F'},
  subtitle: {fontSize: 18, color: '#4E554F', marginTop: 12},
});
