import React, { useCallback, useEffect, useState } from 'react';
import { AppState, Alert, View } from 'react-native';
import { Btn, Card, Chip, ErrorBox, H, Sub, Toggle } from '../ui';
import { devx, deviceExtrasAvailable, native } from '../native';

/** Device screen cards for the notification display, the online lyrics source and the entry to the custom HTML screen. */
/** Plain-language meaning of the lyric state the phone reports (DeviceProtocol LY_*). */
function lyricStatus(state: number): string {
  switch (state) {
    case 1: return 'Looking up lyrics…';
    case 2: return 'Time-synchronised lyrics found for this song.';
    case 3: return 'No lyrics found for this song.';
    case 4: return 'Online lyrics are off.';
    case 5: return 'The lyrics service could not be reached.';
    case 6: return 'Only plain lyrics exist for this song: they are shown without timing.';
    default: return 'Play a song in any music app to look up its lyrics.';
  }
}

export function DeviceExtrasCards({ ready, openCustom }: { ready: boolean; openCustom: () => void }) {
  const [n, setN] = useState<{ enabled: boolean; access: boolean; count: number; preview: boolean } | null>(null);
  const [ly, setLy] = useState<'off' | 'online'>('off');
  const [lyInfo, setLyInfo] = useState<{ state: number; detail: string; source: string }>({ state: 0, detail: '', source: '' });
  const [err, setErr] = useState('');

  const load = useCallback(async () => {
    if (!deviceExtrasAvailable) return;
    try {
      setN(await devx.notifsGet());
      const l = await devx.lyricsGet();
      if (l) { setLy(l.provider === 'online' ? 'online' : 'off'); setLyInfo({ state: l.state, detail: l.detail || '', source: l.source || '' }); }
      setErr('');
    } catch (e: any) { setErr(e?.message ?? 'Could not read the settings'); }
  }, []);
  useEffect(() => {
    load();
    const poll = setInterval(load, 4000);                      // the lyric state changes while a song plays
    const sub = AppState.addEventListener('change', (st) => { if (st === 'active') load(); });   // coming back from Android's notification-access screen
    return () => { sub.remove(); clearInterval(poll); };
  }, [load]);

  if (!deviceExtrasAvailable) return <Card><H>New display screens</H><Sub>This APK was built before the new display screens were added. Rebuild the app (see the README) to use them.</Sub></Card>;

  const toggleNotifs = async (on: boolean) => { try { await devx.notifsSetEnabled(on); await load(); } catch (e: any) { setErr(e?.message ?? 'Failed'); } };
  const setLyrics = async (on: boolean) => {
    if (!on) { try { await devx.lyricsSetProvider('off'); setLy('off'); } catch (e: any) { setErr(e?.message ?? 'Failed'); } return; }
    Alert.alert('Turn on online lyrics?', 'The title, artist, album and length of the song that is playing are sent over HTTPS to LRCLIB (lrclib.net) and, if it has no answer, to lyrics.ovh. Both are free public lyrics services. Nothing else is sent: no account, no notification content, no device identifier. Lyrics are fetched for every music app, not just Spotify; YouTube and video apps are never looked up. Time-synced lyrics are shown in step with playback; plain lyrics are shown without timing. Without this switch no lyrics are ever fetched.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Turn on', onPress: async () => { try { await devx.lyricsSetProvider('online'); setLy('online'); } catch (e: any) { setErr(e?.message ?? 'Failed'); } } },
    ]);
  };

  return (
    <>
      <Card>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}><H>Notifications on the display</H><Chip ok={!!n?.enabled && !!n?.access} label={!n?.enabled ? 'Off' : n.access ? 'On' : 'Needs access'} /></View>
        <Sub>Shows your newest notifications (app, title, a short preview) on the Notifications screen. They go straight from this phone to the display over Bluetooth; nothing is uploaded and nothing is saved on the display or on this phone's storage. The list is kept in memory only, holds the newest 20 for up to 6 hours, and clearing it never dismisses anything on the phone.</Sub>
        <Toggle label="Show notifications on the display" value={!!n?.enabled} onChange={toggleNotifs} />
        {!!n?.enabled && !n.access && (
          <View style={{ marginTop: 6 }}>
            <Sub>Android needs to allow Meow Island to read notifications. On the next screen switch on Meow Island.</Sub>
            <View style={{ height: 8 }} /><Btn title="Open Notification access settings" onPress={() => native.requestNotificationAccess()} />
          </View>
        )}
        {!!n?.enabled && n.access && <Sub>{n.preview ? 'Titles and previews are shown.' : 'Previews are off (Settings › notification previews): only the app name is shown.'} Notifications marked private by their app are never shown. {n.count} in the list now.</Sub>}
        {!!n?.enabled && <View style={{ marginTop: 8, alignSelf: 'flex-start' }}><Btn small kind="ghost" title="Clear the display's list" onPress={() => devx.notifsClear().then(load).catch((e: any) => setErr(e?.message ?? 'Failed'))} /></View>}
      </Card>

      <Card>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}><H>Music: lyrics</H><Chip ok={ly === 'online'} label={ly === 'online' ? 'Lyrics on' : 'Lyrics off'} /></View>
        <Sub>The Music screen shows album art, title, artist and progress from whichever player is on this phone. Online lyrics work for every music app that publishes title and artist (Spotify, Amazon Music, Apple Music, Tidal, Deezer, JioSaavn and others), not just Spotify. YouTube and video apps are excluded: they show only their thumbnail, title, duration and progress.</Sub>
        <Sub>LRCLIB (lrclib.net) is tried first for time-synchronised lyrics; if it has no answer, lyrics.ovh supplies plain lyrics, which are shown without timing. Every line is rendered on the phone, so Hindi, Urdu, Arabic, Korean, Japanese and other scripts are shown correctly (right-to-left included) instead of being dropped by the display's ASCII fonts. Lyrics are never translated or invented. The line under the switch tells you what the last lookup answered.</Sub>
        <Toggle label="Look up lyrics online (LRCLIB, then lyrics.ovh)" value={ly === 'online'} onChange={setLyrics} />
        {ly === 'online' && <Sub>{lyricStatus(lyInfo.state)}{lyInfo.source ? ` Source: ${lyInfo.source}.` : ''}{lyInfo.detail ? ` (${lyInfo.detail})` : ''}</Sub>}
      </Card>

      <Card>
        <H>Custom screen</H>
        <Sub>Design your own display with an HTML file (a name, a QR code, a logo, a message). It is rendered on this phone and stored on the display.</Sub>
        <View style={{ marginTop: 8, alignSelf: 'flex-start' }}><Btn title="Open Custom Screen" onPress={openCustom} /></View>
        {!ready && <Sub>Connect to the display to send a design. You can prepare one now.</Sub>}
      </Card>
      {!!err && <ErrorBox text={err} />}
    </>
  );
}
