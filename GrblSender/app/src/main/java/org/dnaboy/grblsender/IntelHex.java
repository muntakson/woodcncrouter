package org.dnaboy.grblsender;

import java.io.IOException;
import java.util.Arrays;

/** Minimal Intel HEX reader for AVR flash images (record types 00, 01, 02, 04). */
final class IntelHex {

    /** Flash image: bytes [0, size) with unprogrammed gaps left at 0xFF. */
    static final class Image {
        final byte[] data;
        final int size;
        Image(byte[] data, int size) { this.data = data; this.size = size; }
    }

    private IntelHex() { }

    static Image parse(String text, int maxBytes) throws IOException {
        byte[] mem = new byte[maxBytes];
        Arrays.fill(mem, (byte) 0xFF);
        int size = 0, base = 0, lineNo = 0;
        boolean eof = false;
        for (String raw : text.split("\n")) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (eof) break;
            if (line.charAt(0) != ':' || line.length() < 11 || (line.length() - 1) % 2 != 0) {
                throw new IOException("line " + lineNo + ": not an Intel HEX record");
            }
            byte[] rec = new byte[(line.length() - 1) / 2];
            for (int i = 0; i < rec.length; i++) {
                int hi = Character.digit(line.charAt(1 + 2 * i), 16);
                int lo = Character.digit(line.charAt(2 + 2 * i), 16);
                if (hi < 0 || lo < 0) throw new IOException("line " + lineNo + ": bad hex digit");
                rec[i] = (byte) (hi << 4 | lo);
            }
            int len = rec[0] & 0xFF;
            if (rec.length != len + 5) throw new IOException("line " + lineNo + ": wrong record length");
            int sum = 0;
            for (byte b : rec) sum += b & 0xFF;
            if ((sum & 0xFF) != 0) throw new IOException("line " + lineNo + ": checksum error");
            int addr = (rec[1] & 0xFF) << 8 | (rec[2] & 0xFF);
            int type = rec[3] & 0xFF;
            switch (type) {
                case 0x00: {
                    int a = base + addr;
                    if (a + len > maxBytes) {
                        throw new IOException(String.format("firmware too large: data at 0x%04X–0x%04X, limit %d bytes",
                                a, a + len - 1, maxBytes));
                    }
                    System.arraycopy(rec, 4, mem, a, len);
                    size = Math.max(size, a + len);
                    break;
                }
                case 0x01: eof = true; break;
                case 0x02: base = ((rec[4] & 0xFF) << 8 | (rec[5] & 0xFF)) << 4; break;
                case 0x04: base = ((rec[4] & 0xFF) << 8 | (rec[5] & 0xFF)) << 16; break;
                default: break;   // 03/05 start address: not needed for AVR
            }
        }
        if (!eof) throw new IOException("missing end-of-file record (file truncated?)");
        if (size == 0) throw new IOException("no data in HEX file");
        return new Image(mem, size);
    }
}
