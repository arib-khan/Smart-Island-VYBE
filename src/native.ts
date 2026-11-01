import { NativeModules, NativeEventEmitter, AppState } from 'react-native';
import { useCallback, useEffect, useState } from 'react';

const N: any = NativeModules.IslandModule;
export const available = !!N;
/** False when the installed APK was built from older native code (e.g. prebuild --clean failed). */
export const nativeCurrent = !!N && (N.nativeVersion ?? 0) >= 8;
export const events = N ? new NativeEventEmitter(N) : (null as unknown as NativeEventEmitter);

const W: any = NativeModules.WellbeingModule;
export const wellbeingAvailable = !!W;
export type ShortsStatus = { serviceEnabled: boolean; serviceConnected: boolean; onFeed: boolean; app: string; shield: boolean; evals: number; matches: number; lastError: string; blockingConfigured: boolean; blockingActive: boolean; hasPin: boolean; shieldToday: number };
export type AdultStatus = { enabled: boolean; running: boolean; permission: boolean; error: string; privateDns: string; otherVpn: boolean; blockedToday: number; blockedTotal: number; listUpdatedAt: number; listCount: number; listSource: string };
/** Typed bridge to WellbeingModule.kt. Setters that can weaken protection reject with code PIN_REQUIRED / PIN_WRONG / PIN_LOCKED when a PIN is set. */
export const wb = {
  hasShortsGuard: (): Promise<boolean> => W.hasShortsGuard(),
  requestShortsGuard: () => W.requestShortsGuard(),
  getShortsConfig: (): Promise<string> => W.getShortsConfig(),
  setShortsConfig: (json: string, pin?: string): Promise<string> => W.setShortsConfig(json, pin ?? null),
  getShortsStatus: (): Promise<ShortsStatus> => W.getShortsStatus(),
  getShortsStats: (): Promise<string> => W.getShortsStats(),
  clearShortsStats: (): Promise<boolean> => W.clearShortsStats(),
  getDiagnosticLog: (): Promise<string> => W.getDiagnosticLog(),
  clearDiagnosticLog: (): Promise<boolean> => W.clearDiagnosticLog(),
  hasPin: (): Promise<boolean> => W.hasPin(),
  setPin: (pin: string, oldPin?: string): Promise<boolean> => W.setPin(pin, oldPin ?? null),
  clearPin: (pin?: string): Promise<boolean> => W.clearPin(pin ?? null),
  hasVpnPermission: (): Promise<boolean> => W.hasVpnPermission(),
  requestVpnPermission: (): Promise<boolean> => W.requestVpnPermission(),
  openVpnSettings: () => W.openVpnSettings(),
  getAdultConfig: (): Promise<string> => W.getAdultConfig(),
  setAdultConfig: (json: string, pin?: string): Promise<string> => W.setAdultConfig(json, pin ?? null),
  getAdultStatus: (): Promise<AdultStatus> => W.getAdultStatus(),
  updateAdultBlocklist: (url: string): Promise<number> => W.updateAdultBlocklist(url),
};
export const wbEvents = W ? new NativeEventEmitter(N) : (null as unknown as NativeEventEmitter);   // events are emitted through IslandModule

const Dv: any = NativeModules.DeviceModule;
export const deviceAvailable = !!Dv;
/** Typed bridge to DeviceModule.kt (the Meow Island ESP32 display). The Wi-Fi password is passed straight through and never stored by the app. */
export const dev = {
  start: () => { try { Dv?.deviceStart(); } catch { /* optional feature */ } },
  hasPermission: (): Promise<boolean> => Dv.deviceHasPermission(),
  getState: (): Promise<any> => Dv.deviceGetState(),
  scanStart: (): Promise<boolean> => Dv.deviceScanStart(),
  scanStop: () => Dv.deviceScanStop(),
  scanResults: (): Promise<string> => Dv.deviceScanResults(),
  pair: (address: string): Promise<boolean> => Dv.devicePair(address),
  disconnect: () => Dv.deviceDisconnect(),
  /** Stops a pairing that is in progress and returns the link to a clean state. */
  cancelPair: () => Dv.deviceCancelPair(),
  /** Forgets the phone-side pairing and pairs afresh (the display must be in pairing mode). */
  repair: (): Promise<boolean> => Dv.deviceRepair(),
  reconnect: () => Dv.deviceReconnect(),
  unpair: (clearWifi: boolean): Promise<boolean> => Dv.deviceUnpair(clearWifi),
  setScreen: (id: number): Promise<boolean> => Dv.deviceSetScreen(id),
  syncNow: (): Promise<boolean> => Dv.deviceSyncNow(),
  setWifi: (ssid: string, pass: string): Promise<boolean> => Dv.deviceSetWifi(ssid, pass),
  clearWifi: (): Promise<boolean> => Dv.deviceClearWifi(),
  openBluetoothSettings: () => Dv.deviceOpenBluetoothSettings(),
};
export const devEvents = Dv ? new NativeEventEmitter(N) : (null as unknown as NativeEventEmitter);   // emitted through IslandModule

