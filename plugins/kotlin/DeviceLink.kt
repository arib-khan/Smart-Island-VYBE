package com.smartisland.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Phone side of the Meow Island TFT device: scanning, pairing (Android's own passkey dialog), connecting, automatic reconnecting and
 * pushing the step card + music data. Everything runs natively so it keeps working while the React Native app is in the background
 * (the process is kept alive by the existing island service / notification listener); the JS screen only displays state and sends commands.
 * Wi-Fi passwords pass through here once, straight into a frame: they are never stored or logged.
 */
@Suppress("DEPRECATION")
object DeviceLink {
    const val S_UNPAIRED = "unpaired"; const val S_PAIRING = "pairing"; const val S_CONNECTING = "connecting"; const val S_CONNECTED = "connected"
    const val S_RECONNECTING = "reconnecting"; const val S_DISCONNECTED = "disconnected"; const val S_BT_OFF = "bluetooth_off"; const val S_NO_PERMISSION = "no_permission"

    private val SERVICE = UUID.fromString(DeviceProtocol.SERVICE_UUID)
    private val RX = UUID.fromString(DeviceProtocol.RX_UUID)
    private val STATUS = UUID.fromString(DeviceProtocol.STATUS_UUID)
    private val CCCD = UUID.fromString(DeviceProtocol.CCCD_UUID)
    private val RECONNECT_DELAYS = longArrayOf(3000, 6000, 12000, 30000)

    class Found(val address: String, val name: String, val rssi: Int, val pairingOpen: Boolean, val bonded: Boolean)

    private val h = Handler(Looper.getMainLooper())
    @Volatile private var appCtx: Context? = null
    private var receiversOn = false

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var statusChr: BluetoothGattCharacteristic? = null
    private var state = S_UNPAIRED
    private var ready = false
    private var error = ""
    private var bondProblem = false
    private var status: DeviceProtocol.Status? = null
    private var mtu = 23
    private var pendingPair: String? = null
    private var attempts = 0
    private var encFailures = 0
    private var writeFailures = 0

    // pairing / connection robustness
    private const val CLOSE_GRACE_MS = 1500L              // after disconnect(): wait for Android to confirm before close() (closing at once can leave the link up)
    private const val CLOSE_SETTLE_MS = 700L              // ...and give the controller this long before opening a new connection to the same device
    private const val CONNECT_TIMEOUT_MS = 20_000L
    private const val PAIR_BOND_TIMEOUT_MS = 90_000L      // Android's bonding (user types the code)
    private const val PAIR_CONNECT_TIMEOUT_MS = 40_000L   // first secure connection after bonding
    private var lastCloseAt = 0L
    private var connectGen = 0                            // bumped on every teardown: delayed connects and step timers of an older attempt are ignored
    private var stepGen = 0
    private var connectPending = false
    private var verifying = false                         // waiting for the first ENCRYPTED read: only then is the link really usable
    private var removingBond = false
    private var recoveryTried = false                     // at most one automatic bond repair per user action
    private var pairPhase = 0                             // 0 none, 1 Android is bonding, 2 bonded and waiting for the first secure connection
    private var sawBonding = false
    private var bondedBefore: Set<String> = emptySet()
    private val diagLog = ArrayDeque<String>()

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false

    private var scanning = false
    private val found = LinkedHashMap<String, Found>()

    // what was last sent, so only changes cross the air
    private var lastStepsKey = ""; private var lastStepsAt = 0L
    private var lastMusicKey = ""; private var lastMusicAt = 0L; private var lastMusicPos = 0; private var lastMusicPlaying = false
    private var lastTimeAt = 0L
    private var tickCount = 0
    // new screens: what the display was last given, so only changes cross the air
    private var lastScreenTime: DeviceExtras.ScreenTime? = null; private var lastScreenTimeKey = ""; private var lastScreenTimeAt = 0L
    private var lastNotifKey = ""
    private var lastMusic2Key = ""; private var lastMusic2At = 0L
    @Volatile private var artKeyWanted = ""; private var artKeyOnDevice = ""; private var artKeyFailed = ""
    @Volatile private var artTriedKey = ""; @Volatile private var artRevWanted = -1; @Volatile private var artBytes: ByteArray? = null; @Volatile private var artLoading = false
    private var artCrcBytes: ByteArray? = null; private var artCrcValue = 0L
    private var lastBandKey = ""; private var lastBandAt = 0L

    /** Short timeline of what the link did (shown in the app under Connection log, also in logcat as MeowLink). Never contains Wi-Fi details. */
    private fun diag(msg: String) {
        Log.i("MeowLink", msg)
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        diagLog.addLast("$t $msg"); while (diagLog.size > 40) diagLog.removeFirst()
    }
    private fun diagText(): String = diagLog.toList().takeLast(14).joinToString("\n")

    // ------------------------------------------------------------------ persistence (the device address and a display name only)
    private fun prefs() = appCtx!!.getSharedPreferences("meowdevice", Context.MODE_PRIVATE)
    fun isPaired(): Boolean = appCtx != null && prefs().getBoolean("paired", false) && prefs().getString("address", null) != null
    private fun wantConnected() = prefs().getBoolean("enabled", true)

    // ------------------------------------------------------------------ environment
    fun hasPermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        else ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun adapter(): BluetoothAdapter? = (appCtx?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Safe to call from any entry point (app start, island service, notification listener, boot): starts what is needed, once. */
    fun ensureStarted(ctx: Context) {
        appCtx = ctx.applicationContext
        h.post {
            registerReceivers()
            if (isPaired() && wantConnected() && gatt == null && !ready && pendingPair == null && !connectPending) connectSaved(false)
            else if (!isPaired() && state != S_PAIRING) setState(S_UNPAIRED)
        }
    }

    private fun deviceOf(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun registerReceivers() {
        val ctx = appCtx ?: return
        if (receiversOn) return
        receiversOn = true
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val dev = deviceOf(i)
                        val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                        val prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                        h.post { onBondChanged(dev?.address, st, prev) }
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        h.post { onAdapterChanged(st) }
                    }
                    // Android 16 no longer repairs a lost bond by itself: it tells the app (the action string is used directly so the app still builds on older SDKs)
                    "android.bluetooth.device.action.KEY_MISSING" -> {
                        val dev = deviceOf(i)
                        h.post { onKeyMissing(dev?.address) }
                    }
                }
            }
        }
        val f = IntentFilter().apply { addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED); addAction(BluetoothAdapter.ACTION_STATE_CHANGED); addAction("android.bluetooth.device.action.KEY_MISSING") }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(r, f)
    }

    private fun onAdapterChanged(st: Int) {
        if (st == BluetoothAdapter.STATE_OFF || st == BluetoothAdapter.STATE_TURNING_OFF) {
            diag("bluetooth turned off")
            if (pendingPair != null && pairPhase == 1) { failPairing("Bluetooth was turned off during pairing."); return }
            cancelPairTimers(); closeGatt(); stopScan(); if (isPaired()) setState(S_BT_OFF)
        }
        else if (st == BluetoothAdapter.STATE_ON && isPaired() && wantConnected()) connectSaved(false)
    }

    /** Broadcasts are only a hint: the pairing poll below reads the real bond state, so a missed or oddly addressed broadcast cannot strand the UI. */
    private fun onBondChanged(address: String?, st: Int, prev: Int) {
        diag("bond $address: $prev -> $st")
        if (st == BluetoothDevice.BOND_BONDING) sawBonding = true
        if (pendingPair != null && pairPhase == 1) { pairPoll(); return }
        // a bond that disappears while we think we are paired (removed in Android's settings, or lost)
        val saved = prefs().getString("address", null)
        if (!removingBond && address != null && address == saved && st == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDED && isPaired()) {
            bondLost("This phone's pairing with the display was removed.")
        }
    }

    /** The phone side of the pairing is gone or no longer accepted by the display. */
    private fun bondLost(why: String) {
        closeGatt()
        if (pendingPair != null && !recoveryTried) { recoveryTried = true; diag("bond lost during pairing: automatic repair"); repair(); return }
        markBondProblem()
        error = why + " Use Repair pairing (hold the display's BOOT button for 3 seconds first)."
        setState(S_DISCONNECTED); emitState()
    }

    private fun onKeyMissing(address: String?) {
        diag("KEY_MISSING $address")
        val saved = prefs().getString("address", null)
        if (address == null || address != saved) return
        encFailures += 2
        bondLost("The display no longer recognises this phone.")
    }

    // ------------------------------------------------------------------ state
    private fun setState(s: String) { state = s; emitState() }
    private fun snapshot(): Map<String, Any> {
        val ctx = appCtx; val st = status
        return mapOf(
            "state" to state, "paired" to isPaired(), "ready" to ready, "scanning" to scanning,
            "name" to (if (ctx != null) prefs().getString("name", "") ?: "" else ""), "address" to (if (ctx != null) prefs().getString("address", "") ?: "" else ""),
            "ssid" to (if (ctx != null) prefs().getString("ssid", "") ?: "" else ""), "error" to error, "bondProblem" to bondProblem,
            "bluetoothOn" to (adapter()?.isEnabled == true), "permission" to (ctx != null && hasPermission(ctx)),
            "fw" to (if (st != null) "${st.fwMajor}.${st.fwMinor}" else ""), "screen" to (st?.screen ?: 0), "wifiState" to (st?.wifiState ?: 0),
            "wifiReason" to DeviceProtocol.wifiReasonText(st?.wifiReason ?: 0), "timeValid" to (st?.timeValid ?: false), "hasWifi" to (st?.hasWifi ?: false),
            "pairingOpen" to (st?.pairingOpen ?: false), "stepsFresh" to (st?.stepsFresh ?: false), "musicFresh" to (st?.musicFresh ?: false),
            "diag" to diagText()
        )
    }
    fun state(): Map<String, Any> = snapshot()
    private fun emitState() { IslandModule.emit("deviceState", snapshot()) }

    // ------------------------------------------------------------------ scanning
    fun scanResultsJson(): String {
        val a = JSONArray()
        for (f in found.values.sortedByDescending { it.rssi }) a.put(JSONObject().put("address", f.address).put("name", f.name).put("rssi", f.rssi).put("pairingOpen", f.pairingOpen).put("bonded", f.bonded))
        return a.toString()
    }

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val rec = r.scanRecord
            val mfg = rec?.getManufacturerSpecificData(0xFFFF)
            val open = mfg != null && mfg.size >= 3 && mfg[0] == 'M'.code.toByte() && mfg[1] == 'I'.code.toByte() && (mfg[2].toInt() and 1) != 0
            val name = rec?.deviceName ?: try { r.device.name } catch (e: SecurityException) { null } ?: "Meow Island"
            val bonded = try { r.device.bondState == BluetoothDevice.BOND_BONDED } catch (e: SecurityException) { false }
            h.post { found[r.device.address] = Found(r.device.address, name, r.rssi, open, bonded); IslandModule.emit("deviceScan", mapOf("json" to scanResultsJson())) }
        }
        override fun onScanFailed(errorCode: Int) { h.post { scanning = false; error = "Bluetooth scan failed (code $errorCode)."; emitState() } }
    }
    private val stopScanRun = Runnable { stopScan() }

    @SuppressLint("MissingPermission")
    fun startScan(): Boolean {
        val ctx = appCtx ?: return false
        if (!hasPermission(ctx)) { error = "Allow Nearby devices (Bluetooth) for Meow Island first."; setState(S_NO_PERMISSION); return false }
        val a = adapter()
        if (a == null || !a.isEnabled) { error = "Turn Bluetooth on first."; setState(S_BT_OFF); return false }
        stopScan(); found.clear(); error = ""; bondProblem = false
        return try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            a.bluetoothLeScanner.startScan(listOf(filter), settings, scanCb)
            scanning = true; h.removeCallbacks(stopScanRun); h.postDelayed(stopScanRun, 25_000); emitState(); true
        } catch (e: Exception) { error = "Could not start scanning: " + (e.message ?: e.javaClass.simpleName); emitState(); false }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        h.removeCallbacks(stopScanRun)
        if (!scanning) return
        scanning = false
        try { adapter()?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) { }
        emitState()
    }

    // ------------------------------------------------------------------ pairing and connecting
    private val pairPollRun = Runnable { pairPoll() }
    private val pairWatchRun = Runnable { pairWatchdog() }
    private fun cancelPairTimers() { h.removeCallbacks(pairPollRun); h.removeCallbacks(pairWatchRun) }

    @SuppressLint("MissingPermission")
    private fun bondedAddresses(a: BluetoothAdapter): Set<String> = try { a.bondedDevices?.map { it.address }?.toSet() ?: emptySet() } catch (e: Exception) { emptySet() }

    /** Starts Android's bonding with the chosen device. Android shows its own dialog: the user types the 6-digit code shown on the TFT. */
    fun pair(address: String): Boolean { recoveryTried = false; return startPairing(address) }

    @SuppressLint("MissingPermission")
    private fun startPairing(address: String): Boolean {
        val ctx = appCtx ?: return false
        if (!hasPermission(ctx)) { setState(S_NO_PERMISSION); return false }
        val a = adapter()
        if (a == null || !a.isEnabled) { setState(S_BT_OFF); return false }
        val picked = found[address]
        stopScan(); error = ""; bondProblem = false; encFailures = 0
        cancelPairTimers(); closeGatt()                    // never pair on top of an old connection or a stale pending operation
        return try {
            val dev = a.getRemoteDevice(address)
            pendingPair = address; sawBonding = false; pairPhase = 1; bondedBefore = bondedAddresses(a)
            prefs().edit().putString("address", address).putString("name", picked?.name ?: dev.name ?: "Meow Island").putBoolean("paired", false).putBoolean("enabled", true).remove("ssid").apply()
            setState(S_PAIRING)
            diag("pair $address bondState=${dev.bondState}")
            if (dev.bondState == BluetoothDevice.BOND_BONDED) { onBonded(address, "already bonded on this phone"); true }
            else {
                h.postDelayed(pairWatchRun, PAIR_BOND_TIMEOUT_MS)
                h.postDelayed(pairPollRun, 1000)
                if (dev.bondState != BluetoothDevice.BOND_BONDING && !dev.createBond()) { failPairing("Android could not start pairing. Turn Bluetooth off and on, then try again."); false } else true
            }
        } catch (e: Exception) { failPairing("Pairing failed: " + (e.message ?: e.javaClass.simpleName)); false }
    }

    /** Reads the real bond state every second while Android is bonding (broadcasts can be missed or carry another address). */
    @SuppressLint("MissingPermission")
    private fun pairPoll() {
        h.removeCallbacks(pairPollRun)
        val addr = pendingPair ?: return
        if (pairPhase != 1) return
        try {
            val a = adapter() ?: return
            val st = a.getRemoteDevice(addr).bondState
            if (st == BluetoothDevice.BOND_BONDING) sawBonding = true
            if (st == BluetoothDevice.BOND_BONDED) { onBonded(addr, "bond state"); return }
            val moved = a.bondedDevices?.firstOrNull { it.address !in bondedBefore && (it.name ?: "").startsWith("MeowIsland") }
            if (moved != null) { onBonded(moved.address, "bonded under another address"); return }
            if (st == BluetoothDevice.BOND_NONE && sawBonding) {
                failPairing("Pairing did not finish. The code may have been wrong or it timed out. If the display showed no code, hold its BOOT button for 3 seconds first, then try again.")
                return
            }
        } catch (e: Exception) { diag("pairPoll: " + e.javaClass.simpleName) }
        h.postDelayed(pairPollRun, 1000)
    }

    private fun onBonded(addr: String, why: String) {
        if (pendingPair == null) return
        diag("bonded ($why): $addr")
        cancelPairTimers()
        pairPhase = 2; pendingPair = addr
        prefs().edit().putString("address", addr).putBoolean("paired", true).apply()
        h.postDelayed(pairWatchRun, PAIR_CONNECT_TIMEOUT_MS)
        connectSaved(false)
    }

    private fun pairWatchdog() {
        if (pendingPair == null) return
        if (pairPhase == 1) {
            failPairing("Pairing timed out. Check that the display shows a 6-digit code (hold its BOOT button for 3 seconds if it does not), then try again.")
        } else if (pairPhase == 2) {
            // bonded, but the first secure connection did not complete: the bond is real, so keep it, stop showing "Pairing" and keep retrying
            diag("connection after bonding timed out")
            pendingPair = null; pairPhase = 0
            closeGatt()
            error = "Paired, but the connection is not complete yet. It keeps retrying. If it does not connect, use Repair pairing."
            setState(S_RECONNECTING); scheduleReconnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun failPairing(msg: String) {
        diag("pairing failed: $msg")
        cancelPairTimers()
        val addr = pendingPair
        pendingPair = null; pairPhase = 0
        if (addr != null) { try { BluetoothDevice::class.java.getMethod("cancelBondProcess").invoke(adapter()?.getRemoteDevice(addr)) } catch (e: Exception) { } }
        closeGatt()
        error = msg
        setState(S_UNPAIRED)
    }

    /** The user cancelled (or left) the pairing screen: never leave the link in "Pairing". */
    fun cancelPair() {
        if (pendingPair == null) return
        diag("pairing cancelled by the user")
        if (pairPhase == 1) { failPairing(""); error = ""; emitState() }
        else { cancelPairTimers(); pendingPair = null; pairPhase = 0; closeGatt(); setState(if (isPaired()) S_DISCONNECTED else S_UNPAIRED) }
    }

    /** Asks Android to forget the bond with the device. @hide API: reflection, best effort. Returns false when Android refuses or the API is not reachable. */
    @SuppressLint("MissingPermission")
    private fun removeBondQuietly(addr: String): Boolean {
        return try {
            val dev = adapter()?.getRemoteDevice(addr) ?: return false
            if (dev.bondState == BluetoothDevice.BOND_NONE) return true
            removingBond = true
            val r = (BluetoothDevice::class.java.getMethod("removeBond").invoke(dev) as? Boolean) ?: false
            diag("removeBond $addr -> $r")
            h.postDelayed({ removingBond = false }, 5000)
            r
        } catch (e: Exception) { diag("removeBond not available: " + e.javaClass.simpleName); removingBond = false; false }
    }

    /**
     * Repairs a pairing the two sides no longer agree on (the phone still has a bond, the display has none, or the other way round): forgets the phone's
     * bond, then pairs afresh. The display must be in pairing mode: it is when it has no paired phone, otherwise hold its BOOT button for 3 seconds.
     */
    fun repair(): Boolean {
        val ctx = appCtx ?: return false
        val addr = prefs().getString("address", null) ?: return false
        if (!hasPermission(ctx)) { setState(S_NO_PERMISSION); return false }
        diag("repair $addr")
        recoveryTried = true
        cancelPairTimers(); closeGatt(); stopScan()
        error = ""; bondProblem = false; encFailures = 0
        if (!removeBondQuietly(addr)) {
            error = "Android would not let the app remove the old pairing. Open Bluetooth settings, forget “MeowIsland”, then use Add Device."
            bondProblem = true; pendingPair = null; pairPhase = 0; setState(S_DISCONNECTED); return false
        }
        pendingPair = addr; pairPhase = 0
        setState(S_PAIRING)
        awaitUnbond(addr, SystemClock.elapsedRealtime() + 6000)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun awaitUnbond(addr: String, until: Long) {
        h.postDelayed({
            if (pendingPair != addr || pairPhase != 0) return@postDelayed          // cancelled meanwhile
            val st = try { adapter()?.getRemoteDevice(addr)?.bondState ?: BluetoothDevice.BOND_NONE } catch (e: Exception) { BluetoothDevice.BOND_NONE }
            if (st == BluetoothDevice.BOND_NONE || SystemClock.elapsedRealtime() > until) { pendingPair = null; startPairing(addr) } else awaitUnbond(addr, until)
        }, 300)
    }

    @SuppressLint("MissingPermission")
    private fun connectSaved(auto: Boolean) {
        val ctx = appCtx ?: return
        val addr = prefs().getString("address", null) ?: return
        if (!hasPermission(ctx)) { setState(S_NO_PERMISSION); return }
        val a = adapter()
        if (a == null || !a.isEnabled) { setState(S_BT_OFF); return }
        closeGatt()
        setState(if (attempts > 0 || state == S_RECONNECTING || (isPaired() && pendingPair == null && state != S_PAIRING)) S_RECONNECTING else S_CONNECTING)
        val gen = connectGen
        val wait = CLOSE_SETTLE_MS - (SystemClock.elapsedRealtime() - lastCloseAt)
        if (lastCloseAt != 0L && wait > 0) { connectPending = true; h.postDelayed({ if (gen == connectGen) openGatt(addr, auto) }, wait) } else openGatt(addr, auto)
    }

    @SuppressLint("MissingPermission")
    private fun openGatt(addr: String, auto: Boolean) {
        connectPending = false
        val ctx = appCtx ?: return
        try {
            val dev = adapter()?.getRemoteDevice(addr) ?: return
            diag("connect $addr auto=$auto bond=${dev.bondState}")
            gatt = if (Build.VERSION.SDK_INT >= 23) dev.connectGatt(ctx, auto, cb, BluetoothDevice.TRANSPORT_LE) else dev.connectGatt(ctx, auto, cb)
            armStep("connect", if (auto) 0L else CONNECT_TIMEOUT_MS)
        } catch (e: Exception) { error = "Could not connect: " + (e.message ?: e.javaClass.simpleName); setState(S_DISCONNECTED); scheduleReconnect() }
    }

    /** Ends the current GATT connection cleanly. disconnect() first and close() only afterwards: closing at once can leave the link (and the display's only connection slot) occupied. */
    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        h.removeCallbacks(tickRun); h.removeCallbacks(reconnectRun)
        connectGen++; stepGen++; connectPending = false; verifying = false
        try { ImageTransfers.onDisconnected() } catch (e: Exception) { }
        ready = false; writing = false; writeQueue.clear(); rx = null; statusChr = null
        val g = gatt
        gatt = null
        if (g != null) {
            try { g.disconnect() } catch (e: Exception) { }
            h.postDelayed({ try { g.close() } catch (e: Exception) { } }, CLOSE_GRACE_MS)
            lastCloseAt = SystemClock.elapsedRealtime()
        }
    }

    // ---- watchdogs: every step of connecting has a time limit, so nothing can wait forever
    private fun armStep(what: String, ms: Long) {
        val gen = ++stepGen
        if (ms > 0) h.postDelayed({ if (gen == stepGen) stepTimeout(what) }, ms)
    }
    private fun stepTimeout(what: String) {
        diag("timeout: $what")
        val g = gatt
        if (what == "mtu") { if (g != null) { armStep("discover", 10_000L); discover(g) }; return }      // some Android versions never call back after their own MTU exchange
        connectFailed(when (what) {
            "connect" -> "Could not reach the display (timed out). Is it switched on and nearby?"
            "discover" -> "The display did not list its services in time."
            "cccd" -> "The display did not accept notifications in time."
            "verify" -> "The display did not answer the secure status request in time."
            else -> "The display did not respond in time."
        })
    }
    private fun connectFailed(msg: String) {
        error = msg; diag(msg)
        closeGatt()
        if (wantConnected()) scheduleReconnect() else setState(S_DISCONNECTED)
    }

    private val reconnectRun = Runnable { if (isPaired() && wantConnected() && gatt == null && !connectPending) connectSaved(attempts > RECONNECT_DELAYS.size) }
    private fun scheduleReconnect() {
        h.removeCallbacks(reconnectRun)
        if (!isPaired() || !wantConnected() || state == S_BT_OFF) return
        val i = attempts; attempts++
        if (i < RECONNECT_DELAYS.size) h.postDelayed(reconnectRun, RECONNECT_DELAYS[i])
        else h.post(reconnectRun)                       // from here on Android reconnects by itself when the device comes into range (autoConnect)
        if (state != S_RECONNECTING) setState(S_RECONNECTING)
    }

    /** Pause: keep the pairing but stop connecting (the device keeps showing its clock). */
    fun disconnect() { prefs().edit().putBoolean("enabled", false).apply(); attempts = 0; cancelPairTimers(); pendingPair = null; pairPhase = 0; closeGatt(); setState(if (isPaired()) S_DISCONNECTED else S_UNPAIRED) }
    fun reconnect() { prefs().edit().putBoolean("enabled", true).apply(); attempts = 0; error = ""; bondProblem = false; encFailures = 0; recoveryTried = false; if (isPaired()) connectSaved(false) }

    /** Forgets the device on the phone, and tells the device to forget this phone (and optionally its Wi-Fi) when it is connected. */
    fun unpair(clearWifi: Boolean): Boolean {
        val wasReady = ready
        if (wasReady) { send(DeviceProtocol.unpair(clearWifi)); h.postDelayed({ finishUnpair() }, 1500) } else finishUnpair()
        return wasReady
    }
    private fun finishUnpair() {
        val addr = prefs().getString("address", null)
        cancelPairTimers(); closeGatt(); stopScan(); pendingPair = null; pairPhase = 0; status = null; error = ""; bondProblem = false; attempts = 0
        prefs().edit().clear().apply()
        if (addr != null) removeBondQuietly(addr)       // forget the phone-side bond too, so a fresh pairing cannot hit a stale key
        setState(S_UNPAIRED)
    }

    // ------------------------------------------------------------------ GATT
    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            h.post { onConn(g, st, newState) }
        }
        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, m: Int, st: Int) { h.post { if (g === gatt) { mtu = m; armStep("discover", 10_000L); discover(g) } } }
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) { h.post { onServices(g, st) } }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) { h.post { onCccd(g, st) } }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) { h.post { if (g === gatt) onWritten(st) } }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { val v = c.value; h.post { if (g === gatt) onStatusBytes(v) } }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { h.post { if (g === gatt) onStatusBytes(value) } }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) { val v = c.value; h.post { onRead(g, st, v) } }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, st: Int) { h.post { onRead(g, st, value) } }
    }

    @SuppressLint("MissingPermission")
    private fun onConn(g: BluetoothGatt, st: Int, newState: Int) {
        diag("gatt " + (if (g === gatt) "" else "(old) ") + "state=$newState status=$st")
        if (g !== gatt) {                                   // a callback from a connection we already dropped: finish closing it, never let it touch the current one
            try { if (newState == BluetoothProfile.STATE_DISCONNECTED) g.close() else g.disconnect() } catch (e: Exception) { }
            return
        }
        if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
            armStep("mtu", 3500L)
            h.postDelayed({ if (g === gatt) { try { if (!g.requestMtu(247)) discover(g) } catch (e: Exception) { discover(g) } } }, 400)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            val wasReady = ready
            closeGatt()
            // Dropping right after connecting, repeatedly, with a bond on record usually means one side lost the pairing keys.
            if (!wasReady && st != 0 && (st == 5 || st == 15 || st == 22 || st == 19 || st == 133 || st == 8)) encFailures++ else if (wasReady) encFailures = 0
            if (pendingPair != null && encFailures >= 2 && !recoveryTried) { recoveryTried = true; diag("repeated drops while pairing: automatic repair"); repair(); return }
            if (encFailures >= 4 && st != 8) markBondProblem()
            if (wantConnected()) scheduleReconnect() else setState(S_DISCONNECTED)
        }
    }

    private fun markBondProblem() {
        bondProblem = true
        error = "This phone and the display no longer agree on their pairing. Use Repair pairing below (hold the display's BOOT button for 3 seconds first)."
        emitState()
    }

    @SuppressLint("MissingPermission")
    private fun discover(g: BluetoothGatt) { if (g === gatt) try { g.discoverServices() } catch (e: Exception) { } }

    @SuppressLint("MissingPermission")
    private fun onServices(g: BluetoothGatt, st: Int) {
        if (g !== gatt) return
        val svc = g.getService(SERVICE)
        rx = svc?.getCharacteristic(RX); statusChr = svc?.getCharacteristic(STATUS)
        if (st != BluetoothGatt.GATT_SUCCESS || rx == null || statusChr == null) {
            error = "This device does not look like a Meow Island display."; diag(error); closeGatt(); setState(S_DISCONNECTED); return
        }
        try {
            armStep("cccd", 8000L)
            g.setCharacteristicNotification(statusChr, true)
            val d = statusChr!!.getDescriptor(CCCD)
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        } catch (e: Exception) { connectFailed("Could not subscribe to the display: " + (e.message ?: "")) }
    }

    /**
     * Notifications are on. That alone proves nothing about security (the descriptor needs no encryption), so the link is only declared ready after
     * a successful ENCRYPTED read of the status characteristic. A phone and display that disagree about their pairing fail right here.
     */
    @SuppressLint("MissingPermission")
    private fun onCccd(g: BluetoothGatt, st: Int) {
        if (g !== gatt) return
        diag("notifications status=$st")
        if (st == BluetoothGatt.GATT_SUCCESS) {
            verifying = true; armStep("verify", 8000L)
            val started = try { g.readCharacteristic(statusChr) } catch (e: Exception) { false }
            if (!started) { verifying = false; connectFailed("Could not read the display status.") }
        }
        else if (st == 5 || st == 15 || st == 8) authFailed(st)       // insufficient authentication / encryption
        else connectFailed("The display refused notifications (code $st).")
    }

    private fun onRead(g: BluetoothGatt, st: Int, v: ByteArray?) {
        if (g !== gatt) return
        if (verifying) {
            verifying = false
            diag("secure read status=$st")
            if (st == BluetoothGatt.GATT_SUCCESS) { onStatusBytes(v); becomeReady(g) }
            else if (st == 5 || st == 15 || st == 8 || st == 137) authFailed(st)      // 137 = GATT_AUTH_FAIL
            else connectFailed("The display did not answer the status request (code $st).")
        } else if (st == BluetoothGatt.GATT_SUCCESS) onStatusBytes(v)
    }

    /** The display refused an encrypted request: the stored keys do not match. While the user is pairing the old phone-side bond is replaced once, automatically. */
    private fun authFailed(st: Int) {
        encFailures++
        diag("secure request refused (status $st), failures=$encFailures")
        closeGatt()
        if (pendingPair != null && !recoveryTried) { recoveryTried = true; repair(); return }
        if (encFailures >= 2) markBondProblem()
        if (wantConnected()) scheduleReconnect() else setState(S_DISCONNECTED)
    }

    @SuppressLint("MissingPermission")
    private fun becomeReady(g: BluetoothGatt) {
        stepGen++                                        // cancels every pending step timer of this connection
        cancelPairTimers(); pairPhase = 0
        ready = true; attempts = 0; encFailures = 0; writeFailures = 0; bondProblem = false; error = ""; pendingPair = null; recoveryTried = false
        diag("ready (mtu $mtu)")
        prefs().edit().putBoolean("paired", true).apply()
        lastStepsKey = ""; lastMusicKey = ""; lastTimeAt = 0
        lastScreenTime = null; lastScreenTimeKey = ""; lastNotifKey = ""; lastMusic2Key = ""
        artKeyOnDevice = ""; artKeyFailed = ""; artTriedKey = ""; lastBandKey = ""
        CustomScreen.onConnected()
        setState(S_CONNECTED)
        send(DeviceProtocol.hello())
        syncAll(true)
        h.removeCallbacks(tickRun); h.postDelayed(tickRun, 1000)
    }

    private fun onStatusBytes(b: ByteArray?) {
        val s = DeviceProtocol.parseStatus(b) ?: return
        val old = status; status = s
        if (s.hasExt) { try { ImageTransfers.onStatus(s); appCtx?.let { CustomScreen.autoSendIfNeeded(it, s) } } catch (e: Exception) { } }
        if (old == null || old.screen != s.screen || old.wifiState != s.wifiState || old.wifiReason != s.wifiReason || old.timeValid != s.timeValid || old.hasWifi != s.hasWifi ||
            old.pairingOpen != s.pairingOpen || old.stepsFresh != s.stepsFresh || old.musicFresh != s.musicFresh) emitState()
    }

    // ------------------------------------------------------------------ writing (one at a time, in order)
    /** Queues a frame for the device (also used by the image transfer). Ignored when not connected. */
    fun send(frame: ByteArray) {
        if (!ready) return
        for (c in DeviceProtocol.chunks(frame, mtu - 3)) writeQueue.addLast(c)
        pump()
    }
    /** For frequently refreshed state: skipped when earlier writes are still waiting (the next tick sends the newest data). */
    private fun sendState(frame: ByteArray): Boolean { if (writeQueue.size <= 12) { send(frame); return true }; return false }
    fun isReady() = ready
    fun lastStatus(): DeviceProtocol.Status? = status

    @SuppressLint("MissingPermission")
    private fun pump() {
        val g = gatt ?: return; val c = rx ?: return
        if (writing || writeQueue.isEmpty() || !ready) return
        val data = writeQueue.removeFirst()
        writing = true
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = data; g.writeCharacteristic(c) }
        } catch (e: Exception) { false }
        if (!ok) {
            writing = false; writeQueue.addFirst(data); writeFailures++
            if (writeFailures > 8) { closeGatt(); scheduleReconnect() } else h.postDelayed({ pump() }, 250)
        }
    }
    private fun onWritten(st: Int) {
        writing = false
        if (st == BluetoothGatt.GATT_SUCCESS) writeFailures = 0
        else if (++writeFailures > 6) { closeGatt(); scheduleReconnect(); return }
        pump()
    }

    // ------------------------------------------------------------------ data
    private val tickRun = object : Runnable {
        override fun run() { if (!ready) return; try { syncAll(false) } catch (e: Exception) { }; try { ImageTransfers.tick() } catch (e: Exception) { }; h.postDelayed(this, 1000) }
    }

    private fun stepsFrom(d: StepCardData): DeviceProtocol.Steps {
        val hint = when { d.hint.contains("Allow Physical") -> 2; d.hint.contains("Waiting") -> 3; d.hint.contains("Turn it on") -> 1; else -> 0 }
        val sub = when (d.subtitle) { "Goal reached!" -> 2; "Keep moving" -> 1; else -> 0 }
        return DeviceProtocol.Steps(d.steps, d.goal.toLong(), d.hasSteps, d.goalOn, d.heatOn, d.happy, if (d.label.contains("off")) 1 else 0, hint, sub, d.levels)
    }

    /** Sends whatever changed since last time. force = send everything now (just connected, or "Sync now"). */
    fun syncAll(force: Boolean) {
        val ctx = appCtx ?: return
        if (!ready) return
        val now = System.currentTimeMillis()
        tickCount++
        if (force || now - lastTimeAt > 30 * 60_000L) {
            lastTimeAt = now
            val tz = java.util.TimeZone.getDefault().getOffset(now) / 60_000
            send(DeviceProtocol.time(now / 1000, tz))
        }
        // steps: the same data the home-screen "Step goals" widget shows; looked at every 5 s, sent only when it changed
        if (force || tickCount % 5 == 0) {
            val d = try { MeowWidgets.stepCardData(ctx) } catch (e: Exception) { null }
            if (d != null) {
                val s = stepsFrom(d)
                val key = "${s.steps}|${s.goal}|${s.hasSteps}|${s.goalOn}|${s.heatOn}|${s.happy}|${s.label}|${s.hint}|${s.subtitle}|${s.levels.joinToString("")}"
                if (force || key != lastStepsKey || now - lastStepsAt > 120_000L) { lastStepsKey = key; lastStepsAt = now; sendState(DeviceProtocol.steps(s)) }
            }
        }
        // music: the same state the Dancing Cat widget shows
        val ms = try { MeowMusic.state(ctx) } catch (e: Exception) { null }
        if (ms != null) {
            val session = ms.view.mode == MusicMode.PLAYING || ms.view.mode == MusicMode.PAUSED
            val playing = session && ms.playing
            val pos = if (session && ms.pos >= 0) (MusicProgress.now(ms.pos, ms.posAt, ms.playing, now, ms.dur) / 1000).toInt() else 0
            val dur = if (session && ms.dur > 0) (ms.dur / 1000).toInt() else 0
            val key = "$session|$playing|${ms.view.title}|${ms.view.artist}|$dur"
            val expected = lastMusicPos + (if (lastMusicPlaying) ((now - lastMusicAt) / 1000).toInt() else 0)
            val seeked = key == lastMusicKey && Math.abs(pos - expected) > 3
            if (force || key != lastMusicKey || seeked || (playing && now - lastMusicAt >= 10_000L)) {
                lastMusicKey = key; lastMusicAt = now; lastMusicPos = pos; lastMusicPlaying = playing
                sendState(DeviceProtocol.music(session, playing, pos, dur, ms.view.title, ms.view.artist))
            }
        }
        try { syncExtras(force) } catch (e: Exception) { }      // new screens must never be able to break the existing ones
    }

    // ------------------------------------------------------------------ screen time, notifications, music extras (source, lyrics, album art)
    /** Called when the daily goal changed or Usage Access was granted: read and send again right away. */
    fun screenTimeChanged() { h.post { lastScreenTime = null; if (ready) try { syncExtras(false) } catch (e: Exception) { } } }
    /** Called by the notification queue when its content changed. */
    fun notifsChanged() { h.post { if (ready) try { syncNotifs(false) } catch (e: Exception) { } } }
    /** Called when something arrived asynchronously (lyrics fetched, art transfer finished). */
    fun requestSync() { h.post { if (ready) try { syncExtras(false) } catch (e: Exception) { } } }

    private fun syncExtras(force: Boolean) {
        val ctx = appCtx ?: return
        val now = System.currentTimeMillis()
        // phone screen time: read from Android every minute (or when asked), sent when the numbers changed or every 5 minutes
        if (force || lastScreenTime == null || tickCount % 60 == 0) {
            val s = DeviceExtras.readScreenTime(ctx); lastScreenTime = s
            val key = DeviceExtras.screenTimeKey(s)
            if (force || key != lastScreenTimeKey || now - lastScreenTimeAt > 300_000L) { if (sendState(DeviceExtras.screenTimeFrame(s))) { lastScreenTimeKey = key; lastScreenTimeAt = now } }
        }
        syncNotifs(force)
        syncMusicExtras(force, now)
    }

    private fun syncNotifs(force: Boolean) {
        val ctx = appCtx ?: return
        val now = System.currentTimeMillis()
        val key = DeviceExtras.notifKey(ctx, now)
        if (force || key != lastNotifKey) { if (sendState(DeviceExtras.notifFrame(ctx, now))) lastNotifKey = key }
    }

    private fun syncMusicExtras(force: Boolean, now: Long) {
        val ctx = appCtx ?: return
        val track = MediaListener.current
        val snap = MediaListener.snapshot
        val active = track?.active ?: (snap["active"] == true)
        val pkg = track?.pkg ?: (snap["app"] as? String) ?: ""
        val title = track?.title ?: (snap["title"] as? String) ?: ""
        val artist = track?.artist ?: (snap["artist"] as? String) ?: ""
        val album = track?.album ?: (snap["album"] as? String) ?: ""
        val durMs = track?.durationMs ?: (snap["duration"] as? Double)?.toLong() ?: 0L
        val playing = track?.playing ?: (snap["playing"] == true)
        val pos = track?.positionNow(now) ?: MusicProgress.now((snap["position"] as? Double)?.toLong() ?: 0L,
            (snap["posAt"] as? Double)?.toLong() ?: 0L, playing, now, durMs)
        val src = if (active) DeviceExtras.sourceLabel(ctx, pkg) else ""
        val video = track?.isVideo ?: (snap["video"] == true)
        val trackKey = track?.key() ?: Provider.cacheKey(pkg, (snap["trackId"] as? String) ?: "", title, artist, album)

        // Lyrics for every music provider, not just Spotify. Video apps (YouTube, streaming video) never get a lookup.
        val eligible = active && title.isNotBlank() && !video
        DeviceExtras.Lyrics.update(ctx, eligible, title, artist, album, (durMs / 1000L).toInt())
        val lyState = if (eligible) DeviceExtras.Lyrics.state else DeviceProtocol.LY_NA
        val synced = lyState == DeviceProtocol.LY_AVAILABLE

        // album art: the cached 80x80 RGB565 image is sent once per track, never for progress or text changes
        val artSource = if (active) MediaListener.artSmall else null
        val artKey = if (active && artSource != null) trackKey else ""
        val artRev = MediaListener.artRev
        val keyChanged = artKey != artKeyWanted
        if (keyChanged || artRev != artRevWanted) {
            artKeyWanted = artKey; artRevWanted = artRev
            artTriedKey = ""; artLoading = false
            if (keyChanged) artKeyFailed = ""
            // a cached cover is reused for the same track; a genuinely new bitmap is decoded again
            artBytes = if (artKey.isEmpty()) null else if (keyChanged) ArtCache.memory(artKey) else null
        }
        if (artKey.isNotEmpty() && artBytes == null && !artLoading && artKey != artTriedKey) {
            artTriedKey = artKey; artLoading = true
            val wantRev = artRev
            ArtCache.request(ctx, artKey, artSource) { bytes ->
                if (artKeyWanted == artKey && artRevWanted == wantRev) {
                    artBytes = bytes
                    artLoading = false
                    if (bytes != null) DeviceLink.requestSync()
                }
            }
        }
        val bytes = artBytes
        if (bytes != null && bytes.size == ArtCache.BYTES && artKeyWanted.isNotEmpty()) {
            val crc = if (bytes === artCrcBytes) artCrcValue else DeviceProtocol.crc32(bytes).also { artCrcBytes = bytes; artCrcValue = it }
            // the device may still hold exactly this image (replay / app switch): then no transfer at all is needed
            val alreadyThere = artKeyWanted == artKeyOnDevice || (crc != 0L && status?.artCrc == crc)
            if (alreadyThere) {
                artKeyOnDevice = artKeyWanted
            } else if (!ImageTransfers.busy() && status?.hasExt == true && artKeyWanted != artKeyFailed) {
                val k = artKeyWanted
                ImageTransfers.start(
                    DeviceProtocol.KIND_ART, bytes, ArtCache.ART, ArtCache.ART,
                    { ok, reason, _ ->
                        // an abort or a lost link (reason 0) is retried later, a real failure is not
                        if (ok) { artKeyOnDevice = k } else if (reason != 0) { artKeyFailed = k }
                        requestSync()
                    },
                    { _, _ -> }
                )
            }
        }
        val hasArt = artKeyWanted.isNotEmpty() && artKeyWanted == artKeyOnDevice

        var idx = 0; var prev = ""; var cur = ""; var next = ""
        if (synced) {
            val c = DeviceExtras.Lyrics.current(pos)
            idx = (c.idx + 1).coerceIn(0, 65535); prev = c.prev; cur = c.cur; next = c.next
        } else if (lyState == DeviceProtocol.LY_UNSYNCED) {
            val c = DeviceExtras.Lyrics.plainCurrent(pos)
            idx = (c.idx + 1).coerceIn(0, 65535); prev = c.prev; cur = c.cur; next = c.next
        }
        val key = "$src|$video|$hasArt|$lyState|$synced|$idx|$prev|$cur|$next"
        if (force || key != lastMusic2Key || now - lastMusic2At > 30_000L) {
            if (sendState(DeviceProtocol.music2(hasArt, synced, lyState, idx, src, prev, cur, next, video))) { lastMusic2Key = key; lastMusic2At = now }
        }

        // The phone renders the three lyric lines into a 1-bit mask: the TFT has no Unicode fonts, and Android does the shaping,
        // bidi and font fallback. One small transfer per changed line, never for progress.
        val bandKey = if (lyState == DeviceProtocol.LY_AVAILABLE || lyState == DeviceProtocol.LY_UNSYNCED) "$trackKey|$idx|$prev|$cur|$next" else ""
        if (bandKey.isEmpty()) {
            if (lastBandKey.isNotEmpty()) { send(DeviceProtocol.lyricClear()); lastBandKey = "" }
        } else if (bandKey != lastBandKey && now - lastBandAt >= 250L) {
            val mask = try { LyricBandRenderer.render(prev, cur, next) } catch (e: Exception) { ByteArray(0) }
            if (mask.size == DeviceProtocol.LYR_BYTES) {
                val token = (trackKey.hashCode() and 0xFF).let { if (it == 0) 1 else it }
                var off = 0; var seq = 0
                while (off < mask.size) {
                    val n = minOf(DeviceProtocol.LYR_CHUNK, mask.size - off)
                    send(DeviceProtocol.lyricBand(token, seq, idx, mask, off, n))
                    off += n; seq++
                }
                lastBandKey = bandKey; lastBandAt = now
            }
        }
    }

    // ------------------------------------------------------------------ commands from the app
    fun setScreen(id: Int): Boolean { if (!ready) return false; send(DeviceProtocol.screen(id)); return true }

    /** Sends the Wi-Fi details to the device over the encrypted link. Returns false if not connected or the values are not valid. The password is not kept. */
    fun setWifi(ssid: String, pass: String): Boolean {
        if (!ready) return false
        val f = DeviceProtocol.wifiSet(ssid, pass) ?: return false
        send(f)
        prefs().edit().putString("ssid", ssid).apply()           // the name only, for display; never the password
        emitState(); return true
    }
    fun clearWifi(): Boolean { if (!ready) return false; send(DeviceProtocol.wifiClear()); prefs().edit().remove("ssid").apply(); emitState(); return true }
}
