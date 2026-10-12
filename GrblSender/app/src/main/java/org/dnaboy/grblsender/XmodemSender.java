package org.dnaboy.grblsender;

import java.util.Arrays;

/**
 * XMODEM-CRC sender (128-byte packets) for FluidNC's $Xmodem/Receive=<file>, which stores the file on the
 * ESP32's local filesystem. Runs on the UI thread: the activity feeds received bytes in with {@link #onBytes}
 * and calls {@link #onTimeout} from its watchdog. The file is padded with newlines to whole packets, so no
 * ^Z padding ever ends up in a YAML file.
 */
final class XmodemSender {

    interface Io {
        void write(byte[] data);
        void progress(int sent, int total);
        void finished(boolean ok, String message);
    }

    private static final byte SOH = 0x01, EOT = 0x04, ACK = 0x06, NAK = 0x15, CAN = 0x18, CRC_START = 'C';
    private static final int BLOCK = 128, MAX_RETRIES = 10;

    private final byte[] data;
    private final Io io;
    private int block;            // index of the packet waiting for its ACK; -1 before the receiver said 'C'
    private int retries;
    private boolean eotSent, done;

    XmodemSender(byte[] content, Io io) {
        int padded = (content.length + BLOCK - 1) / BLOCK * BLOCK;
        this.data = Arrays.copyOf(content, Math.max(padded, BLOCK));
        Arrays.fill(this.data, content.length, this.data.length, (byte) '\n');
        this.io = io;
        this.block = -1;
    }

    boolean active() { return !done; }
    int packets() { return data.length / BLOCK; }

    void onBytes(byte[] in) {
        for (byte b : in) {
            if (done) return;
            if (block < 0) {
                if (b == CRC_START) { block = 0; sendBlock(); }
                else if (b == NAK) { fail("the receiver asked for checksum mode, only CRC is supported"); }
                continue;                              // text printed before the transfer starts
            }
            if (b == ACK) {
                retries = 0;
                if (eotSent) { finish(); return; }
                block++;
                io.progress(Math.min(block, packets()), packets());
                if (block < packets()) sendBlock(); else { eotSent = true; io.write(new byte[]{EOT}); }
            } else if (b == NAK) {
                if (++retries > MAX_RETRIES) { fail("too many retries at packet " + (block + 1)); return; }
                if (eotSent) io.write(new byte[]{EOT}); else sendBlock();
            } else if (b == CAN) {
                fail("the receiver cancelled the transfer");
            }
        }
    }

    /** No answer for a while: resend (the receiver may have missed a packet), give up after MAX_RETRIES. */
    void onTimeout() {
        if (done) return;
        if (block < 0) { fail("the board never started the transfer (no 'C')"); return; }
        if (++retries > MAX_RETRIES) { fail("no answer from the board"); return; }
        if (eotSent) io.write(new byte[]{EOT}); else sendBlock();
    }

    void cancel() {
        if (done) return;
        io.write(new byte[]{CAN, CAN, CAN});
        fail("cancelled");
    }

    private void sendBlock() {
        byte[] p = new byte[3 + BLOCK + 2];
        int n = (block + 1) & 0xFF;                    // packet numbers start at 1 and wrap
        p[0] = SOH;
        p[1] = (byte) n;
        p[2] = (byte) (255 - n);
        System.arraycopy(data, block * BLOCK, p, 3, BLOCK);
        int crc = crc16(p, 3, BLOCK);
        p[3 + BLOCK] = (byte) (crc >> 8);
        p[4 + BLOCK] = (byte) crc;
        io.write(p);
    }

    private void finish() {
        done = true;
        io.finished(true, data.length + " bytes in " + packets() + " packets");
    }

    private void fail(String why) {
        done = true;
        io.finished(false, why);
    }

    /** CRC-16/XMODEM (poly 0x1021, init 0). */
    static int crc16(byte[] b, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc ^= (b[i] & 0xFF) << 8;
            for (int k = 0; k < 8; k++) crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
        }
        return crc & 0xFFFF;
    }
}
