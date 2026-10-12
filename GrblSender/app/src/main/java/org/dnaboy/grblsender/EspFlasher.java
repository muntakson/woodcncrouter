package org.dnaboy.grblsender;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;

/**
 * Flashes an ESP32 (FluidNC board) through its ROM serial bootloader, the protocol esptool uses:
 * DTR/RTS auto-reset into the bootloader, SYNC, optional baud change, SPI_ATTACH, SPI_SET_PARAMS,
 * FLASH_DEFL_BEGIN/DATA/END with zlib-compressed data, SPI_FLASH_MD5 to verify, then a hard reset.
 * Runs on a worker thread; bytes from the serial link are fed in with {@link #onBytes}.
 */
final class EspFlasher {

    interface Port {
        void write(byte[] data) throws IOException;
        void setDtrRts(boolean dtr, boolean rts) throws IOException;
        void setBaud(int baud) throws IOException;
    }

    interface Progress {
        void status(String text);
        void progress(long done, long total);
    }

    /** One region of flash: data written at offset. */
    static final class Segment {
        final int offset;
        final byte[] data;
        Segment(int offset, byte[] data) { this.offset = offset; this.data = data; }
    }

    private static final int OP_SYNC = 0x08, OP_READ_REG = 0x0A, OP_SPI_SET_PARAMS = 0x0B,
            OP_SPI_ATTACH = 0x0D, OP_CHANGE_BAUD = 0x0F, OP_DEFL_BEGIN = 0x10, OP_DEFL_DATA = 0x11,
            OP_DEFL_END = 0x12, OP_MD5 = 0x13;
    private static final int BLOCK = 0x400;                        // ROM FLASH_WRITE_SIZE
    private static final int CHIP_MAGIC_REG = 0x40001000, ESP32_MAGIC = 0x00F01D83;
    static final int FLASH_SIZE = 4 * 1024 * 1024;                  // the Macrobase card's ESP32-WROOM

    private final Port port;
    private final Progress progress;
    private final LinkedBlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
    private final ByteArrayOutputStream slip = new ByteArrayOutputStream();
    private boolean inFrame, escape;

    EspFlasher(Port port, Progress progress) {
        this.port = port;
        this.progress = progress;
    }

    // ---------------------------------------------------------------- receive side (SLIP)

    synchronized void onBytes(byte[] in) {
        for (byte b : in) {
            int c = b & 0xFF;
            if (c == 0xC0) {
                if (inFrame && slip.size() > 0) frames.add(slip.toByteArray());
                slip.reset();
                inFrame = true;
                escape = false;
            } else if (inFrame) {
                if (escape) {
                    slip.write(c == 0xDC ? 0xC0 : c == 0xDD ? 0xDB : c);
                    escape = false;
                } else if (c == 0xDB) {
                    escape = true;
                } else {
                    slip.write(c);
                }
            }
        }
    }

    // ---------------------------------------------------------------- main sequence

    /** Writes all segments, verifies them, and restarts the board. Throws with a readable message on failure. */
    void flash(List<Segment> segments, int fastBaud) throws Exception {
        connect();
        progress.status("Bootloader found – chip check…");
        int magic = readReg(CHIP_MAGIC_REG);
        if (magic != ESP32_MAGIC) {
            throw new IOException(String.format(Locale.US, "not an ESP32 (chip magic 0x%08X) – wrong board?", magic));
        }
        if (fastBaud > 115200) {
            try {
                command(OP_CHANGE_BAUD, le(fastBaud, 0), 0, 3000);
                port.setBaud(fastBaud);
                Thread.sleep(60);
                frames.clear();
                readReg(CHIP_MAGIC_REG);                      // does the new speed work?
                progress.status("Bootloader at " + fastBaud + " baud");
            } catch (Exception e) {
                progress.status("Fast baud failed (" + e.getMessage() + ") – staying at 115200");
                port.setBaud(115200);
                connect();
            }
        }
        command(OP_SPI_ATTACH, new byte[8], 0, 3000);
        command(OP_SPI_SET_PARAMS, le(0, FLASH_SIZE, 64 * 1024, 4096, 256, 0xFFFF), 0, 3000);

        long total = 0, done = 0;
        for (Segment s : segments) total += s.data.length;
        for (Segment s : segments) {
            if (s.offset + s.data.length > FLASH_SIZE) throw new IOException("segment beyond 4 MB flash");
            byte[] z = deflate(s.data);
            int blocks = (z.length + BLOCK - 1) / BLOCK;
            int eraseSize = (s.data.length + BLOCK - 1) / BLOCK * BLOCK;
            progress.status(String.format(Locale.US, "Erasing %d KB at 0x%06X…", eraseSize / 1024, s.offset));
            // the ROM erases the whole region in DEFL_BEGIN: allow 30 s per MB
            command(OP_DEFL_BEGIN, le(eraseSize, blocks, BLOCK, s.offset), 0, Math.max(5000, (int) (30000L * eraseSize / 1048576)));
            for (int i = 0; i < blocks; i++) {
                int len = Math.min(BLOCK, z.length - i * BLOCK);
                byte[] p = new byte[16 + len];
                System.arraycopy(le(len, i, 0, 0), 0, p, 0, 16);
                System.arraycopy(z, i * BLOCK, p, 16, len);
                command(OP_DEFL_DATA, p, checksum(z, i * BLOCK, len), 10000);
                long written = Math.min(s.data.length, (long) s.data.length * (i + 1) / blocks);
                progress.progress(done + written, total);
            }
            done += s.data.length;
            progress.status(String.format(Locale.US, "Verifying 0x%06X…", s.offset));
            byte[] r = command(OP_MD5, le(s.offset, s.data.length, 0, 0), 0, Math.max(5000, (int) (8000L * s.data.length / 1048576)));
            String got = new String(r, 0, Math.min(32, r.length), "US-ASCII");
            String want = hex(MessageDigest.getInstance("MD5").digest(s.data));
            if (!got.equalsIgnoreCase(want)) throw new IOException(String.format(Locale.US, "verify failed at 0x%06X (MD5 %s, expected %s)", s.offset, got, want));
        }
        // No FLASH_END: the MD5 checks above prove every block is in flash, and the reset starts the new firmware
        progress.status("Written and verified – restarting the board");
        hardReset();
    }

