package org.dnaboy.grblsender;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbRequest;
import android.os.Build;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Self-contained CH340/CH341 driver, following the Linux ch341 driver.
 * Unlike usb-serial-for-android it treats the vendor "read version" request
 * as optional and logs every control transfer, so hosts that reject some
 * control requests still get a working port where possible.
 */
final class Ch340Link implements SerialLink {

    private static final int CTRL_TIMEOUT = 1000;
    private static final int REQTYPE_OUT = UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_OUT; // 0x40
    private static final int REQTYPE_IN = UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_IN;   // 0xC0

    private static final int REQ_READ_VERSION = 0x5F;
    private static final int REQ_WRITE_REG = 0x9A;
    private static final int REQ_SERIAL_INIT = 0xA1;
    private static final int REQ_MODEM_CTRL = 0xA4;
    private static final int REG_DIVISOR_PRESCALER = 0x1312;
    private static final int REG_LCR = 0x2518;
    private static final int LCR_8N1 = 0xC3;            // ENABLE_RX | ENABLE_TX | CS8
    private static final int MCR_DTR = 0x20;
    private static final int MCR_RTS = 0x40;

    private static final int CLKRATE = 48000000;

    private final UsbDeviceConnection conn;
    private final UsbInterface intf;
    private final UsbEndpoint epIn;
    private final UsbEndpoint epOut;
    private volatile boolean running = true;
    private Thread reader;

    private Ch340Link(UsbDeviceConnection conn, UsbInterface intf, UsbEndpoint in, UsbEndpoint out) {
        this.conn = conn;
        this.intf = intf;
        this.epIn = in;
        this.epOut = out;
    }

