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
 * Streaming uses Grbl's character-counting protocol: keep at most RX_BUFFER
 * bytes outstanding, release them as each "ok"/"error" comes back.
 */
public class MainActivity extends Activity implements SerialLink.Listener {

    private static final String ACTION_USB_PERMISSION = "org.dnaboy.grblsender.USB_PERMISSION";
    private static final int REQ_OPEN_FILE = 1;
    private static final int REQ_SAVE_AS = 2;
    private static final int REQ_FIRMWARE = 4;
    private static final int FLASH_LIMIT = 30720;    // 32 KB minus the 2 KB Pro Mini bootloader
    private static final int RX_BUFFER = 127;        // Grbl serial RX buffer is 128 bytes
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
        RemoteLog.init(getApplicationContext(), (code, name) -> ui.post(() -> {
            if (isFinishing()) return;
            new AlertDialog.Builder(this)
                    .setTitle("Update available")
                    .setMessage("Version " + name + " (" + code + ") is available. You have "
                            + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ").")
                    .setPositiveButton("Download", (d, w) -> startActivity(
                            new Intent(Intent.ACTION_VIEW, Uri.parse(RemoteLog.downloadUrl()))))
                    .setNegativeButton("Later", null)
                    .show();
        }));
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
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        showConsole(false);
        updateTitle();
        updateButtons();
        log("Grbl Sender " + BuildConfig.VERSION_NAME + " ready. Plug in the Arduino and tap Connect.");
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
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
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
        final int baud = Integer.parseInt((String) baudSpinner.getSelectedItem());
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
        ui.postDelayed(() -> {
            if (port == link && !gotData) log("Warning: no data from Arduino yet (expected Grbl banner)");
        }, 4000);
        ui.postDelayed(statusPoll, 1500);
        // learn the rapid rate for time estimates
        ui.postDelayed(() -> { if (port == link) queueSilent("$$"); }, 2000);
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
            if (inFlightBytes + len > RX_BUFFER && !inFlight.isEmpty()) break;
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

    // ---------------------------------------------------------------- Firmware update

    private void showFirmwareMenu() {
        if (streaming) { log("Stop the running job before updating the firmware"); return; }
        if (port == null || pendingDriver == null) {
            new AlertDialog.Builder(this).setTitle("Update firmware")
                    .setMessage("Connect to the Arduino first, so the app knows which USB device to flash.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        log("Fetching the firmware list…");
        worker.execute(() -> {
            org.json.JSONArray list;
            try {
                list = new org.json.JSONArray(RemoteLog.get("/api/firmware"));
            } catch (Exception e) {
                list = new org.json.JSONArray();
                ui.post(() -> log("Firmware list unavailable (" + e.getMessage() + ") – you can still pick a .hex file"));
            }
            final org.json.JSONArray fl = list;
            ui.post(() -> {
                String[] items = new String[fl.length() + 1];
                for (int i = 0; i < fl.length(); i++) {
                    org.json.JSONObject f = fl.optJSONObject(i);
                    items[i] = (i == 0 ? "★ " : "") + f.optString("name") + "\n" + f.optString("version") + " · "
                            + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date(f.optLong("published")));
                }
                items[fl.length()] = "Choose a .hex file on the tablet…";
                new AlertDialog.Builder(this)
                        .setTitle("Update Arduino firmware")
                        .setItems(items, (d, which) -> {
                            if (which == fl.length()) {
                                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                                intent.addCategory(Intent.CATEGORY_OPENABLE);
                                intent.setType("*/*");
                                startActivityForResult(intent, REQ_FIRMWARE);
                            } else {
                                downloadFirmware(fl.optJSONObject(which));
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    private void downloadFirmware(org.json.JSONObject f) {
        log("Downloading " + f.optString("file") + "…");
        worker.execute(() -> {
            try {
                String hex = RemoteLog.get(f.optString("url"));
                String sha = sha256(hex.getBytes(StandardCharsets.US_ASCII));
                if (!f.optString("sha256").isEmpty() && !f.optString("sha256").equalsIgnoreCase(sha)) {
                    throw new Exception("checksum mismatch – download corrupted");
                }
                ui.post(() -> confirmFlash(hex, f.optString("name"), f.optString("version")
                        + (f.optString("notes").isEmpty() ? "" : "\n" + f.optString("notes"))));
            } catch (Exception e) {
                ui.post(() -> log("Firmware download failed: " + e.getMessage()));
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

    private void startFlash(IntelHex.Image img, String name) {
        if (streaming || pendingDriver == null) return;
        final UsbSerialDriver driver = pendingDriver;
        final UsbDevice device = driver.getDevice();
        RemoteLog.i("flash", "start: " + name + " (" + img.size + " bytes)");
        disconnect();

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad / 2);
        TextView status = new TextView(this);
        status.setText("Preparing…");
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        box.addView(status);
        box.addView(bar);
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Flashing " + name)
                .setView(box).setCancelable(false).show();

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
                dlg.dismiss();
                log(r);
                new AlertDialog.Builder(this)
                        .setTitle(success ? "✔ Firmware updated" : "✖ Firmware update failed")
                        .setMessage(r + (success
                                ? "\n\nReconnecting – check that the Grbl banner appears in the Console."
                                : "\n\nThe bootloader is untouched, so you can simply try again. Reconnecting…"))
                        .setPositiveButton("OK", null).show();
                showConsole(true);
                ui.postDelayed(() -> connect(driver), 1500);   // Grbl starts after the bootloader times out
            });
        }, "flash").start();
    }

    private void showMenu(View anchor) {
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, anchor);
        m.getMenu().add(0, 2, 0, "Update firmware…");
        m.getMenu().add(0, 1, 1, "About");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) showAbout();
            if (item.getItemId() == 2) showFirmwareMenu();
            return true;
        });
        m.show();
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
                        new Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.LOG_SERVER + "/"))))
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
        baudSpinner.setEnabled(!connected);
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
