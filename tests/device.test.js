const D = src('core/device.ts');

test('device state from the native side is clamped to known values', () => {
  const d = D.mergeDeviceState({ state: 'exploding', paired: 'yes', screen: 99, wifiState: -4, name: 'x'.repeat(200), error: 5 });
  assert.strictEqual(d.state, 'unpaired'); assert.strictEqual(d.paired, false); assert.strictEqual(d.screen, 5); assert.strictEqual(d.wifiState, 0);
  assert.strictEqual(d.name.length, 40); assert.strictEqual(d.error, '');
  assert.doesNotThrow(() => D.mergeDeviceState(null)); assert.doesNotThrow(() => D.mergeDeviceState('x'));
  assert.strictEqual(D.mergeDeviceState({ state: 'connected', ready: true }).ready, true);
});
test('status labels cover every state', () => {
  for (const [st, tone] of [['connected', 'ok'], ['connecting', 'warn'], ['reconnecting', 'warn'], ['pairing', 'warn'], ['bluetooth_off', 'bad'], ['no_permission', 'bad'], ['unpaired', 'idle'], ['disconnected', 'idle']])
    assert.strictEqual(D.statusLabel(D.mergeDeviceState({ state: st, paired: true })).tone, tone, st);
  assert.strictEqual(D.statusLabel(D.mergeDeviceState({ state: 'disconnected', paired: true })).text, 'Paired · disconnected');
});
test('scan results are validated', () => {
  const j = JSON.stringify([{ address: 'AA:BB:CC:DD:EE:FF', name: 'MeowIsland-1234', rssi: -55, pairingOpen: true, bonded: false }, { address: 'not an address' }, null, { address: 'AA:BB:CC:DD:EE:00', rssi: 50 }]);
  const f = D.parseFound(j);
  assert.strictEqual(f.length, 2); assert.strictEqual(f[0].pairingOpen, true); assert.strictEqual(f[1].name, 'Meow Island'); assert.strictEqual(f[1].rssi, 0);
  assert.strictEqual(D.parseFound('garbage').length, 0); assert.strictEqual(D.parseFound(undefined).length, 0); assert.strictEqual(D.parseFound('{"a":1}').length, 0);
});
test('Wi-Fi input rules match the device (protocol.h)', () => {
  assert.strictEqual(D.wifiProblem('Home', 'password1'), null); assert.strictEqual(D.wifiProblem('Cafe', ''), null);
  assert.ok(D.wifiProblem('', 'password1')); assert.ok(D.wifiProblem('x'.repeat(33), 'password1')); assert.ok(D.wifiProblem('ok', 'short')); assert.ok(D.wifiProblem('ok', 'p'.repeat(64)));
  assert.strictEqual(D.wifiProblem('x'.repeat(32), 'p'.repeat(63)), null);
  assert.strictEqual(D.utf8Len('é'), 2); assert.strictEqual(D.utf8Len('日本'), 6); assert.strictEqual(D.utf8Len('😀'), 4);
  assert.ok(D.wifiProblem('日'.repeat(11), 'password1'), '33 bytes of CJK is too long even though it is only 11 characters');
});
test('Wi-Fi status text never contains a password and reads sensibly', () => {
  const base = { ready: true, ssid: 'HomeNet' };
  assert.strictEqual(D.wifiText(D.mergeDeviceState({ ...base, wifiState: 2 })), 'Connected to Wi-Fi “HomeNet”');
  assert.strictEqual(D.wifiText(D.mergeDeviceState({ ...base, wifiState: 1 })), 'Connecting to Wi-Fi “HomeNet”…');
  assert.strictEqual(D.wifiText(D.mergeDeviceState({ ...base, wifiState: 3, wifiReason: 'The Wi-Fi password was not accepted' })), 'The Wi-Fi password was not accepted');
  assert.strictEqual(D.wifiText(D.mergeDeviceState({ ready: true, wifiState: 0 })), 'No Wi-Fi set up');
  assert.strictEqual(D.wifiText(D.mergeDeviceState({ ready: false })), 'Connect to the device to see its Wi-Fi');
});
test('signal bars and screen list', () => {
  assert.strictEqual(D.signalBars(-50), 3); assert.strictEqual(D.signalBars(-70), 2); assert.strictEqual(D.signalBars(-85), 1); assert.strictEqual(D.signalBars(-100), 0);
  assert.strictEqual(D.SCREENS.length, 6); assert.strictEqual(D.SCREENS[0].name, 'Clock'); assert.deepStrictEqual(Array.from(D.SCREENS.map((s) => s.id)), [0, 1, 2, 3, 4, 5]);
});

