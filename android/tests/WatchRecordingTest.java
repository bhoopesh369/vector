import com.vector.motion.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
public class WatchRecordingTest {
 static byte[] fixture(){ByteBuffer b=ByteBuffer.allocate(64+52).order(ByteOrder.LITTLE_ENDIAN);b.put("VECTORM1".getBytes());b.putShort((short)1).putShort((short)64).put((byte)1).put((byte)0).putShort((short)0).putLong(1700000000L).putLong(1000L).put("0123456789abcdef".getBytes()).putFloat(112.1f).putShort((short)16).putShort((short)1024).putShort((short)26);b.position(64);for(int i=0;i<2;i++){b.put((byte)1).put((byte)0).putInt(i).putLong(1000L+i*9000);for(int a=0;a<6;a++)b.putShort((short)(a<3?1000:16));}return b.array();}
 static void check(boolean good,String why){if(!good)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory(Paths.get("build"),"watch-import-test-");try{
   byte[] data=fixture();CRC32 crc=new CRC32();crc.update(data);File source=root.resolve("watch.vmo").toFile();Files.write(source.toPath(),data);
   File csv=root.resolve("watch.csv").toFile();WatchRecording.Header h=WatchRecording.importCsv(source,csv,data.length,crc.getValue(),"0123456789abcdef");
   check(Math.abs(h.nominalHz-112.1)<0.01&&h.timingMode==0,"legacy timing metadata");
   check(h.sport==1&&h.wrist==0&&h.startedUnix==1700000000L,"header fields");check(Files.readAllLines(csv.toPath()).size()==3,"two complete samples");
   check(Files.readAllLines(csv.toPath()).get(1).contains("1.000,1.000,1.000,1.0000,1.0000,1.0000"),"packet units preserved");
   boolean rejected=false;File bad=root.resolve("bad.csv").toFile();try{WatchRecording.importCsv(source,bad,data.length,crc.getValue()+1,"0123456789abcdef");}catch(IOException expected){rejected=true;}check(rejected&&!bad.exists(),"CRC mismatch never published");
   rejected=false;try{WatchRecording.importCsv(source,bad,data.length,crc.getValue(),"ffffffffffffffff");}catch(IOException expected){rejected=true;}check(rejected&&!bad.exists(),"different session never published");
   rejected=false;byte[] before=Files.readAllBytes(csv.toPath());try{WatchRecording.importCsv(source,csv,data.length,crc.getValue(),"0123456789abcdef");}catch(IOException expected){rejected=true;}check(rejected&&Arrays.equals(before,Files.readAllBytes(csv.toPath())),"existing CSV untouched");
   byte[] high=data.clone();ByteBuffer hb=ByteBuffer.wrap(high).order(ByteOrder.LITTLE_ENDIAN);hb.putFloat(48,1793.6f);high[14]=1;high[15]=1;hb.putShort(58,(short)2);hb.putShort(60,(short)3);hb.putShort(62,(short)4);CRC32 hc=new CRC32();hc.update(high);Files.write(source.toPath(),high);
   WatchRecording.Header hh=WatchRecording.importCsv(source,root.resolve("high.csv").toFile(),high.length,hc.getValue(),"0123456789abcdef");
   check(hh.timingMode==1&&hh.qualityFinalized&&hh.fifoOverflows==2&&hh.readErrors==3&&hh.queueDropped==4,"FIFO acquisition quality imported");
   byte[] preview=data.clone();preview[64]=2;CRC32 pc=new CRC32();pc.update(preview);Files.write(source.toPath(),preview);rejected=false;try{WatchRecording.importCsv(source,bad,preview.length,pc.getValue(),"0123456789abcdef");}catch(WatchRecording.InvalidRecording expected){rejected=true;}check(rejected,"preview must not masquerade as raw archive");
   byte[] corrupt=data.clone();corrupt[0]^=1;Files.write(source.toPath(),corrupt);boolean invalid=false;
   try{WatchRecording.importCsv(source,bad,data.length,crc.getValue(),"0123456789abcdef");}catch(WatchRecording.InvalidRecording expected){invalid=true;}
   check(invalid&&!bad.exists(),"corrupt complete header is classified for fresh retry");
   Files.write(source.toPath(),Arrays.copyOf(data,data.length-1));rejected=false;try{WatchRecording.importCsv(source,bad,data.length-1,0,"0123456789abcdef");}catch(IOException expected){rejected=true;}check(rejected&&!bad.exists(),"partial frame rejected");
   System.out.println("Watch import: binary format, CRC, identity, framing and existing-data preservation PASS");
  }finally{try(java.util.stream.Stream<Path> files=Files.walk(root)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new RuntimeException(e);}});}}
 }
}
