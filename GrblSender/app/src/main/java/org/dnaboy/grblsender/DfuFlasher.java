package org.dnaboy.grblsender;

import java.io.IOException;
import java.util.Locale;

/**
 * USB DFU 1.1 download for the STM32duino / Maple bootloader (Blue Pill, 1EAF:0003).
 * The firmware is written to the alternate setting "Flash 0x8002000" block by block
 * (the bootloader programs each block during GETSTATUS), then read back with UPLOAD
 * when the bootloader advertises it, and finally ABORTed back to dfuIDLE. Maple writes
 * flash as it goes, so no manifestation phase is needed; the bootloader starts the new
 * firmware after the next reset.
 */
final class DfuFlasher {

    /** Control transfers on the claimed DFU interface (UsbDeviceConnection in the app, a fake in tests). */
    interface Io {
        int control(int requestType, int request, int value, int index, byte[] buf, int length, int timeoutMs);
    }

    interface Progress {
        void status(String message);
        void progress(int done, int total);
    }

    static final int VID_MAPLE = 0x1EAF, PID_MAPLE_DFU = 0x0003;
    static final int VID_ST = 0x0483, PID_ST_VCP = 0x5740;
    static final long APP_BASE = 0x08002000L;
    static final int MAX_IMAGE = 0x0801F800 - 0x08002000;   // up to the grblHAL settings pages (128 KB part)

    private static final int OUT = 0x21, IN = 0xA1;          // class request, interface recipient
    private static final int DNLOAD = 1, UPLOAD = 2, GETSTATUS = 3, CLRSTATUS = 4, ABORT = 6;
    private static final int ST_IDLE = 2, ST_DNLOAD_SYNC = 3, ST_DNBUSY = 4, ST_DNLOAD_IDLE = 5,
            ST_UPLOAD_IDLE = 9, ST_ERROR = 10;
    private static final int TIMEOUT = 5000;

    private final Io io;
    private final int intf;

    DfuFlasher(Io io, int interfaceNumber) {
        this.io = io;
        this.intf = interfaceNumber;
    }

    /**
     * @param transferSize wTransferSize from the DFU functional descriptor
     * @param canUpload    bitCanUpload from the DFU functional descriptor
     * @return true if the image was read back and matched, false if read-back is not supported
     */
    boolean flash(byte[] image, int transferSize, boolean canUpload, Progress p) throws IOException, InterruptedException {
        if (image.length == 0 || image.length > MAX_IMAGE) {
            throw new IOException("image size " + image.length + " outside 1.." + MAX_IMAGE + " bytes");
        }
        int ts = transferSize > 0 ? transferSize : 1024;
        p.status("Connecting to the DFU bootloader…");
        toIdle();

        int blocks = (image.length + ts - 1) / ts;
        int total = blocks * (canUpload ? 2 : 1);
        p.status(String.format(Locale.US, "Writing %d bytes in %d blocks of %d…", image.length, blocks, ts));
        for (int b = 0; b < blocks; b++) {
            int off = b * ts, len = Math.min(ts, image.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(image, off, chunk, 0, len);
            int r = io.control(OUT, DNLOAD, b, intf, chunk, len, TIMEOUT);
            if (r < 0) throw new IOException("DNLOAD block " + b + " failed (USB error " + r + ")");
            waitDownloadIdle(b);
            p.progress(b + 1, total);
        }
        // Back to dfuIDLE without manifestation (Maple has already programmed every block)
        abort();

        if (!canUpload) {
            p.status("Done – " + image.length + " bytes written (this bootloader cannot read back, so not verified).");
            return false;
        }
        p.status("Verifying…");
        byte[] buf = new byte[ts];
        int pos = 0;
        for (int b = 0; pos < image.length; b++) {
            int want = Math.min(ts, image.length - pos);
            int r = io.control(IN, UPLOAD, b, intf, buf, ts, TIMEOUT);
            if (r <= 0) {
                if (b == 0) {          // read-back not actually supported: report, do not fail the flash
                    abort();
                    p.status("Done – " + image.length + " bytes written (read-back not supported, so not verified).");
                    return false;
                }
                throw new IOException("UPLOAD stopped at byte " + pos + " of " + image.length);
            }
            for (int k = 0; k < Math.min(r, want); k++) {
                if (buf[k] != image[pos + k]) {
                    throw new IOException(String.format(Locale.US, "verify failed at 0x%08X: wrote %02X, read %02X",
                            APP_BASE + pos + k, image[pos + k] & 0xFF, buf[k] & 0xFF));
                }
            }
            pos += Math.min(r, want);
            p.progress(blocks + (pos + ts - 1) / ts, total);
        }
        abort();
        p.status("Done – " + image.length + " bytes written and verified.");
        return true;
    }

    private int[] status() throws IOException {
        byte[] s = new byte[6];
        int r = io.control(IN, GETSTATUS, 0, intf, s, 6, TIMEOUT);
        if (r < 6) throw new IOException("GETSTATUS failed (" + r + ")");
        int poll = (s[1] & 0xFF) | (s[2] & 0xFF) << 8 | (s[3] & 0xFF) << 16;
        return new int[]{s[0] & 0xFF, s[4] & 0xFF, poll};     // bStatus, bState, bwPollTimeout
    }

    private void toIdle() throws IOException {
        int[] st = status();
        if (st[1] == ST_ERROR) {
            io.control(OUT, CLRSTATUS, 0, intf, null, 0, TIMEOUT);
            st = status();
        }
        if (st[1] == ST_DNLOAD_IDLE || st[1] == ST_UPLOAD_IDLE) {
            abort();
            st = status();
        }
        if (st[1] != ST_IDLE) throw new IOException("bootloader not idle (DFU state " + st[1] + ", status " + st[0] + ")");
    }

    private void waitDownloadIdle(int block) throws IOException, InterruptedException {
        for (int i = 0; i < 200; i++) {
            int[] st = status();
            if (st[0] != 0) throw new IOException("bootloader reported error " + st[0] + " on block " + block);
            if (st[1] == ST_DNLOAD_IDLE) return;
            if (st[1] != ST_DNBUSY && st[1] != ST_DNLOAD_SYNC) {
                throw new IOException("unexpected DFU state " + st[1] + " after block " + block);
            }
            Thread.sleep(Math.max(1, Math.min(st[2], 1000)));
        }
        throw new IOException("bootloader stayed busy on block " + block);
    }

    private void abort() {
        io.control(OUT, ABORT, 0, intf, null, 0, TIMEOUT);
    }

    /** Reads wTransferSize and bitCanUpload from the DFU functional descriptor (type 0x21) in raw descriptors. */
    static int[] functionalDescriptor(byte[] raw) {
        for (int i = 0; raw != null && i + 1 < raw.length; ) {
            int len = raw[i] & 0xFF;
            if (len == 0) break;
            if ((raw[i + 1] & 0xFF) == 0x21 && len >= 7 && i + 6 < raw.length) {
                int attrs = raw[i + 2] & 0xFF;
                int ts = (raw[i + 5] & 0xFF) | (raw[i + 6] & 0xFF) << 8;
                return new int[]{ts, (attrs & 0x02) != 0 ? 1 : 0};
            }
            i += len;
        }
        return new int[]{1024, 0};
    }
}
