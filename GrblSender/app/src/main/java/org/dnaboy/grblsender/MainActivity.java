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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
    private static final int REQ_FLUID_UPLOAD = 5;
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
    private volatile SerialLink port;
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

    // X, Y, Z and the 4th axis (A) when the controller reports one (Blue Pill)
    private final float[] mpos = new float[4];
    private final float[] wco = new float[4];
    private final float[] wpos = new float[4];
    private int statusAxes = 3;
    private boolean wposKnown = false;
    private String machineState = "";
    /** Input pins Grbl reports as active ("Pn:" field: X Y Z A home sensors, P probe, D door, H hold, R reset, S start). */
    private String pinState = "";
    /** Spindle from the status report: "" off, "S" on CW (M3), "C" on CCW (M4) – the "A:" field, sent with "Ov:". */
    private String spindleDir = "";
    /** Spindle speed (S) from the "FS:" field, -1 until a report carries it. */
    private int spindleSpeed = -1;
    /** Feed override % from the status report's "Ov:" field (sent every few reports), -1 until seen. */
    private int feedOv = -1;
    private static final int[] OV_PCT = {50, 100, 200};
    /** Grbl latches each ±10 % byte as a flag until the main loop runs, so a burst could collapse into one step. */
    private static final int OV_STEP_MS = 40;
    private int ovSeq;                         // a newer button press cancels the steps still pending
    private int ovCheckPct = -1;               // value to verify against the first Ov: report after ovCheckAfterMs
    private long ovCheckAfterMs;
    private Button[] ovButtons;
    private android.content.res.ColorStateList ovTextColor;
    private TextView txtFeedOv;
    /** Grbl motion settings ($11, $12, $110-112, $120-122) for run-time estimates; read from $$ on connect. */
    private final TimeEstimator.Limits limits = new TimeEstimator.Limits();
    /** Last $$ report ("$100" -> "81.490"), shown on the CNC setup page. */
    private final java.util.Map<String, String> grblSettings = new java.util.HashMap<>();
    private static final java.util.regex.Pattern SETTING_LINE = java.util.regex.Pattern.compile("^\\$(\\d+)=([-0-9.]+)\\s*$");
    /** FluidNC config items that stand in for Grbl's $100–$132 (FluidNC 3.0 lists only $10 in $$). */
    static final String[] FLUID_ITEMS = {"steps_per_mm", "max_rate_mm_per_min", "acceleration_mm_per_sec2", "max_travel_mm"};
    private static final java.util.regex.Pattern FLUID_LINE = java.util.regex.Pattern.compile(
            "^\\$/axes/([xyz])/(" + String.join("|", FLUID_ITEMS) + ")=([-0-9.]+)\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** "$/axes/y/max_rate_mm_per_min=1000" -> "$111=1000", so FluidNC answers feed the same settings map and estimate. */
    static String fluidToGrbl(String line) {
        java.util.regex.Matcher m = FLUID_LINE.matcher(line);
        if (!m.matches()) return null;
        int item = java.util.Arrays.asList(FLUID_ITEMS).indexOf(m.group(2).toLowerCase(Locale.US));
        return "$1" + item + ("xyz".indexOf(m.group(1).toLowerCase(Locale.US))) + "=" + m.group(3);
    }
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
    private TextView txtState, txtPos, txtMPos, txtHome, txtFile, txtJob, txtLog;
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
    private TextView chipUsb, chipMachine, chipCloud, chipRemote, chipCamera;
    private CameraStreamer camera;
    private View pageControl;
    private FrameLayout pageSettings;
    private final LinearLayout[] navItems = new LinearLayout[5];
    private static final int NAV_CONTROL = 0, NAV_SETTINGS = 1, NAV_WIRING = 2, NAV_SETUP = 3, NAV_ABOUT = 4;
    private int page = NAV_CONTROL;

    // Remote control and job log
    private RemoteControl remote;
    private JobLog jobLog;
    private JobLog.Job currentJob;
    private String jobSource = "tablet";       // who started the running job
    private String holdSource = "tablet";      // who pressed Hold last
    private String endResult, endDetail;       // why the running job is about to end (set before finishRun(false))
    private long etaSec = -1;
    private long lastPublishMs;
    private View fullControls;
    private TextView fullState;
    private boolean fullScreen;

    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            if (port != null) {
                // a '?' would corrupt an XMODEM transfer, and must not break the silence FluidNC waits for after it
                if (xmodem == null && espFlasher == null && System.currentTimeMillis() >= quietUntilMs) writeRaw(new byte[]{'?'});
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
                    log("USB device detached" + (autoConnectEnabled() ? " – auto-connect will pick it up again when it returns" : ""));
                    disconnect();
                }
                autoFails = 0;
                autoNextMs = 0;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        RemoteLog.setServer(getPreferences(MODE_PRIVATE).getString("server", BuildConfig.LOG_SERVER));
        // RemoteLog's own startup check is replaced by the update checker below (respects the auto-check setting)
        RemoteLog.init(getApplicationContext(), (code, name) -> { });
        ui.postDelayed(autoUpdateCheck, 3000);
        ui.postDelayed(autoScan, 1500);
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
        txtHome = findViewById(R.id.txtHome);
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
        chipUsb = findViewById(R.id.chipUsb);
        chipMachine = findViewById(R.id.chipMachine);
        chipCloud = findViewById(R.id.chipCloud);
        chipRemote = findViewById(R.id.chipRemote);
        chipCamera = findViewById(R.id.chipCamera);
        chipCamera.setOnClickListener(v -> openSettings(SEC_CAMERA));
        pageControl = findViewById(R.id.pageControl);
        pageSettings = findViewById(R.id.pageSettings);
        buildNavRail();
        chipCloud.setOnClickListener(v -> openSettings(SEC_DEBUG));
        chipRemote.setOnClickListener(v -> openSettings(SEC_GENERAL));
        chipUsb.setOnClickListener(v -> openSettings(SEC_DEBUG));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, BAUDS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        baudSpinner.setAdapter(adapter);
        applyBoardToUi();

        btnConnect.setOnClickListener(v -> {
            if (port == null) {
                userDisconnected = false;
                autoAttempt = false;
                connect();
            } else {
                userDisconnected = true;             // no auto-connect until Connect or a replug
                disconnect();
                log("Disconnected – auto-connect paused until you tap Connect or replug the board");
            }
        });

        findViewById(R.id.btnUnlock).setOnClickListener(v -> queueCommand("$X"));
        findViewById(R.id.btnGoG54).setOnClickListener(v -> goG54());
        findViewById(R.id.btnHome).setOnClickListener(v -> home(""));
        findViewById(R.id.btnHomeX).setOnClickListener(v -> home("X"));
        findViewById(R.id.btnHomeY).setOnClickListener(v -> home("Y"));
        findViewById(R.id.btnHomeZ).setOnClickListener(v -> home("Z"));
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
        findViewById(R.id.btnHold).setOnClickListener(v -> feedHold("tablet"));
        findViewById(R.id.btnResume).setOnClickListener(v -> writeRaw(new byte[]{'~'}));
        findViewById(R.id.btnStop).setOnClickListener(v -> stopJob());
        findViewById(R.id.btnFit).setOnClickListener(v -> preview.fit());
        fullControls = findViewById(R.id.fullControls);
        fullState = findViewById(R.id.fullState);
        findViewById(R.id.btnFull).setOnClickListener(v -> setFullScreen(!fullScreen));
        findViewById(R.id.fullHold).setOnClickListener(v -> feedHold("tablet"));
        findViewById(R.id.fullResume).setOnClickListener(v -> writeRaw(new byte[]{'~'}));
        findViewById(R.id.fullStop).setOnClickListener(v -> stopJob());
        txtFeedOv = findViewById(R.id.txtFeedOv);
        int[] ovIds = {R.id.ov50, R.id.ov100, R.id.ov200, R.id.fullOv50, R.id.fullOv100, R.id.fullOv200};
        ovButtons = new Button[ovIds.length];
        for (int i = 0; i < ovIds.length; i++) {
            final int pct = OV_PCT[i % OV_PCT.length];
            ovButtons[i] = findViewById(ovIds[i]);
            ovButtons[i].setOnClickListener(v -> setFeedOverride(pct, true));
        }
        ovTextColor = ovButtons[0].getTextColors();
        showFeedOv();
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
        log("Grbl Sender " + BuildConfig.VERSION_NAME + " ready. Board: " + boardName(board()) + " (Settings → General). "
                + (autoConnectEnabled() ? "Auto-connect is on: plug the board in." : "Plug it in and tap Connect."));
        jobLog = JobLog.get(this);
        remote = new RemoteControl(this::remoteExecute, jobLog);
        remote.setStateListener((on, detail) -> updateChips());
        camera = new CameraStreamer(this, getPreferences(MODE_PRIVATE), () -> getWindowManager().getDefaultDisplay().getRotation());
        camera.setListener(() -> ui.post(this::updateChips));
        remote.setCamera(camera);
        publishStatus(true);
        remote.start();
        updateChips();
        restoreDocument();
        java.util.List<String> perms = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT >= 23 && camera.enabled() && !camera.hasPermission()) perms.add(android.Manifest.permission.CAMERA);
        if (!perms.isEmpty()) requestPermissions(perms.toArray(new String[0]), 3);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction())) connect();
    }

    /** Settings → Camera asks for the camera permission with this. */
    void requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 4);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (camera != null) camera.settingsChanged();
        if (cameraSection != null) cameraSection.refresh();
        updateChips();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction()) && port == null) {
            userDisconnected = false;
            autoFails = 0;
            autoNextMs = 0;
            ui.removeCallbacks(autoScan);
            ui.postDelayed(autoScan, 300);
        }
        // adb: am start -n org.dnaboy.grblsender/.MainActivity --ez espProbe true  (bootloader check, writes nothing)
        if (intent.getBooleanExtra("espProbe", false)) startEspProbe();
        // adb: am start -n org.dnaboy.grblsender/.MainActivity --es fluidUpload config.yaml
        // uploads <app external files dir>/fluidnc/<name> to FluidNC's local filesystem under the same name
        String up = intent.getStringExtra("fluidUpload");
        if (up != null && up.matches("[A-Za-z0-9._-]+")) {
            java.io.File f = new java.io.File(getExternalFilesDir("fluidnc"), up);
            try (InputStream in = new java.io.FileInputStream(f)) {
                startFluidUpload(readBytes(in), up);
            } catch (Exception e) {
                log("FluidNC upload: cannot read " + f + ": " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- FluidNC file upload (XMODEM)

    private XmodemSender xmodem;
    /** No writes until then: after XMODEM, FluidNC discards input until the line has been silent for 1.5 s. */
    private long quietUntilMs;
    private final Runnable pumpLater = this::pump;
    private TextView fluidUploadStatus;           // on the (C) FluidNC firmware page while it is shown
    private final Runnable xmodemWatchdog = new Runnable() {
        @Override public void run() {
            if (xmodem == null) return;
            xmodem.onTimeout();
            if (xmodem != null) ui.postDelayed(this, 3000);
        }
    };

    private static byte[] readBytes(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    /** Rapids to the G54 origin (X0 Y0 Z0). Below Z0 it lifts to Z0 first, otherwise it moves X/Y before lowering. */
    private void goG54() {
        if (machineState.startsWith("Alarm")) { log("Grbl is in ALARM – unlock ($X) or home first"); return; }
        String g54 = grblSettings.get("G54");
        log("Go to G54 origin" + (g54 != null ? " (G54 = " + g54 + ")" : ""));
        queueCommand("G54");
        if (wposKnown && wpos[2] < 0) {
            queueCommand("G90G0Z0");
            queueCommand("G0X0Y0");
        } else {
            queueCommand("G90G0X0Y0");
            queueCommand("G0Z0");
        }
    }

    /** Sends a file to FluidNC's local filesystem with $Xmodem/Receive=<name> (e.g. a new config.yaml). */
    private void startFluidUpload(byte[] content, String name) { startFluidUpload(content, name, null); }

    /** @param after called once FluidNC is listening again (2.5 s after the transfer), or right away when it cannot start */
    private void startFluidUpload(byte[] content, String name, SetupPages.Done after) {
        if (after != null && (port == null || !boardIsFluid() || streaming || xmodem != null
                || System.currentTimeMillis() < quietUntilMs || !manualQueue.isEmpty() || !inFlight.isEmpty())) {
            after.done(false, "the board is busy or not connected");
            return;
        }
        if (port == null) { log("FluidNC upload: connect to the board first"); return; }
        if (!boardIsFluid()) { log("FluidNC upload: the selected board is not FluidNC"); return; }
        if (streaming) { log("FluidNC upload: a job is running"); return; }
        if (xmodem != null || System.currentTimeMillis() < quietUntilMs) { log("FluidNC upload: a transfer is already running"); return; }
        if (!manualQueue.isEmpty() || !inFlight.isEmpty()) { log("FluidNC upload: wait until the board has answered all commands"); return; }
        if (content.length == 0 || content.length > 256 * 1024) { log("FluidNC upload: file size " + content.length + " bytes is not sensible"); return; }
        final SerialLink link = port;
        // FluidNC deletes the target of a failed or cancelled $Xmodem/Receive, so never receive straight into
        // <name>: receive <name>.part, then swap it in by renames (old file kept as <name>.bak).
        final String part = name + ".part";
        log("FluidNC upload: " + name + " (" + content.length + " bytes) via XMODEM as " + part + "…");
        RemoteLog.i("fluidnc", "xmodem upload " + name + " " + content.length + " bytes");
        xmodem = new XmodemSender(content, new XmodemSender.Io() {
            @Override public void write(byte[] d) {
                writer.execute(() -> { try { link.write(d, 2000); } catch (Exception e) { RemoteLog.e("fluidnc", "xmodem write", e); } });
            }
            @Override public void progress(int sent, int total) {
                if (fluidUploadStatus != null) fluidUploadStatus.setText("Uploading " + name + ": packet " + sent + " / " + total);
            }
            @Override public void finished(boolean ok, String message) {
                xmodem = null;
                ui.removeCallbacks(xmodemWatchdog);
                RemoteLog.i("fluidnc", "xmodem " + (ok ? "ok " : "failed ") + message);
                // the receiver ends with flushinput(): it drops input until 1.5 s of silence, and any byte
                // (our status poll) restarts that wait – stay quiet so FluidNC returns to normal
                quietUntilMs = System.currentTimeMillis() + 2500;
                if (!ok) {
                    String m = "✖ Upload of " + name + " failed: " + message + " – the board's " + name + " is unchanged.";
                    log("FluidNC upload: " + m);
                    if (fluidUploadStatus != null) fluidUploadStatus.setText(m);
                    if (after != null) ui.postDelayed(() -> after.done(false, message), 2600);
                    return;
                }
                ui.postDelayed(() -> swapInFluidFile(name, part, (swapped, why) -> {
                    String m = swapped ? "✔ " + name + " uploaded (" + message + "), previous copy kept as " + name + ".bak."
                            + (name.equals("config.yaml") ? " Restart FluidNC ($Bye) to load it." : "")
                            : "✖ " + name + " was received as " + part + " but could not replace " + name + ": " + why;
                    log("FluidNC upload: " + m);
                    if (fluidUploadStatus != null) fluidUploadStatus.setText(m);
                    if (after != null) after.done(swapped, swapped ? message : why);
                }), 2600);
            }
        });
        DebugLog.cnc(">> $Xmodem/Receive=" + part);
        writeRaw(("$Xmodem/Receive=" + part + "\n").getBytes(StandardCharsets.US_ASCII));
        ui.postDelayed(xmodemWatchdog, 5000);
    }

    /** Replaces name by the uploaded part file on FluidNC's local FS: name → name.bak, part → name (old file put back on failure). */
    private void swapInFluidFile(String name, String part, SetupPages.Done done) {
        captureCommand("$LocalFS/Delete=" + name + ".bak", r1 ->                      // may not exist: ignore
            captureCommand("$LocalFS/Rename=" + name + ">" + name + ".bak", r2 ->      // may not exist: ignore
                captureCommand("$LocalFS/Rename=" + part + ">" + name, r3 -> {
                    if (r3 != null) { done.done(true, ""); return; }
                    if (r2 == null) { done.done(false, "rename refused (" + part + " is still on the board)"); return; }
                    captureCommand("$LocalFS/Rename=" + name + ".bak>" + name, r4 ->
                        done.done(false, "rename refused; " + (r4 != null ? "the old " + name + " was put back"
                                : "the old file is " + name + ".bak – rename it back before restarting")));
                })));
    }

    private void xmodemBytes(byte[] data) {
        ui.removeCallbacks(xmodemWatchdog);
        xmodem.onBytes(data);
        if (xmodem != null) ui.postDelayed(xmodemWatchdog, 3000);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (camera != null) camera.setForeground(true);
        if (streaming) RemoteLog.i("app", "back in foreground during run, line " + ackedLines + "/" + program.size());
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (camera != null) camera.setForeground(false);
        if (streaming) RemoteLog.i("app", "app in background during run (foreground service keeps streaming), line "
                + ackedLines + "/" + program.size());
    }

    @Override
    protected void onDestroy() {
        grblWaitId++;
        if (streaming) {
            endResult = "aborted";
            endDetail = "the app was closed";
            RemoteLog.e("app", "app closed during run at line " + ackedLines + "/" + program.size() + " – job aborted");
            JobService.stop(this, "App was closed – job aborted at line " + ackedLines + " / " + program.size());
        }
        ui.removeCallbacks(autoUpdateCheck);
        ui.removeCallbacks(autoScan);
        unregisterReceiver(usbReceiver);
        disconnect();
        if (settingsLeave != null) settingsLeave.run();
        remote.stop();
        camera.shutdown();
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
            UsbSerialDriver d0 = all.get(0);
            String other = guessBoard(d0);
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
            log("Bootloader (DFU 1EAF:0003) detected – it starts Grbl by itself after about 1.5 s; to flash use ☰ → Firmware update – STM32F103C8T6. If it stays, set BOOT1 = 0 and replug or press RESET");
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

    /** Default drivers plus grblHAL's Blue Pill USB serial (STM32 Virtual ComPort, CDC ACM) and FluidNC's CH340K / CH9102. */
    private static UsbSerialProber serialProber() {
        com.hoho.android.usbserial.driver.ProbeTable t = UsbSerialProber.getDefaultProbeTable();
        t.addProduct(DfuFlasher.VID_ST, DfuFlasher.PID_ST_VCP, com.hoho.android.usbserial.driver.CdcAcmSerialDriver.class);
        // FluidNC ESP32 boards: CH340K (1A86:7522, opened by Ch340Link) or CH9102 (1A86:55D4, CDC ACM)
        t.addProduct(VID_CH340, 0x7522, com.hoho.android.usbserial.driver.Ch34xSerialDriver.class);
        t.addProduct(VID_CH340, 0x55D4, com.hoho.android.usbserial.driver.CdcAcmSerialDriver.class);
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
        // ignored by native USB (Blue Pill); FluidNC always runs at 115200
        final int baud = boardIsFluid() ? FLUIDNC_BAUD : Integer.parseInt((String) baudSpinner.getSelectedItem());
        final boolean ch340 = device.getVendorId() == VID_CH340 && device.getProductId() != 0x55D4;   // CH9102 is CDC ACM
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
                    if (autoAttempt) autoFailed();
                    RemoteLog.e("usb", "Open failed (" + describe(driver) + ")", e);
                });
            }
        }, "usb-open").start();
    }

    private void onOpened(SerialLink link, UsbSerialDriver driver, int baud, boolean ch340) {
        bannerSeen = false;
        if (autoAttempt) ui.postDelayed(() -> autoCheckBanner(link, driver), AUTO_BANNER_MS);
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
        } else if (boardIsFluid()) {
            // DTR and RTS are both asserted, so the ESP32 auto-reset circuit leaves it running: ask for the banner
            log("FluidNC (ESP32) – sending a soft reset");
            ui.postDelayed(() -> { if (port == link) writeRaw(new byte[]{0x18}); }, 300);
        }
        ui.postDelayed(() -> {
            if (port == link && !gotData) log("Warning: no data from the " + boardName(board()) + " yet (expected Grbl banner)");
        }, 4000);
        ui.postDelayed(statusPoll, 1500);
        // learn the rapid rate for time estimates
        ui.postDelayed(() -> {
            if (port != link) return;
            queueSilent("$$");
            queueSilent("$I");
            if (boardIsFluid()) queryFluidSettings();
        }, 2000);
        updateButtons();
        updateChips();
        publishStatus(true);
    }

    private void disconnect() {
        ui.removeCallbacks(statusPoll);
        if (xmodem != null) xmodem.cancel();
        if (streaming) {
            if (endResult == null) { endResult = "disconnected"; endDetail = "the USB link to the controller closed"; }
            finishRun(false);
        }
        streaming = false;
        closePort();
        machineState = "";
        pinState = "";
        spindleDir = "";
        spindleSpeed = -1;
        feedOv = -1;
        ovSeq++;
        ovCheckPct = -1;
        showFeedOv();
        showHomeSensors(null);
        afterBanner = null;
        if (captureBuf != null) finishCapture(null);
        txtState.setText("Disconnected");
        txtState.setTextColor(0xFFAAAAAA);
        updateButtons();
        updateChips();
        publishStatus(true);
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
        if (data.length == 1 && data[0] != '?') DebugLog.cnc(">> " + realtimeName(data[0]));
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
                    log("USB link is not responding – disconnected." + (autoConnectEnabled()
                            ? " Auto-connect will retry; if it keeps failing, replug the cable at the tablet."
                            : " Unplug the cable from the tablet and plug it back in."));
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
        EspFlasher ef = espFlasher;
        if (ef != null) { ef.onBytes(data); return; }       // bootloader frames, straight from the reader thread
        String s = new String(data, StandardCharsets.US_ASCII);
        ui.post(() -> {
            if (xmodem != null) { xmodemBytes(data); return; }
            if (espFlasher != null) return;                 // handled on the reader thread below
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\n') {
                    String raw = rxLine.toString().replaceAll("\\s+$", "");
                    rxLine.setLength(0);
                    String line = raw.trim();
                    if (line.isEmpty()) continue;
                    // indented text (FluidNC's YAML from $CD or $LocalFS/Show) is never a protocol line: keep its indent
                    if (raw.charAt(0) == ' ') {
                        DebugLog.cnc("<< " + raw);
                        if (captureBuf != null) captureBuf.add(raw); else log("<< " + raw);
                        continue;
                    }
                    handleLine(line);
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

    private static String realtimeName(byte b) {
        switch (b) {
            case '!': return "! (feed hold)";
            case '~': return "~ (cycle start / resume)";
            case 0x18: return "Ctrl-X (soft reset)";
            case (byte) 0x85: return "0x85 (jog cancel)";
            case (byte) 0x90: return "0x90 (feed override 100 %)";
            case (byte) 0x91: return "0x91 (feed override +10 %)";
            case (byte) 0x92: return "0x92 (feed override −10 %)";
            default: return String.format(Locale.US, "0x%02X", b & 0xFF);
        }
    }

    private void handleLine(String line) {
        if (line.startsWith("<")) DebugLog.cncVerbose("<< " + line);
        else if (line.startsWith("error:") || line.startsWith("ALARM")) DebugLog.cncErr("<< " + line + (line.startsWith("error:") ? describeError(line) : ""));
        else DebugLog.cnc("<< " + line);
        if (line.startsWith("<")) {
            lastStatusMs = System.currentTimeMillis();
            parseStatus(line);
            return;
        }
        boolean reply = line.equals("ok") || line.startsWith("error:");
        if (line.startsWith("[G54:")) grblSettings.put("G54", line.substring(5, line.length() - 1));   // $# report, for CNC setup
        if (captureBuf != null && !reply && !line.startsWith("[") && !line.startsWith("Grbl ")) { captureBuf.add(line); return; }
        if (reply) {
            Sent s = inFlight.poll();
            if (s != null) {
                inFlightBytes -= s.len;
                if (s.fromProgram) ackedLines++;
            }
            if (captureBuf != null && inFlight.isEmpty() && manualQueue.isEmpty()) finishCapture(line.equals("ok") ? captureBuf : null);
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
        if (line.contains("Ambiguous limit switch")) {
            log("ℹ FluidNC says \"limit switch\" for the home sensors: a home sensor reads triggered, so homing will not start. "
                    + "Check Settings → Diagnosis → Home sensors. If one shows TRIGGERED with nothing in front of it after an "
                    + "interrupted homing, restart the board (firmware older than v4.1.1-wcr2 keeps it stuck until a restart).");
        }
        String fluid = fluidToGrbl(line);
        String setting = fluid != null ? fluid : line;
        java.util.regex.Matcher sm = SETTING_LINE.matcher(setting);
        if (sm.matches()) grblSettings.put("$" + sm.group(1), sm.group(2));
        if (limits.apply(setting) && !streaming) {
            // machine settings changed: redo the estimate once the $$ report is complete
            ui.removeCallbacks(reparse);
            ui.postDelayed(reparse, 600);
        }
        if (line.startsWith("Grbl ")) {
            // Banner after reset: everything outstanding was discarded
            if (streaming) {
                endResult = "aborted";
                endDetail = "Grbl reset during the job";
                log("!! Grbl reset during job – job aborted");
                finishRun(false);
            }
            resetStreamState();
            bannerSeen = true;
            if (afterBanner != null) { Runnable r = afterBanner; afterBanner = null; ui.postDelayed(r, 1500); }
            if (autoAttempt) {
                autoFails = 0;
                autoTriedAlt = false;
                if (isSerialBridge(pendingDriver)) {
                    String b = line.contains("FluidNC") ? BOARD_FLUIDNC : BOARD_PROMINI;
                    getPreferences(MODE_PRIVATE).edit().putString("lastSerialBoard", b).apply();
                    if (!b.equals(board())) { log("Auto-connect: the banner says " + boardName(b) + " – board switched"); setBoard(b); }
                }
            } else {
                checkBannerBoard(line);
            }
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

    // ---------------------------------------------------------------- Auto-connect

    private static final long AUTO_SCAN_MS = 3000, AUTO_BANNER_MS = 6000;
    private boolean userDisconnected;            // Disconnect was tapped: wait for Connect or a replug
    private boolean autoAttempt;                 // the current link was opened by the scanner
    private boolean bannerSeen;                  // a "Grbl …" banner arrived on the current link
    private boolean autoTriedAlt;                // the other CH340 board (Pro Mini <-> FluidNC) was tried
    private int autoFails;
    private long autoNextMs;
    private final java.util.Set<String> autoAsked = new java.util.HashSet<>();   // devices we asked permission for

    private boolean autoConnectEnabled() { return getPreferences(MODE_PRIVATE).getBoolean("autoConnect", true); }

    /** Every 3 s while not connected: look for a CNC board on USB and connect to it. */
    private final Runnable autoScan = new Runnable() {
        @Override public void run() {
            ui.removeCallbacks(this);
            try { autoScanOnce(); } catch (Exception e) { RemoteLog.e("auto", "scan failed", e); }
            ui.postDelayed(this, AUTO_SCAN_MS);
        }
    };

    /** USB-serial bridge (CH340 / CP210x / FTDI): Pro Mini or FluidNC, told apart by the banner. */
    private static boolean isSerialBridge(UsbSerialDriver d) {
        if (d == null) return false;
        int vid = d.getDevice().getVendorId();
        return vid == VID_CH340 || vid == VID_CP210X || vid == 0x0403;
    }

    /** Which board this USB device is, or null when it is not a CNC controller we know. */
    private String cncBoardFor(UsbSerialDriver d) {
        UsbDevice dev = d.getDevice();
        if (dev.getVendorId() == DfuFlasher.VID_ST && dev.getProductId() == DfuFlasher.PID_ST_VCP) return BOARD_BLUEPILL;
        if (!isSerialBridge(d)) return null;
        if (!BOARD_BLUEPILL.equals(board())) return board();           // keep the selected Pro Mini / FluidNC
        if (dev.getVendorId() == VID_CP210X) return BOARD_FLUIDNC;
        return getPreferences(MODE_PRIVATE).getString("lastSerialBoard", BOARD_PROMINI);
    }

    private void autoScanOnce() {
        if (!autoConnectEnabled() || userDisconnected || port != null || opening || fwBusy || streaming
                || espFlasher != null || xmodem != null) return;
        if (System.currentTimeMillis() < autoNextMs) return;
        UsbSerialDriver pick = null;
        String pickBoard = null;
        for (UsbSerialDriver d : serialProber().findAllDrivers(usbManager)) {
            String b = cncBoardFor(d);
            if (b == null) continue;
            if (pick == null || b.equals(board())) { pick = d; pickBoard = b; }
        }
        if (pick == null) return;
        UsbDevice dev = pick.getDevice();
        if (!usbManager.hasPermission(dev)) {
            if (!autoAsked.add(dev.getDeviceName())) return;            // asked once for this plug-in; wait for the answer
        }
        if (!pickBoard.equals(board())) {
            log("Auto-connect: " + describe(pick) + " looks like a " + boardName(pickBoard) + " – board switched");
            setBoard(pickBoard);
        }
        log("Auto-connect: " + boardName(pickBoard) + " found (" + describe(pick) + ")" + (autoFails > 0 ? ", attempt " + (autoFails + 1) : ""));
        RemoteLog.i("auto", "connect " + describe(pick) + " as " + pickBoard + " fails=" + autoFails);
        autoAttempt = true;
        autoNextMs = System.currentTimeMillis() + AUTO_SCAN_MS;
        connect(pick);
    }

    /** No Grbl banner after an automatic connect: try the other CH340 board once, then back off and retry. */
    private void autoCheckBanner(SerialLink link, UsbSerialDriver driver) {
        if (port != link || bannerSeen || !autoAttempt) return;
        if (isSerialBridge(driver) && !autoTriedAlt) {
            autoTriedAlt = true;
            String other = boardIsFluid() ? BOARD_PROMINI : BOARD_FLUIDNC;
            log("Auto-connect: no Grbl banner from " + describe(driver) + " as " + boardName(board()) + " – trying " + boardName(other));
            disconnect();
            setBoard(other);
            autoNextMs = 0;
            return;
        }
        log("Auto-connect: no Grbl banner from " + describe(driver) + " – disconnecting and trying again later"
                + " (if this repeats, replug the USB cable at the tablet)");
        disconnect();
        autoFailed();
    }

    private void autoFailed() {
        autoFails++;
        autoTriedAlt = false;
        long wait = Math.min(30000, AUTO_SCAN_MS << Math.min(4, autoFails - 1));    // 3, 6, 12, 24, 30 s
        autoNextMs = System.currentTimeMillis() + wait;
    }

    /** "Grbl 3.x [FluidNC v3.x …]" says FluidNC; offer to switch when Settings name another CH340 board. */
    private void checkBannerBoard(String banner) {
        boolean fluid = banner.contains("FluidNC");
        if (fluid == boardIsFluid() || boardIsBlue()) return;
        String other = fluid ? BOARD_FLUIDNC : BOARD_PROMINI;
        log("The banner says " + boardName(other) + ", but Settings say " + boardName(board()));
        new AlertDialog.Builder(this)
                .setTitle("Different board connected")
                .setMessage("Settings: " + boardName(board()) + "\nBanner: " + banner
                        + "\n\nSwitch the controller board to " + boardName(other) + "?")
                .setPositiveButton("Switch", (dlg, w) -> setBoard(other))
                .setNegativeButton("Keep", null)
                .show();
    }

    /** X / Y / Z home sensors from the status report's Pn: field (Grbl calls them limit pins); red = triggered. */
    private void showHomeSensors(String pins) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder("Home");
        for (char ax : new char[]{'X', 'Y', 'Z'}) {
            b.append("  ").append(ax).append(' ');
            int start = b.length();
            boolean hit = pins != null && pins.indexOf(ax) >= 0;
            b.append(pins == null ? "–" : hit ? "TRIG" : "open");
            if (hit) {
                b.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF5555), start, b.length(), 0);
                b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, b.length(), 0);
            }
        }
        txtHome.setText(b);
    }

    /**
     * Sets the feed override to 50, 100 or 200 % (Grbl's range is 10–200): 0x90 resets it to 100 %, then one
     * 0x91 (+10 %) or 0x92 (−10 %) per step, spaced so Grbl sees each one. Rapids and the spindle are untouched.
     * With {@code retry}, the value from the next "Ov:" report is checked and the sequence sent once more if it differs.
     */
    private void setFeedOverride(int pct, boolean retry) {
        if (port == null) { log("Feed override: not connected"); return; }
        int seq = ++ovSeq;
        log("Feed override → " + pct + " %" + (retry ? "" : " (resent)"));
        writeRaw(new byte[]{(byte) 0x90});
        int steps = Math.abs(pct - 100) / 10;
        byte step = pct > 100 ? (byte) 0x91 : (byte) 0x92;
        for (int k = 1; k <= steps; k++) {
            ui.postDelayed(() -> { if (seq == ovSeq) writeRaw(new byte[]{step}); }, (long) k * OV_STEP_MS);
        }
        // FluidNC puts Ov: in only every few reports, so wait for one that comes after the last step
        ovCheckPct = retry ? pct : -1;
        ovCheckAfterMs = System.currentTimeMillis() + (long) (steps + 1) * OV_STEP_MS + 100;
    }

    /** Called with each Ov: report: resends the override once if the controller did not end up at the chosen value. */
    private void checkFeedOv() {
        if (ovCheckPct < 0 || System.currentTimeMillis() < ovCheckAfterMs) return;
        int pct = ovCheckPct;
        ovCheckPct = -1;
        if (feedOv == pct || port == null) return;
        log("Feed override reads " + feedOv + " %, not " + pct + " % – sending it again");
        setFeedOverride(pct, false);
    }

    /** Feed override label and the lit 0.5× / 1× / 2× button. */
    private void showFeedOv() {
        txtFeedOv.setText(feedOv < 0 ? "Feed –" : "Feed " + feedOv + " %");
        txtFeedOv.setTextColor(feedOv < 0 || feedOv == 100 ? 0xFF8B939C : 0xFFFFC040);
        for (int i = 0; i < ovButtons.length; i++) {
            boolean on = feedOv == OV_PCT[i % OV_PCT.length];
            if (on) ovButtons[i].setTextColor(getColor(R.color.accent));
            else ovButtons[i].setTextColor(ovTextColor);
            ovButtons[i].setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void parseStatus(String line) {
        String body = line.substring(1, line.endsWith(">") ? line.length() - 1 : line.length());
        String[] fields = body.split("\\|");
        String prevState = machineState;
        machineState = fields[0];
        boolean haveWpos = false;
        float[] w = new float[4];
        String pins = "";                      // Grbl leaves Pn: out when no input is active
        boolean haveOv = false;
        String acc = "";                       // A: is left out when no accessory is on, but only reports with Ov: tell
        for (int i = 1; i < fields.length; i++) {
            String f = fields[i];
            if (f.startsWith("MPos:")) statusAxes = parseXYZ(f.substring(5), mpos);
            else if (f.startsWith("WPos:")) { statusAxes = parseXYZ(f.substring(5), w); haveWpos = true; }
            else if (f.startsWith("WCO:")) parseXYZ(f.substring(4), wco);
            else if (f.startsWith("Pn:")) pins = f.substring(3);
            else if (f.startsWith("Ov:")) {
                haveOv = true;
                try { feedOv = Integer.parseInt(f.substring(3).split(",")[0].trim()); } catch (NumberFormatException ignored) { }
            }
            else if (f.startsWith("A:")) acc = f.substring(2);
            else if (f.startsWith("FS:")) {
                String[] fs = f.substring(3).split(",");
                if (fs.length > 1) try { spindleSpeed = Math.round(Float.parseFloat(fs[1])); } catch (NumberFormatException ignored) { }
            }
        }
        if (haveOv || !acc.isEmpty()) spindleDir = acc.contains("S") ? "S" : acc.contains("C") ? "C" : "";
        pinState = pins;
        if (haveOv) { showFeedOv(); checkFeedOv(); }
        if (haveWpos) {
            for (int i = 0; i < 4; i++) mpos[i] = w[i] + wco[i];
        } else {
            for (int i = 0; i < 4; i++) w[i] = mpos[i] - wco[i];
        }
        boolean moved = !wposKnown || Math.abs(w[0] - wpos[0]) > 1e-3 || Math.abs(w[1] - wpos[1]) > 1e-3;
        System.arraycopy(w, 0, wpos, 0, 4);
        wposKnown = true;

        txtPos.setText(dro(w));
        txtMPos.setText(String.format(Locale.US, "MPos %.3f, %.3f, %.3f", mpos[0], mpos[1], mpos[2]));
        showHomeSensors(pins);
        txtState.setText(machineState + (streaming ? "  ▶" : ""));
        int color;
        if (machineState.startsWith("Alarm")) color = 0xFFFF5555;
        else if (machineState.startsWith("Hold") || machineState.startsWith("Door")) color = 0xFFFFC040;
        else if (machineState.startsWith("Run") || machineState.startsWith("Jog")) color = 0xFF55AAFF;
        else color = 0xFF66DD66;
        txtState.setTextColor(color);
        fullState.setText(String.format(Locale.US, "%s   X%.2f Y%.2f Z%.2f", machineState, w[0], w[1], w[2]));
        fullState.setTextColor(color);
        if (!machineState.equals(prevState)) updateChips();
        publishStatus(false);

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

    /** Fills out[] from "x,y,z[,a]"; returns the number of axes in the report. */
    private static int parseXYZ(String s, float[] out) {
        String[] p = s.split(",");
        for (int i = 0; i < out.length && i < p.length; i++) {
            try { out[i] = Float.parseFloat(p[i]); } catch (NumberFormatException ignored) { }
        }
        return p.length;
    }

    /** Send queued manual commands first, then program lines, while Grbl's RX buffer has room. */
    private void pump() {
        if (port == null) return;
        long quiet = quietUntilMs - System.currentTimeMillis();
        if (quiet > 0) { ui.removeCallbacks(pumpLater); ui.postDelayed(pumpLater, quiet); return; }
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
            DebugLog.cnc(">> " + line);
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
    /** FluidNC: read steps/mm, max rate, acceleration and travel of X/Y/Z from config (see fluidToGrbl). */
    private void queryFluidSettings() {
        for (char a : new char[]{'x', 'y', 'z'}) for (String item : FLUID_ITEMS) queueSilent("$/axes/" + a + "/" + item);
    }

    /** Typed commands go out in upper case (Grbl's $ commands need it), except FluidNC's $ commands: their
     *  file names, SSIDs and passwords are case-sensitive ($CD=config.yaml must not become CONFIG.YAML). */
    private String typedCase(String cmd) {
        return boardIsFluid() && cmd.startsWith("$") ? cmd : cmd.toUpperCase(Locale.US);
    }

    private void queueSilent(String cmd) {
        if (port == null) return;
        silentAcks++;
        manualQueue.add(cmd);
        pump();
    }

    // ---------------------------------------------------------------- Command output capture

    private java.util.List<String> captureBuf;     // lines of the captured command's reply, until its ok
    private SetupPages.Lines captureDone;
    private Runnable afterBanner;                  // runs once after the next "Grbl …" banner (FluidNC restart)

    /** Sends cmd and hands its reply lines (indent kept) to done, or null on error / timeout / busy. */
    private void captureCommand(String cmd, SetupPages.Lines done) {
        if (port == null || streaming || xmodem != null || captureBuf != null || !manualQueue.isEmpty() || !inFlight.isEmpty()
                || System.currentTimeMillis() < quietUntilMs) {
            done.got(null);
            return;
        }
        captureBuf = new java.util.ArrayList<>();
        captureDone = done;
        queueSilent(cmd);
        ui.postDelayed(captureTimeout, 10000);
    }

    private final Runnable captureTimeout = () -> { if (captureBuf != null) { log("No complete reply – gave up reading"); finishCapture(null); } };

    private void finishCapture(java.util.List<String> result) {
        ui.removeCallbacks(captureTimeout);
        SetupPages.Lines d = captureDone;
        captureBuf = null;
        captureDone = null;
        if (d != null) d.got(result);
    }

    /** Keeps a copy in Android/data/org.dnaboy.grblsender/files/fluidnc/ (re-upload: am start … --es fluidUpload <name>). */
    private void saveFluidLocal(String name, String text) {
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(getExternalFilesDir("fluidnc"), name))) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            RemoteLog.e("fluidnc", "save " + name, e);
        }
    }

    /** $Bye (FluidNC restarts and loads config.yaml), then re-read the settings once its banner is back. */
    private void restartFluid(Runnable back) {
        afterBanner = () -> {
            queueSilent("$$");
            if (boardIsFluid()) queryFluidSettings();
            ui.postDelayed(back, 1500);
        };
        queueCommand("$Bye");
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
            queueCommand(typedCase(cmd));
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

    /** $H homes all axes (Z first, then X and Y); $HX / $HY / $HZ one axis – FluidNC only, as our Grbl builds
     *  leave out HOMING_SINGLE_AXIS_COMMANDS. */
    private void home(String axis) {
        if (streaming) { log("Homing is disabled while a job runs"); return; }
        if (!axis.isEmpty() && !boardIsFluid()) {
            String m = "Home " + axis + ": single-axis homing needs FluidNC – the Grbl build on the " + boardName(board())
                    + " only homes all axes (Home all = $H)";
            log(m);
            android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (port != null && "0".equals(grblSettings.get("$22"))) {
            String m = "Homing is off ($22=0) – switch it on in CNC setup → Axes & homing";
            log(m);
            android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show();
        }
        queueCommand("$H" + axis);
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
        log("Run started: " + (docName != null ? docName : "untitled") + (jobSource.equals("tablet") ? "" : " (from the " + jobSource + ")"));
        endResult = null;
        endDetail = null;
        etaSec = -1;
        currentJob = jobLog.start(docName != null ? docName : "untitled", program.size(),
                job != null ? Math.round(job.estSeconds) : 0, jobSource);
        if (jobsSection != null) jobsSection.refresh();
        JobService.start(this, "Running " + (docName != null ? docName : "untitled"));
        updateButtons();
        updateChips();
        publishStatus(true);
        pump();
    }

    /** Feed hold ('!'); {@code who} ("tablet" / "backoffice") goes into the job log's pause entry. */
    private void feedHold(String who) {
        holdSource = who;
        writeRaw(new byte[]{'!'});
    }

    private void stopJob() { stopJob("tablet"); }

    private void stopJob(String who) {
        if (!streaming) { softReset(); return; }
        endResult = "stopped";
        endDetail = "Stop pressed on the " + who;
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
        long now = System.currentTimeMillis();
        if (holdSinceMs != 0) {
            holdMs += now - holdSinceMs;
            holdSinceMs = 0;
        }
        jobLog.end(currentJob, completed ? "completed" : endResult != null ? endResult : "stopped", ackedLines, errorCount,
                holdMs, completed ? null : endDetail);
        currentJob = null;
        if (jobsSection != null) jobsSection.refresh();
        jobSource = "tablet";
        endResult = null;
        endDetail = null;
        etaSec = -1;
        ui.post(() -> { updateChips(); publishStatus(true); });
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
        int line = best < program.size() && best >= 0 ? program.get(best).editorLine + 1 : ackedLines;
        if (held && holdSinceMs == 0) {
            holdSinceMs = now;
            String why;
            if (pauseLine >= 0) {
                String c = pauseComment(program.get(pauseLine).editorLine);
                why = "program pause (M0)" + (c.isEmpty() ? "" : ": " + c);
            } else if (machineState.startsWith("Door")) {
                why = "safety door";
            } else {
                why = "feed hold from the " + holdSource;
            }
            jobLog.event(currentJob, "paused", line, why);
            if (jobsSection != null) jobsSection.refresh();
            holdSource = "tablet";
        }
        if (!held && holdSinceMs != 0) {
            long paused = now - holdSinceMs;
            holdMs += paused;
            holdSinceMs = 0;
            jobLog.event(currentJob, "resumed", line, "after " + hms(paused / 1000));
            if (jobsSection != null) jobsSection.refresh();
        }
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
            Uri uri = saveLocalFile(SAMPLE_NAME, text.getBytes(StandardCharsets.UTF_8));
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
            if (loadDocument(uri, true) && page != NAV_CONTROL) showPage(NAV_CONTROL);
        } else if (requestCode == REQ_FLUID_UPLOAD) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] content = readBytes(in);
                String name = displayName(uri).replaceAll("[^A-Za-z0-9._-]", "_");
                new AlertDialog.Builder(this)
                        .setTitle("Upload to FluidNC?")
                        .setMessage(name + " (" + content.length + " bytes) goes to the board's local filesystem as \"" + name
                                + "\", replacing a file of that name. A new config.yaml is used after a restart ($Bye).")
                        .setPositiveButton("Upload", (dlg, w) -> startFluidUpload(content, name))
                        .setNegativeButton("Cancel", null)
                        .show();
            } catch (Exception e) {
                log("Could not read the file: " + e.getMessage());
            }
        } else if (requestCode == REQ_FIRMWARE) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (fluidFw()) {
                    confirmEspFlash(readBytes(in), displayName(uri), "from the tablet's storage");
                    return;
                }
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
    private View fwDialog;                       // the Firmware section while it is shown
    private TextView fwInstalled, fwStatus;
    private ProgressBar fwBar;
    private android.widget.ListView fwList;
    private Button fwFlashBtn, fwRefreshBtn, fwFileBtn;
    private final List<org.json.JSONObject> fwItems = new ArrayList<>();
    private int fwSelected = -1;
    private boolean fwBusy;
    /** "promini" (Arduino Pro Mini, STK500 .hex) or "bluepill" (STM32F103C8, Maple DFU .bin). */
    private String fwTarget = "promini";

    // ---------------------------------------------------------------- Controller board (Settings)

    static final String BOARD_PROMINI = "promini", BOARD_BLUEPILL = "bluepill", BOARD_FLUIDNC = "fluidnc";
    /** FluidNC's default UART speed; the ESP32 board ignores the baud spinner. */
    static final int FLUIDNC_BAUD = 115200;

    /** The controller board chosen in Settings: decides which USB device Connect opens and which firmware is offered. */
    private String board() { return getPreferences(MODE_PRIVATE).getString("board", BOARD_PROMINI); }
    private boolean boardIsBlue() { return BOARD_BLUEPILL.equals(board()); }
    private boolean boardIsFluid() { return BOARD_FLUIDNC.equals(board()); }
    private static String boardName(String b) {
        if (BOARD_BLUEPILL.equals(b)) return "STM32F103C8T6 Blue / Black Pill";
        if (BOARD_FLUIDNC.equals(b)) return "FluidNC ESP32 6-axis";
        return "Arduino Pro Mini";
    }
    private static String boardShort(String b) {
        return BOARD_BLUEPILL.equals(b) ? "Blue Pill" : BOARD_FLUIDNC.equals(b) ? "FluidNC" : "Pro Mini";
    }

    private void setBoard(String b) {
        if (b.equals(board())) return;
        getPreferences(MODE_PRIVATE).edit().putString("board", b).apply();
        log("Controller board set to " + boardName(b));
        RemoteLog.i("settings", "board=" + b);
        applyBoardToUi();
    }

    /** Pro Mini: CH340 at the chosen baud (38400 for our 8 MHz build). Blue Pill: native USB, baud is ignored.
     *  FluidNC: CH340K / CP2102 at a fixed 115200. */
    private void applyBoardToUi() {
        if (!BOARD_PROMINI.equals(board())) {
            baudSpinner.setEnabled(false);
            baudSpinner.setVisibility(View.GONE);
        } else {
            baudSpinner.setEnabled(port == null);
            baudSpinner.setVisibility(View.VISIBLE);
        }
    }

    /** Does this USB serial device belong to the given board? ST VCP 0483:5740 = Blue Pill; CH340 = Pro Mini or
     *  FluidNC (the ESP32 board uses a CH340K; some versions a CP2102), told apart later by the banner. */
    private static boolean driverIsBoard(UsbSerialDriver d, String b) {
        int vid = d.getDevice().getVendorId();
        if (BOARD_BLUEPILL.equals(b)) return vid == DfuFlasher.VID_ST;
        if (BOARD_FLUIDNC.equals(b)) return vid == VID_CH340 || vid == VID_CP210X;
        return vid != DfuFlasher.VID_ST;
    }
    static final int VID_CH340 = 0x1A86, VID_CP210X = 0x10C4;

    /** Best guess which board a USB serial device is when it does not match the selected one. */
    private static String guessBoard(UsbSerialDriver d) {
        int vid = d.getDevice().getVendorId();
        if (vid == DfuFlasher.VID_ST) return BOARD_BLUEPILL;
        if (vid == VID_CP210X) return BOARD_FLUIDNC;
        return BOARD_PROMINI;          // a CH340 could also be FluidNC: the banner check offers that switch
    }
    private boolean blue() { return "bluepill".equals(fwTarget); }
    private boolean fluidFw() { return BOARD_FLUIDNC.equals(fwTarget); }

    /** Settings → Firmware: installed firmware, list of builds on the backoffice, download + flash with progress. */
    private View buildFirmwarePage(String target) {
        fwTarget = target;
        fwItems.clear();
        fwSelected = -1;
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dp);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        LinearLayout head = Ui.row(this);
        boolean fluid = BOARD_FLUIDNC.equals(target);
        head.addView(Ui.heading(this, "Firmware update", fluid ? "(C) FluidNC ESP32 6-axis – over USB through the ESP32 ROM bootloader"
                : blue() ? "(B) STM32F103C8T6 Blue / Black Pill – Grbl 1.1f over USB DFU"
                : "(A) Arduino Pro Mini – Grbl 1.1h over the Arduino bootloader"), Ui.weight(1));
        for (String b : new String[]{BOARD_PROMINI, BOARD_BLUEPILL, BOARD_FLUIDNC}) {
            Button t = Ui.button(this, BOARD_BLUEPILL.equals(b) ? "(B) Blue / Black Pill"
                    : BOARD_FLUIDNC.equals(b) ? "(C) FluidNC" : "(A) Pro Mini", b.equals(target) ? 1 : 0);
            t.setOnClickListener(v -> { if (!fwBusy && !b.equals(fwTarget)) showSection(SEC_FIRMWARE, b); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(Ui.dp(this, 8));
            head.addView(t, lp);
        }
        root.addView(head);
        if (streaming) {
            TextView busy = Ui.text(this, "A job is running – flashing is disabled until it ends.", 14, Ui.WARN);
            busy.setPadding(0, 0, 0, Ui.dp(this, 8));
            root.addView(busy);
        }

        fwInstalled = new TextView(this);
        fwInstalled.setTextColor(0xFFB8E0B8);
        fwInstalled.setPadding(0, (int) (6 * dp), 0, (int) (10 * dp));
        root.addView(fwInstalled);
        updateInstalledText();

        if (fluid) {
            TextView how = Ui.text(this, "Flashing uses the ESP32's own serial bootloader over the board's USB (CH340K), like esptool: "
                    + "the app resets the board into the bootloader with DTR/RTS, writes the parts in the package, "
                    + "checks every part with MD5 and restarts the board. Takes about a minute. Stay connected and keep the "
                    + "machine idle. If the bootloader does not answer, hold BOOT while tapping Flash.\nThe builds below are for "
                    + "the Macrobase 6-axis ESP32 card (ESP32-WROOM, 4 MB, I2S step outputs). A full package (wcr1) also writes the "
                    + "file system with this router's config.yaml; a firmware-only package (wcr2 and later) keeps the board's "
                    + "config.yaml. The Flash dialog says which.", 13, 0xFFF0C050);
            how.setPadding(0, 0, 0, (int) (8 * dp));
            root.addView(how);
            LinearLayout upRow = Ui.row(this);
            fluidUploadStatus = Ui.text(this, "Or upload one file (e.g. an edited config.yaml) to the board's file system (XMODEM). "
                    + "Read the current one with $LocalFS/Show=config.yaml; a new config.yaml is used after $Bye.", 12, Ui.MUTED);
            upRow.addView(fluidUploadStatus, Ui.weight(1));
            Button upload = Ui.button(this, "Upload file…", 0);
            upload.setOnClickListener(v -> {
                if (port == null) { fluidUploadStatus.setText("Connect to the board first."); return; }
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, REQ_FLUID_UPLOAD);
            });
            upRow.addView(upload);
            root.addView(upRow, Ui.margins(this, 0, 0, 0, 8));
        }

        if (blue()) {
            TextView how = new TextView(this);
            how.setTextColor(0xFFF0C050);
            how.setText("Flashing uses the STM32duino bootloader over the board's own USB port (USB DFU).\n"
                    + "Keep BOOT0 and BOOT1 at 0. Tap Download & Flash first, then press RESET on the board: the bootloader only "
                    + "runs for about 1.5 s and the app grabs it straight away. The first time, Android asks for USB access – "
                    + "tick \"Always\", allow, and press RESET once more.\n"
                    + "Afterwards the board restarts as \"STM32 Virtual ComPort\" running the new firmware.");
            how.setPadding(0, 0, 0, (int) (8 * dp));
            root.addView(how);
        }
        TextView hdr = new TextView(this);
        hdr.setText("Available on the backoffice (" + RemoteLog.server() + ")");
        hdr.setTextColor(0xFF8B939C);
        root.addView(hdr);

        fwList = new android.widget.ListView(this);
        fwList.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
        fwList.setBackgroundResource(R.drawable.dro_bg);
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
                row.setBackgroundColor(sel ? 0x2200E5FF : 0x00000000);
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
        fwFlashBtn = Ui.button(this, "⬇ Download & Flash", 2);
        for (Button b : new Button[]{fwRefreshBtn, fwFileBtn, fwFlashBtn}) {
            b.setAllCaps(false);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, b == fwFlashBtn ? 1.6f : 1f);
            lp.setMarginEnd(b == fwFlashBtn ? 0 : Ui.dp(this, 8));
            buttons.addView(b, lp);
        }
        root.addView(buttons);

        fwRefreshBtn.setOnClickListener(v -> loadFirmwareList());
        fwFileBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_FIRMWARE);
        });
        fwFlashBtn.setOnClickListener(v -> {
            if (fwSelected >= 0 && fwSelected < fwItems.size()) downloadFirmware(fwItems.get(fwSelected));
        });

        fwDialog = root;
        if (port != null) queueSilent("$I");            // refresh the "installed" line
        loadFirmwareList();
        return root;
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
                    + (fwVersion.contains("FluidNC") ? " (ESP32, pins from config.yaml)" : isBlue ? (fwVersion.startsWith("1.1") ? " (Blue Pill, PB13 = spindle enable)" : " (grblHAL on Blue Pill)")
                            : flags.contains("D") ? " (D13 = spindle enable)" : " (no spindle-enable pin)")
                    + (o.length >= 3 ? " · planner " + o[1].trim() + " blocks, RX " + o[2].trim() + " bytes" : "")
                    + "\nBoard: " + board);
        }
    }

    private void updateFirmwareButtons() {
        if (fwDialog == null) return;
        boolean canFlash = !fwBusy && !streaming && (blue() || (fluidFw() ? port != null : pendingDriver != null));
        fwFlashBtn.setEnabled(canFlash && fwSelected >= 0);
        fwFileBtn.setEnabled(canFlash);
        fwRefreshBtn.setEnabled(!fwBusy);
        fwList.setEnabled(!fwBusy);
        if (!fwBusy && fluidFw() && port == null) fwStatus.setText("Connect to the FluidNC board first – flashing uses its USB serial link.");
        else if (!fwBusy && !blue() && !fluidFw() && pendingDriver == null) fwStatus.setText("Connect to the Arduino first – flashing needs its USB device.");
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
        if (blue() || fluidFw()) {
            final boolean esp = fluidFw();
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
                        String details = f.optString("version") + (f.optString("notes").isEmpty() ? "" : "\n" + f.optString("notes"));
                        if (esp) confirmEspFlash(bin, f.optString("name"), details);
                        else confirmDfuFlash(bin, f.optString("name"), details);
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

    /**
     * FluidNC package: a zip of "0x<offset>.bin" entries (bootloader 0x1000, partitions 0x8000, boot_app0 0xe000,
     * firmware 0x10000, littlefs 0x3d0000), written by the ESP32 ROM bootloader (EspFlasher).
     */
    static List<EspFlasher.Segment> espSegments(byte[] zip) throws java.io.IOException {
        List<EspFlasher.Segment> segs = new ArrayList<>();
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:.*/)?0x([0-9a-fA-F]+)\\.bin").matcher(e.getName());
                if (e.isDirectory() || !m.matches()) continue;
                segs.add(new EspFlasher.Segment(Integer.parseInt(m.group(1), 16), readBytes(z)));
            }
        }
        segs.sort((a, b) -> Integer.compare(a.offset, b.offset));
        if (segs.isEmpty()) throw new java.io.IOException("no 0x<offset>.bin entries – not a FluidNC package (.zip)");
        int end = 0;
        for (EspFlasher.Segment sg : segs) {
            if (sg.offset < end) throw new java.io.IOException(String.format(Locale.US, "overlapping parts at 0x%X", sg.offset));
            end = sg.offset + sg.data.length;
            if (end > EspFlasher.FLASH_SIZE) throw new java.io.IOException(String.format(Locale.US, "part at 0x%X goes beyond 4 MB", sg.offset));
        }
        EspFlasher.Segment first = segs.get(0);
        if (first.offset != 0x1000 || first.data.length < 1 || (first.data[0] & 0xFF) != 0xE9) {
            throw new java.io.IOException("no ESP32 bootloader image at 0x1000");
        }
        boolean app = false;
        for (EspFlasher.Segment sg : segs) if (sg.offset == 0x10000 && (sg.data[0] & 0xFF) == 0xE9) app = true;
        if (!app) throw new java.io.IOException("no ESP32 application image at 0x10000");
        return segs;
    }

    /** True when the package writes the LittleFS partition (0x3D0000 on the Macrobase card), i.e. replaces config.yaml. */
    private static boolean fsInPackage(List<EspFlasher.Segment> segs) {
        for (EspFlasher.Segment sg : segs) if (sg.offset >= 0x3D0000) return true;
        return false;
    }

    private void confirmEspFlash(byte[] zip, String name, String details) {
        final List<EspFlasher.Segment> segs;
        try {
            segs = espSegments(zip);
        } catch (Exception e) {
            if (fwStatus != null) fwStatus.setText("Not a usable FluidNC package: " + e.getMessage());
            new AlertDialog.Builder(this).setTitle("Not a usable FluidNC package").setMessage(name + "\n\n" + e.getMessage())
                    .setPositiveButton("OK", null).show();
            return;
        }
        StringBuilder parts = new StringBuilder();
        for (EspFlasher.Segment sg : segs) parts.append(String.format(Locale.US, "0x%06X  %,d bytes\n", sg.offset, sg.data.length));
        new AlertDialog.Builder(this)
                .setTitle("Flash FluidNC?")
                .setMessage(name + "\n" + details + "\n\n" + parts
                        + "\nThe board restarts into its bootloader, the parts are written and checked, then it restarts with the "
                        + "new firmware. " + (fsInPackage(segs)
                                ? "Its file system is replaced too (config.yaml from this package; Wi-Fi settings are cleared). "
                                : "The file system is not touched: the board keeps its config.yaml. ")
                        + "Keep the USB cable in and the machine idle.")
                .setPositiveButton("Flash", (d, w) -> startEspFlash(segs, name))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EspFlasher espFlasher;

    /** Resets the FluidNC board into its ROM bootloader, reads the chip id and MAC, and restarts it. Writes nothing. */
    private void startEspProbe() {
        if (streaming || port == null || espFlasher != null || !boardIsFluid()) { log("ESP probe: connect to the FluidNC board first (idle)"); return; }
        final SerialLink link = port;
        final UsbSerialDriver driver = pendingDriver;
        ui.removeCallbacks(statusPoll);
        log("ESP probe: resetting into the bootloader…");
        espFlasher = new EspFlasher(new EspFlasher.Port() {
            @Override public void write(byte[] d) throws java.io.IOException { link.write(d, 5000); }
            @Override public void setDtrRts(boolean dtr, boolean rts) throws java.io.IOException { link.setDtrRts(dtr, rts); }
            @Override public void setBaud(int b) throws java.io.IOException { link.setBaud(b); }
        }, new EspFlasher.Progress() {
            @Override public void status(String m) { ui.post(() -> log("ESP probe: " + m)); }
            @Override public void progress(long done, long total) { }
        });
        new Thread(() -> {
            String r;
            try {
                r = "bootloader OK – " + espFlasher.probe();
            } catch (Exception e) {
                r = "failed: " + e.getMessage();
                try { espFlasher.hardReset(); } catch (Exception ignored) { }
            }
            final String res = r;
            RemoteLog.i("esp", "probe " + res);
            ui.post(() -> {
                espFlasher = null;
                log("ESP probe: " + res);
                if (port == link) disconnect();
                if (driver != null) ui.postDelayed(() -> { if (port == null && !opening) connect(driver); }, 3000);
            });
        }, "esp-probe").start();
    }

    private void startEspFlash(List<EspFlasher.Segment> segs, String name) {
        if (streaming || port == null || espFlasher != null) return;
        final SerialLink link = port;
        final UsbSerialDriver driver = pendingDriver;
        RemoteLog.i("esp", "start: " + name);
        log("FluidNC flash: " + name);
        ui.removeCallbacks(statusPoll);
        fwBusy = true;
        updateFirmwareButtons();
        final TextView status = fwStatus;
        final ProgressBar bar = fwBar;
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        status.setText("Resetting the board into its bootloader…");
        espFlasher = new EspFlasher(new EspFlasher.Port() {
            @Override public void write(byte[] d) throws java.io.IOException { link.write(d, 5000); }
            @Override public void setDtrRts(boolean dtr, boolean rts) throws java.io.IOException { link.setDtrRts(dtr, rts); }
            @Override public void setBaud(int b) throws java.io.IOException { link.setBaud(b); }
        }, new EspFlasher.Progress() {
            @Override public void status(String m) {
                RemoteLog.i("esp", m);
                ui.post(() -> { status.setText(m); log("FluidNC flash: " + m); });
            }
            @Override public void progress(long done, long total) {
                ui.post(() -> { bar.setIndeterminate(false); bar.setProgress((int) (1000 * done / total)); });
            }
        });
        new Thread(() -> {
            String result;
            boolean ok = false;
            try {
                espFlasher.flash(segs, 460800);
                ok = true;
                result = "FluidNC installed and verified: " + name;
            } catch (Exception e) {
                result = "FluidNC flash failed: " + e.getMessage();
                RemoteLog.e("esp", result, e);
                try { espFlasher.hardReset(); } catch (Exception ignored) { }
            }
            try { link.setBaud(FLUIDNC_BAUD); } catch (Exception ignored) { }
            final String r = result;
            final boolean success = ok;
            ui.post(() -> {
                espFlasher = null;
                fwBusy = false;
                bar.setVisibility(View.INVISIBLE);
                status.setText(r + (success ? " – reconnecting…" : ""));
                log(r);
                RemoteLog.i("esp", r);
                updateFirmwareButtons();
                // start over cleanly: the new firmware boots in ~2 s, then the usual banner / $I / settings queries
                if (port == link) disconnect();
                if (driver != null) ui.postDelayed(() -> { if (port == null && !opening) connect(driver); }, 3500);
            });
        }, "esp-flash").start();
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
                + "Next: tap Flash, then press RESET on the board (BOOT0 and BOOT1 at 0). "
                + "The app waits up to 2 minutes for the bootloader, catches it as it starts, then writes and verifies.\n\n"
                + "The STM32duino bootloader itself is never overwritten.";
        new AlertDialog.Builder(this)
                .setTitle("Flash Blue Pill?")
                .setMessage(msg)
                .setPositiveButton("Flash", (d, w) -> startDfuFlash(bin, name))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startDfuFlash(byte[] bin, String name) {
        grblWaitId++;   // a new flash replaces any earlier wait for Grbl
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
        status.setText("Press RESET on the board now (BOOT0 and BOOT1 stay at 0). The app catches the bootloader as it starts.");

        new Thread(() -> {
            String result;
            boolean ok = false, verified = false;
            UsbDeviceConnection conn = null;
            UsbInterface claimed = null;
            try {
                // The STM32duino bootloader only answers reliably in the first ~1.5 s after it enumerates: once the
                // host autosuspends the idle device it refuses SET_INTERFACE ("could not select DFU alt 2").
                // So never use a bootloader that was already there; wait for a fresh one and claim it at once.
                java.util.Set<String> stale = new java.util.HashSet<>();
                UsbDevice already = findDfuDevice();
                if (already != null) {
                    stale.add(already.getDeviceName());
                    RemoteLog.i("dfu", "bootloader already present (" + already.getDeviceName() + ") – waiting for a fresh one");
                }
                UsbDevice dev = null;
                long until = System.currentTimeMillis() + 120_000;
                while (true) {
                    UsbDevice d0 = findDfuDevice();
                    if (d0 != null && !stale.contains(d0.getDeviceName())) {
                        if (usbManager.hasPermission(d0)) { dev = d0; break; }
                        // first time: ask, then wait for the next RESET (the window is over by the time the user taps Allow)
                        final UsbDevice d = d0;
                        stale.add(d0.getDeviceName());
                        ui.post(() -> {
                            status.setText("Allow USB access (tick \"Always\"), then press RESET on the board once more.");
                            Intent pi = new Intent(ACTION_DFU_PERMISSION).setPackage(getPackageName());
                            usbManager.requestPermission(d, PendingIntent.getBroadcast(this, 1, pi,
                                    Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
                        });
                    }
                    if (d0 == null) stale.clear();      // it left: any later appearance is fresh
                    if (System.currentTimeMillis() > until) throw new java.io.IOException("no fresh bootloader (1EAF:0003) within 2 minutes – press RESET while the app is waiting");
                    Thread.sleep(50);
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
                if (!conn.setInterface(target)) throw new java.io.IOException("could not select DFU alt " + target.getAlternateSetting()
                        + " – the bootloader stopped answering. Tap Download & Flash again, then press RESET");
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
                            ? "\n▶ Replug the USB cable or press RESET on the board to start the new firmware (Android cannot restart it over USB). "
                            + "The first time, Android asks to open Grbl Sender for \"STM32F103C8T6\" – tick \"Always\" and tap OK. "
                            + "The app connects by itself when Grbl appears."
                            : "\nThe bootloader is untouched – you can simply try again."));
                }
                updateFirmwareButtons();
                if (success) awaitGrblAfterDfu(status);
            });
        }, "dfu").start();
    }

    /**
     * After a DFU flash the STM32duino bootloader waits for a USB bus reset, which an Android app cannot send.
     * The user presses RESET; wait for the board to come back as Grbl's USB serial (0483:5740) and connect.
     */
    /** Bumped by each new wait (and by onDestroy) so an older waiting thread stops. */
    private volatile int grblWaitId;

    private void awaitGrblAfterDfu(TextView status) {
        log("Replug the USB cable or press RESET on the board to start the new firmware – the app connects by itself");
        final int id = ++grblWaitId;
        new Thread(() -> {
            // no deadline: Grbl r3 re-enumerates by itself about 2 s after the bootloader, whenever the user replugs
            while (id == grblWaitId && port == null) {
                for (UsbSerialDriver d : serialProber().findAllDrivers(usbManager)) {
                    if (d.getDevice().getVendorId() == DfuFlasher.VID_ST) {
                        ui.post(() -> {
                            if (port != null || opening) return;
                            if (status != null && fwDialog != null) status.setText("✔ New firmware is running – connecting…");
                            setBoard(BOARD_BLUEPILL);
                            connect(d);
                        });
                        return;
                    }
                }
                try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            }
        }, "await-grbl").start();
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

    // ---------------------------------------------------------------- Navigation: rail, pages, settings sections

    static final int SEC_GENERAL = 0, SEC_FIRMWARE = 1, SEC_UPDATE = 2, SEC_FILES = 3, SEC_JOBS = 4, SEC_CAMERA = 5, SEC_DEBUG = 6, SEC_DIAG = 7;
    private static final String[] SEC_NAMES = {"General", "Firmware", "App update", "Files", "Job log", "Camera", "Debug console", "Diagnosis"};
    private static final String[] SEC_ICONS = {"general", "firmware", "update", "files", "jobs", "camera", "debug", "diag"};
    private final LinearLayout[] secItems = new LinearLayout[SEC_NAMES.length];
    private FrameLayout secContent;
    private int section = -1;
    private Runnable settingsLeave;              // detaches the section being shown

    private void buildNavRail() {
        LinearLayout rail = findViewById(R.id.navRail);
        String[] icons = {"control", "settings", "wiring", "setup", "about"};
        String[] labels = {"Control", "Settings", "Wiring", "CNC setup", "About"};
        for (int i = 0; i < icons.length; i++) {
            if (i == NAV_ABOUT) rail.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
            LinearLayout item = Ui.navItem(this, icons[i], labels[i], true);
            final int target = i;
            item.setOnClickListener(v -> showPage(target));
            rail.addView(item, Ui.margins(this, 0, 0, 0, 6));
            navItems[i] = item;
        }
        Ui.setNavSelected(navItems[NAV_CONTROL], true);
    }

    private void showPage(int p) {
        if (p == NAV_WIRING) { setupPages().showWiring(); return; }
        if (p == NAV_SETUP) { setupPages().showCncSetup(); return; }
        if (p == NAV_ABOUT) { showAbout(); return; }
        page = p;
        for (int i = 0; i < navItems.length; i++) Ui.setNavSelected(navItems[i], i == p);
        pageControl.setVisibility(p == NAV_CONTROL ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(p == NAV_SETTINGS ? View.VISIBLE : View.GONE);
        if (p == NAV_SETTINGS) {
            buildSettingsShell();
            if (section < 0) showSection(SEC_GENERAL, null);
        }
        if (p == NAV_CONTROL) {
            View focus = getCurrentFocus();
            if (focus != null) ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    private void openSettings(int sec) {
        showPage(NAV_SETTINGS);
        showSection(sec, null);
    }

    /** Settings page: section list on the left, the chosen section on the right. */
    private void buildSettingsShell() {
        if (secContent != null) return;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(0xFF0E141B);
        int p = Ui.dp(this, 10);
        list.setPadding(p, p, p, p);
        TextView hdr = Ui.label(this, "Settings");
        hdr.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), 0, Ui.dp(this, 8));
        list.addView(hdr);
        for (int i = 0; i < SEC_NAMES.length; i++) {
            LinearLayout item = Ui.navItem(this, SEC_ICONS[i], SEC_NAMES[i], false);
            final int sec = i;
            item.setOnClickListener(v -> showSection(sec, null));
            list.addView(item, Ui.margins(this, 0, 0, 0, 4));
            secItems[i] = item;
        }
        root.addView(list, new LinearLayout.LayoutParams(Ui.dp(this, 210), LinearLayout.LayoutParams.MATCH_PARENT));
        View div = new View(this);
        div.setBackgroundColor(Ui.BORDER);
        root.addView(div, new LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT));
        secContent = new FrameLayout(this);
        int cp = Ui.dp(this, 16);
        secContent.setPadding(cp, Ui.dp(this, 12), cp, Ui.dp(this, 12));
        root.addView(secContent, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        pageSettings.addView(root);
        updateNavBadge();
    }

    private void showSection(int sec, String arg) {
        buildSettingsShell();
        if (fwBusy && section == SEC_FIRMWARE) {
            android.widget.Toast.makeText(this, "Flashing – wait until it has finished", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (settingsLeave != null) { settingsLeave.run(); settingsLeave = null; }
        section = sec;
        for (int i = 0; i < secItems.length; i++) Ui.setNavSelected(secItems[i], i == sec);
        View v;
        switch (sec) {
            case SEC_FIRMWARE:
                v = buildFirmwarePage(arg != null ? arg : board());
                settingsLeave = () -> { fwDialog = null; fwInstalled = null; };
                break;
            case SEC_UPDATE:
                v = buildUpdatePage();
                settingsLeave = () -> { updDialog = null; updStatus = null; };
                break;
            case SEC_FILES: {
                FilesSection fs = new FilesSection(this, filesHost, worker);
                v = fs.build();
                settingsLeave = fs::detach;
                break;
            }
            case SEC_JOBS: {
                JobsSection js = new JobsSection(this, jobLog);
                v = js.build();
                jobsSection = js;
                settingsLeave = () -> { js.detach(); jobsSection = null; };
                break;
            }
            case SEC_CAMERA: {
                CameraSection cs = new CameraSection(this, camera, getPreferences(MODE_PRIVATE), this::requestCameraPermission);
                v = cs.build();
                cameraSection = cs;
                settingsLeave = () -> { cs.detach(); cameraSection = null; };
                break;
            }
            case SEC_DEBUG: {
                DebugSection ds = new DebugSection(this, this::linkSummary);
                v = ds.build();
                settingsLeave = ds::detach;
                break;
            }
            case SEC_DIAG: {
                DiagSection dg = new DiagSection(this, new DiagSection.Host() {
                    @Override public boolean connected() { return port != null; }
                    @Override public boolean busy() { return streaming; }
                    @Override public String machineState() { return machineState; }
                    @Override public float workPos(int axis) { return wpos[axis]; }
                    @Override public int axes() { return statusAxes; }
                    @Override public String pins() { return pinState; }
                    @Override public String spindleDir() { return spindleDir; }
                    @Override public int spindleSpeed() { return spindleSpeed; }
                    @Override public boolean proMini() { return BOARD_PROMINI.equals(board()); }
                    @Override public boolean fluidnc() { return boardIsFluid(); }
                    @Override public boolean fluidM150() { return boardIsFluid() && fwVersion != null && fwVersion.contains("-wcr"); }
                    @Override public void realtime(byte b) { writeRaw(new byte[]{b}); }
                    @Override public void reset() { softReset(); }
                    @Override public java.util.Map<String, String> grblSettings() { return grblSettings; }
                    @Override public void send(String cmd) { queueCommand(cmd); }
                    @Override public void log(String msg) { MainActivity.this.log(msg); }
                });
                v = dg.build();
                settingsLeave = dg::detach;
                break;
            }
            default:
                v = buildGeneralPage();
                settingsLeave = () -> setDialog = null;
        }
        secContent.removeAllViews();
        secContent.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private JobsSection jobsSection;
    private CameraSection cameraSection;

    private void updateNavBadge() {
        boolean upd = updateCode > BuildConfig.VERSION_CODE;
        if (navItems[NAV_SETTINGS] != null) ((TextView) navItems[NAV_SETTINGS].getChildAt(1)).setText(upd ? "Settings ●" : "Settings");
        if (secItems[SEC_UPDATE] != null) ((TextView) secItems[SEC_UPDATE].getChildAt(1)).setText(upd ? "App update  ●" : "App update");
    }

    private final FilesSection.Host filesHost = new FilesSection.Host() {
        @Override public void openLocal(Uri uri) {
            confirmDiscard(() -> { if (loadDocument(uri, true)) showPage(NAV_CONTROL); });
        }
        @Override public void openRemote(String name) {
            confirmDiscard(() -> remoteOpen(name, () -> showPage(NAV_CONTROL), err -> {
                log("Could not open " + name + " from the backoffice: " + err);
                android.widget.Toast.makeText(MainActivity.this, err, android.widget.Toast.LENGTH_LONG).show();
            }));
        }
        @Override public void browse() { confirmDiscard(MainActivity.this::openFile); }
        @Override public boolean busy() { return streaming; }
    };

    // ---------------------------------------------------------------- Title bar status chips

    private boolean remoteControlAllowed() { return getPreferences(MODE_PRIVATE).getBoolean("remoteControl", true); }

    private void updateChips() {
        if (chipUsb == null) return;
        String boardShort = boardShort(board());
        if (port != null) Ui.setChip(chipUsb, boardShort, Ui.OK);
        else if (opening) Ui.setChip(chipUsb, "Opening…", Ui.WARN);
        else Ui.setChip(chipUsb, boardShort + " · off", Ui.MUTED);
        if (port == null) {
            chipMachine.setVisibility(View.GONE);
        } else {
            chipMachine.setVisibility(View.VISIBLE);
            String st = machineState.isEmpty() ? "Connected" : machineState;
            Ui.setChip(chipMachine, st + (streaming ? " ▶" : ""), stateColor(st));
        }
        boolean online = remote != null && remote.isOnline();
        Ui.setChip(chipCloud, online ? "Backoffice" : "Offline", online ? Ui.OK : Ui.ERR);
        Ui.setChip(chipRemote, remoteControlAllowed() ? "Remote" : "Remote off", remoteControlAllowed() ? Ui.ACCENT : Ui.MUTED);
        // privacy indicator: shown while the camera is being watched on the backoffice
        boolean camLive = camera != null && camera.isLive();
        chipCamera.setVisibility(camLive ? View.VISIBLE : View.GONE);
        if (camLive) Ui.setChip(chipCamera, "Camera LIVE", Ui.ERR);
    }

    private static int stateColor(String st) {
        if (st.startsWith("Alarm")) return Ui.ERR;
        if (st.startsWith("Hold") || st.startsWith("Door")) return Ui.WARN;
        if (st.startsWith("Run") || st.startsWith("Jog")) return Ui.RUN;
        return Ui.OK;
    }

    /** Big work-position readout with coloured axis letters. */
    private static CharSequence dro(float[] w) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        String[] ax = {"X", "Y", "Z"};
        int[] colors = {Ui.AX_X, Ui.AX_Y, Ui.AX_Z};
        for (int i = 0; i < 3; i++) {
            if (i > 0) b.append('\n');
            int s = b.length();
            b.append(ax[i]);
            b.setSpan(new android.text.style.ForegroundColorSpan(colors[i]), s, b.length(), 0);
            b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s, b.length(), 0);
            b.append(String.format(Locale.US, " %9.3f", w[i]));
        }
        return b;
    }

    /** Live status for the backoffice (sent with the next sync); throttled to 4 per second unless forced. */
    private void publishStatus(boolean force) {
        if (remote == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastPublishMs < 250) return;
        lastPublishMs = now;
        try {
            org.json.JSONObject o = new org.json.JSONObject()
                    .put("connected", port != null)
                    .put("usb", port != null && pendingDriver != null ? describe(pendingDriver) : null)
                    .put("board", board())
                    .put("boardName", boardName(board()))
                    .put("machine", machineState)
                    .put("wpos", new org.json.JSONArray().put(wpos[0]).put(wpos[1]).put(wpos[2]))
                    .put("mpos", new org.json.JSONArray().put(mpos[0]).put(mpos[1]).put(mpos[2]))
                    .put("streaming", streaming)
                    .put("paused", holdSinceMs != 0)
                    .put("file", docName)
                    .put("dirty", dirty)
                    .put("loaded", job != null)
                    .put("lines", program.size())
                    .put("acked", ackedLines)
                    .put("permille", program.isEmpty() ? 0 : streaming ? 1000L * ackedLines / program.size() : progress.getProgress())
                    .put("elapsedSec", streaming ? (now - jobStartMs) / 1000 : 0)
                    .put("estSec", job != null ? Math.round(job.estSeconds) : 0)
                    .put("errors", errorCount)
                    .put("jobId", currentJob != null ? currentJob.id : null)
                    .put("fw", fwVersion)
                    .put("remoteControl", remoteControlAllowed())
                    .put("app", BuildConfig.VERSION_NAME);
            if (streaming && etaSec >= 0) o.put("etaSec", etaSec);
            remote.setStatus(o);
        } catch (org.json.JSONException ignored) { }
    }

    /** One-screen summary of both links for the Debug console. */
    private String linkSummary() {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder();
        b.append("Backoffice  ").append(RemoteLog.server()).append("  ");
        if (remote.isOnline()) b.append("ONLINE · last sync ").append((now - remote.lastOkMs()) / 1000).append(" s ago");
        else b.append("OFFLINE").append(remote.lastError() != null ? " · " + remote.lastError() : "");
        b.append("\nRemote      ").append(remoteControlAllowed() ? "control allowed" : "control off – status, Hold and Stop only");
        b.append("\nCamera      ").append(camera != null ? camera.summary() : "–");
        b.append("\nCNC         ");
        if (port == null) b.append("not connected · ").append(boardName(board()));
        else {
            b.append(pendingDriver != null ? describe(pendingDriver) : "?").append(" · ").append(boardName(board()))
                    .append(fwVersion != null ? " · Grbl " + fwVersion : "")
                    .append(" · ").append(machineState.isEmpty() ? "no status yet" : machineState)
                    .append(" · last status ").append(now - lastStatusMs).append(" ms ago");
            String d = port.diag();
            if (!d.isEmpty()) b.append("\nUSB         ").append(d);
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- Remote commands (from the backoffice)

    private void remoteExecute(org.json.JSONObject c, RemoteControl.Result r) {
        String cmd = c.optString("cmd");
        boolean safe = cmd.equals("hold") || cmd.equals("stop") || cmd.equals("status");
        if (!safe && !remoteControlAllowed()) {
            r.done(false, "remote control is switched off on the tablet (Settings → General)");
            return;
        }
        if (!cmd.equals("status")) {
            log("☁ Remote " + cmd + (c.has("file") ? " " + c.optString("file") : "") + (c.has("line") ? " " + c.optString("line") : "")
                    + (c.has("axes") ? " " + c.optString("axes") : ""));
        }
        if (!cmd.equals("status") && !cmd.equals("load") && port == null) {
            r.done(false, "the controller is not connected to the tablet");
            return;
        }
        switch (cmd) {
            case "status":
                r.done(true, linkSummary().replace("\n", " | "));
                return;
            case "hold":
                feedHold("backoffice");
                r.done(true, "feed hold sent" + (streaming ? " at line " + ackedLines + " / " + program.size() : ""));
                return;
            case "resume":
                writeRaw(new byte[]{'~'});
                r.done(true, "resume sent");
                return;
            case "stop":
                if (streaming) {
                    int at = ackedLines;
                    stopJob("backoffice");
                    r.done(true, "job stopped at line " + at + " / " + program.size() + " – unlock with $X");
                } else {
                    softReset();
                    r.done(true, "no job running – soft reset sent");
                }
                return;
            case "reset":
                if (streaming) { endResult = "stopped"; endDetail = "reset from the backoffice"; }
                softReset();
                r.done(true, "soft reset sent");
                return;
            default:
        }
        if (streaming && !cmd.equals("send")) {
            r.done(false, "a job is running – only hold, resume and stop are accepted");
            return;
        }
        switch (cmd) {
            case "unlock":
                queueCommand("$X");
                r.done(true, "$X sent");
                return;
            case "home":
                queueCommand("$H");
                r.done(true, "$H sent – homing");
                return;
            case "zero": {
                String axes = c.optString("axes", "XY").toUpperCase(Locale.US);
                StringBuilder g = new StringBuilder("G10L20P1");
                for (char a : new char[]{'X', 'Y', 'Z'}) if (axes.indexOf(a) >= 0) g.append(a).append('0');
                if (g.length() == 8) { r.done(false, "axes must contain X, Y and/or Z"); return; }
                queueCommand(g.toString());
                r.done(true, g + " sent – work zero set");
                return;
            }
            case "send": {
                String line = c.optString("line").trim();
                if (line.isEmpty()) { r.done(false, "empty line"); return; }
                if (line.equals("!")) { feedHold("backoffice"); r.done(true, "feed hold sent"); return; }
                if (line.equals("~")) { writeRaw(new byte[]{'~'}); r.done(true, "resume sent"); return; }
                if (line.equals("?")) { r.done(true, linkSummary().replace("\n", " | ")); return; }
                if (line.matches("0x[0-9A-Fa-f]{2}")) {           // debug: one raw byte, e.g. 0x11 (XON)
                    writeRaw(new byte[]{(byte) Integer.parseInt(line.substring(2), 16)});
                    r.done(true, "raw byte " + line + " sent");
                    return;
                }
                if (streaming) { r.done(false, "a job is running – only !, ~ and ? are accepted"); return; }
                String sent = typedCase(line);
                queueCommand(sent);
                r.done(true, "sent " + sent + " – the reply shows in the console");
                return;
            }
            case "load":
            case "run": {
                boolean run = cmd.equals("run");
                if (run && machineState.startsWith("Alarm")) { r.done(false, "Grbl is in ALARM – unlock ($X) or home first"); return; }
                String file = c.optString("file", "").trim();
                Runnable parse = () -> {
                    if (docLength() == 0) { r.done(false, "the editor on the tablet is empty – load a file"); return; }
                    load(() -> {
                        if (program.isEmpty()) { r.done(false, "no G-code lines in " + docName); return; }
                        String what = (docName != null ? docName : "untitled") + ": " + program.size() + " lines, est. "
                                + hms(Math.round(job.estSeconds));
                        if (!run) { r.done(true, "loaded " + what); return; }
                        if (port == null || streaming) { r.done(false, port == null ? "not connected" : "already running"); return; }
                        jobSource = "backoffice";
                        startJob();
                        r.done(true, "started " + what);
                    });
                };
                if (file.isEmpty()) { parse.run(); return; }
                if (dirty) { r.done(false, "the editor on the tablet has unsaved changes – save or close them first"); return; }
                remoteOpen(file, parse, err -> r.done(false, err));
                return;
            }
            default:
                r.done(false, "unknown command " + cmd);
        }
    }

    interface ErrorCallback { void onError(String msg); }

    /** Download a G-code file from the backoffice, save it to Documents/GrblSender and open it in the editor. */
    private void remoteOpen(String name, Runnable onOpened, ErrorCallback onError) {
        log("Downloading " + name + " from the backoffice…");
        worker.execute(() -> {
            try {
                byte[] data = RemoteLog.getBytes("/gcode/" + Uri.encode(name));
                Uri uri = saveLocalFile(name, data);
                DebugLog.srv("Downloaded " + name + " (" + data.length + " bytes) to Documents/" + SAMPLE_DIR);
                ui.post(() -> {
                    if (streaming) { onError.onError("a job started meanwhile"); return; }
                    if (loadDocument(uri, true)) onOpened.run();
                    else onError.onError("downloaded, but the file could not be opened");
                });
            } catch (Exception e) {
                DebugLog.srvErr("Download of " + name + " failed: " + e.getMessage());
                ui.post(() -> onError.onError("download failed: " + e.getMessage()));
            }
        });
    }

    /** Write {@code data} to Documents/GrblSender/{@code name} (replacing the app's own copy). Off the UI thread. */
    private Uri saveLocalFile(String name, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            Uri uri = null;
            for (FilesSection.LocalFile f : FilesSection.listLocal(this)) if (f.name.equals(name)) { uri = f.uri; break; }
            if (uri == null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + SAMPLE_DIR);
                uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), v);
                if (uri == null) throw new java.io.IOException("MediaStore insert returned null");
            }
            try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                if (os == null) throw new java.io.IOException("cannot write " + name);
                os.write(data);
            }
            return uri;
        }
        File dir = new File(getExternalFilesDir(null), SAMPLE_DIR);
        dir.mkdirs();
        File f = new File(dir, name);
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(data);
        }
        return Uri.fromFile(f);
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
            @Override public void capture(String cmd, SetupPages.Lines done) { captureCommand(cmd, done); }
            @Override public void uploadFluid(String name, byte[] content, SetupPages.Done done) { startFluidUpload(content, name, done); }
            @Override public void restartFluid(Runnable back) { MainActivity.this.restartFluid(back); }
            @Override public void saveLocal(String name, String text) { saveFluidLocal(name, text); }
            @Override public void home(String axis) { MainActivity.this.home(axis); }
            @Override public void estop() { log("E-STOP (CNC setup)"); softReset(); }
            @Override public void hold() { feedHold("tablet"); }
            @Override public void resume() { writeRaw(new byte[]{'~'}); log(">> ~ (resume)"); }
            @Override public String machineStatus() {
                return String.format(Locale.US, "State: %s   MPos X %.3f  Y %.3f  Z %.3f%s", machineState.isEmpty() ? "–" : machineState,
                        mpos[0], mpos[1], mpos[2], pinState.isEmpty() ? "" : "   active inputs: " + pinState);
            }
        });
    }

    // ---------------------------------------------------------------- Settings page

    private View setDialog;                      // the General section while it is shown
    private TextView setProfile, setLatest, setInstalled;
    private org.json.JSONObject setLatestBuild;
    private String setLatestError;

    /** What the app expects from each board's firmware (see README.md and firmware/bluepill/BUILD.md). */
    private static String boardProfile(String b) {
        if (BOARD_FLUIDNC.equals(b)) {
            return "Firmware: FluidNC (bdring/FluidNC, Grbl 1.1 protocol), machine set up in config.yaml\n"
                    + "MCU: ESP32 dual core, 240 MHz · Macrobase 6-axis board, up to 6 axes (X Y Z A B C)\n"
                    + "USB: CH340K serial at " + FLUIDNC_BAUD + " baud (baud setting ignored) · also Wi-Fi / Bluetooth\n"
                    + "Flashing: ESP32 ROM bootloader over USB (Settings → Firmware), woodcncrouter build v4.1.1-wcr1 with M150\n"
                    + "RX buffer: not reported – streams with 127 bytes\n"
                    + "Pins: steps/dir/enable through I2S shift registers · spindle on/off GPIO12 · PWM GPIO27 · "
                    + "home sensors X GPIO33, Y GPIO32, Z GPIO35 · probe GPIO34 · feed hold GPIO36";
        }
        if (BOARD_BLUEPILL.equals(b)) {
            return "Firmware: Grbl 1.1f (robomechs STM32F103 port), image at 0x08002000\n"
                    + "MCU: STM32F103C8T6, 72 MHz, 64 KB flash · settings in flash page 0x0800FC00\n"
                    + "USB: native USB serial 0483:5740 (baud setting ignored)\n"
                    + "Flashing: STM32duino bootloader over USB DFU (1EAF:0003)\n"
                    + "RX buffer: 254 bytes · reports 4 axes (A is ignored)\n"
                    + "Pins: spindle on/off PB13 · PWM PA8 · step PA0–PA2 · dir PA3–PA5 · disable PA6 · home sensors PB12–PB14 · probe PA15";
        }
        return "Firmware: Grbl 1.1h (gnea/grbl), 8 MHz build, D13 spindle enable\n"
                + "MCU: ATmega328P, 3.3 V / 8 MHz, 32 KB flash · settings in EEPROM\n"
                + "USB: CH340 serial at 38400 baud\n"
                + "Flashing: Arduino bootloader (STK500v1, 57600 baud)\n"
                + "RX buffer: 128 bytes\n"
                + "Pins: spindle on/off D13 · PWM D11 · step D2–D4 · dir D5–D7 · enable D8 · home sensors D9/D10/D12 · probe A5";
    }

    /** Settings → General: controller board, backoffice server, remote control. */
    private View buildGeneralPage() {
        int gap = Ui.dp(this, 10);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        root.addView(Ui.heading(this, "General", "Grbl Sender " + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ") · built " + BuildConfig.BUILD_TIME));

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = new LinearLayout(this), right = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.setMarginEnd(gap);
        cols.addView(left, lp);
        cols.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(cols);

        // ---- controller board
        LinearLayout boardCard = Ui.card(this);
        boardCard.addView(Ui.label(this, "Controller board"));
        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        android.widget.RadioButton rbPro = new android.widget.RadioButton(this);
        rbPro.setId(View.generateViewId());
        rbPro.setText("(A) Arduino Pro Mini – Grbl 1.1h, CH340 USB serial");
        android.widget.RadioButton rbBlue = new android.widget.RadioButton(this);
        rbBlue.setId(View.generateViewId());
        rbBlue.setText("(B) STM32F103C8T6 Blue / Black Pill – Grbl 1.1f, native USB");
        android.widget.RadioButton rbFluid = new android.widget.RadioButton(this);
        rbFluid.setId(View.generateViewId());
        rbFluid.setText("(C) FluidNC ESP32 6-axis – CH340K USB serial, 115200");
        for (android.widget.RadioButton rb : new android.widget.RadioButton[]{rbPro, rbBlue, rbFluid}) {
            rb.setTextColor(Ui.TEXT);
            rb.setTextSize(15);
            group.addView(rb);
        }
        java.util.function.IntSupplier checkedId = () -> boardIsBlue() ? rbBlue.getId() : boardIsFluid() ? rbFluid.getId() : rbPro.getId();
        group.check(checkedId.getAsInt());
        boardCard.addView(group);
        TextView note = Ui.text(this, "Connect opens this board's USB device and Firmware offers its builds. "
                + "A change takes effect at the next Connect.", 12, Ui.MUTED);
        note.setPadding(0, Ui.dp(this, 4), 0, 0);
        boardCard.addView(note);
        android.widget.Switch auto = new android.widget.Switch(this);
        auto.setText("Auto-connect: scan USB every 3 s and connect to a Pro Mini, Blue Pill or FluidNC board; reconnect after an unplug or a USB error");
        auto.setTextColor(Ui.TEXT);
        auto.setTextSize(14);
        auto.setChecked(autoConnectEnabled());
        auto.setPadding(0, Ui.dp(this, 8), 0, 0);
        auto.setOnCheckedChangeListener((sw, on) -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("autoConnect", on).apply();
            log("Auto-connect " + (on ? "on" : "off"));
            RemoteLog.i("settings", "autoConnect=" + on);
            if (on) { userDisconnected = false; autoFails = 0; autoNextMs = 0; }
        });
        boardCard.addView(auto);
        left.addView(boardCard, Ui.margins(this, 0, 0, 0, 10));

        LinearLayout fwCard = Ui.card(this);
        fwCard.addView(Ui.label(this, "Selected firmware"));
        setProfile = Ui.text(this, "", 13, 0xFFC8CDD3);
        setLatest = Ui.text(this, "", 13, 0xFFB8D0F0);
        setLatest.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        setInstalled = Ui.text(this, "", 13, 0xFFB8E0B8);
        fwCard.addView(setProfile);
        fwCard.addView(setLatest);
        fwCard.addView(setInstalled);
        Button fwBtn = Ui.button(this, "Firmware update…", 1);
        fwCard.addView(fwBtn, Ui.margins(this, 0, 10, 0, 0));
        left.addView(fwCard);

        // ---- backoffice server
        LinearLayout srvCard = Ui.card(this);
        srvCard.addView(Ui.label(this, "Backoffice server"));
        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setText(RemoteLog.server());
        url.setTextColor(Ui.TEXT);
        url.setTypeface(android.graphics.Typeface.MONOSPACE);
        url.setBackgroundResource(R.drawable.input_bg);
        url.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setImeOptions(EditorInfo.IME_ACTION_DONE);
        srvCard.addView(url);
        LinearLayout srvBtns = Ui.row(this);
        Button save = Ui.button(this, "Save", 1);
        Button test = Ui.button(this, "Test", 0);
        Button def = Ui.button(this, "Default", 0);
        for (Button bt : new Button[]{save, test, def}) {
            LinearLayout.LayoutParams bp = Ui.weight(1);
            bp.setMarginEnd(bt == def ? 0 : Ui.dp(this, 8));
            srvBtns.addView(bt, bp);
        }
        srvCard.addView(srvBtns, Ui.margins(this, 0, 8, 0, 0));
        TextView srvStatus = Ui.text(this, "Logs, app and firmware updates, G-code files, job log and remote control all use this server.", 12, Ui.MUTED);
        srvStatus.setPadding(0, Ui.dp(this, 8), 0, 0);
        srvCard.addView(srvStatus);
        right.addView(srvCard, Ui.margins(this, 0, 0, 0, 10));

        // ---- remote control
        LinearLayout rcCard = Ui.card(this);
        rcCard.addView(Ui.label(this, "Remote control"));
        android.widget.Switch allow = new android.widget.Switch(this);
        allow.setText("Allow the backoffice to run, resume, load and send commands");
        allow.setTextColor(Ui.TEXT);
        allow.setTextSize(15);
        allow.setChecked(remoteControlAllowed());
        rcCard.addView(allow);
        TextView rcNote = Ui.text(this, "Live status, the console and the job log are always shared. Hold and Stop from the "
                + "backoffice always work, since they only make the machine safer. Keep someone at the machine when a job is "
                + "started remotely.\n\nDashboard: " + RemoteLog.server() + "/cnc\nHTTP API: " + RemoteLog.server()
                + "/api/cnc (dashboard login or Bearer CNC_API_KEY)", 12, Ui.MUTED);
        rcNote.setPadding(0, Ui.dp(this, 6), 0, 0);
        rcNote.setTextIsSelectable(true);
        rcCard.addView(rcNote);
        right.addView(rcCard);

        group.setOnCheckedChangeListener((g, id) -> {
            if (streaming) {
                log("Stop the running job before changing the board");
                g.check(checkedId.getAsInt());
                return;
            }
            setBoard(id == rbBlue.getId() ? BOARD_BLUEPILL : id == rbFluid.getId() ? BOARD_FLUIDNC : BOARD_PROMINI);
            if (port != null) log("Disconnect and connect again to use the " + boardName(board()));
            loadLatestFirmware();
            updateSettingsInfo();
        });
        fwBtn.setOnClickListener(v -> showSection(SEC_FIRMWARE, board()));
        Runnable doSave = () -> {
            String u = RemoteLog.setServer(url.getText().toString());
            url.setText(u);
            getPreferences(MODE_PRIVATE).edit().putString("server", u).apply();
            DebugLog.srv("Server set to " + u);
            RemoteLog.i("settings", "server=" + u);
            remote.restart();
            srvStatus.setText("Saved. Now using " + u);
            updateChips();
        };
        save.setOnClickListener(v -> doSave.run());
        url.setOnEditorActionListener((v, actionId, ev) -> { doSave.run(); return false; });
        def.setOnClickListener(v -> { url.setText(BuildConfig.LOG_SERVER); doSave.run(); });
        test.setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            srvStatus.setText("Testing " + u + " …");
            worker.execute(() -> {
                String result;
                long t0 = System.currentTimeMillis();
                try {
                    String target = RemoteLog.normalize(u);
                    String health = RemoteLog.getAbs(target + "/health");
                    String ver = RemoteLog.getAbs(target + "/api/version");
                    org.json.JSONObject vj = new org.json.JSONObject(ver);
                    result = "✔ " + target + " answers in " + (System.currentTimeMillis() - t0) + " ms · " + health.trim()
                            + "\nNewest app on it: " + vj.optString("versionName", "none") + " (build " + vj.optInt("versionCode") + ")";
                } catch (Exception e) {
                    result = "✖ " + e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                final String r = result;
                DebugLog.srv("Connection test: " + r.replace('\n', ' '));
                ui.post(() -> srvStatus.setText(r));
            });
        });
        allow.setOnCheckedChangeListener((sw, on) -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("remoteControl", on).apply();
            log("Remote control from the backoffice " + (on ? "allowed" : "switched off"));
            RemoteLog.i("settings", "remoteControl=" + on);
            updateChips();
            publishStatus(true);
        });

        setDialog = scroll;
        if (port != null) queueSilent("$I");
        loadLatestFirmware();
        updateSettingsInfo();
        return scroll;
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
            String other = guessBoard(pendingDriver);
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
            s.append(fwVersion.contains("FluidNC") ? "" : "Grbl ").append(fwVersion).append(" · options ").append(o[0])
                    .append(o.length >= 3 ? " · planner " + o[1].trim() + " blocks · RX buffer " + o[2].trim() + " bytes" : "")
                    .append("\n").append(compatibility(b, fwVersion, o));
        }
        setInstalled.setText(s);
    }

    /** Checks the installed firmware against what this app needs: Grbl 1.1 protocol, a known RX buffer, the right board. */
    static String compatibility(String b, String version, String[] opt) {
        if (version.contains("FluidNC")) {
            if (!BOARD_FLUIDNC.equals(b)) return "⚠ This is FluidNC – select (C) FluidNC ESP32 as the controller board";
            return "✓ Works with this app: FluidNC speaks the Grbl 1.1 protocol; RX buffer not reported, streams with 127 bytes";
        }
        if (BOARD_FLUIDNC.equals(b)) return "⚠ Not FluidNC – the selected board is (C) FluidNC ESP32";
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
    private View updDialog;                      // the App update section while it is shown
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
        if (updStatus != null) updStatus.setText("Checking " + RemoteLog.server() + " …");
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
                            .setPositiveButton("Details", (d, w) -> openSettings(SEC_UPDATE))
                            .setNegativeButton("Later", null)
                            .show();
                }
            }
        }
        updateNavBadge();
        if (updDialog != null) fillUpdatePage(v, history, when);
    }

    /** Settings → App update: installed vs. newest APK on the backoffice, release history, auto-check switch. */
    private View buildUpdatePage() {
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dp);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.addView(Ui.heading(this, "App update", "New versions of Grbl Sender published on the backoffice"));

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
        sv.setBackgroundResource(R.drawable.dro_bg);
        updHistory = new android.widget.LinearLayout(this);
        updHistory.setOrientation(android.widget.LinearLayout.VERTICAL);
        updHistory.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        sv.addView(updHistory);
        root.addView(sv, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        android.widget.LinearLayout buttons = new android.widget.LinearLayout(this);
        Button check = Ui.button(this, "⟳ Check now", 0);
        updDownloadBtn = Ui.button(this, "⬇ Download & install", 2);
        updDownloadBtn.setEnabled(false);
        android.widget.LinearLayout.LayoutParams cp = Ui.weight(1f);
        cp.setMarginEnd(Ui.dp(this, 8));
        buttons.addView(check, cp);
        buttons.addView(updDownloadBtn, Ui.weight(1.6f));
        root.addView(buttons, Ui.margins(this, 0, 8, 0, 0));
        check.setOnClickListener(v -> checkForUpdate(true));
        updDownloadBtn.setOnClickListener(v -> openDownload());

        updDialog = root;
        checkForUpdate(true);
        return root;
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
        updStatus.setText("Checked " + RemoteLog.server() + " at " + when);
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
                + "Backoffice: " + RemoteLog.server() + (remote.isOnline() ? " (online)" : " (offline)") + "\n"
                + "Package: " + getPackageName();
        new AlertDialog.Builder(this)
                .setTitle("Grbl Sender")
                .setIcon(R.mipmap.ic_launcher)
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
        for (int id : new int[]{R.id.toolbarRow, R.id.progressRow, R.id.leftPanel, R.id.middlePanel,
                R.id.titleBar, R.id.navRail, R.id.navDivider}) {
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
        if (page == NAV_SETTINGS) { showPage(NAV_CONTROL); return; }
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
            etaSec = rem;
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

    private void setLive(int id, boolean on) {
        View v = findViewById(id);
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.38f);
    }

    private void updateButtons() {
        boolean connected = port != null;
        btnConnect.setText(connected ? "Disconnect" : "Connect");
        btnConnect.setBackgroundResource(connected ? R.drawable.btn_tech : R.drawable.btn_accent);
        btnConnect.setTextColor(connected ? Ui.TEXT : Ui.ACCENT);
        baudSpinner.setEnabled(!connected && BOARD_PROMINI.equals(board()));
        btnRun.setEnabled(connected && !streaming && docLength() > 0);
        btnLoad.setEnabled(!streaming && docLength() > 0);
        for (int id : new int[]{R.id.btnNew, R.id.btnOpen, R.id.btnClose}) findViewById(id).setEnabled(!streaming);
        // realtime commands only mean something with a machine attached; dim them otherwise
        setLive(R.id.btnHold, connected);
        setLive(R.id.btnResume, connected);
        setLive(R.id.btnStop, connected || streaming);
        setLive(R.id.btnRun, btnRun.isEnabled());
        setLive(R.id.btnLoad, btnLoad.isEnabled());
        publishStatus(true);
    }

    private void log(String msg) {
        String lower = msg.toLowerCase(Locale.US);
        if (lower.contains("fail") || lower.contains("error:") || lower.contains("alarm")) RemoteLog.e("console", msg);
        else if (lower.startsWith("warning") || lower.contains("denied") || lower.startsWith("!!")) RemoteLog.w("console", msg);
        else RemoteLog.i("console", msg);
        if (!msg.startsWith("<< ") && !msg.startsWith(">> ")) {
            if (lower.contains("fail") || lower.contains("error") || lower.startsWith("!!")) DebugLog.cncErr(msg);
            else DebugLog.cnc(msg);
        }
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
