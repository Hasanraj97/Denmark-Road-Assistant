import { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  // Must match the Android project's applicationId and namespace in
  // android/app/build.gradle. It used to say 'com.ahmed.roadassistant' while the
  // Android project said 'com.denmarkroadassistant.app'. Capacitor writes this
  // value into the generated files during `npx cap sync`, so the mismatch meant
  // the two halves of the project disagreed about the app's own identity.
  appId: 'com.denmarkroadassistant.app',
  appName: 'Denmark Road Assistant',

  // Angular 17+ emits the browser bundle into dist/<project>/browser.
  webDir: 'dist/frontend/browser',

  server: {
    // Capacitor's default, and deliberately not 'http'.
    //
    // With androidScheme: 'http' the app served itself from http://localhost,
    // which is not an HTTPS origin. Browsers grant localhost a secure-context
    // exemption so geolocation still worked, but it left the app one small
    // change away from silently losing access to every secure-context browser
    // API, and it forced cleartext to be permitted for the app's own origin.
    // 'https' serves the app from https://localhost, a genuine secure context.
    //
    // Note the backend must allow https://localhost as a CORS origin - it is in
    // the default app.frontend-origins list.
    androidScheme: 'https',

    // Still needed: the app itself is served over https, but during development
    // it calls a backend running on plain http on the developer's machine
    // (http://10.0.2.2:8080 from the emulator). Which hosts that is allowed for
    // is controlled by android/app/src/main/res/xml/network_security_config.xml.
    cleartext: true
  }
};

export default config;
