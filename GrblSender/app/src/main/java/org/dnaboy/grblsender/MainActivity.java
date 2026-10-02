package org.dnaboy.grblsender;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Grbl 1.1 G-code sender over USB OTG serial (CH340/FTDI/CP210x/CDC) with a
 * G-code editor, toolpath preview and live tracing while a job runs.
 * Streaming uses Grbl's character-counting protocol: keep at most rxLimit
 * bytes outstanding, release them as each "ok"/"error" comes back.
 */
public class MainActivity extends Activity implements SerialLink.Listener {

    private static final String ACTION_USB_PERMISSION = "org.dnaboy.grblsender.USB_PERMISSION";
    private static final String ACTION_DFU_PERMISSION = "org.dnaboy.grblsender.DFU_PERMISSION";
    private static final int REQ_OPEN_FILE = 1;
    private static final int REQ_SAVE_AS = 2;
    private static final int REQ_FIRMWARE = 4;
    private static final int FLASH_LIMIT = 30720;    // 32 KB minus the 2 KB Pro Mini bootloader
    /** Bytes we may have outstanding in the controller's RX buffer: 127 for classic Grbl (128-byte buffer), raised from $I. */
    private int rxLimit = 127;
    private static final int STATUS_POLL_MS = 250;
    private static final int MAX_LOG_CHARS = 20000;
    private static final int PLANNER_LOOKBACK = 24;  // lines that may sit in Grbl's planner + RX buffer
    private static final String[] BAUDS = {"38400", "115200", "57600", "9600"};
    private static final String SAMPLE_NAME = "circle_d100.nc";
    private static final String SAMPLE_DIR = "GrblSender";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder rxLine = new StringBuilder();

    private UsbManager usbManager;
    private SerialLink port;
    private UsbSerialDriver pendingDriver;
    private boolean opening = false;
    private volatile boolean gotData = false;
    private volatile int writeFailures = 0;

    // Streaming state (main thread only)
    private static final class Sent {
        final int len;
        final boolean fromProgram;
        Sent(int len, boolean fromProgram) { this.len = len; this.fromProgram = fromProgram; }
    }
    private final ArrayDeque<String> manualQueue = new ArrayDeque<>();
    private final ArrayDeque<Sent> inFlight = new ArrayDeque<>();
    private int inFlightBytes = 0;
    private final List<GCodeParser.Line> program = new ArrayList<>();
    private GCodeParser.Result job;            // what Load produced; streamed by Run
    private String jobText;                    // editor text the job was built from
    private int nextLine = 0;
    private int ackedLines = 0;
    private int errorCount = 0;
    private boolean streaming = false;
    private long jobStartMs;
    private long allAckedAtMs;

    private final float[] mpos = new float[3];
    private final float[] wco = new float[3];
    private final float[] wpos = new float[3];
    private boolean wposKnown = false;
    private String machineState = "";
    /** Grbl motion settings ($11, $12, $110-112, $120-122) for run-time estimates; read from $$ on connect. */
    private final TimeEstimator.Limits limits = new TimeEstimator.Limits();
    /** Last $$ report ("$100" -> "81.490"), shown on the CNC setup page. */
    private final java.util.Map<String, String> grblSettings = new java.util.HashMap<>();
    private static final java.util.regex.Pattern SETTING_LINE = java.util.regex.Pattern.compile("^\\$(\\d+)=([-0-9.]+)\\s*$");
    private int execIdx = -1;                  // program line the machine is executing (from live position)
    private long holdMs, holdSinceMs;          // time spent paused (M0 / feed hold) during the run
    private long lastStatusMs;                 // when Grbl last answered a status query
    private boolean stallWarned;
    private static final long HEARTBEAT_MS = 5 * 60 * 1000;
    private static final long STALL_MS = 5000;
    private static final long LOST_OK_MS = 5000;
    private long idleWithPendingSinceMs;       // Grbl Idle while we still wait for "ok"s

    /** During a run: every 5 min log progress + tablet health; warn if Grbl stops answering. */
    private final Runnable runWatch = new Runnable() {
        private long lastBeat;
        @Override public void run() {
            if (!streaming) { lastBeat = 0; return; }
            long now = System.currentTimeMillis();
            long silent = now - lastStatusMs;
            if (silent > STALL_MS && !stallWarned) {
                stallWarned = true;
                log("Warning: no reply from Grbl for " + silent / 1000 + " s – USB link may be stuck");
                RemoteLog.e("run", "STALL: no status reply for " + silent + " ms at line " + ackedLines + "/" + program.size());
            } else if (silent <= STALL_MS && stallWarned) {
                stallWarned = false;
                log("Grbl is answering again");
            }
            checkLostAcks(now);
            if (lastBeat == 0 || now - lastBeat >= HEARTBEAT_MS) {
                lastBeat = now;
                RemoteLog.i("run", heartbeat());
            }
            ui.postDelayed(this, 1000);
        }
    };

    // Document state
    private Uri docUri;
    private String docName;
    private boolean dirty;
    private int parseSeq;

