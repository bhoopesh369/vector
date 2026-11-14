package com.vector.motion;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;

/** Validates a compact watch archive before publishing any CSV. */
public final class WatchRecording {
    public static final class InvalidRecording extends IOException {
        InvalidRecording(String message){super(message);}
        InvalidRecording(String message,Throwable cause){super(message,cause);}
    }
    public static final class Header {
        public final int sport,wrist,timingMode;public final float nominalHz;public final boolean qualityFinalized;public final int fifoOverflows,readErrors,queueDropped;public final long startedUnix,bootStartUs;public final String id;
        Header(byte[] bytes)throws IOException{
            ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if(!new String(bytes,0,8,StandardCharsets.US_ASCII).equals("VECTORM1")||b.getShort(8)!=1||b.getShort(10)!=64||b.getShort(56)!=26)throw new InvalidRecording("Unsupported watch recording format");
            timingMode=bytes[14]&255;qualityFinalized=bytes[15]==1;fifoOverflows=Short.toUnsignedInt(b.getShort(58));readErrors=Short.toUnsignedInt(b.getShort(60));queueDropped=Short.toUnsignedInt(b.getShort(62));nominalHz=b.getFloat(48);sport=bytes[12]&255;wrist=bytes[13]&255;startedUnix=b.getLong(16);bootStartUs=b.getLong(24);id=new String(bytes,32,16,StandardCharsets.US_ASCII);
            if(timingMode>1||sport>3||wrist>1||startedUnix<0||bootStartUs<0||!id.matches("[0-9a-f]{16}")||b.getShort(52)!=16||b.getShort(54)!=1024||!Float.isFinite(b.getFloat(48))||b.getFloat(48)<=0)throw new InvalidRecording("Invalid watch recording header");
        }
    }
    public static Header importCsv(File binary,File csv,long expectedBytes,long expectedCrc,String expectedId)throws IOException {
        if(expectedBytes<64||expectedBytes>16*1024*1024||(expectedBytes-64)%26!=0||binary.length()!=expectedBytes)throw new InvalidRecording("Watch recording is incomplete");
        if(csv.exists())throw new IOException("Session already imported");
        File temporary=File.createTempFile("watch-import-",".tmp",csv.getParentFile());
        CRC32 checksum=new CRC32();Header header;
        try {
            try(DataInputStream in=new DataInputStream(new BufferedInputStream(new CheckedInputStream(new FileInputStream(binary),checksum)));
                BufferedWriter out=new BufferedWriter(new FileWriter(temporary))) {
                byte[] bytes=new byte[64];in.readFully(bytes);header=new Header(bytes);if(!header.id.equals(expectedId))throw new InvalidRecording("Watch session identity mismatch");
                out.write(MotionPacket.HEADER);out.write('\n');MotionPacket previous=null;
                long samples=(expectedBytes-64)/26;byte[] packet=new byte[26];
                for(long i=0;i<samples;i++) {
                    in.readFully(packet);MotionPacket next;
                    try{next=new MotionPacket(packet);if(next.preview)throw new IllegalArgumentException("Preview packet in raw watch archive");if(next.micros<header.bootStartUs)throw new IllegalArgumentException("Sample precedes session");if(previous!=null)MotionPacket.gap(previous.sequence,previous.micros,next);}
                    catch(IllegalArgumentException e){throw new InvalidRecording("Invalid watch motion sample",e);}
                    out.write(next.csv());previous=next;
                }
                if(in.read()!=-1)throw new InvalidRecording("Unexpected trailing watch bytes");
            }
            if(checksum.getValue()!=expectedCrc)throw new InvalidRecording("Watch recording checksum mismatch; retry download");
            Files.move(temporary.toPath(),csv.toPath());return header;
        }finally{temporary.delete();}
    }
}
