import React, { useState } from 'react';
import { Alert, Pressable, Text, TextInput, View } from 'react-native';
import { Btn, C, Card, Chip, ErrorBox, H, Loading, Sub, Toggle } from '../ui';
import { dev, deviceAvailable } from '../native';
import { useDevice } from '../device';
import { SCREENS, signalBars, statusLabel, wifiProblem, wifiText } from '../core/device';
import { DeviceExtrasCards } from './DeviceExtras';

const Step = ({ n, children }: { n: number; children: React.ReactNode }) => (
  <View style={{ flexDirection: 'row', gap: 10, marginTop: 8 }}>
    <View style={{ width: 24, height: 24, borderRadius: 12, backgroundColor: C.accent, alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: C.onAccent, fontWeight: '800' }}>{n}</Text></View>
    <Text style={{ color: C.text, flex: 1, lineHeight: 20 }}>{children}</Text>
  </View>
);

/** The "Meow Island Device" section: pair the ESP32 display, see its connection, choose its screen, set its Wi-Fi. Reached from Settings. */
export default function Device({ back, openCustom }: { back: () => void; openCustom: () => void }) {
  const { state: d, found, ready, refresh, scan } = useDevice();
  const [busy, setBusy] = useState('');
  const [ssid, setSsid] = useState(''); const [pass, setPass] = useState(''); const [showPass, setShowPass] = useState(false);
  const [wifiSent, setWifiSent] = useState(false);
  const [note, setNote] = useState('');
  const [showLog, setShowLog] = useState(false);

  if (!deviceAvailable) return <Card><H>Needs the latest build</H><Sub>This APK was built before the device feature was added. Run the prebuild again and rebuild the app.</Sub><View style={{ marginTop: 10, alignSelf: 'flex-start' }}><Btn small kind="ghost" title="← Back" onPress={back} /></View></Card>;
  if (!ready) return <Loading label="Loading…" />;

  const st = statusLabel(d);
  const act = async (name: string, fn: () => Promise<any>) => { setBusy(name); setNote(''); try { await fn(); } catch (e: any) { setNote(String(e?.message ?? e)); } finally { setBusy(''); refresh(); } };
  const problem = ssid || pass ? wifiProblem(ssid, pass) : null;

  const sendWifi = () => act('wifi', async () => {
    const p = wifiProblem(ssid, pass); if (p) { setNote(p); return; }
    await dev.setWifi(ssid, pass); setWifiSent(true); setPass('');            // the password leaves this screen the moment it is sent
  });
  const unpair = () => Alert.alert('Unpair this device?', d.ready ? 'The display will forget this phone and can be paired again. Choose whether it should also forget its Wi-Fi.' : 'The phone will forget the device. Because it is not connected now, the display itself keeps this phone until you hold its BOOT button for 10 seconds.', [
    { text: 'Cancel', style: 'cancel' },
    { text: 'Unpair', style: 'destructive', onPress: () => act('unpair', async () => { const told = await dev.unpair(false); setNote(told ? 'Unpaired. If the display still appears in Android’s Bluetooth settings, forget it there too.' : 'Unpaired on this phone only. Hold the BOOT button on the display for 10 seconds to reset it.'); }) },
    ...(d.ready ? [{ text: 'Unpair + forget Wi-Fi', style: 'destructive' as const, onPress: () => act('unpair', async () => { await dev.unpair(true); setNote('Unpaired and Wi-Fi erased from the display. If it still appears in Android’s Bluetooth settings, forget it there too.'); }) }] : []),
  ]);

  return (
    <View>
      <View style={{ alignSelf: 'flex-start', marginBottom: 8 }}><Btn small kind="ghost" title="← Settings" onPress={back} /></View>

      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
          <H>Meow Island Device</H><Chip ok={st.tone === 'ok'} label={st.text} />
        </View>
        {d.paired ? (
          <>
            <Text style={{ color: C.text, fontSize: 18, fontWeight: '800', marginTop: 4 }}>{d.name || 'Meow Island'}</Text>
            <Sub>{d.address}{d.fw ? '  ·  firmware ' + d.fw : ''}</Sub>
            <Sub>{d.state === 'connected' ? 'Connected over Bluetooth. Steps and music are sent to the display automatically.' : d.state === 'reconnecting' || d.state === 'connecting' ? 'Looking for the display… it reconnects by itself when it is switched on and nearby.' : d.state === 'disconnected' ? 'Paused: the display keeps showing its clock.' : ''}</Sub>
            <View style={{ flexDirection: 'row', gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
              {d.state === 'disconnected' || d.state === 'reconnecting' || d.state === 'no_permission' || d.state === 'bluetooth_off'
                ? <Btn small title="Reconnect" onPress={() => act('reconnect', async () => dev.reconnect())} />
                : <Btn small kind="ghost" title="Disconnect" onPress={() => act('disc', async () => dev.disconnect())} />}
              <Btn small kind="danger" title="Unpair" onPress={unpair} />
            </View>
          </>
        ) : <Sub>Pair the Meow Island display to see your clock, steps and music on it.</Sub>}
        {!!d.error && <ErrorBox text={d.error} />}
        {d.bondProblem && (
          <View style={{ marginTop: 8, flexDirection: 'row', gap: 8, flexWrap: 'wrap' }}>
            <Btn small title="Repair pairing" onPress={() => act('repair', async () => { await dev.repair(); })} />
            <Btn small kind="ghost" title="Open Bluetooth settings" onPress={() => dev.openBluetoothSettings()} />
          </View>
        )}
        {d.bondProblem && <Sub>Repair forgets this phone's old pairing and pairs again. Hold the display's BOOT button for 3 seconds first if it already has a phone paired, then type the 6-digit code it shows.</Sub>}
        {!!note && <Sub>{note}</Sub>}
      </Card>

      {!d.paired && (
        <Card>
          <H>Add Device</H>
          <Step n={1}>Power the display. It shows the clock and advertises as “MeowIsland-…”.</Step>
          <Step n={2}>A new display is ready to pair. If it was paired before, hold its BOOT button for 3 seconds.</Step>
          <Step n={3}>Tap <Text style={{ fontWeight: '800' }}>Find my display</Text> and pick it.</Step>
          <Step n={4}>The display shows a 6-digit code. Type it into Android’s pairing box. Only someone who can see the display can pair.</Step>
          <View style={{ marginTop: 12, alignSelf: 'flex-start' }}><Btn title={d.scanning ? 'Searching…' : 'Find my display'} disabled={d.scanning || d.state === 'pairing'} onPress={() => act('scan', scan)} /></View>
          {d.state === 'bluetooth_off' && <ErrorBox text="Bluetooth is off. Turn it on, then search again." />}
          {d.state === 'no_permission' && <ErrorBox text="Meow Island needs the “Nearby devices” permission (Android 11 and older: Location) to find the display. Tap Find my display again and allow it." />}
          {d.scanning && <Loading label="Looking for Meow Island displays nearby…" />}
          {found.map((f) => (
            <View key={f.address} style={{ flexDirection: 'row', alignItems: 'center', paddingVertical: 10, borderTopWidth: 1, borderTopColor: C.line, gap: 10 }}>
              <View style={{ flex: 1 }}>
                <Text style={{ color: C.text, fontWeight: '800' }}>{f.name}</Text>
                <Sub>{'Signal ' + '▮'.repeat(signalBars(f.rssi)) + '▯'.repeat(3 - signalBars(f.rssi)) + (f.pairingOpen ? '  ·  ready to pair' : f.bonded ? '  ·  paired with this phone before' : '  ·  pairing is closed: hold BOOT for 3 s')}</Sub>
              </View>
              <Btn small title={d.state === 'pairing' ? 'Pairing…' : 'Pair'} disabled={d.state === 'pairing' || (!f.pairingOpen && !f.bonded)} onPress={() => act('pair', async () => { await dev.pair(f.address); })} />
            </View>
          ))}
          {!d.scanning && found.length === 0 && d.state === 'unpaired' && !d.error && <Sub>Nothing found yet. Check the display is on, close to the phone, and not already connected to another phone.</Sub>}
          {d.state === 'pairing' && <Sub>Check the display for a 6-digit code and type it into the Android pairing box. This can take a few seconds to appear. Pairing stops by itself after 90 seconds.</Sub>}
          {d.state === 'pairing' && <View style={{ marginTop: 8, alignSelf: 'flex-start' }}><Btn small kind="ghost" title="Cancel pairing" onPress={() => act('cancel', async () => { await dev.cancelPair(); })} /></View>}
        </Card>
      )}

      {d.paired && (
        <Card>
          <H>Screen</H>
          <Sub>Choose what the display shows. The BOOT button on the display cycles the same screens, and the clock is always available.</Sub>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 10 }}>
            {SCREENS.map((sc) => (
              <Pressable key={sc.id} accessibilityRole="button" accessibilityState={{ selected: d.screen === sc.id, disabled: !d.ready }} disabled={!d.ready} onPress={() => act('screen', () => dev.setScreen(sc.id))}
                style={{ width: '31%', flexGrow: 1, minHeight: 64, borderRadius: 16, alignItems: 'center', justifyContent: 'center', backgroundColor: d.screen === sc.id ? C.accent : C.card2, opacity: d.ready ? 1 : 0.5 }}>
                <Text style={{ fontSize: 22 }}>{sc.icon}</Text><Text style={{ color: d.screen === sc.id ? C.onAccent : C.text, fontWeight: '700' }}>{sc.name}</Text>
              </Pressable>
            ))}
          </View>
          <Sub>{SCREENS[d.screen].hint}</Sub>
          <View style={{ marginTop: 8, alignSelf: 'flex-start' }}><Btn small kind="ghost" title="Sync now" disabled={!d.ready || busy === 'sync'} onPress={() => act('sync', () => dev.syncNow())} /></View>
          <Sub>{d.ready ? (d.stepsFresh ? 'Steps are up to date. ' : 'Steps are not on the display yet: turn on the Step counter in the Health tab. ') + (d.musicFresh ? 'Music is syncing.' : '') : ''}</Sub>
          <Sub>The display shows what Meow Island already knows: steps from the Health tab, what is playing (needs Notification access, as for the music widget), phone screen time (needs Usage Access) and, if you switch it on below, your newest notifications. Titles and notifications in non-Latin scripts are simplified on the display, but lyrics are rendered on the phone, so Hindi, Urdu, Arabic, Korean, Japanese and other scripts are shown correctly.</Sub>
        </Card>
      )}

      {d.paired && <DeviceExtrasCards ready={d.ready} openCustom={openCustom} />}

      {!!d.diag && (
        <Card>
          <Pressable accessibilityRole="button" onPress={() => setShowLog(!showLog)} style={{ minHeight: 44, justifyContent: 'center' }}><Text style={{ color: C.accent, fontWeight: '700' }}>{showLog ? 'Hide connection log' : 'Show connection log'}</Text></Pressable>
          {showLog && <Text selectable style={{ color: C.sub, fontSize: 11, fontFamily: 'monospace' }}>{d.diag}</Text>}
          {showLog && <Sub>This shows only Bluetooth steps and status codes (no Wi-Fi details). It is the first thing to send when a connection problem needs investigating.</Sub>}
        </Card>
      )}

      {d.paired && (
        <Card>
          <H>Wi-Fi</H>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}><Text style={{ color: C.text, flex: 1 }}>{wifiText(d)}</Text><Chip ok={d.wifiState === 2} label={d.wifiState === 2 ? 'Online' : d.wifiState === 1 ? 'Connecting' : d.wifiState === 3 ? 'Failed' : 'Off'} /></View>
          <Sub>Optional. With Wi-Fi the display sets its clock from the internet. Without it, the phone sets the clock whenever they connect. The display only works on 2.4 GHz networks.</Sub>
          <Text style={{ color: C.text, marginTop: 10, marginBottom: 6 }}>Network name</Text>
          <TextInput value={ssid} onChangeText={(v) => { setSsid(v); setWifiSent(false); }} maxLength={32} autoCapitalize="none" autoCorrect={false} accessibilityLabel="Wi-Fi network name" placeholderTextColor={C.sub} placeholder="Your home Wi-Fi" style={{ color: C.text, backgroundColor: C.card2, borderRadius: 12, padding: 12, minHeight: 48 }} />
          <Text style={{ color: C.text, marginTop: 10, marginBottom: 6 }}>Password</Text>
          <TextInput value={pass} onChangeText={(v) => { setPass(v); setWifiSent(false); }} maxLength={63} secureTextEntry={!showPass} autoCapitalize="none" autoCorrect={false} accessibilityLabel="Wi-Fi password" style={{ color: C.text, backgroundColor: C.card2, borderRadius: 12, padding: 12, minHeight: 48 }} />
          <Toggle label="Show password" value={showPass} onChange={setShowPass} />
          {!!problem && <Sub>{problem}</Sub>}
          <View style={{ flexDirection: 'row', gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
            <Btn small title={busy === 'wifi' ? 'Sending…' : 'Send to display'} disabled={!d.ready || !!problem || !ssid || busy === 'wifi'} onPress={sendWifi} />
            {(d.hasWifi || !!d.ssid) && <Btn small kind="ghost" title="Remove Wi-Fi" disabled={!d.ready} onPress={() => act('wifi', async () => { await dev.clearWifi(); setWifiSent(false); })} />}
          </View>
          {wifiSent && d.wifiState === 1 && <Loading label="The display is connecting…" />}
          {wifiSent && d.wifiState === 2 && <Sub>Done. The display saved the network and reconnects by itself after every restart.</Sub>}
          {wifiSent && d.wifiState === 3 && <ErrorBox text={(d.wifiReason || 'The display could not join that network.') + ' Check the name and password and try again.'} />}
          {!d.ready && <Sub>Connect to the display first.</Sub>}
          <Sub>The password goes straight to the display over the encrypted Bluetooth link and is stored only in the display’s own memory. This app does not keep it and the display never sends it back.</Sub>
        </Card>
      )}

      <Card>
        <H>Good to know</H>
        <Sub>• The display has no touch: music shows what is playing but you control playback on the phone.</Sub>
        <Sub>• If the phone is away, the display keeps its clock and shows “Phone offline” on the other screens. It reconnects by itself.</Sub>
        <Sub>• Lost or sold a phone? Hold the display’s BOOT button 3 seconds to allow a new phone, or 10 seconds to erase all pairing and Wi-Fi.</Sub>
        <Sub>• If pairing fails after unpairing, remove the old “MeowIsland-…” entry in Android Bluetooth settings first.</Sub>
      </Card>
    </View>
  );
}
