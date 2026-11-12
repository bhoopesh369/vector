import com.vector.motion.MotionPacket;
import java.nio.*;
public class MotionPacketTest {
 static byte[] data(long seq,long time) {
  ByteBuffer b=ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
  b.put((byte)1).put((byte)1).putInt((int)seq).putLong(time);
  b.putShort((short)-16000).putShort((short)1000).putShort((short)500);
  b.putShort((short)-16384).putShort((short)16).putShort((short)0);
  return b.array();
 }
 static void check(boolean condition){if(!condition)throw new AssertionError();}
 static void rejects(Runnable fn){try{fn.run();}catch(IllegalArgumentException ok){return;}throw new AssertionError("Expected rejection");}
 public static void main(String[] args){
  MotionPacket p=new MotionPacket(data(0xffffffffL,5000000000L));
  check(p.sequence==0xffffffffL && p.micros==5000000000L);
  check(p.axes[0]==-16 && p.axes[1]==1 && p.axes[3]==-1024 && p.axes[4]==1 && p.clipped==1);
  check(p.csv().startsWith("4294967295,5000000000,-16.000,1.000"));
  check(MotionPacket.gap(0xfffffffeL,1,new MotionPacket(data(1,2)))==2);
  check(MotionPacket.gap(20,1,new MotionPacket(data(21,2)))==0);
  rejects(()->MotionPacket.gap(20,5,new MotionPacket(data(21,4))));
  rejects(()->MotionPacket.gap(20,5,new MotionPacket(data(20,6))));
  rejects(()->new MotionPacket(new byte[25]));
  byte[] bad=data(1,1);bad[0]=2;rejects(()->new MotionPacket(bad));
  byte[] flag=data(1,1);flag[1]=2;rejects(()->new MotionPacket(flag));
  System.out.println("Protocol decoding, scaling, gaps, wrap and invalid packets: PASS");
 }
}
