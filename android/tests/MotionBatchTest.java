import com.vector.motion.*;
import java.nio.*;
public class MotionBatchTest {
 static void check(boolean x){if(!x)throw new AssertionError();}
 static byte[] batch(){ByteBuffer b=ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN);b.put((byte)0xa3).put((byte)1).put((byte)2).put((byte)0).putInt(0);for(int i=0;i<2;i++){b.put((byte)1).put((byte)0).putInt(i).putLong(1000+i*558);for(int a=0;a<6;a++)b.putShort((short)(a<3?-1:1));}return b.array();}
 static void rejects(byte[] p){try{new MotionBatch(p);}catch(IllegalArgumentException expected){return;}throw new AssertionError("Malformed batch accepted");}
 public static void main(String[] args){byte[] bytes=batch();MotionBatch b=new MotionBatch(bytes);check(b.frames.length==2&&!b.hasLoss());check(b.frames[1].sequence==1&&b.frames[1].micros==1558);check(b.frames[0].csv().equals("0,1000,-0.001,-0.001,-0.001,0.0625,0.0625,0.0625,0\n"));bytes[3]=1;bytes[4]=5;check(new MotionBatch(bytes).hasLoss());bytes=batch();bytes[2]=3;rejects(bytes);bytes=batch();bytes[8]=2;rejects(bytes);rejects(new byte[8]);System.out.println("Full-rate batch framing, raw frame provenance, timestamp preservation, loss signaling PASS");}
}
