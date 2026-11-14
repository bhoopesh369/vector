package com.vector.motion;
import java.nio.*;
import java.util.Arrays;
/** Full acquired samples, never synthesized or decimated. */
public final class MotionBatch {
 public static final int HEADER=8,MAX_FRAMES=18;
 public final MotionPacket[] frames;public final long dropped;
 public MotionBatch(byte[] bytes){
  if(bytes.length<HEADER||bytes[0]!=(byte)0xa3||bytes[1]!=1)throw new IllegalArgumentException("Unsupported full-rate batch");
  int count=bytes[2]&255,flags=bytes[3]&255;
  if(count<1||count>MAX_FRAMES||bytes.length!=HEADER+26*count||(flags&~1)!=0)throw new IllegalArgumentException("Invalid full-rate batch length or flags");
  dropped=Integer.toUnsignedLong(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(4));
  if(((flags&1)!=0)!=(dropped!=0))throw new IllegalArgumentException("Invalid transport loss status");
  frames=new MotionPacket[count];for(int i=0;i<count;i++){frames[i]=new MotionPacket(Arrays.copyOfRange(bytes,HEADER+i*26,HEADER+(i+1)*26));if(frames[i].preview)throw new IllegalArgumentException("Preview in full-rate stream");if(i>0)MotionPacket.gap(frames[i-1].sequence,frames[i-1].micros,frames[i]);}
 }
 public boolean hasLoss(){return dropped!=0;}
}
