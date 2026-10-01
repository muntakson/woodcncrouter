package org.dnaboy.grblsender;

import android.hardware.usb.UsbDeviceConnection;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;

/** SerialLink backed by usb-serial-for-android (FTDI, CP210x, CDC-ACM, ...). */
final class LibLink implements SerialLink {

    private final UsbSerialPort port;
    private final SerialInputOutputManager io;

    private LibLink(UsbSerialPort port, SerialInputOutputManager io) {
        this.port = port;
        this.io = io;
    }

    static LibLink open(UsbSerialDriver driver, UsbDeviceConnection conn, int baud, Listener listener) throws IOException {
        UsbSerialPort p = driver.getPorts().get(0);
        try {
            p.open(conn);
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            try {
                p.setDTR(true);   // DTR pulse resets the Arduino -> Grbl banner follows
                p.setRTS(true);
            } catch (Exception e) {
                RemoteLog.w("usb", "DTR/RTS not supported: " + e.getMessage());
            }
        } catch (IOException e) {
            try { p.close(); } catch (Exception ignored) { }
            throw e;
        }
        SerialInputOutputManager io = new SerialInputOutputManager(p, new SerialInputOutputManager.Listener() {
            @Override public void onNewData(byte[] data) { listener.onNewData(data); }
            @Override public void onRunError(Exception e) { listener.onRunError(e); }
        });
        io.start();
        return new LibLink(p, io);
    }

    @Override
    public void write(byte[] data, int timeoutMs) throws IOException {
        port.write(data, timeoutMs);
    }

    @Override
    public void close() {
        io.setListener(null);
        io.stop();
        try { port.close(); } catch (Exception ignored) { }
    }
}
