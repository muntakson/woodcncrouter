package org.dnaboy.grblsender;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Flashes an ATmega328P through the Arduino bootloader (STK500v1, as `avrdude -c arduino`):
 * reset via DTR, sync, check signature, write 128-byte pages, read every page back, leave.
 * The bootloader cannot overwrite itself and EEPROM ($ settings) is not touched.
 */
final class Stk500Flasher implements SerialLink.Listener {

    interface Progress {
        void status(String message);
        void progress(int done, int total);
    }

    static final int BOOTLOADER_BAUD = 57600;
    static final int PAGE = 128;                       // ATmega328P flash page, bytes
    static final byte[] SIG_328P = {0x1E, (byte) 0x95, 0x0F};

    private static final int STK_OK = 0x10, STK_INSYNC = 0x14, CRC_EOP = 0x20;
    private static final int GET_SYNC = 0x30, GET_PARAMETER = 0x41, ENTER_PROGMODE = 0x50, LEAVE_PROGMODE = 0x51;
    private static final int LOAD_ADDRESS = 0x55, PROG_PAGE = 0x64, READ_PAGE = 0x74, READ_SIGN = 0x75;
    private static final int TIMEOUT_MS = 1000;

    private final ByteArrayOutputStream rx = new ByteArrayOutputStream();
    private SerialLink link;
    private volatile Exception linkError;

    // ---------------------------------------------------------------- SerialLink.Listener

    @Override public void onNewData(byte[] data) {
        synchronized (rx) {
            rx.write(data, 0, data.length);
            rx.notifyAll();
        }
    }

    @Override public void onRunError(Exception e) {
        linkError = e;
        synchronized (rx) { rx.notifyAll(); }
    }

    // ---------------------------------------------------------------- flashing

    /** Writes and verifies {@code image[0, size)}. The link must be open at {@link #BOOTLOADER_BAUD}. */
    void flash(SerialLink link, byte[] image, int size, Progress p) throws IOException, InterruptedException {
        this.link = link;
        p.status("Resetting the Arduino into its bootloader…");
        sync(p);

        int major = getParameter(0x81), minor = getParameter(0x82);
        byte[] sig = command(new byte[]{(byte) READ_SIGN, CRC_EOP}, 3);
        String sigText = String.format(Locale.US, "%02X %02X %02X", sig[0], sig[1], sig[2]);
        RemoteLog.i("flash", "bootloader " + major + "." + minor + ", signature " + sigText);
        if (sig[0] != SIG_328P[0] || sig[1] != SIG_328P[1] || sig[2] != SIG_328P[2]) {
            throw new IOException("unexpected chip signature " + sigText + " (expected ATmega328P 1E 95 0F)");
        }
        command(new byte[]{(byte) ENTER_PROGMODE, CRC_EOP}, 0);

        int pages = (size + PAGE - 1) / PAGE;
        int total = pages * 2;
        p.status(String.format(Locale.US, "Writing %d bytes (%d pages)…", size, pages));
        for (int i = 0; i < pages; i++) {
            loadAddress(i * PAGE);
            byte[] cmd = new byte[5 + PAGE];
            cmd[0] = PROG_PAGE;
            cmd[1] = 0;
            cmd[2] = (byte) PAGE;
            cmd[3] = 'F';
            System.arraycopy(image, i * PAGE, cmd, 4, PAGE);
            cmd[4 + PAGE] = CRC_EOP;
            command(cmd, 0);
            p.progress(i + 1, total);
        }

        p.status("Verifying…");
        for (int i = 0; i < pages; i++) {
            loadAddress(i * PAGE);
            byte[] got = command(new byte[]{READ_PAGE, 0, (byte) PAGE, 'F', CRC_EOP}, PAGE);
            for (int k = 0; k < PAGE; k++) {
                if (got[k] != image[i * PAGE + k]) {
                    throw new IOException(String.format(Locale.US,
                            "verify failed at 0x%04X: wrote %02X, read %02X", i * PAGE + k,
                            image[i * PAGE + k] & 0xFF, got[k] & 0xFF));
                }
            }
            p.progress(pages + i + 1, total);
        }
        command(new byte[]{(byte) LEAVE_PROGMODE, CRC_EOP}, 0);
        p.status("Done – " + size + " bytes written and verified.");
    }

    /** Pulse reset with DTR and get in sync; retries the reset a few times. */
    private void sync(Progress p) throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= 3; attempt++) {
            link.setDtrRts(false, false);
            Thread.sleep(100);
            link.setDtrRts(true, true);       // DTR active -> reset pulse through the 100 nF cap
            Thread.sleep(50);
            clear();
            long until = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < until) {
                link.write(new byte[]{GET_SYNC, CRC_EOP}, 500);
                byte[] r = read(2, 200, false);
                if (r != null && r[0] == STK_INSYNC && r[1] == STK_OK) {
                    // a couple more syncs, as avrdude does, to flush stray bytes
                    clear();
                    command(new byte[]{GET_SYNC, CRC_EOP}, 0);
                    RemoteLog.i("flash", "bootloader in sync (attempt " + attempt + ")");
                    return;
                }
                clear();
            }
            p.status("No answer from the bootloader – resetting again (" + attempt + "/3)…");
        }
        throw new IOException("bootloader did not answer at " + BOOTLOADER_BAUD + " baud (not an Arduino bootloader, or reset not wired to DTR?)");
    }

    private int getParameter(int param) throws IOException, InterruptedException {
        return command(new byte[]{GET_PARAMETER, (byte) param, CRC_EOP}, 1)[0] & 0xFF;
    }

    private void loadAddress(int byteAddr) throws IOException, InterruptedException {
        int word = byteAddr / 2;
        command(new byte[]{LOAD_ADDRESS, (byte) (word & 0xFF), (byte) (word >> 8), CRC_EOP}, 0);
    }

    /** Sends a command and expects INSYNC, {@code n} payload bytes, OK. Returns the payload. */
    private byte[] command(byte[] cmd, int n) throws IOException, InterruptedException {
        link.write(cmd, TIMEOUT_MS);
        byte[] r = read(n + 2, TIMEOUT_MS, true);
        if (r[0] != STK_INSYNC || r[n + 1] != STK_OK) {
            throw new IOException(String.format(Locale.US, "bootloader protocol error after command 0x%02X: %s",
                    cmd[0] & 0xFF, hex(r)));
        }
        byte[] out = new byte[n];
        System.arraycopy(r, 1, out, 0, n);
        return out;
    }

    private byte[] read(int n, int timeoutMs, boolean required) throws IOException, InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        synchronized (rx) {
            while (rx.size() < n) {
                if (linkError != null) throw new IOException("USB error: " + linkError.getMessage());
                long left = until - System.currentTimeMillis();
                if (left <= 0) {
                    if (required) throw new IOException("bootloader timeout (got " + rx.size() + "/" + n + " bytes)");
                    return null;
                }
                rx.wait(left);
            }
            byte[] all = rx.toByteArray();
            byte[] out = new byte[n];
            System.arraycopy(all, 0, out, 0, n);
            rx.reset();
            rx.write(all, n, all.length - n);
            return out;
        }
    }

    private void clear() {
        synchronized (rx) { rx.reset(); }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02X ", x));
        return s.toString().trim();
    }
}