test('phone and firmware use the same GATT UUIDs, frame magic, protocol version and payload limit', () => {
  const fs = require('fs'), path = require('path');
  const kt = fs.readFileSync(path.join(__dirname, '..', 'plugins', 'kotlin', 'DeviceProtocol.kt'), 'utf8');
  const link = fs.readFileSync(path.join(__dirname, '..', 'firmware', 'MeowIsland', 'device_link.h'), 'utf8');
  const proto = fs.readFileSync(path.join(__dirname, '..', 'firmware', 'MeowIsland', 'protocol.h'), 'utf8');
  const k = (name) => (kt.match(new RegExp(name + '\\s*=\\s*"([0-9a-f-]+)"')) || [])[1];
  const f = (name) => (link.match(new RegExp(name + '\\s*=\\s*"([0-9a-f-]+)"')) || [])[1];
  assert.ok(k('SERVICE_UUID')); assert.strictEqual(k('SERVICE_UUID'), f('BLE_SERVICE_UUID'));
  assert.strictEqual(k('RX_UUID'), f('BLE_RX_UUID')); assert.strictEqual(k('STATUS_UUID'), f('BLE_STATUS_UUID'));
  assert.strictEqual(parseInt((kt.match(/const val MAGIC = (0x[0-9A-Fa-f]+)/) || [])[1], 16), parseInt((proto.match(/FRAME_MAGIC\s*=\s*(0x[0-9A-Fa-f]+)/) || [])[1], 16));
  assert.strictEqual((kt.match(/const val PROTO = (\d+)/) || [])[1], (proto.match(/PROTO_VERSION\s*=\s*(\d+)/) || [])[1]);
  assert.ok(/require\(payload\.size <= 200\)|payload\.size <= 200/.test(kt) && /MAX_PAYLOAD\s*=\s*200/.test(proto), 'both sides cap a frame payload at 200 bytes');
  assert.ok(/STATUS_FULL_LEN = 23/.test(kt) && /STATUS_EXT_LEN = 13/.test(kt), 'phone status is 23 bytes');
  assert.ok(/STATUS_EXT_LEN = 13, STATUS_FULL_LEN = STATUS_LEN \+ STATUS_EXT_LEN/.test(proto), 'firmware status is 10 + 13 bytes');
  assert.strictEqual((kt.match(/MSG_LYRIC = (0x[0-9A-Fa-f]+)/) || [])[1], (proto.match(/MSG_LYRIC = (0x[0-9A-Fa-f]+)/) || [])[1], 'the lyric message has the same type on both sides');
  assert.strictEqual((kt.match(/IMG_CHUNK = (\d+)/) || [])[1], '190');
  assert.ok(/IMG_MAX_CHUNK = 190/.test(proto), 'firmware accepts the phone image chunk size');
  assert.ok(/artCrc/.test(kt) && /artCrc/.test(proto), 'the device reports its album-art CRC');
  assert.ok(/WRITE_ENC \| NIMBLE_PROPERTY::WRITE_AUTHEN/.test(link), 'firmware requires an encrypted + authenticated link for writes (the phone must therefore verify encryption before declaring the link ready)');
});
test('the pairing code path has timeouts, a cancel and a repair on the phone (regression guard for the stuck "Pairing…" bug)', () => {
  const fs = require('fs'), path = require('path');
  const link = fs.readFileSync(path.join(__dirname, '..', 'plugins', 'kotlin', 'DeviceLink.kt'), 'utf8');
  for (const needle of ['PAIR_BOND_TIMEOUT_MS', 'PAIR_CONNECT_TIMEOUT_MS', 'fun cancelPair', 'fun repair', 'private fun pairPoll', 'private fun failPairing', 'private fun armStep', 'verifying = true', 'KEY_MISSING', 'CLOSE_GRACE_MS'])
    assert.ok(link.includes(needle), 'DeviceLink.kt lost: ' + needle);
  assert.ok(!/gatt\?\.disconnect\(\) \} catch \(e: Exception\) \{ \}\s*\n\s*try \{ gatt\?\.close\(\)/.test(link), 'disconnect() must not be followed immediately by close()');
});

