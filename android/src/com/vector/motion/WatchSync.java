package com.vector.motion;

import android.bluetooth.*;
import android.os.Handler;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One outstanding GATT operation; offset-addressed chunks survive a lost connection. */
public final class WatchSync {
    public static final UUID CONTROL=UUID.fromString("7b8e0003-6f27-4b8e-a9a1-3cd405ee1920"), FILE=UUID.fromString("7b8e0004-6f27-4b8e-a9a1-3cd405ee1920");
    private static final UUID CCC=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final class Item {
        public final String id,outcome;public final long bytes,crc,samples,started;public final int sport,wrist;
        Item(JSONObject j)throws JSONException{
            id=j.getString("id");outcome=j.optString("outcome","Not specified");bytes=j.getLong("bytes");crc=j.getLong("crc");samples=j.getLong("samples");started=j.optLong("start",0);sport=j.optInt("sport",3);wrist=j.optInt("wrist",0);
            if(!id.matches("[0-9a-f]{16}")||bytes<64||bytes>16*1024*1024||samples!=(bytes-64)/26||(bytes-64)%26!=0||crc<0||crc>0xffffffffL||sport<0||sport>3||wrist<0||wrist>1)throw new JSONException("Invalid watch inventory entry");
        }
        public String sportName(){return new String[]{"Badminton","Tennis","Running","Other"}[sport];}
    }
    public interface Listener{void changed(boolean available,boolean busy,String message,List<Item> items,String imported);}
    private final BluetoothGatt gatt;private final BluetoothGattCharacteristic control,data;private final Handler work;private final SessionStore store;private final Listener listener;
    private final ArrayList<Item> items=new ArrayList<>();private boolean available,enabled=true,busy,writeDone,chunkDone;private String operation="";private int index;
    private Item download;private RandomAccessFile partial;private File partialPath;private long offset;
    WatchSync(BluetoothGatt g,BluetoothGattService service,Handler worker,SessionStore store,Listener listener){gatt=g;control=service.getCharacteristic(CONTROL);data=service.getCharacteristic(FILE);work=worker;this.store=store;this.listener=listener;}
    boolean supported(){return control!=null&&data!=null&&data.getDescriptor(CCC)!=null;}
    boolean subscribe(){if(!supported()||!gatt.setCharacteristicNotification(data,true))return false;BluetoothGattDescriptor c=data.getDescriptor(CCC);c.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);return gatt.writeDescriptor(c);}
    void subscribed(int status){if(status!=0){fail("Could not enable watch transfers");return;}available=true;busy=true;state("Synchronizing watch clock…","");send("TIME "+System.currentTimeMillis()/1000);}
    public void list(){if(!available||busy)return;busy=true;items.clear();index=0;state("Reading watch recordings…","");send("LIST 0");}
    public void download(String id){if(!available||busy)return;Item chosen=null;for(Item item:items)if(item.id.equals(id))chosen=item;if(chosen==null){state("Refresh watch recordings first","");return;}
        try {
            File existing=new File(store.directory,"watch-"+id+".csv");if(existing.exists()){
                JSONObject saved=store.metadata(existing);if(id.equals(saved.optString("watchSessionId"))&&saved.optLong("watchCrc",-1)==chosen.crc){state("Already imported—open Sessions to review",existing.getName());return;}throw new IOException("Existing CSV was not replaced");}
            download=chosen;File directory=new File(store.directory,".watch-downloads");if(!directory.exists()&&!directory.mkdirs())throw new IOException("Could not create download folder");
            partialPath=new File(directory,id+".vmo.part");partial=new RandomAccessFile(partialPath,"rw");if(partial.length()>download.bytes)partial.setLength(0);offset=partial.length();busy=true;
            state(offset>0?"Resuming watch download…":"Starting watch download…","");send("GET "+id);
        }catch(Exception e){fail(e.getMessage());}
    }
    private void state(String text,String imported){listener.changed(available,busy,text,Collections.unmodifiableList(new ArrayList<>(items)),imported);}
    private void send(String command){if(!enabled)return;operation=command;writeDone=false;chunkDone=false;arm();control.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);control.setValue(command.getBytes(StandardCharsets.US_ASCII));if(!gatt.writeCharacteristic(control))fail("Bluetooth operation could not start; reconnect to resume");}
    void wrote(int status){if(!enabled||!busy)return;if(status!=0){fail("Watch transfer write failed; reconnect to resume");return;}writeDone=true;
        if(operation.startsWith("NEXT ")){advance();return;}
        if(!gatt.readCharacteristic(control))fail("Could not read watch response; reconnect to resume");
    }
    void read(byte[] bytes,int status){if(!enabled||!busy)return;work.removeCallbacks(timeout);
        try{if(status!=0)throw new IOException("Could not read watch response");JSONObject response=new JSONObject(new String(bytes,StandardCharsets.UTF_8));String kind=response.optString("kind");
            if(kind.equals("error"))throw new IOException(response.optString("message","Watch transfer failed"));
            if(operation.startsWith("TIME ")){if(!kind.equals("time"))throw new IOException("Unexpected watch clock response");items.clear();index=0;send("LIST 0");}
            else if(operation.startsWith("LIST ")){if(kind.equals("end")){busy=false;operation="";state(items.size()+" watch recordings available","");}else if(kind.equals("item")){if(index>=512)throw new IOException("Too many watch recordings");Item item=new Item(response);for(Item prior:items)if(prior.id.equals(item.id))throw new IOException("Duplicate watch inventory identity");items.add(item);send("LIST "+(++index));}else throw new IOException("Unexpected watch inventory response");}
            else if(operation.startsWith("GET ")){if(!kind.equals("ready"))throw new IOException("Watch recording unavailable");next();}
            else if(operation.equals("CLOSE")){if(!kind.equals("closed"))throw new IOException("Could not close watch transfer");finish();}
        }catch(Exception e){fail(e.getMessage());}
    }
    void chunk(byte[] bytes){if(!enabled||!busy||download==null||!operation.startsWith("NEXT "))return;
        try {
            if(bytes.length<8||bytes[0]!=(byte)0xa1||bytes[1]!=1)throw new IOException("Invalid watch transfer chunk");ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);long position=Integer.toUnsignedLong(b.getInt(2));int size=Short.toUnsignedInt(b.getShort(6));
            if(size!=bytes.length-8||size==0||position+size>download.bytes)throw new IOException("Invalid watch chunk size");
            if(position<offset&&position+size<=offset)return;
            if(position!=offset)throw new IOException("Unexpected watch chunk offset");
            partial.seek(offset);partial.write(bytes,8,size);offset+=size;chunkDone=true;
            state(String.format(Locale.US,"Downloading %d%% · %,d / %,d bytes",offset*100/download.bytes,offset,download.bytes),"");advance();
        }catch(Exception e){fail(e.getMessage());}
    }
    private void advance(){if(writeDone&&chunkDone){work.removeCallbacks(timeout);if(offset==download.bytes)send("CLOSE");else next();}}
    private void next(){if(offset==download.bytes){send("CLOSE");return;}send("NEXT "+offset);}
    private void finish(){try{partial.getFD().sync();partial.close();partial=null;state("Verifying and importing watch session…","");File imported=store.importWatch(partialPath,download.bytes,download.crc,download.id,download.outcome);partialPath.delete();download=null;busy=false;operation="";state("Imported—open Sessions to review",imported.getName());}
        catch(Exception e){if(partialPath!=null&&e instanceof WatchRecording.InvalidRecording)partialPath.delete();fail(e.getMessage());}}
    private void arm(){work.removeCallbacks(timeout);work.postDelayed(timeout,8000);}
    private final Runnable timeout=()->{android.util.Log.w("VectorWatchSync","Timeout operation="+operation+" offset="+offset+" writeDone="+writeDone+" chunkDone="+chunkDone);fail("Watch transfer timed out. Reconnect and download again to resume.");};
    private void fail(String message){work.removeCallbacks(timeout);try{if(partial!=null)partial.close();}catch(IOException ignored){}partial=null;download=null;busy=false;operation="";state(message==null?"Watch transfer failed":message,"");}
    void close(){enabled=false;available=false;work.removeCallbacks(timeout);try{if(partial!=null)partial.close();}catch(IOException ignored){}partial=null;download=null;busy=false;state("Watch disconnected—partial downloads can resume","");}
}
