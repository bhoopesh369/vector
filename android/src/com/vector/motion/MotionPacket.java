package com.vector.motion;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

public final class MotionPacket {
    public static final String HEADER = "seq,t_us,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps,clipped";
    public final long sequence, micros;
    public final int clipped;
    public final float[] axes = new float[6];
    public MotionPacket(byte[] bytes) {
        if (bytes.length != 26 || bytes[0] != 1 || (bytes[1] & 0xfe) != 0)
            throw new IllegalArgumentException("Unsupported motion packet");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        clipped = b.get(1);
        sequence = Integer.toUnsignedLong(b.getInt(2));
        micros = b.getLong(6);
        if (micros < 0) throw new IllegalArgumentException("Invalid timestamp");
        for (int i=0; i<6; i++) axes[i] = b.getShort(14+2*i) / (i<3 ? 1000f : 16f);
    }
    public String csv() {
        return String.format(Locale.US, "%d,%d,%.3f,%.3f,%.3f,%.4f,%.4f,%.4f,%d\n",
            sequence, micros, axes[0], axes[1], axes[2], axes[3], axes[4], axes[5], clipped);
    }
    public static long gap(long previousSequence, long previousTime, MotionPacket next) {
        if (next.micros <= previousTime) throw new IllegalArgumentException("Watch restarted or timestamp reversed");
        long delta = (next.sequence - previousSequence) & 0xffffffffL;
        if (delta == 0 || delta > 0x7fffffffL) throw new IllegalArgumentException("Sequence reversed or duplicated");
        return delta - 1;
    }
}
