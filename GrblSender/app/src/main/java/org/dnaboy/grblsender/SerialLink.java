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

    /** Change the UART speed of an open link (USB-serial bridges only). */
    default void setBaud(int baud) throws IOException { throw new IOException("baud change not supported"); }

    /** One line about the link's reader, for the remote link summary. */
    default String diag() { return ""; }
}