test('lyrics: multiple legitimate providers, accurate matching and plain-lyrics fallback', () => {
  const fs = require('fs'), path = require('path');
  const root = path.join(__dirname, '..');
  const kt = fs.readFileSync(path.join(root, 'plugins', 'kotlin', 'LrcLibProvider.kt'), 'utf8');
  const ovh = fs.readFileSync(path.join(root, 'plugins', 'kotlin', 'LyricsOvhProvider.kt'), 'utf8');
  const logic = fs.readFileSync(path.join(root, 'plugins', 'kotlin', 'LyricsLogic.kt'), 'utf8');
  assert.ok(/"get\?track_name="/.test(kt) && /"search\?track_name="/.test(kt) && /"search\?q="/.test(kt), 'LRCLIB exact, search, cleaned search and free-text queries');
  assert.ok(/LyricsMatcher\.pick/.test(kt) && /versionCompatible/.test(logic) && /durationScore/.test(logic), 'search results are matched by title/artist/album/length and version markers');
  assert.ok(/api\.lyrics\.ovh/.test(ovh) && /supportsSynced = false/.test(ovh), 'lyrics.ovh is the plain-lyrics fallback');
  assert.ok(/class LyricsService/.test(logic) && /providers/.test(logic) && /LY_UNSYNCED/.test(logic), 'provider chain falls back and can return plain lyrics');
  assert.ok(/PlainLyrics/.test(logic) && /\\p\{M\}/.test(logic), 'plain lyrics keep Unicode combining marks (Devanagari/Arabic)');
  assert.ok(!/isSpotify\(pkg\)/.test(fs.readFileSync(path.join(root, 'plugins', 'kotlin', 'DeviceLink.kt'), 'utf8')), 'lyrics are no longer gated on Spotify');
  assert.ok(/r\.code == 429 \|\| r\.code >= 500/.test(kt), 'only 429/5xx are "service busy"');
  assert.ok(/Retry-After/.test(kt) && /MIN_GAP_MS/.test(kt), 'LRCLIB rate limits and Retry-After are respected');
  assert.ok(!/if \(code != 200\) return LyricsResult\(DeviceProtocol\.LY_OFFLINE/.test(kt), 'a 400/404 answer must never be reported as "offline"');
});
test('captions: YouTube caption/subtitle extraction is gone, not just switched off', () => {
  const fs = require('fs'), path = require('path');
  const root = path.join(__dirname, '..');
  assert.ok(!fs.existsSync(path.join(root, 'plugins/kotlin/MeowCaptionService.kt')), 'caption accessibility service removed');
  assert.ok(!fs.existsSync(path.join(root, 'plugins/widgets/res/xml/meow_caption_config.xml')), 'caption service config removed');
  const manifest = fs.readFileSync(path.join(root, 'plugins/withIsland.js'), 'utf8');
  assert.ok(!manifest.includes('.MeowCaptionService') && !manifest.includes('meow_caption_config'), 'no caption service in the manifest');
  const extras = fs.readFileSync(path.join(root, 'plugins/kotlin/DeviceExtras.kt'), 'utf8');
  assert.ok(!/Captions|captionState|captionsEnabled/.test(extras), 'no caption buffer or caption state anymore');
  const ui = fs.readFileSync(path.join(root, 'src/screens/DeviceExtras.tsx'), 'utf8');
  assert.ok(!/caption/i.test(ui), 'no caption switch in the app');
  const proto = fs.readFileSync(path.join(root, 'firmware/MeowIsland/protocol.h'), 'utf8');
  assert.ok(!/caption/i.test(proto), 'protocol no longer documents caption states');
});
test('album art: persistent cache, stable key, no repeat transfer and stale-cover protection', () => {
  const fs = require('fs'), path = require('path');
  const root = path.join(__dirname, '..');
  const art = fs.readFileSync(path.join(root, 'plugins/kotlin/ArtCache.kt'), 'utf8');
  const link = fs.readFileSync(path.join(root, 'plugins/kotlin/DeviceLink.kt'), 'utf8');
  assert.ok(/filesDir/.test(art) && /artcache/.test(art) && /sha256/.test(art), 'artwork is cached on disk under a stable hash');
  assert.ok(/AppWidgetManager|LinkedHashMap/.test(art) && /MAX_FILES/.test(art), 'memory LRU + bounded disk cache');
  assert.ok(/MediaListener\.artRev/.test(link) && /ArtCache\.request/.test(link), 'new artwork is decoded asynchronously and a changed cover is detected');
  assert.ok(/status\?\.artCrc == crc/.test(link), 'a cover the display already holds is not transferred again');
  assert.ok(/artKeyWanted == artKeyOnDevice/.test(link) && /artKeyFailed/.test(link), 'failed/interrupted transfers do not loop, and the old cover is not marked current');
});
test('YouTube: video plays get metadata/progress but never lyrics', () => {
  const fs = require('fs'), path = require('path');
  const root = path.join(__dirname, '..');
  const model = fs.readFileSync(path.join(root, 'plugins/kotlin/NowPlaying.kt'), 'utf8');
  const screen = fs.readFileSync(path.join(root, 'firmware/MeowIsland/music_screen.h'), 'utf8');
  assert.ok(/isVideo/.test(model) && /lyricsEligible/.test(model), 'the model marks video apps and excludes them from lyrics');
  assert.ok(/musicVideo/.test(screen) && /drawBottomProgress/.test(screen), 'the display has a video mode and a bottom progress bar');
  assert.ok(!/Live Caption|LY_CAPTION|lyricState == 7|lyricState == 8/.test(screen), 'no caption states remain on the music screen');
});
