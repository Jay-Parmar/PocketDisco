import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export interface Spec extends TurboModule {
  getSettings(): { apiUrl: string; allowLocalServer: boolean };
  randomId(): string;
  load(): Promise<string | null>;
  save(value: string): Promise<void>;
  clear(): Promise<void>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('DeviceSession');
