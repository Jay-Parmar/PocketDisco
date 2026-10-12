import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export interface Spec extends TurboModule {
  getAvailability(): { enabled: boolean; supported: boolean };
  open(roomId: string): Promise<void>;
  stop(): Promise<void>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('NearbyAudio');