    /** Read-only check: enter the bootloader, read the chip magic and MAC, restart the board. */
    String probe() throws Exception {
        connect();
        int magic = readReg(CHIP_MAGIC_REG);
        int mac0 = readReg(0x3FF5A004), mac1 = readReg(0x3FF5A008);   // EFUSE_BLK0_RDATA1/2
        hardReset();
        return String.format(Locale.US, "chip magic 0x%08X (%s) · MAC %02X:%02X:%02X:%02X:%02X:%02X", magic,
                magic == ESP32_MAGIC ? "ESP32" : "not an ESP32",
                (mac1 >> 8) & 0xFF, mac1 & 0xFF, (mac0 >> 24) & 0xFF, (mac0 >> 16) & 0xFF, (mac0 >> 8) & 0xFF, mac0 & 0xFF);
    }

    /** Pulses EN (via RTS) without holding IO0 low: the board restarts into the new firmware. */
    void hardReset() throws Exception {
        port.setDtrRts(false, true);
        Thread.sleep(120);
        port.setDtrRts(false, false);
    }

    // ---------------------------------------------------------------- protocol helpers

    private void connect() throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            // classic esptool reset: EN low (RTS), then IO0 low (DTR) while EN rises, then release IO0
            port.setDtrRts(false, true);
            Thread.sleep(100);
            port.setDtrRts(true, false);
            Thread.sleep(attempt < 3 ? 50 : 550);
            port.setDtrRts(false, false);
            Thread.sleep(50);
            frames.clear();
            for (int s = 0; s < 4; s++) {
                byte[] sync = new byte[36];
                sync[0] = 0x07; sync[1] = 0x07; sync[2] = 0x12; sync[3] = 0x20;
                for (int i = 4; i < 36; i++) sync[i] = 0x55;
                try {
                    command(OP_SYNC, sync, 0, 150);
                    Thread.sleep(100);
                    frames.clear();                         // the ROM answers SYNC several times
                    return;
                } catch (IOException e) {
                    last = e;
                }
            }
            progress.status("No answer from the ESP32 bootloader – retrying (" + (attempt + 1) + "/6)");
        }
        throw new IOException("the ESP32 did not enter its bootloader (" + (last != null ? last.getMessage() : "?")
                + "). Hold the board's BOOT button, tap Flash again, release BOOT when it says Erasing");
    }

    private int readReg(int addr) throws Exception {
        command(OP_READ_REG, le(addr), 0, 1000);
        return lastValue;                                   // READ_REG answers in the header's value field
    }

    private int lastValue;

    /** Sends one command and waits for its response; returns the response data without the 4 status bytes. */
    private byte[] command(int op, byte[] data, int checksum, int timeoutMs) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(8 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0).put((byte) op).putShort((short) data.length).putInt(checksum).put(data);
        port.write(slipEncode(b.array()));
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            byte[] f = left > 0 ? frames.poll(left, TimeUnit.MILLISECONDS) : null;
            if (f == null) throw new IOException(String.format(Locale.US, "timeout waiting for command 0x%02X", op));
            if (f.length < 8 || f[0] != 1 || (f[1] & 0xFF) != op) continue;      // stale or other answers
            ByteBuffer r = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN);
            r.position(4);
            lastValue = r.getInt();
            int len = f.length - 8;
            if (len < 4) throw new IOException(String.format(Locale.US, "short response to 0x%02X", op));
            int status = f[8 + len - 4] & 0xFF, error = f[8 + len - 3] & 0xFF;
            if (status != 0) throw new IOException(String.format(Locale.US, "command 0x%02X failed, ROM error 0x%02X", op, error));
            byte[] out = new byte[len - 4];
            System.arraycopy(f, 8, out, 0, out.length);
            return out;
        }
    }

    private static byte[] slipEncode(byte[] p) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(p.length + 16);
        o.write(0xC0);
        for (byte x : p) {
            int c = x & 0xFF;
            if (c == 0xC0) { o.write(0xDB); o.write(0xDC); }
            else if (c == 0xDB) { o.write(0xDB); o.write(0xDD); }
            else o.write(c);
        }
        o.write(0xC0);
        return o.toByteArray();
    }

    private static byte[] le(int... v) {
        ByteBuffer b = ByteBuffer.allocate(4 * v.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int x : v) b.putInt(x);
        return b.array();
    }

    private static int checksum(byte[] d, int off, int len) {
        int c = 0xEF;
        for (int i = off; i < off + len; i++) c ^= d[i] & 0xFF;
        return c;
    }

    private static byte[] deflate(byte[] d) {
        Deflater z = new Deflater(9);                       // zlib stream, as the ROM's inflater expects
        z.setInput(d);
        z.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream(d.length / 2);
        byte[] buf = new byte[65536];
        while (!z.finished()) o.write(buf, 0, z.deflate(buf));
        z.end();
        return o.toByteArray();
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
