package com.vector.motion.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.vector.motion.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class DeviceChecks extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments;start();}
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private void click(Activity activity,String tag){runOnMainSync(()->{View view=activity.getWindow().getDecorView().findViewWithTag(tag);check(view!=null,"Missing control "+tag);check(view.isEnabled(),"Disabled control "+tag);view.performClick();});waitForIdleSync();}
    @Override public void onStart(){if("archive".equals(args.getString("case"))){ArchiveChecks.run(this);return;}if("visual".equals(args.getString("case"))){VisualChecks.run(this);return;}if("live".equals(args.getString("case"))){LiveChecks.run(this);return;}Bundle result=new Bundle();File dir=new File(getTargetContext().getCacheDir(),"checks-"+System.currentTimeMillis());try{
        SessionStore store=new SessionStore(dir);File csv=new File(dir,"legacy.csv");String raw=MotionPacket.HEADER+"\n1,1000000,0,0,1,0,0,0,0\n3,2000000,3,4,0,0,3,4,1\n";Files.write(csv.toPath(),raw.getBytes("UTF-8"));
        List<SessionStore.Session> legacy=store.list();check(legacy.size()==1,"Legacy listed");check(legacy.get(0).sport().equals("Not specified"),"Legacy metadata defaults");check(legacy.get(0).stats.missing==1,"Legacy gap");
        store.create(csv,"Tennis","Left");store.marker(csv,"Forehand",1500000);store.finish(csv,"Interrupted: test disconnect",2,1,1,1);
        SessionStore.Session saved=store.list().get(0);store.notes(saved,"Windy morning");check(store.metadata(csv).getJSONArray("markers").length()==1,"Notes preserve markers");check(store.metadata(csv).getString("outcome").startsWith("Interrupted"),"Interruption persisted");
        check(saved.sport().equals("Tennis")&&saved.wrist().equals("Left"),"Sport/wrist persisted");
        check(Arrays.equals(Files.readAllBytes(csv.toPath()),raw.getBytes("UTF-8")),"CSV not rewritten");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();store.exportZip(saved,bytes);Set<String> names=new HashSet<>();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))){ZipEntry entry;while((entry=in.getNextEntry())!=null){names.add(entry.getName());ByteArrayOutputStream content=new ByteArrayOutputStream();SessionStore.copy(in,content);if(entry.getName().equals("legacy.csv"))check(content.toString("UTF-8").equals(raw),"ZIP raw CSV unchanged");if(entry.getName().equals("session.json"))check(new JSONObject(content.toString("UTF-8")).getString("notes").equals("Windy morning"),"ZIP notes");if(entry.getName().equals("analytics.json"))check(new JSONObject(content.toString("UTF-8")).getLong("missingSequences")==1,"ZIP analytics");}}
        check(names.size()==3,"ZIP has all three artifacts");
        File active=new File(csv.getPath()+".active");active.createNewFile();check(store.list().isEmpty(),"Active excluded from history");boolean refused=false;try{store.exportZip(saved,new ByteArrayOutputStream());}catch(IOException ok){refused=true;}check(refused,"Active export refused");active.delete();
        Files.write(csv.toPath(),(raw+"4,3000000,0,0,1,0,0,0,0\n").getBytes("UTF-8"));check(store.list().get(0).stats.count==3,"Cache invalidated by changed CSV");
        File empty=new File(dir,"empty.csv");Files.write(empty.toPath(),(MotionPacket.HEADER+"\n").getBytes("UTF-8"));check(store.summary(empty).count==0,"Empty recording supported");
        File bad=new File(dir,"bad.csv");Files.write(bad.toPath(),"not,a,valid,sample\n".getBytes("UTF-8"));boolean rejected=false;try{store.summary(bad);}catch(IOException ok){rejected=true;}check(rejected,"Malformed CSV rejected");
        if(!"lifecycle".equals(args.getString("case"))) {
        File partial=new File(dir,"partial.csv");byte[] partialBytes=(raw+"4,3000000,0,").getBytes("UTF-8");Files.write(partial.toPath(),partialBytes);new File(partial.getPath()+".active").createNewFile();
        store.recoverInterrupted();
        check(!new File(partial.getPath()+".active").exists(),"Partial crash recording finalized rather than hidden");
        check(Arrays.equals(Files.readAllBytes(partial.toPath()),partialBytes),"Partial crash raw data preserved");
        boolean visible=false;for(SessionStore.Session row:store.list())if(row.csv.equals(partial)){visible=true;check(!row.error.isEmpty(),"Partial file has visible error");}
        check(visible,"Partial crash recording visible for raw CSV export");
        }
        java.nio.ByteBuffer fixture=java.nio.ByteBuffer.allocate(90).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        fixture.put("VECTORM1".getBytes("US-ASCII")).putShort((short)1).putShort((short)64).put((byte)1).put((byte)0).putShort((short)0).putLong(1700000000).putLong(1000).put("0123456789abcdef".getBytes("US-ASCII")).putFloat(112.1f).putShort((short)16).putShort((short)1024).putShort((short)26);
        fixture.position(64);fixture.put((byte)1).put((byte)0).putInt(1).putLong(1000);for(int axis=0;axis<6;axis++)fixture.putShort((short)(axis<3?1000:16));
        java.util.zip.CRC32 checksum=new java.util.zip.CRC32();checksum.update(fixture.array());File binary=new File(dir,"archive.vmo");Files.write(binary.toPath(),fixture.array());
        File imported=store.importWatch(binary,90,checksum.getValue(),"0123456789abcdef","Stopped");
        check(store.metadata(imported).getString("sport").equals("Tennis"),"Watch sport imported");check(store.metadata(imported).getString("wrist").equals("Right"),"Watch wrist imported");
        byte[] importedBytes=Files.readAllBytes(imported.toPath());File repeated=store.importWatch(binary,90,checksum.getValue(),"0123456789abcdef","Stopped");
        check(imported.equals(repeated)&&Arrays.equals(importedBytes,Files.readAllBytes(imported.toPath())),"Duplicate watch import preserves CSV");
        MainActivity activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        click(activity,"nav:Record");click(activity,"nav:Sessions");click(activity,"nav:Home");
        runOnMainSync(activity::finish);waitForIdleSync();
        for(int i=0;i<50&&!activity.isDestroyed();i++)Thread.sleep(100);
        check(activity.isDestroyed(),"Activity destroyed before stale callback check");
        java.lang.reflect.Method refresh=MainActivity.class.getDeclaredMethod("refreshSessions");refresh.setAccessible(true);
        runOnMainSync(()->{try{refresh.invoke(activity);}catch(Exception e){throw new RuntimeException("Stale notes callback must not crash",e);}});
        result.putString("stream","PASS: legacy data, metadata, markers, notes, interruption, cache invalidation, empty/malformed data, ZIP round trip, native navigation\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable failure){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(failure));finish(Activity.RESULT_CANCELED,result);}finally{if(dir.exists())try{Files.walk(dir.toPath()).sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}catch(IOException ignored){}}}
}
