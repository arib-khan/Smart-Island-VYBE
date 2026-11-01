/** Pure helpers for the Meow Island Device section: state sanitising, labels, Wi-Fi input validation. No React / native imports (unit-tested). */
export type LinkState = 'unpaired' | 'pairing' | 'connecting' | 'connected' | 'reconnecting' | 'disconnected' | 'bluetooth_off' | 'no_permission';
const STATES: readonly LinkState[] = ['unpaired', 'pairing', 'connecting', 'connected', 'reconnecting', 'disconnected', 'bluetooth_off', 'no_permission'];

export type DeviceState = {
  state: LinkState; paired: boolean; ready: boolean; scanning: boolean; name: string; address: string; ssid: string; error: string; bondProblem: boolean;
  bluetoothOn: boolean; permission: boolean; fw: string; screen: number; wifiState: number; wifiReason: string; timeValid: boolean; hasWifi: boolean;
  pairingOpen: boolean; stepsFresh: boolean; musicFresh: boolean; diag: string;
};
export const EMPTY_DEVICE: DeviceState = {
  state: 'unpaired', paired: false, ready: false, scanning: false, name: '', address: '', ssid: '', error: '', bondProblem: false, bluetoothOn: true, permission: false,
  fw: '', screen: 0, wifiState: 0, wifiReason: '', timeValid: false, hasWifi: false, pairingOpen: false, stepsFresh: false, musicFresh: false, diag: '',
};

const b = (v: any, d = false) => (typeof v === 'boolean' ? v : d);
const s = (v: any, max = 120) => (typeof v === 'string' ? v.slice(0, max) : '');
const n = (v: any, lo: number, hi: number) => (typeof v === 'number' && isFinite(v) ? Math.min(hi, Math.max(lo, Math.round(v))) : lo);

/** Anything the native side sends is clamped to known values; unknown states fall back to "unpaired". */
export function mergeDeviceState(raw: any): DeviceState {
  const r = raw && typeof raw === 'object' ? raw : {};
  return {
    state: STATES.includes(r.state) ? r.state : 'unpaired', paired: b(r.paired), ready: b(r.ready), scanning: b(r.scanning), name: s(r.name, 40), address: s(r.address, 40), ssid: s(r.ssid, 40),
    error: s(r.error, 300), bondProblem: b(r.bondProblem), bluetoothOn: b(r.bluetoothOn, true), permission: b(r.permission), fw: s(r.fw, 12), screen: n(r.screen, 0, 5), wifiState: n(r.wifiState, 0, 3),
    wifiReason: s(r.wifiReason, 120), timeValid: b(r.timeValid), hasWifi: b(r.hasWifi), pairingOpen: b(r.pairingOpen), stepsFresh: b(r.stepsFresh), musicFresh: b(r.musicFresh), diag: s(r.diag, 2000),
  };
}

export type Found = { address: string; name: string; rssi: number; pairingOpen: boolean; bonded: boolean };
export function parseFound(json: any): Found[] {
  try {
    const a = JSON.parse(typeof json === 'string' ? json : '[]');
    if (!Array.isArray(a)) return [];
    return a.filter((x) => x && typeof x.address === 'string' && /^[0-9A-Fa-f:]{11,17}$/.test(x.address))
      .slice(0, 20).map((x) => ({ address: x.address, name: s(x.name, 40) || 'Meow Island', rssi: n(x.rssi, -127, 0), pairingOpen: b(x.pairingOpen), bonded: b(x.bonded) }));
  } catch { return []; }
}

export type Tone = 'ok' | 'warn' | 'bad' | 'idle';
export function statusLabel(d: DeviceState): { text: string; tone: Tone } {
  switch (d.state) {
    case 'connected': return { text: 'Connected', tone: 'ok' };
    case 'connecting': return { text: 'Connecting…', tone: 'warn' };
    case 'reconnecting': return { text: 'Reconnecting…', tone: 'warn' };
    case 'pairing': return { text: 'Pairing…', tone: 'warn' };
    case 'disconnected': return { text: d.paired ? 'Paired · disconnected' : 'Not connected', tone: 'idle' };
    case 'bluetooth_off': return { text: 'Bluetooth is off', tone: 'bad' };
    case 'no_permission': return { text: 'Needs permission', tone: 'bad' };
    default: return { text: 'Not paired', tone: 'idle' };
  }
}

/** The device's own Wi-Fi state (0 none, 1 connecting, 2 connected, 3 failed) in words. Never includes a password. */
export function wifiText(d: DeviceState): string {
  if (!d.ready) return d.hasWifi || d.ssid ? 'Wi-Fi saved on the device' : 'Connect to the device to see its Wi-Fi';
  const name = d.ssid ? ' “' + d.ssid + '”' : '';
  if (d.wifiState === 2) return 'Connected to Wi-Fi' + name;
  if (d.wifiState === 1) return 'Connecting to Wi-Fi' + name + '…';
  if (d.wifiState === 3) return d.wifiReason || 'Could not connect to Wi-Fi';
  return d.hasWifi ? 'Wi-Fi saved' : 'No Wi-Fi set up';
}

export const SCREENS = [
  { id: 0, name: 'Clock', icon: '🕒', hint: 'The neon sleeping-cat clock. Always available.' },
  { id: 1, name: 'Steps', icon: '👟', hint: 'Today’s steps, goal and heat-map from your Health tab.' },
  { id: 2, name: 'Music', icon: '🎵', hint: 'What is playing on this phone: album art, title, progress and lyrics from any music app when online lyrics is on. YouTube shows only its thumbnail, title and progress.' },
  { id: 3, name: 'Screen time', icon: '📱', hint: 'How long this PHONE has been used today, your daily goal and the most-used apps.' },
  { id: 4, name: 'Notifications', icon: '🔔', hint: 'The newest notifications from this phone (needs Notification Access and the switch below).' },
  { id: 5, name: 'Custom', icon: '🎨', hint: 'Your own HTML design, rendered on the phone and stored on the device.' },
] as const;

export function utf8Len(str: string): number {
  let len = 0;
  for (const ch of str) { const c = ch.codePointAt(0)!; len += c < 0x80 ? 1 : c < 0x800 ? 2 : c < 0x10000 ? 3 : 4; }
  return len;
}
/** The exact rules the device enforces (protocol.h parseWifi): name 1-32 bytes, password empty or 8-63 bytes. Returns a message, or null when fine. */
export function wifiProblem(ssid: string, pass: string): string | null {
  const sl = utf8Len(ssid), pl = utf8Len(pass);
  if (sl === 0) return 'Enter the Wi-Fi network name.';
  if (sl > 32) return 'A Wi-Fi name can be at most 32 bytes long.';
  if (pl !== 0 && pl < 8) return 'A Wi-Fi password needs at least 8 characters (leave it empty for an open network).';
  if (pl > 63) return 'A Wi-Fi password can be at most 63 characters long.';
  return null;
}

export function signalBars(rssi: number): number { return rssi >= -60 ? 3 : rssi >= -75 ? 2 : rssi >= -90 ? 1 : 0; }