    static Ch340Link open(UsbDeviceConnection conn, UsbDevice dev, int baud, Listener listener) throws IOException {
        UsbInterface intf = dev.getInterface(0);
        if (!conn.claimInterface(intf, true)) throw new IOException("claimInterface failed");
        UsbEndpoint in = null, out = null;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep; else out = ep;
        }
        if (in == null || out == null) {
            conn.releaseInterface(intf);
            throw new IOException("bulk endpoints not found");
        }
        Ch340Link link = new Ch340Link(conn, intf, in, out);
        link.initialize(baud);
        link.startReader(listener);
        return link;
    }

    private void initialize(int baud) {
        StringBuilder diag = new StringBuilder("CH340 init @ " + baud);

        // Standard (non-vendor) control IN: shows whether control transfers work at all on this host
        byte[] desc = new byte[18];
        int r = conn.controlTransfer(0x80, 6, 0x0100, 0, desc, desc.length, CTRL_TIMEOUT);
        diag.append(String.format(Locale.US, "\n  std GET_DESCRIPTOR(device) -> %d %s", r,
                r > 0 ? hex(desc, r) : ""));
        String raw = hex(conn.getRawDescriptors(), 18);
        diag.append("\n  raw descriptors: ").append(raw);

        byte[] ver = new byte[2];
        r = conn.controlTransfer(REQTYPE_IN, REQ_READ_VERSION, 0, 0, ver, 2, CTRL_TIMEOUT);
        diag.append(String.format(Locale.US, "\n  vendor READ_VERSION -> %d %s", r, r > 0 ? hex(ver, r) : "(optional, ignored)"));

        int fails = 0;
        fails += out(diag, "SERIAL_INIT", REQ_SERIAL_INIT, 0, 0);
        int div = divisor(baud) | 0x80;   // bit 7: don't wait for a full 32-byte packet before sending
        fails += out(diag, String.format(Locale.US, "WRITE_REG divisor=0x%04X", div), REQ_WRITE_REG, REG_DIVISOR_PRESCALER, div);
        fails += out(diag, "WRITE_REG LCR 8N1", REQ_WRITE_REG, REG_LCR, LCR_8N1);
        // Asserting DTR pulses the Arduino's reset line -> Grbl prints its banner
        fails += out(diag, "MODEM_CTRL DTR+RTS", REQ_MODEM_CTRL, ~(MCR_DTR | MCR_RTS) & 0xFF, 0);

        diag.append("\n  vendor OUT failures: ").append(fails).append("/4");
        if (fails > 0) RemoteLog.w("ch340", diag.toString());
        else RemoteLog.i("ch340", diag.toString());
    }

    private int out(StringBuilder diag, String name, int req, int value, int index) {
        int r = conn.controlTransfer(REQTYPE_OUT, req, value, index, null, 0, CTRL_TIMEOUT);
        diag.append(String.format(Locale.US, "\n  %s -> %d", name, r));
        return r < 0 ? 1 : 0;
    }

    /** Baud divisor/prescaler register value, as computed by Linux ch341_get_divisor(). */
    static int divisor(int speed) {
        int[] minRates = new int[4];
        for (int ps = 0; ps < 4; ps++) minRates[ps] = CLKRATE / (clkDiv(ps, 1) * 512);
        int maxBps = CLKRATE / (clkDiv(3, 0) * 2);
        speed = Math.max(minRates[0], Math.min(speed, maxBps));
        int ps = 3;
        while (ps >= 0 && speed < minRates[ps]) ps--;
        int fact = 1;
        int clkDiv = clkDiv(ps, fact);
        int div = CLKRATE / (clkDiv * speed);
        if (div < 9 || div > 255) {
            div /= 2;
            clkDiv *= 2;
            fact = 0;
        }
        if (16L * CLKRATE / (clkDiv * div) - 16L * speed >= 16L * speed - 16L * CLKRATE / (clkDiv * (div + 1))) div++;
        if (fact == 1 && div % 2 == 0) {
            div /= 2;
            fact = 0;
        }
        return (0x100 - div) << 8 | fact << 2 | ps;
    }

    private static int clkDiv(int ps, int fact) { return 1 << (12 - 3 * ps - fact); }

    private void startReader(Listener listener) {
        reader = new Thread(() -> {
            if (Build.VERSION.SDK_INT >= 26) readQueued(listener); else readBulk(listener);
        }, "ch340-read");
        reader.start();
    }

    /**
     * Reads with a queued UsbRequest. Unlike bulkTransfer() with a timeout, a request that is
     * still pending when requestWait() times out stays queued, so no incoming bytes are dropped.
     */
    private void readQueued(Listener listener) {
        UsbRequest req = new UsbRequest();
        if (!req.initialize(conn, epIn)) {
            RemoteLog.e("ch340", "UsbRequest.initialize failed – falling back to bulkTransfer");
            readBulk(listener);
            return;
        }
        ByteBuffer buf = ByteBuffer.allocate(Math.max(epIn.getMaxPacketSize(), 64));
        try {
            while (running) {
                buf.clear();
                if (!req.queue(buf)) throw new IOException("queueing USB read failed");
                UsbRequest done = null;
                while (running && done == null) {
                    try {
                        done = conn.requestWait(250);
                    } catch (TimeoutException e) {
                        // nothing yet; the request stays queued
                    }
                }
                if (!running) break;
                if (done != req) continue;
                int n = buf.position();
                if (n > 0) {
                    byte[] data = new byte[n];
                    buf.flip();
                    buf.get(data);
                    listener.onNewData(data);
                }
            }
        } catch (Exception e) {
            if (running) listener.onRunError(e);
        } finally {
            req.cancel();
            req.close();
        }
    }

    /** Pre-Android-8 fallback (can lose bytes that arrive exactly at a read timeout). */
    private void readBulk(Listener listener) {
        byte[] buf = new byte[Math.max(epIn.getMaxPacketSize(), 64)];
        while (running) {
            int n = conn.bulkTransfer(epIn, buf, buf.length, 200);
            if (!running) break;
            if (n > 0) listener.onNewData(Arrays.copyOf(buf, n));
        }
    }

    @Override
    public void setDtrRts(boolean dtr, boolean rts) throws IOException {
        int bits = (dtr ? MCR_DTR : 0) | (rts ? MCR_RTS : 0);
        int r = conn.controlTransfer(REQTYPE_OUT, REQ_MODEM_CTRL, ~bits & 0xFF, 0, null, 0, CTRL_TIMEOUT);
        if (r < 0) throw new IOException("MODEM_CTRL failed");
    }

    @Override
    public void write(byte[] data, int timeoutMs) throws IOException {
        int off = 0;
        while (off < data.length) {
            int len = Math.min(data.length - off, epOut.getMaxPacketSize() * 8);
            int n = conn.bulkTransfer(epOut, data, off, len, timeoutMs);
            if (n < 0) throw new IOException("bulk write failed at " + off + "/" + data.length);
            off += n;
        }
    }

    @Override
    public void close() {
        running = false;
        try { if (reader != null) reader.join(800); } catch (InterruptedException ignored) { }
        conn.releaseInterface(intf);
        conn.close();
    }

    private static String hex(byte[] b, int n) {
        if (b == null) return "null";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) s.append(String.format("%02X ", b[i]));
        return s.toString().trim();
    }
}