// ---- screen time / notifications / lyrics / custom HTML screen (DeviceModule.kt). Everything is JSON strings across the bridge. ----
export const deviceExtrasAvailable = !!(Dv && Dv.customPick && Dv.deviceScreenTimeGet && Dv.deviceNotifsGet && Dv.deviceLyricsGet);
export type CustomInfo = {
  hasSaved: boolean; name: string; size: number; crc: string; auto: boolean; connected: boolean; deviceHasExt: boolean; deviceCrc: string;
  state: 'idle' | 'sending' | 'done' | 'error'; sent: number; total: number; message: string; image?: string; html?: string;
};
export type PickedFile = { name: string; size: number; tooLarge: boolean; html: string };
const parse = (s: any): any => { try { return JSON.parse(String(s)); } catch { return null; } };
export const devx = {
  screenTimeGet: async (): Promise<any> => parse(await Dv.deviceScreenTimeGet()),
  screenTimeSetGoal: (min: number): Promise<number> => Dv.deviceScreenTimeSetGoal(min),
  notifsGet: async (): Promise<{ enabled: boolean; access: boolean; count: number; preview: boolean } | null> => parse(await Dv.deviceNotifsGet()),
  notifsSetEnabled: (on: boolean): Promise<boolean> => Dv.deviceNotifsSetEnabled(on),
  notifsClear: (): Promise<boolean> => Dv.deviceNotifsClear(),
  lyricsGet: async (): Promise<{ provider: 'off' | 'online'; state: number; detail: string; source: string } | null> => parse(await Dv.deviceLyricsGet()),
  lyricsSetProvider: (name: 'off' | 'online'): Promise<string> => Dv.deviceLyricsSetProvider(name),
  customPick: async (): Promise<PickedFile | null> => { const r = await Dv.customPick(); return r ? parse(r) : null; },
  customRender: (html: string, allowScripts: boolean): Promise<string> => Dv.customRender(html, allowScripts),
  customSave: async (imageB64: string, html: string, name: string): Promise<CustomInfo | null> => parse(await Dv.customSave(imageB64, html, name)),
  customGetSaved: async (full: boolean): Promise<CustomInfo | null> => parse(await Dv.customGetSaved(full)),
  customSend: (): Promise<boolean> => Dv.customSend(),
  customCancel: () => Dv.customCancel(),
  customRemove: (): Promise<boolean> => Dv.customRemove(),
  customSetAuto: (on: boolean): Promise<boolean> => Dv.customSetAuto(on),
};

export type AppInfo = { pkg: string; name: string; icon: string };
export type TimerInfo = { id: string; label: string; endAt: number; notifId: string };
export type Media = { active: boolean; title?: string; artist?: string; album?: string; app?: string; appName?: string; source?: string; video?: boolean; trackId?: string; playing?: boolean; position?: number; duration?: number; canNext?: boolean; canPrev?: boolean; art?: string; artRev?: number };

export const native = {
  hasOverlay: (): Promise<boolean> => N.hasOverlay(),
  requestOverlay: () => N.requestOverlay(),
  requestNotificationAccess: () => N.requestNotificationAccess(),
  requestUsageAccess: () => N.requestUsageAccess(),
  requestExactAlarm: () => N.requestExactAlarm(),
  openNotificationSettings: () => N.openNotificationSettings(),
  openBatterySettings: () => N.openBatterySettings(),
  hasAccessibility: (): Promise<boolean> => N.hasAccessibility(),
  requestAccessibility: () => N.requestAccessibility(),
  getLayer: (): Promise<string> => N.getLayer(),
  startIsland: (): Promise<boolean> => N.startIsland(),
  stopIsland: (): Promise<boolean> => N.stopIsland(),
  isRunning: (): Promise<boolean> => N.isRunning(),
  lastError: (): Promise<string> => N.lastError(),
  setConfig: (json: string) => N.setConfig(json),
  postActivity: (id: string, priority: number, title: string, sub: string, ttlMs: number) => N.postActivity(id, priority, title, sub, ttlMs),
  startTimer: (label: string, endAtMs: number, notifId: string): Promise<string> => N.startTimer(label, endAtMs, notifId),
  cancelTimer: (id: string): Promise<boolean> => N.cancelTimer(id),
  getTimers: (): Promise<TimerInfo[]> => N.getTimers(),
  setHealthConfig: (json: string) => N.setHealthConfig(json),
  getHealthSnapshot: (): Promise<string> => N.getHealthSnapshot(),
  pinWidget: (kind: 'clock' | 'round' | 'heat' | 'card' | 'music'): Promise<boolean> => N.pinWidget(kind),
  syncSteps: (): Promise<boolean> => N.syncSteps(),
  clearHealthData: (): Promise<boolean> => N.clearHealthData(),
  waterAction: (a: 'done' | 'snooze' | 'dismiss' | 'test') => N.waterAction(a),
  getMedia: (): Promise<Media> => N.getMedia(),
  mediaControl: (a: 'play' | 'pause' | 'next' | 'prev') => N.mediaControl(a),
  getInstalledApps: (): Promise<AppInfo[]> => N.getInstalledApps(),
  getUsageToday: (): Promise<Record<string, number>> => N.getUsageToday(),
};

export type Perms = { a11y: boolean; overlay: boolean; notif: boolean; listener: boolean; usage: boolean; exact: boolean; battery: boolean };
const NONE: Perms = { a11y: false, overlay: false, notif: false, listener: false, usage: false, exact: false, battery: false };

/** Live permission status; re-checked every time the app returns to the foreground. */
export function usePerms() {
  const [p, setP] = useState<Perms>(NONE);
  const [loading, setLoading] = useState(true);
  const refresh = useCallback(async () => {
    try {
      const [a11y, overlay, notif, listener, usage, exact, battery] = await Promise.all([
        N.hasAccessibility(), N.hasOverlay(), N.hasNotificationPermission(), N.hasNotificationAccess(), N.hasUsageAccess(), N.canScheduleExactAlarms(), N.isIgnoringBattery(),
      ]);
      setP({ a11y, overlay, notif, listener, usage, exact, battery });
    } catch { /* leave previous values */ }
    setLoading(false);
  }, []);
  useEffect(() => {
    refresh();
    const sub = AppState.addEventListener('change', (st) => { if (st === 'active') refresh(); });
    return () => sub.remove();
  }, [refresh]);
  return { ...p, loading, refresh };
}