    // Views
    private Spinner baudSpinner;
    private Button btnConnect, btnRun, btnLoad, tabEditor, tabConsole;
    private TextView txtState, txtPos, txtMPos, txtFile, txtJob, txtLog;
    private ScrollView logScroll;
    private ProgressBar progress;
    private EditText cmdInput, jogFeed;
    private RadioGroup stepGroup;
    private GCodeEditor editor;
    private GCodeViewer viewer;
    /** Text of a large document shown read-only in {@link #viewer}; null when the editor holds the document. */
    private String largeText;
    private static final int LARGE_LINES = 4000;
    private static final int LARGE_CHARS = 150_000;
    private ToolpathView preview;
    private View fullControls;
    private TextView fullState;
    private boolean fullScreen;

    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            if (port != null) {
                writeRaw(new byte[]{'?'});
                ui.postDelayed(this, STATUS_POLL_MS);
            }
        }
    };

    private final Runnable reparse = () -> refreshPreview(null);

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && pendingDriver != null) {
                    connect(pendingDriver);
                } else {
                    log("USB permission denied");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (port != null) {
                    log("USB device detached");
                    disconnect();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // RemoteLog's own startup check is replaced by the update checker below (respects the auto-check setting)
        RemoteLog.init(getApplicationContext(), (code, name) -> { });
        ui.postDelayed(autoUpdateCheck, 3000);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        baudSpinner = findViewById(R.id.baud);
        btnConnect = findViewById(R.id.btnConnect);
        btnRun = findViewById(R.id.btnRun);
        btnLoad = findViewById(R.id.btnLoad);
        tabEditor = findViewById(R.id.tabEditor);
        tabConsole = findViewById(R.id.tabConsole);
        txtState = findViewById(R.id.txtState);
        txtPos = findViewById(R.id.txtPos);
        txtMPos = findViewById(R.id.txtMPos);
        txtFile = findViewById(R.id.txtFile);
        txtJob = findViewById(R.id.txtJob);
        txtLog = findViewById(R.id.txtLog);
        logScroll = findViewById(R.id.logScroll);
        progress = findViewById(R.id.progress);
        cmdInput = findViewById(R.id.cmdInput);
        jogFeed = findViewById(R.id.jogFeed);
        stepGroup = findViewById(R.id.stepGroup);
        editor = findViewById(R.id.editor);
        viewer = findViewById(R.id.viewer);
        preview = findViewById(R.id.preview);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, BAUDS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        baudSpinner.setAdapter(adapter);
        applyBoardToUi();

        btnConnect.setOnClickListener(v -> { if (port == null) connect(); else disconnect(); });

        findViewById(R.id.btnUnlock).setOnClickListener(v -> queueCommand("$X"));
        findViewById(R.id.btnHome).setOnClickListener(v -> queueCommand("$H"));
        findViewById(R.id.btnReset).setOnClickListener(v -> softReset());
        findViewById(R.id.btnZeroXY).setOnClickListener(v -> queueCommand("G10L20P1X0Y0"));
        findViewById(R.id.btnZeroZ).setOnClickListener(v -> queueCommand("G10L20P1Z0"));
        findViewById(R.id.btnGoZero).setOnClickListener(v -> queueCommand("G90G0X0Y0"));
        findViewById(R.id.btnSettings).setOnClickListener(v -> { showConsole(true); queueCommand("$$"); });

        findViewById(R.id.jXp).setOnClickListener(v -> jog("X", 1));
        findViewById(R.id.jXm).setOnClickListener(v -> jog("X", -1));
        findViewById(R.id.jYp).setOnClickListener(v -> jog("Y", 1));
        findViewById(R.id.jYm).setOnClickListener(v -> jog("Y", -1));
        findViewById(R.id.jZp).setOnClickListener(v -> jog("Z", 1));
        findViewById(R.id.jZm).setOnClickListener(v -> jog("Z", -1));
        findViewById(R.id.jStop).setOnClickListener(v -> writeRaw(new byte[]{(byte) 0x85})); // jog cancel

        findViewById(R.id.btnNew).setOnClickListener(v -> confirmDiscard(this::newDocument));
        findViewById(R.id.btnOpen).setOnClickListener(v -> confirmDiscard(this::openFile));
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        findViewById(R.id.btnSaveAs).setOnClickListener(v -> saveAs());
        findViewById(R.id.btnClose).setOnClickListener(v -> confirmDiscard(this::closeDocument));
        btnLoad.setOnClickListener(v -> load(null));
        btnRun.setOnClickListener(v -> run());
        findViewById(R.id.btnHold).setOnClickListener(v -> writeRaw(new byte[]{'!'}));
        findViewById(R.id.btnResume).setOnClickListener(v -> writeRaw(new byte[]{'~'}));
        findViewById(R.id.btnStop).setOnClickListener(v -> stopJob());
        findViewById(R.id.btnFit).setOnClickListener(v -> preview.fit());
        findViewById(R.id.btnMenu).setOnClickListener(this::showMenu);
        fullControls = findViewById(R.id.fullControls);
        fullState = findViewById(R.id.fullState);
        findViewById(R.id.btnFull).setOnClickListener(v -> setFullScreen(!fullScreen));
        findViewById(R.id.fullHold).setOnClickListener(v -> writeRaw(new byte[]{'!'}));
        findViewById(R.id.fullResume).setOnClickListener(v -> writeRaw(new byte[]{'~'}));
        findViewById(R.id.fullStop).setOnClickListener(v -> stopJob());
        tabEditor.setOnClickListener(v -> showConsole(false));
        tabConsole.setOnClickListener(v -> showConsole(true));

        editor.setOnChange(() -> {
            if (!dirty) { dirty = true; updateTitle(); updateButtons(); }
            if (job != null && !docText().equals(jobText)) txtJob.setText("Edited since load");
            ui.removeCallbacks(reparse);
            ui.postDelayed(reparse, 500);
        });

        findViewById(R.id.btnSend).setOnClickListener(v -> sendInput());
        cmdInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                sendInput();
                return true;
            }
            return false;
        });

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(ACTION_DFU_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        showConsole(false);
        updateTitle();
        updateButtons();
        log("Grbl Sender " + BuildConfig.VERSION_NAME + " ready. Board: " + boardName(board()) + " (☰ → Settings). Plug it in and tap Connect.");
        restoreDocument();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 3);
        }
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction())) connect();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction()) && port == null) connect();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (streaming) RemoteLog.i("app", "back in foreground during run, line " + ackedLines + "/" + program.size());
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (streaming) RemoteLog.i("app", "app in background during run (foreground service keeps streaming), line "
                + ackedLines + "/" + program.size());
    }

    @Override
    protected void onDestroy() {
        if (streaming) {
            RemoteLog.e("app", "app closed during run at line " + ackedLines + "/" + program.size() + " – job aborted");
            JobService.stop(this, "App was closed – job aborted at line " + ackedLines + " / " + program.size());
        }
        ui.removeCallbacks(autoUpdateCheck);
        unregisterReceiver(usbReceiver);
        disconnect();
        writer.shutdown();
        worker.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- USB

    private void connect() {
        if (opening) return;
        if (usbManager.getDeviceList().isEmpty()) log("No USB devices attached at all (OTG not working?)");
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            log(String.format(Locale.US, "USB device %04X:%04X %s", d.getVendorId(), d.getProductId(),
                    d.getProductName() != null ? d.getProductName() : ""));
            RemoteLog.d("usb", dumpDevice(d));
        }
        List<UsbSerialDriver> all = serialProber().findAllDrivers(usbManager);
        String b = board();
        List<UsbSerialDriver> mine = new ArrayList<>();
        for (UsbSerialDriver d : all) if (driverIsBoard(d, b)) mine.add(d);
        if (mine.isEmpty() && !all.isEmpty()) {
            String other = BOARD_BLUEPILL.equals(b) ? BOARD_PROMINI : BOARD_BLUEPILL;
            UsbSerialDriver d0 = all.get(0);
            log("Settings say " + boardName(b) + ", but the USB device looks like a " + boardName(other) + " (" + describe(d0) + ")");
            new AlertDialog.Builder(this)
                    .setTitle("Different board plugged in")
                    .setMessage("Settings: " + boardName(b) + "\nFound: " + describe(d0) + " – this is a " + boardName(other)
                            + ".\n\nSwitch the controller board to " + boardName(other) + " and connect?")
                    .setPositiveButton("Switch & connect", (dlg, w) -> { setBoard(other); connect(d0); })
                    .setNeutralButton("Connect anyway", (dlg, w) -> connect(d0))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        final List<UsbSerialDriver> drivers = mine.isEmpty() ? all : mine;
        if (drivers.isEmpty() && findDfuDevice() != null) {
            log("Blue Pill bootloader (DFU 1EAF:0003) detected – use ☰ → Firmware update, then reset with BOOT1 = 0");
            return;
        }
        if (drivers.isEmpty()) {
            log("No USB serial device found. Check the OTG cable.");
            return;
        }
        if (drivers.size() == 1) {
            connect(drivers.get(0));
            return;
        }
        String[] names = new String[drivers.size()];
        for (int i = 0; i < names.length; i++) names[i] = describe(drivers.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Select USB serial device")
                .setItems(names, (dlg, which) -> connect(drivers.get(which)))
                .show();
    }

    /** Default drivers plus grblHAL's Blue Pill USB serial (STM32 Virtual ComPort, CDC ACM). */
    private static UsbSerialProber serialProber() {
        com.hoho.android.usbserial.driver.ProbeTable t = UsbSerialProber.getDefaultProbeTable();
        t.addProduct(DfuFlasher.VID_ST, DfuFlasher.PID_ST_VCP, com.hoho.android.usbserial.driver.CdcAcmSerialDriver.class);
        return new UsbSerialProber(t);
    }

    private UsbDevice findDfuDevice() {
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (d.getVendorId() == DfuFlasher.VID_MAPLE && d.getProductId() == DfuFlasher.PID_MAPLE_DFU) return d;
        }
        return null;
    }

    private String dumpDevice(UsbDevice d) {
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.US, "%s VID:PID=%04X:%04X class=%d/%d/%d mfr=%s product=%s perm=%b",
                d.getDeviceName(), d.getVendorId(), d.getProductId(), d.getDeviceClass(),
                d.getDeviceSubclass(), d.getDeviceProtocol(), d.getManufacturerName(), d.getProductName(),
                usbManager.hasPermission(d)));
        b.append("\nconfigs=").append(d.getConfigurationCount()).append(" interfaces=").append(d.getInterfaceCount());
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface intf = d.getInterface(i);
            b.append(String.format(Locale.US, "\n  if#%d id=%d alt=%d class=%d/%d/%d endpoints=%d",
                    i, intf.getId(), intf.getAlternateSetting(), intf.getInterfaceClass(),
                    intf.getInterfaceSubclass(), intf.getInterfaceProtocol(), intf.getEndpointCount()));
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                b.append(String.format(Locale.US, "\n    ep addr=0x%02X type=%d dir=%s maxPacket=%d",
                        ep.getAddress(), ep.getType(), ep.getDirection() == 0 ? "OUT" : "IN", ep.getMaxPacketSize()));
            }
        }
        return b.toString();
    }

    private static String describe(UsbSerialDriver driver) {
        UsbDevice d = driver.getDevice();
        return String.format(Locale.US, "%s (%04X:%04X)",
                driver.getClass().getSimpleName().replace("SerialDriver", ""),
                d.getVendorId(), d.getProductId());
    }

    private void connect(UsbSerialDriver driver) {
        pendingDriver = driver;
        UsbDevice device = driver.getDevice();
        if (!usbManager.hasPermission(device)) {
            Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags));
            log("Requesting USB permission…");
            return;
        }
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            log("Could not open USB device (openDevice returned null)");
            return;
        }
        final int baud = Integer.parseInt((String) baudSpinner.getSelectedItem());   // ignored by native USB (Blue Pill)
        final boolean ch340 = device.getVendorId() == 0x1A86;
        gotData = false;
        writeFailures = 0;
        opening = true;
        btnConnect.setEnabled(false);
        log("Opening " + describe(driver) + " @ " + baud + "…");
        // USB control transfers can block for seconds each; never do them on the UI thread
        new Thread(() -> {
            try {
                SerialLink link = ch340
                        ? Ch340Link.open(connection, device, baud, this)
                        : LibLink.open(driver, connection, baud, this);
                ui.post(() -> onOpened(link, driver, baud, ch340));
            } catch (Exception e) {
                try { connection.close(); } catch (Exception ignored) { }
                ui.post(() -> {
                    opening = false;
                    btnConnect.setEnabled(true);
                    log("Open failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    RemoteLog.e("usb", "Open failed (" + describe(driver) + ")", e);
                });
            }
        }, "usb-open").start();
    }

    private void onOpened(SerialLink link, UsbSerialDriver driver, int baud, boolean ch340) {
        opening = false;
        btnConnect.setEnabled(true);
        port = link;
        resetStreamState();
        log("Connected: " + describe(driver) + " @ " + baud + (ch340 ? " (built-in CH340 driver)" : ""));
        rxLimit = 127;
        fwVersion = null;
        fwOptions = null;
        if (driver.getDevice().getVendorId() == DfuFlasher.VID_ST) {
            log("Blue Pill (native USB) – sending a soft reset");
            ui.postDelayed(() -> { if (port == link) writeRaw(new byte[]{0x18}); }, 300);
        }
        ui.postDelayed(() -> {
            if (port == link && !gotData) log("Warning: no data from the " + boardName(board()) + " yet (expected Grbl banner)");
        }, 4000);
        ui.postDelayed(statusPoll, 1500);
        // learn the rapid rate for time estimates
        ui.postDelayed(() -> { if (port == link) { queueSilent("$$"); queueSilent("$I"); } }, 2000);
        updateButtons();
    }

    private void disconnect() {
        ui.removeCallbacks(statusPoll);
        if (streaming) finishRun(false);
        streaming = false;
        closePort();
        machineState = "";
        txtState.setText("Disconnected");
        txtState.setTextColor(0xFFAAAAAA);
        updateButtons();
    }

    private void closePort() {
        if (port != null) {
            final SerialLink p = port;
            port = null;
            new Thread(p::close, "usb-close").start();
        }
    }

    private void writeRaw(byte[] data) {
        final SerialLink p = port;
        if (p == null) return;
        writer.execute(() -> {
            try {
                p.write(data, 1000);
                writeFailures = 0;
            } catch (Exception e) {
                int n = ++writeFailures;
                if (n == 1) RemoteLog.e("usb", "write failed", e);
                if (n <= 3) ui.post(() -> log("Write error: " + e.getMessage()));
                if (n == 3) ui.post(() -> {
                    if (port != p) return;
                    log("USB link is not responding – disconnected. Unplug the cable from the tablet and plug it back in.");
                    disconnect();
                });
            }
        });
    }

    @Override
    public void onNewData(byte[] data) {
        if (!gotData) {
            gotData = true;
            RemoteLog.i("usb", "first data received (" + data.length + " bytes)");
        }
        String s = new String(data, StandardCharsets.US_ASCII);
        ui.post(() -> {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\n') {
                    String line = rxLine.toString().trim();
                    rxLine.setLength(0);
                    if (!line.isEmpty()) handleLine(line);
                } else if (c != '\r') {
                    rxLine.append(c);
                }
            }
        });
    }

    @Override
    public void onRunError(Exception e) {
        ui.post(() -> {
            if (port != null) {
                log("Serial error: " + e.getMessage());
                RemoteLog.e("usb", "serial I/O error", e);
                disconnect();
            }
        });
    }

    // ---------------------------------------------------------------- Grbl protocol

    private int silentAcks = 0;   // responses of internally issued commands are not echoed

    private void handleLine(String line) {
        if (line.startsWith("<")) {
            lastStatusMs = System.currentTimeMillis();
            parseStatus(line);
            return;
        }
        if (line.equals("ok") || line.startsWith("error:")) {
            Sent s = inFlight.poll();
            if (s != null) {
                inFlightBytes -= s.len;
                if (s.fromProgram) ackedLines++;
            }
            boolean silent = s != null && !s.fromProgram && silentAcks > 0;
            if (silent) silentAcks--;
            if (line.startsWith("error:")) {
                errorCount++;
                String where = s != null && s.fromProgram && ackedLines - 1 < program.size()
                        ? " at line " + (program.get(ackedLines - 1).editorLine + 1) + ": " + program.get(ackedLines - 1).text : "";
                log("<< " + line + describeError(line) + where);
            } else if (!silent && (s == null || !s.fromProgram)) {
                log("<< ok");
            }
            pump();
            updateProgress();
            return;
        }
        java.util.regex.Matcher sm = SETTING_LINE.matcher(line);
        if (sm.matches()) grblSettings.put("$" + sm.group(1), sm.group(2));
        if (limits.apply(line) && !streaming) {
            // machine settings changed: redo the estimate once the $$ report is complete
            ui.removeCallbacks(reparse);
            ui.postDelayed(reparse, 600);
        }
        if (line.startsWith("Grbl ")) {
            // Banner after reset: everything outstanding was discarded
            if (streaming) {
                log("!! Grbl reset during job – job aborted");
                finishRun(false);
            }
            resetStreamState();
        }
        if (line.startsWith("[VER:") || line.startsWith("[OPT:")) {
            String v = line.substring(5, line.length() - (line.endsWith("]") ? 1 : 0));
            if (line.startsWith("[VER:")) fwVersion = v.endsWith(":") ? v.substring(0, v.length() - 1) : v;
            else {
                fwOptions = v;
                // [OPT:flags,planner blocks,rx buffer] -> use the real RX buffer size (grblHAL: 512+)
                String[] o = v.split(",");
                if (o.length >= 3) {
                    try { rxLimit = Math.max(63, Math.min(1023, Integer.parseInt(o[2].trim()) - 1)); } catch (NumberFormatException ignored) { }
                }
            }
            updateInstalledText();
            updateSettingsInfo();
            if (silentAcks > 0) return;
        }
        if (silentAcks > 0 && line.startsWith("$")) return;
        log("<< " + line);
    }

    private void parseStatus(String line) {
        String body = line.substring(1, line.endsWith(">") ? line.length() - 1 : line.length());
        String[] fields = body.split("\\|");
        String prevState = machineState;
        machineState = fields[0];
        boolean haveWpos = false;
        float[] w = new float[3];
        for (int i = 1; i < fields.length; i++) {
            String f = fields[i];
            if (f.startsWith("MPos:")) parseXYZ(f.substring(5), mpos);
            else if (f.startsWith("WPos:")) { parseXYZ(f.substring(5), w); haveWpos = true; }
            else if (f.startsWith("WCO:")) parseXYZ(f.substring(4), wco);
        }
        if (haveWpos) {
            for (int i = 0; i < 3; i++) mpos[i] = w[i] + wco[i];
        } else {
            for (int i = 0; i < 3; i++) w[i] = mpos[i] - wco[i];
        }
        boolean moved = !wposKnown || Math.abs(w[0] - wpos[0]) > 1e-3 || Math.abs(w[1] - wpos[1]) > 1e-3;
        System.arraycopy(w, 0, wpos, 0, 3);
        wposKnown = true;

        txtPos.setText(String.format(Locale.US, "X %8.3f\nY %8.3f\nZ %8.3f", w[0], w[1], w[2]));
        txtMPos.setText(String.format(Locale.US, "MPos %.3f, %.3f, %.3f", mpos[0], mpos[1], mpos[2]));
        txtState.setText(machineState + (streaming ? "  ▶" : ""));
        int color;
        if (machineState.startsWith("Alarm")) color = 0xFFFF5555;
        else if (machineState.startsWith("Hold") || machineState.startsWith("Door")) color = 0xFFFFC040;
        else if (machineState.startsWith("Run") || machineState.startsWith("Jog")) color = 0xFF55AAFF;
        else color = 0xFF66DD66;
        txtState.setTextColor(color);
        fullState.setText(String.format(Locale.US, "%s   X%.2f Y%.2f Z%.2f", machineState, w[0], w[1], w[2]));
        fullState.setTextColor(color);

        preview.setTool(w[0], w[1], w[2]);
        if (streaming) {
            checkProgramPause();
            trackExecution();
        } else if (machineState.equals("Idle") && (moved || !prevState.equals("Idle")) && previewIsRelative()) {
            // relative (G91) programs are drawn from the current position, so follow it while idle
            ui.removeCallbacks(reparse);
            ui.postDelayed(reparse, 400);
        }
    }

    private static void parseXYZ(String s, float[] out) {
        String[] p = s.split(",");
        for (int i = 0; i < 3 && i < p.length; i++) {
            try { out[i] = Float.parseFloat(p[i]); } catch (NumberFormatException ignored) { }
        }
    }

    /** Send queued manual commands first, then program lines, while Grbl's RX buffer has room. */
    private void pump() {
        if (port == null) return;
        while (true) {
            String line;
            boolean fromProgram;
            if (!manualQueue.isEmpty()) {
                line = manualQueue.peek();
                fromProgram = false;
            } else if (streaming && nextLine < program.size()) {
                line = program.get(nextLine).text;
                fromProgram = true;
            } else {
                break;
            }
            int len = line.length() + 1;
            if (inFlightBytes + len > rxLimit && !inFlight.isEmpty()) break;
            if (fromProgram) nextLine++; else manualQueue.poll();
            inFlight.add(new Sent(len, fromProgram));
            inFlightBytes += len;
            writeRaw((line + "\n").getBytes(StandardCharsets.US_ASCII));
            if (!fromProgram && !(silentAcks > 0 && line.equals("$$"))) log(">> " + line);
        }
        if (streaming && allAckedAtMs == 0 && nextLine >= program.size() && ackedLines >= program.size()) {
            // Everything is in Grbl's planner; trackExecution() ends the run once a later status says Idle
            allAckedAtMs = System.currentTimeMillis();
        }
    }

    private void queueCommand(String cmd) {
        if (port == null) { log("Not connected"); return; }
        manualQueue.add(cmd);
        pump();
    }

    /** Internal command whose output is not echoed to the console. */
    private void queueSilent(String cmd) {
        if (port == null) return;
        silentAcks++;
        manualQueue.add(cmd);
        pump();
    }

    private void sendInput() {
        String cmd = cmdInput.getText().toString().trim();
        if (cmd.isEmpty()) return;
        cmdInput.setText("");
        showConsole(true);
        if (cmd.equals("?") || cmd.equals("!") || cmd.equals("~")) {
            writeRaw(cmd.getBytes(StandardCharsets.US_ASCII));
            log(">> " + cmd);
        } else {
            queueCommand(cmd.toUpperCase(Locale.US));
        }
    }

    private void jog(String axis, int dir) {
        if (streaming) { log("Jog disabled while running"); return; }
        float step;
        int id = stepGroup.getCheckedRadioButtonId();
        if (id == R.id.s01) step = 0.1f;
        else if (id == R.id.s10) step = 10f;
        else if (id == R.id.s50) step = 50f;
        else step = 1f;
        int feed;
        try { feed = Integer.parseInt(jogFeed.getText().toString()); } catch (NumberFormatException e) { feed = 500; }
        queueCommand(String.format(Locale.US, "$J=G91G21%s%.3fF%d", axis, dir * step, feed));
    }

    private void softReset() {
        writeRaw(new byte[]{0x18});
        log(">> [Ctrl-X soft reset]");
        if (streaming) finishRun(false);
        resetStreamState();
    }

    private void resetStreamState() {
        boolean wasStreaming = streaming;
        streaming = false;
        manualQueue.clear();
        inFlight.clear();
        inFlightBytes = 0;
        silentAcks = 0;
        if (wasStreaming) updateButtons();
    }

    // ---------------------------------------------------------------- Load / Run

    private boolean previewIsRelative() {
        GCodeParser.Result r = preview.getPath();
        return r != null && r.relative;
    }

    /** Re-parse the editor text for the preview (off the UI thread). */
    private void refreshPreview(Runnable then) {
        if (streaming) return;
        final String text = docText();
        final float sx = wposKnown ? wpos[0] : 0, sy = wposKnown ? wpos[1] : 0, sz = wposKnown ? wpos[2] : 0;
        final TimeEstimator.Limits rr = limits.copy();
        final int seq = ++parseSeq;
        worker.execute(() -> {
            GCodeParser.Result r = GCodeParser.parse(text, sx, sy, sz, rr);
            ui.post(() -> {
                if (seq != parseSeq || streaming) return;
                preview.setPath(r);
                if (then != null) then.run();
            });
        });
    }

    /** Parse the editor text into the job that Run streams. */
    private void load(Runnable then) {
        if (streaming) return;
        final String text = docText();
        if (text.trim().isEmpty()) { log("Nothing to load – the editor is empty"); return; }
        final float sx = wposKnown ? wpos[0] : 0, sy = wposKnown ? wpos[1] : 0, sz = wposKnown ? wpos[2] : 0;
        final TimeEstimator.Limits rr = limits.copy();
        final int seq = ++parseSeq;
        btnLoad.setEnabled(false);
        worker.execute(() -> {
            GCodeParser.Result r = GCodeParser.parse(text, sx, sy, sz, rr);
            ui.post(() -> {
                btnLoad.setEnabled(true);
                if (seq != parseSeq) return;
                job = r;
                jobText = text;
                program.clear();
                program.addAll(r.lines);
                nextLine = 0;
                ackedLines = 0;
                preview.setPath(r);
                preview.clearTrace();
                progress.setProgress(0);
                int sec = (int) Math.round(r.feedTimeMin * 60);
                txtJob.setText(String.format(Locale.US, "Loaded %d lines · est. %s", program.size(), hms(sec)));
                log(String.format(Locale.US, "Loaded %s: %d lines, tool Ø%.3f mm%s, size %.1f×%.1f mm, estimated %s%s",
                        docName != null ? docName : "untitled", program.size(), r.toolDiameter,
                        r.toolFromFile ? "" : " (default)", r.hasBounds() ? r.maxX - r.minX : 0,
                        r.hasBounds() ? r.maxY - r.minY : 0, hms(sec), estimateNote(r)));
                if (r.tooLong > 0) log("Warning: " + r.tooLong + " lines longer than 80 chars (Grbl limit)");
                updateButtons();
                if (then != null) then.run();
            });
        });
    }

    private void run() {
        if (port == null) { log("Not connected"); return; }
        if (streaming) return;
        if (machineState.startsWith("Alarm")) { log("Grbl is in ALARM – unlock ($X) or home first"); return; }
        // Always rebuild the job from the current text and position (G91 programs depend on it)
        load(this::confirmRun);
    }

    private void confirmRun() {
        if (job == null || program.isEmpty()) return;
        long sec = Math.round(job.estSeconds);
        String ends = new java.text.SimpleDateFormat("HH:mm", Locale.US)
                .format(new java.util.Date(System.currentTimeMillis() + sec * 1000));
        String msg = String.format(Locale.US,
                "%s\n\n%d lines · tool Ø%.3f mm\nEstimated cut time %s (ends ≈ %s, plus pauses)%s\nSize %.1f × %.1f mm\n\nStart at X %.3f  Y %.3f  Z %.3f\n\nKeep a hand near Hold / Stop.",
                docName != null ? docName : "untitled", program.size(), job.toolDiameter, hms(sec), ends,
                estimateNote(job),
                job.hasBounds() ? job.maxX - job.minX : 0, job.hasBounds() ? job.maxY - job.minY : 0,
                wpos[0], wpos[1], wpos[2]);
        new AlertDialog.Builder(this)
                .setTitle("Run program?")
                .setMessage(msg)
                .setPositiveButton("Run", (d, w) -> startJob())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startJob() {
        if (port == null || program.isEmpty() || streaming) return;
        nextLine = 0;
        ackedLines = 0;
        errorCount = 0;
        allAckedAtMs = 0;
        execIdx = -1;
        holdMs = 0;
        holdSinceMs = 0;
        lastStatusMs = System.currentTimeMillis();
        stallWarned = false;
        streaming = true;
        ui.removeCallbacks(runWatch);
        ui.postDelayed(runWatch, 1000);
        jobStartMs = System.currentTimeMillis();
        if (largeText == null) editor.setReadOnly(true);
        showConsole(false);
        preview.setRunning(true);
        preview.setDoneLine(-1);
        log("Run started: " + (docName != null ? docName : "untitled"));
        JobService.start(this, "Running " + (docName != null ? docName : "untitled"));
        updateButtons();
        pump();
    }

    private void stopJob() {
        if (!streaming) { softReset(); return; }
        // Feed hold first so the reset does not lose position, then reset to flush Grbl's planner
        writeRaw(new byte[]{'!'});
        final int at = ackedLines;
        ui.postDelayed(() -> {
            softReset();
            log("Run stopped at line " + at + "/" + program.size() + " (unlock with $X)");
        }, 300);
        finishRun(false);
    }

    private void finishRun(boolean completed) {
        boolean wasRunning = streaming;
        streaming = false;
        editor.setReadOnly(false);
        editor.setExecLine(-1);
        viewer.setExecLine(-1);
        preview.setRunning(false);
        if (!wasRunning) { updateButtons(); return; }
        ui.removeCallbacks(runWatch);
        clearPause();
        streaming = true;                     // heartbeat() reads run state
        RemoteLog.i("run", "final: " + heartbeat());
        streaming = false;
        long sec = (System.currentTimeMillis() - jobStartMs) / 1000;
        if (!completed) {
            String m = "Stopped at line " + ackedLines + " / " + program.size() + " after " + hms(sec);
            txtJob.setText(m);
            JobService.stop(this, m);
        } else {
            preview.setDoneLine(Integer.MAX_VALUE);
            progress.setProgress(1000);
            String m = String.format(Locale.US, "Run finished: %d lines, %d errors, %s",
                    program.size(), errorCount, hms(sec));
            log(m);
            txtJob.setText(m.replace("Run finished: ", "Done · "));
            JobService.stop(this, m + " – switch the spindle off if it is manual");
        }
        updateButtons();
    }

    // ---------------------------------------------------------------- M0 / M1 program pause

    private static final java.util.regex.Pattern M0_WORD = java.util.regex.Pattern.compile("M0*[01](?![0-9])");
    private int pauseLine = -1;               // program line of the M0 we are alerting about
    private AlertDialog pauseDialog;

    /**
     * Grbl acknowledges an M0/M1 line only after Resume, so "Hold" while the next
     * unacknowledged line is M0/M1 means the program itself paused (not the Hold button).
     */
    private void checkProgramPause() {
        boolean held = machineState.startsWith("Hold");
        int idx = ackedLines;
        boolean atPause = held && idx < program.size() && M0_WORD.matcher(program.get(idx).text).find();
        if (atPause && pauseLine != idx) {
            pauseLine = idx;
            String why = pauseComment(program.get(idx).editorLine);
            String msg = (why.isEmpty() ? "The program paused (M0)." : why)
                    + "\n\nLine " + (program.get(idx).editorLine + 1) + ": " + program.get(idx).text;
            log("Program paused at line " + (program.get(idx).editorLine + 1) + (why.isEmpty() ? "" : ": " + why));
            RemoteLog.w("run", "M0 pause at line " + (idx + 1) + "/" + program.size() + (why.isEmpty() ? "" : " – " + why));
            JobService.pauseAlert(this, why.isEmpty() ? "Program paused (M0) – press Resume" : why);
            if (pauseDialog != null) pauseDialog.dismiss();
            pauseDialog = new AlertDialog.Builder(this)
                    .setTitle("⏸ Program paused")
                    .setMessage(msg)
                    .setCancelable(false)
                    .setPositiveButton("▶ Resume", (d, w) -> writeRaw(new byte[]{'~'}))
                    .setNegativeButton("■ Stop", (d, w) -> stopJob())
                    .setNeutralButton("Later", null)
                    .show();
        } else if (!held && pauseLine >= 0) {
            clearPause();
        }
    }

    private void clearPause() {
        pauseLine = -1;
        JobService.clearPauseAlert(this);
        if (pauseDialog != null) {
            pauseDialog.dismiss();
            pauseDialog = null;
        }
    }

    /** The comment on the M0 line or the comment lines just above it, e.g. "PAUSE - switch the router ON". */
    private String pauseComment(int editorLine) {
        String[] rows = docText().split("\n", -1);
        StringBuilder b = new StringBuilder();
        for (int i = editorLine; i >= 0 && i >= editorLine - 3 && i < rows.length; i--) {
            String row = rows[i].trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(([^)]*)\\)|;(.*)$").matcher(row);
            StringBuilder c = new StringBuilder();
            while (m.find()) c.append(m.group(1) != null ? m.group(1) : m.group(2)).append(' ');
            String code = row.replaceAll("\\([^)]*\\)", "").replaceAll(";.*$", "").trim();
            if (i < editorLine && !code.isEmpty()) break;       // stop at the previous real command
            if (c.length() > 0) b.insert(0, c.toString().trim() + (b.length() > 0 ? " " : ""));
        }
        return b.toString().trim();
    }

    /** Work out which program line the machine is executing from its live position. */
    private void trackExecution() {
        GCodeParser.Result r = job;
        if (r == null) return;
        int hi = Math.min(ackedLines, program.size() - 1);
        int lo = Math.max(0, ackedLines - PLANNER_LOOKBACK);
        int best = -1;
        double bestD = Double.MAX_VALUE;
        double px = wpos[0], py = wpos[1];
        int a = GCodeParser.upperBound(r.feedLine, r.feedCount, lo - 1);
        int b = GCodeParser.upperBound(r.feedLine, r.feedCount, hi);
        for (int i = a; i < b; i++) {
            double d = segDist(r.feedPts, i, px, py);
            if (d < bestD - 1e-4) { bestD = d; best = r.feedLine[i]; }
        }
        a = GCodeParser.upperBound(r.rapidLine, r.rapidCount, lo - 1);
        b = GCodeParser.upperBound(r.rapidLine, r.rapidCount, hi);
        for (int i = a; i < b; i++) {
            double d = segDist(r.rapidPts, i, px, py);
            if (d < bestD - 1e-4) { bestD = d; best = r.rapidLine[i]; }
        }
        if (best < 0) best = Math.max(0, ackedLines - 1);
        boolean done = allAckedAtMs > 0 && machineState.equals("Idle")
                && System.currentTimeMillis() - allAckedAtMs > STATUS_POLL_MS + 100;
        if (done) best = program.size() - 1;
        execIdx = best;
        // pause bookkeeping so the remaining-time estimate is not skewed by M0 / feed hold
        boolean held = machineState.startsWith("Hold") || machineState.startsWith("Door");
        long now = System.currentTimeMillis();
        if (held && holdSinceMs == 0) holdSinceMs = now;
        if (!held && holdSinceMs != 0) { holdMs += now - holdSinceMs; holdSinceMs = 0; }
        updateProgress();
        preview.setDoneLine(best - 1);
        if (best < program.size()) {
            if (largeText != null) viewer.setExecLine(program.get(best).editorLine);
            else editor.setExecLine(program.get(best).editorLine);
        }
        if (done) finishRun(true);
    }

    private static double segDist(float[] p, int i, double x, double y) {
        double x0 = p[i * 4], y0 = p[i * 4 + 1], x1 = p[i * 4 + 2], y1 = p[i * 4 + 3];
        double dx = x1 - x0, dy = y1 - y0;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - x0) * dx + (y - y0) * dy) / len2));
        return Math.hypot(x - (x0 + t * dx), y - (y0 + t * dy));
    }

    // ---------------------------------------------------------------- Documents

    private void restoreDocument() {
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        String last = prefs.getString("lastUri", null);
        if (last != null) {
            Uri uri = Uri.parse(last);
            if (loadDocument(uri, false)) return;
        }
        if (!prefs.getBoolean("sampleCreated", false)) {
            Uri sample = createSampleFile();
            prefs.edit().putBoolean("sampleCreated", true).apply();
            if (sample != null && loadDocument(sample, true)) return;
        }
        newDocument();
    }

    /** Writes the 100 mm circle test program to Documents/GrblSender/ (local storage). */
    private Uri createSampleFile() {
        String text;
        try (InputStream in = getAssets().open(SAMPLE_NAME)) {
            text = readAll(in);
        } catch (Exception e) {
            RemoteLog.e("file", "sample asset missing", e);
            return null;
        }
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, SAMPLE_NAME);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + SAMPLE_DIR);
                uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), v);
                if (uri == null) throw new Exception("MediaStore insert returned null");
                try (OutputStream os = getContentResolver().openOutputStream(uri, "w")) {
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                File dir = new File(getExternalFilesDir(null), SAMPLE_DIR);
                dir.mkdirs();
                File f = new File(dir, SAMPLE_NAME);
                try (OutputStream os = new FileOutputStream(f)) {
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                }
                uri = Uri.fromFile(f);
            }
            log("Created test file Documents/" + SAMPLE_DIR + "/" + SAMPLE_NAME);
            return uri;
        } catch (Exception e) {
            log("Could not create test file: " + e.getMessage());
            RemoteLog.e("file", "create sample failed", e);
            return null;
        }
    }

    private void newDocument() {
        docUri = null;
        docName = null;
        setDocumentText("G21 G90 (mm, absolute)\n");
        dirty = false;
        afterDocumentChange();
    }

    /**
     * Put text in the editor, or – for big CAM output – in the read-only viewer, which
     * stays fast where an EditText would take seconds to lay out tens of thousands of lines.
     */
    private void setDocumentText(String text) {
        int lines = 1;
        for (int i = 0; i < text.length() && lines <= LARGE_LINES; i++) if (text.charAt(i) == '\n') lines++;
        if (lines > LARGE_LINES || text.length() > LARGE_CHARS) {
            largeText = text;
            editor.setDocument("");
            viewer.setText(text);
        } else {
            largeText = null;
            viewer.setText("");
            editor.setDocument(text);
        }
        showConsole(logScroll.getVisibility() == View.VISIBLE);
    }

    private String docText() { return largeText != null ? largeText : editor.getText().toString(); }

    private int docLength() { return largeText != null ? largeText.length() : editor.length(); }

    private int docLines() {
        if (largeText != null) return viewer.getLineCount();
        String t = editor.getText().toString();
        int n = t.isEmpty() ? 0 : 1;
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) == '\n') n++;
        return t.endsWith("\n") ? n - 1 : n;
    }

    private void closeDocument() {
        docUri = null;
        docName = null;
        setDocumentText("");
        dirty = false;
        getPreferences(MODE_PRIVATE).edit().remove("lastUri").apply();
        afterDocumentChange();
    }

    private void afterDocumentChange() {
        job = null;
        jobText = null;
        program.clear();
        progress.setProgress(0);
        txtJob.setText("Not loaded");
        preview.clearTrace();
        updateTitle();
        updateButtons();
        refreshPreview(preview::fit);
    }

    private void confirmDiscard(Runnable action) {
        if (streaming) { log("Stop the running job first"); return; }
        if (!dirty) { action.run(); return; }
        new AlertDialog.Builder(this)
                .setTitle("Unsaved changes")
                .setMessage("Discard changes to " + (docName != null ? docName : "untitled") + "?")
                .setPositiveButton("Discard", (d, w) -> action.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_OPEN_FILE);
    }

    private void save() {
        if (docUri == null || "file".equals(docUri.getScheme()) && docName == null) { saveAs(); return; }
        if (writeDocument(docUri)) {
            dirty = false;
            updateTitle();
            log("Saved " + docName);
        } else {
            saveAs();
        }
    }

    private void saveAs() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, docName != null ? docName : "program.nc");
        startActivityForResult(intent, REQ_SAVE_AS);
    }

    private boolean writeDocument(Uri uri) {
        byte[] bytes = docText().getBytes(StandardCharsets.UTF_8);
        for (String mode : new String[]{"wt", "w"}) {
            try (OutputStream os = getContentResolver().openOutputStream(uri, mode)) {
                if (os == null) continue;
                os.write(bytes);
                return true;
            } catch (Exception e) {
                if (mode.equals("w")) {
                    log("Save failed: " + e.getMessage());
                    RemoteLog.e("file", "save failed", e);
                }
            }
        }
        return false;
    }

    private boolean loadDocument(Uri uri, boolean announce) {
        String text;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            text = readAll(in);
        } catch (Exception e) {
            if (announce) {
                log("Open failed: " + e.getMessage());
                RemoteLog.e("file", "open failed", e);
            }
            return false;
        }
        if (text.length() > 3_000_000) {
            log("File is too large for the editor (" + text.length() / 1024 + " KB)");
            return false;
        }
        docUri = uri;
        docName = displayName(uri);
        setDocumentText(text.replace("\r\n", "\n").replace('\r', '\n'));
        dirty = false;
        getPreferences(MODE_PRIVATE).edit().putString("lastUri", uri.toString()).apply();
        afterDocumentChange();
        log("Opened " + docName + " (" + docLines() + " lines" + (largeText != null ? ", read-only viewer" : "") + ")");
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
        }
        if (requestCode == REQ_OPEN_FILE) {
            loadDocument(uri, true);
        } else if (requestCode == REQ_FIRMWARE) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (blue()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    byte[] bin = bo.toByteArray();
                    if (bin.length > 0 && bin[0] == ':') {
                        log("That is an Intel HEX file – the Blue Pill needs the raw .bin built for 0x08002000");
                        return;
                    }
                    confirmDfuFlash(bin, displayName(uri), "from the tablet's storage");
                    return;
                }
                confirmFlash(readAll(in), displayName(uri), "from the tablet's storage");
            } catch (Exception e) {
                log("Could not read firmware file: " + e.getMessage());
            }
        } else if (requestCode == REQ_SAVE_AS) {
            if (writeDocument(uri)) {
                docUri = uri;
                docName = displayName(uri);
                dirty = false;
                getPreferences(MODE_PRIVATE).edit().putString("lastUri", uri.toString()).apply();
                updateTitle();
                log("Saved as " + docName);
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    private String displayName(Uri uri) {
        if ("file".equals(uri.getScheme())) return uri.getLastPathSegment();
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment();
    }

    // ---------------------------------------------------------------- UI

    // ---------------------------------------------------------------- Firmware update page

    private String fwVersion, fwOptions;          // from Grbl's $I report
    private android.app.Dialog fwDialog;
    private TextView fwInstalled, fwStatus;
    private ProgressBar fwBar;
    private android.widget.ListView fwList;
    private Button fwFlashBtn, fwRefreshBtn, fwFileBtn, fwCloseBtn;
    private final List<org.json.JSONObject> fwItems = new ArrayList<>();
    private int fwSelected = -1;
    private boolean fwBusy;
    /** "promini" (Arduino Pro Mini, STK500 .hex) or "bluepill" (STM32F103C8, Maple DFU .bin). */
    private String fwTarget = "promini";

    // ---------------------------------------------------------------- Controller board (Settings)

    static final String BOARD_PROMINI = "promini", BOARD_BLUEPILL = "bluepill";

    /** The controller board chosen in Settings: decides which USB device Connect opens and which firmware is offered. */
    private String board() { return getPreferences(MODE_PRIVATE).getString("board", BOARD_PROMINI); }
    private boolean boardIsBlue() { return BOARD_BLUEPILL.equals(board()); }
    private static String boardName(String b) {
        return BOARD_BLUEPILL.equals(b) ? "Blue Pill (STM32F103C8T6)" : "Arduino Pro Mini";
    }

    private void setBoard(String b) {
        if (b.equals(board())) return;
        getPreferences(MODE_PRIVATE).edit().putString("board", b).apply();
        log("Controller board set to " + boardName(b));
        RemoteLog.i("settings", "board=" + b);
        applyBoardToUi();
    }

    /** Pro Mini: CH340 at the chosen baud (38400 for our 8 MHz build). Blue Pill: native USB, baud is ignored. */
    private void applyBoardToUi() {
        if (boardIsBlue()) {
            baudSpinner.setEnabled(false);
        } else {
            baudSpinner.setEnabled(port == null);
        }
    }

    /** Does this USB serial device belong to the given board? CH340 = Pro Mini, ST VCP 0483:5740 = Blue Pill. */
    private static boolean driverIsBoard(UsbSerialDriver d, String b) {
        boolean st = d.getDevice().getVendorId() == DfuFlasher.VID_ST;
        return BOARD_BLUEPILL.equals(b) == st;
    }
    private boolean blue() { return "bluepill".equals(fwTarget); }

    /** Full-screen page: installed firmware, list of builds on the backoffice, download + flash with progress. */
    private void showFirmwareMenu(String target) {
        if (streaming) { log("Stop the running job before updating the firmware"); return; }
        fwTarget = target;
        fwItems.clear();
        fwSelected = -1;
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dp);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF1D2024);

        TextView title = new TextView(this);
        title.setText(blue() ? "(B) Blue Pill STM32F103C8T6 firmware" : "(A) Arduino Pro Mini firmware");
        title.setTextSize(22);
        title.setTextColor(0xFFE3E6EA);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        fwInstalled = new TextView(this);
        fwInstalled.setTextColor(0xFFB8E0B8);
        fwInstalled.setPadding(0, (int) (6 * dp), 0, (int) (10 * dp));
        root.addView(fwInstalled);
        updateInstalledText();

        if (blue()) {
            TextView how = new TextView(this);
            how.setTextColor(0xFFF0C050);
            how.setText("Flashing uses the STM32duino bootloader over the Blue Pill's own USB port (USB DFU).\n"
                    + "After you tap Download & Flash: set the BOOT1 jumper to 1 and press RESET (the bootloader then stays active), "
                    + "or just press RESET – the app catches the bootloader as soon as it appears.\n"
                    + "Afterwards: BOOT1 back to 0 and RESET; the Blue Pill reappears as \"STM32 Virtual ComPort\".");
            how.setPadding(0, 0, 0, (int) (8 * dp));
            root.addView(how);
        }
        TextView hdr = new TextView(this);
        hdr.setText("Available on the backoffice (" + BuildConfig.LOG_SERVER + ")");
        hdr.setTextColor(0xFF8B939C);
        root.addView(hdr);

        fwList = new android.widget.ListView(this);
        fwList.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
        fwList.setBackgroundColor(0xFF111316);
        fwList.setDividerHeight((int) dp);
        root.addView(fwList, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        fwList.setAdapter(new android.widget.BaseAdapter() {
            @Override public int getCount() { return fwItems.size(); }
            @Override public Object getItem(int i) { return fwItems.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, android.view.ViewGroup parent) {
                android.widget.LinearLayout row = (android.widget.LinearLayout) convert;
                if (row == null) {
                    row = new android.widget.LinearLayout(MainActivity.this);
                    row.setOrientation(android.widget.LinearLayout.VERTICAL);
                    row.setPadding(pad, pad / 2, pad, pad / 2);
                    TextView name = new TextView(MainActivity.this);
                    name.setTextSize(16);
                    name.setTypeface(null, android.graphics.Typeface.BOLD);
                    TextView meta = new TextView(MainActivity.this);
                    meta.setTextColor(0xFF8B939C);
                    row.addView(name);
                    row.addView(meta);
                }
                org.json.JSONObject f = fwItems.get(i);
                boolean sel = i == fwSelected;
                row.setBackgroundColor(sel ? 0xFF24476E : 0x00000000);
                TextView name = (TextView) row.getChildAt(0);
                name.setTextColor(sel ? 0xFFFFFFFF : 0xFFE3E6EA);
                name.setText((sel ? "● " : "○ ") + (i == 0 ? "★ " : "") + f.optString("name"));
                ((TextView) row.getChildAt(1)).setText(String.format(Locale.US, "%s · %.1f KB · %s%s",
                        f.optString("version"), f.optLong("size") / 1024.0,
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date(f.optLong("published"))),
                        f.optString("notes").isEmpty() ? "" : "\n" + f.optString("notes")));
                return row;
            }
        });
        fwList.setOnItemClickListener((parent, v, pos, id) -> {
            if (fwBusy) return;
            fwSelected = pos;
            ((android.widget.BaseAdapter) fwList.getAdapter()).notifyDataSetChanged();
            updateFirmwareButtons();
        });

        fwStatus = new TextView(this);
        fwStatus.setTextColor(0xFFE3E6EA);
        fwStatus.setPadding(0, (int) (10 * dp), 0, (int) (4 * dp));
        root.addView(fwStatus);
        fwBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        fwBar.setMax(1000);
        fwBar.setVisibility(View.INVISIBLE);
        root.addView(fwBar);

        android.widget.LinearLayout buttons = new android.widget.LinearLayout(this);
        buttons.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        fwRefreshBtn = new Button(this);
        fwRefreshBtn.setText("Refresh");
        fwFileBtn = new Button(this);
        fwFileBtn.setText("From file…");
        fwCloseBtn = new Button(this);
        fwCloseBtn.setText("Close");
        fwFlashBtn = new Button(this);
        fwFlashBtn.setText("⬇ Download & Flash");
        fwFlashBtn.setTextColor(0xFF4CD07D);
        for (Button b : new Button[]{fwRefreshBtn, fwFileBtn, fwCloseBtn, fwFlashBtn}) {
            b.setAllCaps(false);
            buttons.addView(b, new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, b == fwFlashBtn ? 1.6f : 1f));
        }
        root.addView(buttons);

        fwRefreshBtn.setOnClickListener(v -> loadFirmwareList());
        fwFileBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_FIRMWARE);
        });
        fwCloseBtn.setOnClickListener(v -> { if (!fwBusy) fwDialog.dismiss(); });
        fwFlashBtn.setOnClickListener(v -> {
            if (fwSelected >= 0 && fwSelected < fwItems.size()) downloadFirmware(fwItems.get(fwSelected));
        });

        fwDialog = new android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        fwDialog.setContentView(root);
        fwDialog.setOnKeyListener((d, key, ev) -> key == KeyEvent.KEYCODE_BACK && fwBusy);   // no Back while flashing
        fwDialog.setOnDismissListener(d -> fwDialog = null);
        fwDialog.show();

        if (port != null) queueSilent("$I");            // refresh the "installed" line
        loadFirmwareList();
    }

    private void updateInstalledText() {
        if (fwInstalled == null) return;
        String board = pendingDriver != null ? describe(pendingDriver) : "no board connected";
        if (blue() && findDfuDevice() != null) board += " · bootloader (DFU) present now";
        if (port == null) {
            fwInstalled.setText("Installed: unknown – connect to the board to read it (" + board + ")");
        } else if (fwVersion == null) {
            fwInstalled.setText("Installed: reading… (" + board + ")");
        } else {
            String opt = fwOptions != null ? fwOptions : "";
            String flags = opt.contains(",") ? opt.substring(0, opt.indexOf(',')) : opt;
            boolean isBlue = pendingDriver != null && pendingDriver.getDevice().getVendorId() == DfuFlasher.VID_ST;
            String[] o = opt.split(",");
            fwInstalled.setText("Installed: Grbl " + fwVersion + " · options " + flags
                    + (isBlue ? (fwVersion.startsWith("1.1") ? " (Blue Pill, PB13 = spindle enable)" : " (grblHAL on Blue Pill)")
                            : flags.contains("D") ? " (D13 = spindle enable)" : " (no spindle-enable pin)")
                    + (o.length >= 3 ? " · planner " + o[1].trim() + " blocks, RX " + o[2].trim() + " bytes" : "")
                    + "\nBoard: " + board);
        }
    }

    private void updateFirmwareButtons() {
        if (fwDialog == null) return;
        boolean canFlash = !fwBusy && !streaming && (blue() || pendingDriver != null);
        fwFlashBtn.setEnabled(canFlash && fwSelected >= 0);
        fwFileBtn.setEnabled(canFlash);
        fwRefreshBtn.setEnabled(!fwBusy);
        fwCloseBtn.setEnabled(!fwBusy);
        fwList.setEnabled(!fwBusy);
        if (!fwBusy && !blue() && pendingDriver == null) fwStatus.setText("Connect to the Arduino first – flashing needs its USB device.");
    }

    private void loadFirmwareList() {
        fwStatus.setText("Loading the firmware list…");
        fwRefreshBtn.setEnabled(false);
        worker.execute(() -> {
            List<org.json.JSONObject> items = new ArrayList<>();
            String err = null;
            try {
                org.json.JSONArray a = new org.json.JSONArray(RemoteLog.get("/api/firmware"));
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONObject f = a.getJSONObject(i);
                    if (f.optString("target", "promini").equals(fwTarget)) items.add(f);   // never offer the other board's firmware
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String error = err;
            ui.post(() -> {
                if (fwDialog == null) return;
                fwItems.clear();
                fwItems.addAll(items);
                fwSelected = items.isEmpty() ? -1 : 0;     // preselect the newest build
                ((android.widget.BaseAdapter) fwList.getAdapter()).notifyDataSetChanged();
                fwStatus.setText(error != null ? "Could not load the list: " + error + " – use \"From file…\" instead"
                        : items.isEmpty() ? "No firmware published on the backoffice."
                        : items.size() + " firmware build(s) available. Select one and tap Download & Flash.");
                updateFirmwareButtons();
            });
        });
    }

    private void downloadFirmware(org.json.JSONObject f) {
        fwBusy = true;
        updateFirmwareButtons();
        fwStatus.setText("Downloading " + f.optString("file") + "…");
        fwBar.setVisibility(View.VISIBLE);
        fwBar.setIndeterminate(true);
        if (blue()) {
            worker.execute(() -> {
                try {
                    byte[] bin = RemoteLog.getBytes(f.optString("url"));
                    if (!f.optString("sha256").isEmpty() && !f.optString("sha256").equalsIgnoreCase(sha256(bin))) {
                        throw new Exception("checksum mismatch – the download is corrupted");
                    }
                    ui.post(() -> {
                        fwBusy = false;
                        fwBar.setIndeterminate(false);
                        fwBar.setProgress(0);
                        fwStatus.setText("Downloaded " + bin.length + " bytes, SHA-256 OK.");
                        updateFirmwareButtons();
                        confirmDfuFlash(bin, f.optString("name"), f.optString("version")
                                + (f.optString("notes").isEmpty() ? "" : "\n" + f.optString("notes")));
                    });
                } catch (Exception e) {
                    ui.post(() -> {
                        fwBusy = false;
                        fwBar.setIndeterminate(false);
                        fwBar.setVisibility(View.INVISIBLE);
                        fwStatus.setText("Download failed: " + e.getMessage());
                        updateFirmwareButtons();
                    });
                }
            });
            return;
        }
        worker.execute(() -> {
            try {
                String hex = RemoteLog.get(f.optString("url"));
                String sha = sha256(hex.getBytes(StandardCharsets.US_ASCII));
                if (!f.optString("sha256").isEmpty() && !f.optString("sha256").equalsIgnoreCase(sha)) {
                    throw new Exception("checksum mismatch – the download is corrupted");
                }
                ui.post(() -> {
                    fwBusy = false;
                    fwBar.setIndeterminate(false);
                    fwBar.setProgress(0);
                    fwStatus.setText("Downloaded, SHA-256 OK.");
                    updateFirmwareButtons();
                    confirmFlash(hex, f.optString("name"), f.optString("version")
                            + (f.optString("notes").isEmpty() ? "" : "\n" + f.optString("notes")));
                });
            } catch (Exception e) {
                ui.post(() -> {
                    fwBusy = false;
                    fwBar.setIndeterminate(false);
                    fwBar.setVisibility(View.INVISIBLE);
                    fwStatus.setText("Download failed: " + e.getMessage());
                    updateFirmwareButtons();
                    log("Firmware download failed: " + e.getMessage());
                });
            }
        });
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x));
        return b.toString();
    }

    private void confirmFlash(String hex, String name, String details) {
        IntelHex.Image img;
        try {
            img = IntelHex.parse(hex, FLASH_LIMIT);
        } catch (Exception e) {
            if (fwStatus != null) fwStatus.setText("Not a usable firmware file: " + e.getMessage());
            new AlertDialog.Builder(this).setTitle("Not a usable firmware file")
                    .setMessage(name + "\n\n" + e.getMessage()).setPositiveButton("OK", null).show();
            return;
        }
        String msg = name + "\n" + details + "\n\n"
                + String.format(Locale.US, "%d bytes (%.0f%% of the %d bytes available)\n\n", img.size,
                100.0 * img.size / FLASH_LIMIT, FLASH_LIMIT)
                + "The app will disconnect, reset the Arduino into its bootloader and write + verify the "
                + "firmware. Grbl $ settings (EEPROM) are kept.\n\n"
                + "Only install firmware built for this board (ATmega328P, 8 MHz). The spindle will be off.";
        new AlertDialog.Builder(this)
                .setTitle("Flash firmware?")
                .setMessage(msg)
                .setPositiveButton("Flash", (d, w) -> startFlash(img, name))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDfuFlash(byte[] bin, String name, String details) {
        String problem = null;
        if (bin.length < 8 || bin.length > DfuFlasher.MAX_IMAGE) problem = "size " + bin.length + " bytes (allowed 1.." + DfuFlasher.MAX_IMAGE + ")";
        else {
            long sp = (bin[0] & 0xFFL) | (bin[1] & 0xFFL) << 8 | (bin[2] & 0xFFL) << 16 | (bin[3] & 0xFFL) << 24;
            long reset = (bin[4] & 0xFFL) | (bin[5] & 0xFFL) << 8 | (bin[6] & 0xFFL) << 16 | (bin[7] & 0xFFL) << 24;
            // vector table check: stack pointer in the 20 KB RAM, reset handler inside the image above the bootloader
            if (sp < 0x20000000L || sp > 0x20005000L || reset < DfuFlasher.APP_BASE || reset >= DfuFlasher.APP_BASE + bin.length) {
                problem = String.format(Locale.US, "not built for 0x08002000 (SP 0x%08X, reset 0x%08X)", sp, reset);
            }
        }
        if (problem != null) {
            if (fwStatus != null) fwStatus.setText("Not a usable Blue Pill image: " + problem);
            new AlertDialog.Builder(this).setTitle("Not a usable Blue Pill image").setMessage(name + "\n\n" + problem)
                    .setPositiveButton("OK", null).show();
            return;
        }
        String msg = name + "\n" + details + "\n\n"
                + String.format(Locale.US, "%d bytes for 0x08002000 (%.0f%% of %d bytes)\n\n", bin.length,
                100.0 * bin.length / DfuFlasher.MAX_IMAGE, DfuFlasher.MAX_IMAGE)
                + "Next: set BOOT1 = 1 and press RESET on the Blue Pill (or just press RESET). "
                + "The app waits up to 2 minutes for the bootloader, then writes and verifies.\n\n"
                + "The STM32duino bootloader itself is never overwritten.";
        new AlertDialog.Builder(this)
                .setTitle("Flash Blue Pill?")
                .setMessage(msg)
                .setPositiveButton("Flash", (d, w) -> startDfuFlash(bin, name))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startDfuFlash(byte[] bin, String name) {
        if (streaming) return;
        RemoteLog.i("dfu", "start: " + name + " (" + bin.length + " bytes)");
        if (port != null) disconnect();
        fwVersion = null;
        fwOptions = null;
        fwBusy = true;
        updateFirmwareButtons();
        final TextView status = fwStatus;
        final ProgressBar bar = fwBar;
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        status.setText("Waiting for the bootloader… set BOOT1 = 1 and press RESET on the Blue Pill (or press RESET now).");

        new Thread(() -> {
            String result;
            boolean ok = false, verified = false;
            UsbDeviceConnection conn = null;
            UsbInterface claimed = null;
            try {
                UsbDevice dev = null;
                long until = System.currentTimeMillis() + 120_000;
                while ((dev = findDfuDevice()) == null) {
                    if (System.currentTimeMillis() > until) throw new java.io.IOException("no bootloader (1EAF:0003) appeared within 2 minutes");
                    Thread.sleep(250);
                }
                if (!usbManager.hasPermission(dev)) {
                    final UsbDevice d = dev;
                    ui.post(() -> {
                        status.setText("Bootloader found – please allow USB access…");
                        Intent pi = new Intent(ACTION_DFU_PERMISSION).setPackage(getPackageName());
                        usbManager.requestPermission(d, PendingIntent.getBroadcast(this, 1, pi,
                                Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
                    });
                    long pUntil = System.currentTimeMillis() + 60_000;
                    while (!usbManager.hasPermission(dev)) {
                        if (System.currentTimeMillis() > pUntil || findDfuDevice() == null) {
                            throw new java.io.IOException("USB permission not granted in time, or the bootloader left DFU mode "
                                    + "(use the BOOT1 = 1 jumper so it stays in the bootloader)");
                        }
                        Thread.sleep(200);
                    }
                }
                // the alternate setting that writes to 0x08002000
                UsbInterface target = null;
                for (int i = 0; i < dev.getInterfaceCount(); i++) {
                    UsbInterface it = dev.getInterface(i);
                    if (it.getName() != null && it.getName().contains("8002000")) target = it;
                }
                if (target == null) {
                    for (int i = 0; i < dev.getInterfaceCount(); i++) {
                        if (dev.getInterface(i).getAlternateSetting() == 2) target = dev.getInterface(i);
                    }
                }
                if (target == null) throw new java.io.IOException("bootloader has no \"Flash 0x8002000\" DFU target");
                conn = usbManager.openDevice(dev);
                if (conn == null) throw new java.io.IOException("could not open the bootloader USB device");
                if (!conn.claimInterface(target, true)) throw new java.io.IOException("could not claim the DFU interface");
                claimed = target;
                if (!conn.setInterface(target)) throw new java.io.IOException("could not select DFU alt " + target.getAlternateSetting());
                int[] fd = DfuFlasher.functionalDescriptor(conn.getRawDescriptors());
                RemoteLog.i("dfu", "bootloader: alt " + target.getAlternateSetting() + " \"" + target.getName()
                        + "\", transferSize " + fd[0] + ", canUpload " + (fd[1] == 1));
                final UsbDeviceConnection c = conn;
                final int intfNum = target.getId();
                ui.post(() -> { bar.setIndeterminate(false); bar.setProgress(0); });
                verified = new DfuFlasher((rt, rq, v, idx, buf, len, to) -> c.controlTransfer(rt, rq, v, idx, buf, len, to), intfNum)
                        .flash(bin, fd[0], fd[1] == 1, new DfuFlasher.Progress() {
                            @Override public void status(String m) { ui.post(() -> status.setText(m)); }
                            @Override public void progress(int done, int total) {
                                ui.post(() -> bar.setProgress((int) (1000L * done / total)));
                            }
                        });
                ok = true;
                result = "Blue Pill firmware installed" + (verified ? " and verified" : " (not verified – no read-back)")
                        + ": " + name + " (" + bin.length + " bytes)";
                RemoteLog.i("dfu", result);
            } catch (Exception e) {
                result = "Blue Pill flash failed: " + e.getMessage();
                RemoteLog.e("dfu", result, e);
            } finally {
                if (conn != null) {
                    if (claimed != null) conn.releaseInterface(claimed);
                    conn.close();
                }
            }
            final String r = result;
            final boolean success = ok;
            ui.post(() -> {
                log(r);
                fwBusy = false;
                if (status != null) {
                    status.setText((success ? "✔ " : "✖ ") + r + (success
                            ? "\nNow set BOOT1 back to 0 and press RESET. The Blue Pill reappears as \"STM32 Virtual ComPort\" – then tap Connect."
                            : "\nThe bootloader is untouched – you can simply try again."));
                }
                updateFirmwareButtons();
            });
        }, "dfu").start();
    }

    private void startFlash(IntelHex.Image img, String name) {
        if (streaming || pendingDriver == null) return;
        final UsbSerialDriver driver = pendingDriver;
        final UsbDevice device = driver.getDevice();
        RemoteLog.i("flash", "start: " + name + " (" + img.size + " bytes)");
        disconnect();
        fwVersion = null;
        fwOptions = null;

        // progress goes to the firmware page, or to a small dialog when started without it
        final AlertDialog small;
        final TextView status;
        final ProgressBar bar;
        if (fwDialog != null) {
            small = null;
            status = fwStatus;
            bar = fwBar;
            fwBusy = true;
            updateFirmwareButtons();
        } else {
            android.widget.LinearLayout box = new android.widget.LinearLayout(this);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = (int) (20 * getResources().getDisplayMetrics().density);
            box.setPadding(pad, pad, pad, pad / 2);
            status = new TextView(this);
            bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            box.addView(status);
            box.addView(bar);
            small = new AlertDialog.Builder(this).setTitle("Flashing " + name).setView(box).setCancelable(false).show();
        }
        status.setText("Preparing…");
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(false);
        bar.setProgress(0);

        new Thread(() -> {
            String result;
            boolean ok = false;
            SerialLink link = null;
            try {
                Thread.sleep(800);                       // let the Grbl connection finish closing
                UsbDeviceConnection conn = usbManager.openDevice(device);
                if (conn == null) throw new java.io.IOException("could not open the USB device");
                Stk500Flasher flasher = new Stk500Flasher();
                link = device.getVendorId() == 0x1A86
                        ? Ch340Link.open(conn, device, Stk500Flasher.BOOTLOADER_BAUD, flasher)
                        : LibLink.open(driver, conn, Stk500Flasher.BOOTLOADER_BAUD, flasher);
                flasher.flash(link, img.data, img.size, new Stk500Flasher.Progress() {
                    @Override public void status(String m) { ui.post(() -> status.setText(m)); }
                    @Override public void progress(int done, int total) {
                        ui.post(() -> bar.setProgress((int) (1000L * done / total)));
                    }
                });
                ok = true;
                result = "Firmware installed and verified: " + name + " (" + img.size + " bytes)";
                RemoteLog.i("flash", result);
            } catch (Exception e) {
                result = "Firmware update failed: " + e.getMessage();
                RemoteLog.e("flash", result, e);
            } finally {
                if (link != null) link.close();
            }
            final String r = result;
            final boolean success = ok;
            ui.post(() -> {
                log(r);
                if (small != null) {
                    small.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle(success ? "✔ Firmware updated" : "✖ Firmware update failed")
                            .setMessage(r + (success ? "\n\nReconnecting…"
                                    : "\n\nThe bootloader is untouched, so you can simply try again. Reconnecting…"))
                            .setPositiveButton("OK", null).show();
                } else {
                    fwBusy = false;
                    status.setText((success ? "✔ " : "✖ ") + r + (success ? "\nReconnecting to Grbl…"
                            : "\nThe bootloader is untouched – you can try again."));
                    updateFirmwareButtons();
                    updateInstalledText();
                }
                ui.postDelayed(() -> connect(driver), 1500);   // Grbl starts after the bootloader times out
            });
        }, "flash").start();
    }

    private void showMenu(View anchor) {
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, anchor);
        m.getMenu().add(0, 3, 0, "App update…" + (updateCode > BuildConfig.VERSION_CODE ? "  ●" : ""));
        m.getMenu().add(0, 5, 1, "Settings…");
        m.getMenu().add(0, 2, 2, "Firmware update – " + boardName(board()) + "…");
        m.getMenu().add(0, 6, 3, "Wiring…");
        m.getMenu().add(0, 7, 4, "CNC setup…");
        m.getMenu().add(0, 1, 5, "About");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) showAbout();
            if (item.getItemId() == 5) showSettings();
            if (item.getItemId() == 6) setupPages().showWiring();
            if (item.getItemId() == 7) setupPages().showCncSetup();
            if (item.getItemId() == 2) showFirmwareMenu(board());
            if (item.getItemId() == 3) showUpdatePage();
            return true;
        });
        m.show();
    }

    // ---------------------------------------------------------------- Wiring and CNC setup pages

    private SetupPages setupPages() {
        return new SetupPages(this, new SetupPages.Host() {
            @Override public boolean connected() { return port != null; }
            @Override public boolean busy() { return streaming; }
            @Override public String board() { return MainActivity.this.board(); }
            @Override public java.util.Map<String, String> grblSettings() { return grblSettings; }
            @Override public void send(String cmd) { queueCommand(cmd); }
            @Override public void log(String msg) { MainActivity.this.log(msg); }
            @Override public SharedPreferences prefs() { return getPreferences(MODE_PRIVATE); }
        });
    }

    // ---------------------------------------------------------------- Settings page

    private android.app.Dialog setDialog;
    private TextView setProfile, setLatest, setInstalled;
    private org.json.JSONObject setLatestBuild;
    private String setLatestError;

    /** What the app expects from each board's firmware (see README.md and firmware/bluepill/BUILD.md). */
    private static String boardProfile(String b) {
        if (BOARD_BLUEPILL.equals(b)) {
            return "Firmware: Grbl 1.1f (robomechs STM32F103 port), image at 0x08002000\n"
                    + "MCU: STM32F103C8T6, 72 MHz, 64 KB flash · settings in flash page 0x0800FC00\n"
                    + "USB: native USB serial 0483:5740 (baud setting ignored)\n"
                    + "Flashing: STM32duino bootloader over USB DFU (1EAF:0003)\n"
                    + "RX buffer: 254 bytes · reports 4 axes (A is ignored)\n"
                    + "Pins: spindle on/off PB13 · PWM PA8 · step PA0–PA2 · dir PA3–PA5 · disable PA6 · limits PB10–PB12 · probe PA15";
        }
        return "Firmware: Grbl 1.1h (gnea/grbl), 8 MHz build, D13 spindle enable\n"
                + "MCU: ATmega328P, 3.3 V / 8 MHz, 32 KB flash · settings in EEPROM\n"
                + "USB: CH340 serial at 38400 baud\n"
                + "Flashing: Arduino bootloader (STK500v1, 57600 baud)\n"
                + "RX buffer: 128 bytes\n"
                + "Pins: spindle on/off D13 · PWM D11 · step D2–D4 · dir D5–D7 · enable D8 · limits D9/D10/D12 · probe A5";
    }

    /** Settings: choose the controller board (Pro Mini or Blue Pill) and see its firmware. */
    private void showSettings() {
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dp);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF1D2024);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(22);
        title.setTextColor(0xFFE3E6EA);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView boardHdr = new TextView(this);
        boardHdr.setText("Controller board");
        boardHdr.setTextSize(17);
        boardHdr.setTextColor(0xFFE3E6EA);
        boardHdr.setPadding(0, pad, 0, (int) (4 * dp));
        root.addView(boardHdr);

        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        android.widget.RadioButton rbPro = new android.widget.RadioButton(this);
        rbPro.setId(View.generateViewId());
        rbPro.setText("(A) Arduino Pro Mini – Grbl 1.1h, CH340 USB serial");
        android.widget.RadioButton rbBlue = new android.widget.RadioButton(this);
        rbBlue.setId(View.generateViewId());
        rbBlue.setText("(B) Blue Pill STM32F103C8T6 – Grbl 1.1f, native USB");
        for (android.widget.RadioButton rb : new android.widget.RadioButton[]{rbPro, rbBlue}) {
            rb.setTextColor(0xFFE3E6EA);
            rb.setTextSize(16);
            group.addView(rb);
        }
        group.check(boardIsBlue() ? rbBlue.getId() : rbPro.getId());
        root.addView(group);

        TextView note = new TextView(this);
        note.setTextColor(0xFF8B939C);
        note.setText("Connect opens this board's USB device, and ☰ → Firmware update offers only its firmware. "
                + "Changing the board takes effect at the next Connect.");
        note.setPadding(0, (int) (4 * dp), 0, pad);
        root.addView(note);

        TextView fwHdr = new TextView(this);
        fwHdr.setText("Selected firmware");
        fwHdr.setTextSize(17);
        fwHdr.setTextColor(0xFFE3E6EA);
        root.addView(fwHdr);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        android.widget.LinearLayout info = new android.widget.LinearLayout(this);
        info.setOrientation(android.widget.LinearLayout.VERTICAL);
        setProfile = new TextView(this);
        setProfile.setTextColor(0xFFC8CDD3);
        setProfile.setPadding(0, (int) (4 * dp), 0, (int) (8 * dp));
        setLatest = new TextView(this);
        setLatest.setTextColor(0xFFB8D0F0);
        setLatest.setPadding(0, 0, 0, (int) (8 * dp));
        setInstalled = new TextView(this);
        setInstalled.setTextColor(0xFFB8E0B8);
        info.addView(setProfile);
        info.addView(setLatest);
        info.addView(setInstalled);
        scroll.addView(info);
        root.addView(scroll, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        android.widget.LinearLayout buttons = new android.widget.LinearLayout(this);
        buttons.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        Button fwBtn = new Button(this);
        fwBtn.setText("Firmware update…");
        Button closeBtn = new Button(this);
        closeBtn.setText("Close");
        for (Button btn : new Button[]{fwBtn, closeBtn}) {
            btn.setAllCaps(false);
            buttons.addView(btn, new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(buttons);

        group.setOnCheckedChangeListener((g, id) -> {
            if (streaming) {
                log("Stop the running job before changing the board");
                g.check(boardIsBlue() ? rbBlue.getId() : rbPro.getId());
                return;
            }
            setBoard(id == rbBlue.getId() ? BOARD_BLUEPILL : BOARD_PROMINI);
            if (port != null) log("Disconnect and connect again to use the " + boardName(board()));
            loadLatestFirmware();
            updateSettingsInfo();
        });
        fwBtn.setOnClickListener(v -> showFirmwareMenu(board()));
        closeBtn.setOnClickListener(v -> setDialog.dismiss());

        setDialog = new android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        setDialog.setContentView(root);
        setDialog.setOnDismissListener(d -> setDialog = null);
        setDialog.show();

        if (port != null) queueSilent("$I");
        loadLatestFirmware();
        updateSettingsInfo();
    }

    /** Newest build of the selected board's firmware on the backoffice. */
    private void loadLatestFirmware() {
        final String b = board();
        setLatestBuild = null;
        setLatestError = null;
        worker.execute(() -> {
            org.json.JSONObject best = null;
            String err = null;
            try {
                org.json.JSONArray a = new org.json.JSONArray(RemoteLog.get("/api/firmware"));
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONObject f = a.getJSONObject(i);
                    if (!f.optString("target", BOARD_PROMINI).equals(b)) continue;
                    if (best == null || f.optLong("published") > best.optLong("published")) best = f;
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final org.json.JSONObject latest = best;
            final String error = err;
            ui.post(() -> {
                if (!b.equals(board())) return;           // board changed meanwhile
                setLatestBuild = latest;
                setLatestError = error;
                updateSettingsInfo();
            });
        });
    }

    private void updateSettingsInfo() {
        if (setDialog == null) return;
        String b = board();
        setProfile.setText(boardName(b) + "\n" + boardProfile(b));

        if (setLatestError != null) {
            setLatest.setText("Latest on the backoffice: could not load (" + setLatestError + ")");
        } else if (setLatestBuild == null) {
            setLatest.setText("Latest on the backoffice: loading…");
        } else {
            org.json.JSONObject f = setLatestBuild;
            setLatest.setText(String.format(Locale.US, "Latest on the backoffice: %s\nVersion %s · %.1f KB · %s%s",
                    f.optString("name"), f.optString("version"), f.optLong("size") / 1024.0,
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date(f.optLong("published"))),
                    f.optString("notes").isEmpty() ? "" : "\n" + f.optString("notes")));
        }

        StringBuilder s = new StringBuilder("Installed: ");
        if (port == null || pendingDriver == null) {
            s.append("not connected – tap Connect to read and check it");
        } else if (!driverIsBoard(pendingDriver, b)) {
            String other = BOARD_BLUEPILL.equals(b) ? BOARD_PROMINI : BOARD_BLUEPILL;
            s.append("⚠ the connected device (").append(describe(pendingDriver)).append(") is a ")
                    .append(boardName(other)).append(", not the selected board");
        } else if (fwVersion == null) {
            s.append("reading ($I)… (").append(describe(pendingDriver)).append(")");
            if (wposKnown) s.append("\n⚠ No answer to $I although status reports arrive: the firmware ignores command lines"
                    + (BOARD_BLUEPILL.equals(b) ? " (first Blue Pill build, 1.1f-maple64) – flash 1.1f-maple64-r2 or newer" : "")
                    + ". Jobs cannot run until the firmware is updated.");
        } else {
            String opt = fwOptions != null ? fwOptions : "";
            String[] o = opt.split(",");
            s.append("Grbl ").append(fwVersion).append(" · options ").append(o[0])
                    .append(o.length >= 3 ? " · planner " + o[1].trim() + " blocks · RX buffer " + o[2].trim() + " bytes" : "")
                    .append("\n").append(compatibility(b, fwVersion, o));
        }
        setInstalled.setText(s);
    }

    /** Checks the installed firmware against what this app needs: Grbl 1.1 protocol, a known RX buffer, the right board. */
    static String compatibility(String b, String version, String[] opt) {
        boolean grbl11 = version.startsWith("1.1");
        int rx = -1;
        if (opt.length >= 3) {
            try { rx = Integer.parseInt(opt[2].trim()); } catch (NumberFormatException ignored) { }
        }
        if (!grbl11) return "⚠ Not Grbl 1.1 – the app streams with Grbl 1.1's protocol; status and settings may not parse";
        if (rx < 64) return "⚠ RX buffer size unknown – streaming falls back to 127 bytes";
        boolean blue = BOARD_BLUEPILL.equals(b);
        if (blue && !version.startsWith("1.1f")) {
            return "✓ Grbl 1.1 protocol, RX " + rx + " bytes – works, but this is not the tested Blue Pill build (1.1f)";
        }
        if (!blue && !version.startsWith("1.1h")) {
            return "✓ Grbl 1.1 protocol, RX " + rx + " bytes – works, but this is not the tested Pro Mini build (1.1h)";
        }
        return "✓ Works with this app: Grbl 1.1 protocol, RX " + rx + " bytes, status and $$ parsed";
    }

    // ---------------------------------------------------------------- App update page

    private static final long UPDATE_INTERVAL_MS = 6 * 3600 * 1000L;
    private int updateCode;                       // newest versionCode seen on the backoffice
    private org.json.JSONObject updateInfo;
    private android.app.Dialog updDialog;
    private TextView updStatus, updLatest;
    private android.widget.LinearLayout updHistory;
    private Button updDownloadBtn;

    private boolean autoUpdateEnabled() { return getPreferences(MODE_PRIVATE).getBoolean("autoUpdate", true); }

    /** At start and every 6 h while the app runs: check the backoffice, notify once per new version. */
    private final Runnable autoUpdateCheck = new Runnable() {
        @Override public void run() {
            ui.removeCallbacks(this);
            if (autoUpdateEnabled()) checkForUpdate(false);
            ui.postDelayed(this, UPDATE_INTERVAL_MS);
        }
    };

    private void checkForUpdate(boolean manual) {
        if (updStatus != null) updStatus.setText("Checking " + BuildConfig.LOG_SERVER + " …");
        worker.execute(() -> {
            org.json.JSONObject v = null;
            org.json.JSONArray history = null;
            String err = null;
            try {
                v = new org.json.JSONObject(RemoteLog.get("/api/version"));
                try { history = new org.json.JSONArray(RemoteLog.get("/api/releases")); } catch (Exception ignored) { }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final org.json.JSONObject ver = v;
            final org.json.JSONArray hist = history;
            final String error = err;
            ui.post(() -> onUpdateChecked(ver, hist, error, manual));
        });
    }

    private void onUpdateChecked(org.json.JSONObject v, org.json.JSONArray history, String error, boolean manual) {
        String when = new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date());
        if (v == null) {
            RemoteLog.w("update", "check failed: " + error);
            if (updStatus != null) updStatus.setText("Could not reach the backoffice (" + error + ") – " + when);
            return;
        }
        updateInfo = v;
        updateCode = v.optInt("versionCode", 0);
        boolean newer = updateCode > BuildConfig.VERSION_CODE;
        if (newer) {
            SharedPreferences prefs = getPreferences(MODE_PRIVATE);
            String text = "Version " + v.optString("versionName") + " is available (you have " + BuildConfig.VERSION_NAME + ")";
            if (prefs.getInt("notifiedCode", 0) != updateCode) {
                prefs.edit().putInt("notifiedCode", updateCode).apply();
                JobService.updateAvailable(this, text + " – tap to download", RemoteLog.downloadUrl());
                RemoteLog.i("update", "notified about " + v.optString("versionName") + " (" + updateCode + ")");
                if (!manual && updDialog == null && !isFinishing()) {
                    new AlertDialog.Builder(this)
                            .setTitle("Update available")
                            .setMessage(text + ".\n\n" + v.optString("notes"))
                            .setPositiveButton("Details", (d, w) -> showUpdatePage())
                            .setNegativeButton("Later", null)
                            .show();
                }
            }
        }
        if (updDialog != null) fillUpdatePage(v, history, when);
    }

    private void showUpdatePage() {
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dp);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF1D2024);

        TextView title = new TextView(this);
        title.setText("App update");
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFFE3E6EA);
        root.addView(title);

        TextView installed = new TextView(this);
        installed.setTextColor(0xFFE3E6EA);
        installed.setPadding(0, (int) (8 * dp), 0, 0);
        installed.setText("Installed: Grbl Sender " + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE
                + "), built " + BuildConfig.BUILD_TIME);
        root.addView(installed);

        updLatest = new TextView(this);
        updLatest.setTextSize(16);
        updLatest.setPadding(0, (int) (6 * dp), 0, 0);
        root.addView(updLatest);

        updStatus = new TextView(this);
        updStatus.setTextColor(0xFF8B939C);
        updStatus.setPadding(0, (int) (4 * dp), 0, (int) (8 * dp));
        root.addView(updStatus);

        TextView link = new TextView(this);
        link.setText("Download link: " + RemoteLog.downloadUrl());
        link.setTextColor(0xFF4EA1FF);
        link.setPaintFlags(link.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        link.setTextIsSelectable(true);
        link.setOnClickListener(v -> openDownload());
        root.addView(link);

        android.widget.Switch auto = new android.widget.Switch(this);
        auto.setText("Check for updates automatically (at start and every 6 hours)");
        auto.setTextColor(0xFFE3E6EA);
        auto.setChecked(autoUpdateEnabled());
        auto.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));
        auto.setOnCheckedChangeListener((b, on) -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("autoUpdate", on).apply();
            if (on) ui.postDelayed(autoUpdateCheck, 500);
        });
        root.addView(auto);

        TextView hdr = new TextView(this);
        hdr.setText("Release history");
        hdr.setTextColor(0xFF8B939C);
        root.addView(hdr);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF111316);
        updHistory = new android.widget.LinearLayout(this);
        updHistory.setOrientation(android.widget.LinearLayout.VERTICAL);
        updHistory.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        sv.addView(updHistory);
        root.addView(sv, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        android.widget.LinearLayout buttons = new android.widget.LinearLayout(this);
        Button check = new Button(this);
        check.setText("Check now");
        Button close = new Button(this);
        close.setText("Close");
        updDownloadBtn = new Button(this);
        updDownloadBtn.setText("⬇ Download & install");
        updDownloadBtn.setTextColor(0xFF4CD07D);
        updDownloadBtn.setEnabled(false);
        for (Button b : new Button[]{check, close, updDownloadBtn}) {
            b.setAllCaps(false);
            buttons.addView(b, new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, b == updDownloadBtn ? 1.6f : 1f));
        }
        root.addView(buttons);
        check.setOnClickListener(v -> checkForUpdate(true));
        close.setOnClickListener(v -> updDialog.dismiss());
        updDownloadBtn.setOnClickListener(v -> openDownload());

        updDialog = new android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        updDialog.setContentView(root);
        updDialog.setOnDismissListener(d -> { updDialog = null; updStatus = null; });
        updDialog.show();
        checkForUpdate(true);
    }

    private void fillUpdatePage(org.json.JSONObject v, org.json.JSONArray history, String when) {
        boolean newer = v.optInt("versionCode", 0) > BuildConfig.VERSION_CODE;
        String date = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date(v.optLong("published")));
        if (v.optInt("versionCode", 0) == 0) {
            updLatest.setText("No release published on the backoffice.");
            updLatest.setTextColor(0xFF8B939C);
        } else {
            updLatest.setText((newer ? "⬆ Update available: " : "✔ Up to date. Latest: ") + v.optString("versionName")
                    + " (build " + v.optInt("versionCode") + "), " + date
                    + (v.optString("notes").isEmpty() ? "" : "\n" + v.optString("notes")));
            updLatest.setTextColor(newer ? 0xFF4CD07D : 0xFFB8E0B8);
        }
        updStatus.setText("Checked " + BuildConfig.LOG_SERVER + " at " + when);
        updDownloadBtn.setEnabled(newer);
        updHistory.removeAllViews();
        if (history != null) {
            for (int i = 0; i < history.length(); i++) {
                org.json.JSONObject r = history.optJSONObject(i);
                TextView t = new TextView(this);
                boolean current = r.optInt("versionCode") == BuildConfig.VERSION_CODE;
                t.setText(String.format(Locale.US, "%s  v%s (build %d)  %s%s\n%s",
                        current ? "▶" : "  ", r.optString("versionName"), r.optInt("versionCode"),
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date(r.optLong("published"))),
                        current ? "  (installed)" : "", r.optString("notes")));
                t.setTextColor(current ? 0xFFFFB020 : 0xFFE3E6EA);
                t.setPadding(0, 6, 0, 10);
                updHistory.addView(t);
            }
        }
    }

    private void openDownload() {
        RemoteLog.i("update", "download opened from " + BuildConfig.VERSION_NAME);
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RemoteLog.downloadUrl())));
    }

    private void showAbout() {
        String msg = "Version " + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")\n"
                + "Built " + BuildConfig.BUILD_TIME + "\n\n"
                + "G-code sender for Grbl 1.1 CNC controllers over USB OTG.\n\n"
                + "• USB serial: built-in CH340 driver; FTDI, CP210x, CDC-ACM via usb-serial-for-android\n"
                + "• Drip-feed streaming (character-counting protocol, 128-byte Grbl buffer) – job size is limited by the tablet, not the Arduino\n"
                + "• G-code editor, large-file viewer, open / save / save as\n"
                + "• Toolpath preview with tool diameter from the file, live tracing during runs\n"
                + "• Foreground service keeps long jobs running with the screen off\n\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + " (SDK " + Build.VERSION.SDK_INT + ")\n"
                + "Grbl link: " + (port != null ? baudSpinner.getSelectedItem() + " baud, " + (machineState.isEmpty() ? "connected" : machineState) : "not connected") + "\n"
                + "Log server: " + BuildConfig.LOG_SERVER + "\n"
                + "Package: " + getPackageName();
        new AlertDialog.Builder(this)
                .setTitle("Grbl Sender")
                .setIcon(android.R.drawable.ic_menu_compass)
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setNeutralButton("Check for update", (d, w) -> startActivity(
                        new Intent(Intent.ACTION_VIEW, Uri.parse(RemoteLog.downloadUrl()))))
                .show();
    }

    /** Full screen: only the toolpath preview (plus Hold/Resume/Stop) is shown; a tap on it goes back. */
    private void setFullScreen(boolean on) {
        fullScreen = on;
        int vis = on ? View.GONE : View.VISIBLE;
        for (int id : new int[]{R.id.toolbarRow, R.id.progressRow, R.id.leftPanel, R.id.middlePanel}) {
            findViewById(id).setVisibility(vis);
        }
        fullControls.setVisibility(on ? View.VISIBLE : View.GONE);
        ((Button) findViewById(R.id.btnFull)).setText(on ? "✕" : "⛶");
        preview.setOnSingleTap(on ? () -> setFullScreen(false) : null);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                if (on) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(on
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    : 0);
        }
        // re-fit once the new size is laid out (unless the user has zoomed/panned)
        preview.post(preview::fit);
    }

    @Override
    public void onBackPressed() {
        if (fullScreen) { setFullScreen(false); return; }
        super.onBackPressed();
    }

    private void showConsole(boolean console) {
        logScroll.setVisibility(console ? View.VISIBLE : View.GONE);
        editor.setVisibility(console || largeText != null ? View.GONE : View.VISIBLE);
        viewer.setVisibility(console || largeText == null ? View.GONE : View.VISIBLE);
        tabConsole.setTextColor(console ? 0xFF4EA1FF : 0xFF8B939C);
        tabEditor.setTextColor(console ? 0xFF8B939C : 0xFF4EA1FF);
        if (console) logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void updateTitle() {
        String name = docName != null ? docName : (docUri == null && docLength() == 0 ? "No file" : "untitled");
        txtFile.setText(dirty ? name + " •" : name);
    }

    private void updateProgress() {
        if (program.isEmpty() || !streaming) return;
        int permille = (int) (1000L * ackedLines / program.size());
        progress.setProgress(permille);
        long now = System.currentTimeMillis();
        long sec = (now - jobStartMs) / 1000;
        String errs = errorCount > 0 ? " · " + errorCount + " errors" : "";
        String eta = "";
        if (job != null && job.lineEndSec.length > 0) {
            // planner estimate for the rest of the job, scaled by how the run is going so far
            int idx = Math.max(0, Math.min(execIdx, program.size() - 1));
            double estDone = idx > 0 ? job.lineEndSec[idx - 1] : 0;
            double remaining = Math.max(0, job.estSeconds - estDone);
            double moving = (now - jobStartMs - holdMs - (holdSinceMs > 0 ? now - holdSinceMs : 0)) / 1000.0;
            if (estDone > 120 && moving > 60) remaining *= Math.max(0.5, Math.min(3.0, moving / estDone));
            long rem = Math.round(remaining);
            String ends = new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date(now + rem * 1000));
            eta = " · " + hms(rem) + " left (ends ≈ " + ends + ")";
        }
        txtJob.setText(String.format(Locale.US, "Running %d / %d · %s%s%s", ackedLines, program.size(), hms(sec), eta, errs));
        JobService.update(this, String.format(Locale.US, "%.1f%% · line %d / %d · %s%s%s",
                permille / 10.0, ackedLines, program.size(), hms(sec), eta, errs), permille);
    }

    /**
     * If Grbl has been Idle for a while although we still count lines as unacknowledged,
     * their "ok"s were lost on the wire: Idle means Grbl has executed everything it received.
     * Release them so streaming continues instead of stalling forever. Skipped for dwells
     * (Grbl is Idle during G4 and only answers afterwards).
     */
    private void checkLostAcks(long now) {
        boolean pending = !inFlight.isEmpty();
        if (!(pending && machineState.equals("Idle") && allAckedAtMs == 0)) {
            idleWithPendingSinceMs = 0;
            return;
        }
        if (idleWithPendingSinceMs == 0) { idleWithPendingSinceMs = now; return; }
        if (now - idleWithPendingSinceMs < LOST_OK_MS) return;
        for (int i = Math.max(0, ackedLines); i < nextLine && i < program.size(); i++) {
            if (program.get(i).text.contains("G4")) return;
        }
        int lost = inFlight.size(), lostProgram = 0;
        for (Sent s : inFlight) if (s.fromProgram) lostProgram++;
        inFlight.clear();
        inFlightBytes = 0;
        ackedLines += lostProgram;
        idleWithPendingSinceMs = 0;
        log("Warning: " + lost + " 'ok' replies were lost – Grbl is idle, continuing at line " + ackedLines);
        RemoteLog.e("run", "LOST OK: released " + lost + " unacknowledged lines (" + lostProgram
                + " program) at line " + ackedLines + "/" + program.size());
        pump();
        updateProgress();
    }

    private String heartbeat() {
        long now = System.currentTimeMillis();
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = bat != null ? bat.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) : -1;
        int scale = bat != null ? bat.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) : 100;
        int plugged = bat != null ? bat.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) : 0;
        float temp = bat != null ? bat.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10f : 0;
        Runtime rt = Runtime.getRuntime();
        long heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        return String.format(Locale.US,
                "line %d/%d (%.1f%%) · %s · elapsed %s, paused %s · battery %d%% %s %.1f°C · heap %d MB · last status %d ms ago · errors %d",
                ackedLines, program.size(), 100.0 * ackedLines / Math.max(1, program.size()), machineState,
                hms((now - jobStartMs) / 1000), hms((holdMs + (holdSinceMs > 0 ? now - holdSinceMs : 0)) / 1000),
                scale > 0 ? level * 100 / scale : level, plugged != 0 ? "charging" : "ON BATTERY", temp,
                heapMb, now - lastStatusMs, errorCount);
    }

    /** Explains what the time estimate is based on. */
    private static String estimateNote(GCodeParser.Result r) {
        StringBuilder b = new StringBuilder();
        if (r.feedCapped) b.append(String.format(Locale.US, "\nFeeds up to F%.0f are limited to %.0f mm/min by the machine's max rate ($110/$111).",
                r.maxFeedRequested, r.cappedRate));
        if (!r.limitsFromMachine) b.append("\nUsing Grbl default speed/acceleration – connect to use this machine's settings.");
        return b.toString();
    }

    private static String hms(long sec) {
        return sec >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
                : String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    private void updateButtons() {
        boolean connected = port != null;
        btnConnect.setText(connected ? "Disconnect" : "Connect");
        baudSpinner.setEnabled(!connected && !boardIsBlue());
        btnRun.setEnabled(connected && !streaming && docLength() > 0);
        btnLoad.setEnabled(!streaming && docLength() > 0);
        for (int id : new int[]{R.id.btnNew, R.id.btnOpen, R.id.btnClose}) findViewById(id).setEnabled(!streaming);
    }

    private void log(String msg) {
        String lower = msg.toLowerCase(Locale.US);
        if (lower.contains("fail") || lower.contains("error:") || lower.contains("alarm")) RemoteLog.e("console", msg);
        else if (lower.startsWith("warning") || lower.contains("denied") || lower.startsWith("!!")) RemoteLog.w("console", msg);
        else RemoteLog.i("console", msg);
        CharSequence cur = txtLog.getText();
        String next = cur.length() == 0 ? msg : cur + "\n" + msg;
        if (next.length() > MAX_LOG_CHARS) next = next.substring(next.length() - MAX_LOG_CHARS / 2);
        txtLog.setText(next);
        if (logScroll.getVisibility() == View.VISIBLE) logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private static String describeError(String line) {
        switch (line.substring(6)) {
            case "1": return " (expected command letter)";
            case "2": return " (bad number format)";
            case "3": return " (invalid $ statement)";
            case "8": return " (not idle)";
            case "9": return " (G-code locked out during alarm/jog)";
            case "15": return " (travel exceeded)";
            case "20": return " (unsupported command)";
            case "22": return " (feed rate not set)";
            case "24": return " (two G-codes from the same group)";
            case "33": return " (invalid motion target)";
            default: return "";
        }
    }
}
