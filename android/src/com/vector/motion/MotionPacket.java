package com.vector.motion;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class MotionPacket {
    public static final String HEADER = "seq,t_us,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps,clipped";
    public final long sequence, micros;
    public final int clipped;public final boolean preview;
    public final float[] axes = new float[6];
    public MotionPacket(byte[] bytes) {
        if (bytes.length != 26 || (bytes[0] != 1 && bytes[0] != 2) || (bytes[1] & 0xfe) != 0)
            throw new IllegalArgumentException("Unsupported motion packet");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        clipped = b.get(1);preview=bytes[0]==2;
        sequence = Integer.toUnsignedLong(b.getInt(2));
        micros = b.getLong(6);
        if (micros < 0) throw new IllegalArgumentException("Invalid timestamp");
        for (int i=0; i<6; i++) axes[i] = b.getShort(14+2*i) / (i<3 ? 1000f : 16f);
    }
    public String csv() {
        StringBuilder out=new StringBuilder(100);out.append(sequence).append(',').append(micros);
        for(int i=0;i<6;i++){
            out.append(',');int raw=Math.round(axes[i]*(i<3?1000:16));
            if(raw<0){out.append('-');raw=-raw;}
            int scale=i<3?1000:16;out.append(raw/scale).append('.');
            int fraction=i<3?raw%1000:(raw%16)*625;
            for(int divisor=i<3?100:1000;divisor>0;divisor/=10)out.append((fraction/divisor)%10);
        }
        return out.append(',').append(clipped).append('\n').toString();
    }
    public static long gap(long previousSequence, long previousTime, MotionPacket next) {
        if (next.micros <= previousTime) throw new IllegalArgumentException("Watch restarted or timestamp reversed");
        long delta = (next.sequence - previousSequence) & 0xffffffffL;
        if (delta == 0 || delta > 0x7fffffffL) throw new IllegalArgumentException("Sequence reversed or duplicated");
        return delta - 1;
    }
}
