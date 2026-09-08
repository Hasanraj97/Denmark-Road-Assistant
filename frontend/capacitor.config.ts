import { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.ahmed.roadassistant',
  appName: 'Denmark Road Assistant',
  webDir: 'dist/frontend/browser',
  server: {
    androidScheme: 'http',
    cleartext: true
  }
};

export default config;