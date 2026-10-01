package org.dnaboy.grblsender;

import java.io.IOException;

/** A byte pipe to the Arduino. Incoming data is delivered to the listener given at open time. */
interface SerialLink {
    interface Listener {
        void onNewData(byte[] data);
        void onRunError(Exception e);
    }

    void write(byte[] data, int timeoutMs) throws IOException;

    /** Drive the modem control lines. On an Arduino, DTR going active pulses the reset pin. */
    void setDtrRts(boolean dtr, boolean rts) throws IOException;

    void close();
}
