package com.vector.motion;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Navigation and activity lifecycle only; recording lives in RecorderService. */
public class MainActivity extends Activity {
    public RecorderService recorder;
    final Handler handler=new Handler(Looper.getMainLooper());
    final ExecutorService io=Executors.newSingleThreadExecutor();
    SessionStore store;List<SessionStore.Session> sessions=new ArrayList<>();
    final LinkedHashSet<String> comparing=new LinkedHashSet<>();
    int sportIndex,wristIndex,filterSport,filterDate;
    Object screenObject;
    private LinearLayout container,navigation;private ScrollView scroll;
    private String tab="Home",restoreDetail="";private int generation;private boolean bound,destroyed;
    private File exportFile;private boolean exportZip;private boolean wasRecording,watchPage;private String observedImport="";
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){recorder=((RecorderService.LocalBinder)binder).service();update();}
        public void onServiceDisconnected(ComponentName name){recorder=null;update();}
    };
    @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setStatusBarColor(SportUi.BG);getWindow().setNavigationBarColor(SportUi.BG);
        if(saved!=null){tab=saved.getString("tab","Home");watchPage=saved.getBoolean("watchPage");sportIndex=saved.getInt("sport");wristIndex=saved.getInt("wrist");filterSport=saved.getInt("filterSport");filterDate=saved.getInt("filterDate");restoreDetail=saved.getString("detail","");
            if(saved.getString("export")!=null)exportFile=new File(saved.getString("export"));exportZip=saved.getBoolean("exportZip");String[] selected=saved.getStringArray("compare");if(selected!=null)comparing.addAll(Arrays.asList(selected));}
        store=new SessionStore(new File(getFilesDir(),"recordings"));
        LinearLayout page=SportUi.column(this);page.setBackgroundColor(SportUi.BG);page.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});setContentView(page);
        LinearLayout brand=SportUi.row(this);brand.setPadding(SportUi.dp(this,22),SportUi.dp(this,10),SportUi.dp(this,22),SportUi.dp(this,6));page.addView(brand);
        SportUi.text(brand,"V / VECTOR",16,SportUi.MINT).setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        scroll=new ScrollView(this);scroll.setFillViewport(true);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        container=SportUi.column(this);int pad=SportUi.dp(this,20);container.setPadding(pad,pad,pad,pad);scroll.addView(container);
        navigation=SportUi.row(this);navigation.setPadding(8,8,8,8);page.addView(navigation,new LinearLayout.LayoutParams(-1,-2));
        String pending=restoreDetail;boolean pendingWatch=watchPage;navigate(tab);restoreDetail=pending;if(pendingWatch)watchLibrary();permissions();
    }
    void navigate(String destination){watchPage=false;generation++;restoreDetail="";tab=destination;container.removeAllViews();scroll.scrollTo(0,0);
        if("Record".equals(tab)){RecordScreen screen=new RecordScreen(this);screenObject=screen;container.addView(screen.root);}
        else if("Sessions".equals(tab)){HistoryScreen screen=new HistoryScreen(this);screenObject=screen;container.addView(screen.root);}
        else{HomeScreen screen=new HomeScreen(this);screenObject=screen;container.addView(screen.root);}
        navigation.removeAllViews();for(String name:new String[]{"Home","Record","Sessions"}){
            Button b=new Button(this);b.setAllCaps(false);b.setText(name);b.setTextSize(14);b.setTextColor(name.equals(tab)?SportUi.BG:SportUi.MUTED);b.setBackground(SportUi.background(name.equals(tab)?SportUi.MINT:SportUi.BG,SportUi.dp(this,14)));b.setTag("nav:"+name);b.setMinHeight(SportUi.dp(this,52));navigation.addView(b,new LinearLayout.LayoutParams(0,SportUi.dp(this,54),1));b.setOnClickListener(v->{navigate(name);if(!"Record".equals(name))refreshSessions();});
        }
    }
    void refreshSessions(){if(destroyed)return;int request=++generation;io.execute(()->{List<SessionStore.Session> loaded=store.list();handler.post(()->{
        if(destroyed||request!=generation)return;sessions=loaded;
        if(!restoreDetail.isEmpty()){String wanted=restoreDetail;restoreDetail="";for(SessionStore.Session s:sessions)if(s.csv.getName().equals(wanted)){detail(s);return;}}
        if(screenObject instanceof HomeScreen||screenObject instanceof HistoryScreen)navigate(tab);
    });});}
    void detail(SessionStore.Session session){int request=++generation;restoreDetail=session.csv.getName();container.removeAllViews();SportUi.heading(container,"Opening session…",24);screenObject=null;
        io.execute(()->{try{List<MotionAnalytics.Sample> data=MotionAnalytics.read(session.csv);handler.post(()->{if(destroyed||request!=generation)return;AnalysisScreen screen=new AnalysisScreen(this,session,data);screenObject=screen;container.removeAllViews();container.addView(screen.root);scroll.scrollTo(0,0);});}
            catch(Exception e){handler.post(()->{if(request!=generation)return;message("Could not open session: "+e.getMessage());navigate("Sessions");});}
        });
    }
    void watchLibrary(){navigate("Sessions");watchPage=true;WatchScreen screen=new WatchScreen(this);screenObject=screen;container.removeAllViews();container.addView(screen.root);scroll.scrollTo(0,0);}
    void compare(SessionStore.Session a,SessionStore.Session b){generation++;restoreDetail="";screenObject=null;container.removeAllViews();container.addView(AnalysisScreen.compare(this,a,b));scroll.scrollTo(0,0);}
    private boolean permissions(){ArrayList<String> needed=new ArrayList<>();
        for(String p:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT})if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)needed.add(p);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!needed.isEmpty()){requestPermissions(needed.toArray(new String[0]),1);return false;}return true;
    }
    void command(String action,Intent extras){if("CONNECT".equals(action)&&!permissions()){message("Allow Nearby devices and notifications, then connect");return;}
        Intent intent=new Intent(this,RecorderService.class).setAction(action);if(extras!=null&&extras.getExtras()!=null)intent.putExtras(extras.getExtras());
        try{if("CONNECT".equals(action))startForegroundService(intent);else startService(intent);}catch(RuntimeException e){message("Could not start: "+e.getMessage());}
        if("MARKER".equals(action))message("Drill marker queued");
    }
    @Override protected void onStart(){super.onStart();bound=bindService(new Intent(this,RecorderService.class),connection,BIND_AUTO_CREATE);handler.post(tick);refreshSessions();}
    @Override protected void onStop(){handler.removeCallbacks(tick);if(bound){unbindService(connection);bound=false;recorder=null;}super.onStop();}
    @Override protected void onDestroy(){destroyed=true;generation++;handler.removeCallbacksAndMessages(null);io.shutdown();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("tab",tab);out.putBoolean("watchPage",watchPage);out.putInt("sport",sportIndex);out.putInt("wrist",wristIndex);out.putInt("filterSport",filterSport);out.putInt("filterDate",filterDate);out.putStringArray("compare",comparing.toArray(new String[0]));out.putString("detail",restoreDetail);
        if(exportFile!=null)out.putString("export",exportFile.getPath());out.putBoolean("exportZip",exportZip);}
    private final Runnable tick=new Runnable(){public void run(){update();handler.postDelayed(this,250);}};
    private void update(){if(screenObject instanceof WatchScreen)((WatchScreen)screenObject).update();
        if(recorder!=null&&!recorder.lastImported.equals(observedImport)){observedImport=recorder.lastImported;refreshSessions();}
        if(screenObject instanceof RecordScreen)((RecordScreen)screenObject).update();if(screenObject instanceof HomeScreen)((HomeScreen)screenObject).update(recorder);
        boolean recording=recorder!=null&&recorder.recording;if(wasRecording&&!recording)refreshSessions();wasRecording=recording;}
    void message(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    void export(SessionStore.Session session,boolean zip){exportFile=session.csv;exportZip=zip;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(zip?"application/zip":"text/csv")
        .putExtra(Intent.EXTRA_TITLE,zip?session.csv.getName().replace(".csv",".zip"):session.csv.getName());startActivityForResult(intent,2);}
    @Override protected void onActivityResult(int request,int result,Intent intent){super.onActivityResult(request,result,intent);if(request!=2||result!=RESULT_OK||intent==null||intent.getData()==null||exportFile==null)return;
        File source=exportFile;boolean zip=exportZip;android.net.Uri destination=intent.getData();
        io.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(destination)){
            if(out==null)throw new IOException("Could not open destination");if(new File(source.getPath()+".active").exists())throw new IOException("Stop recording before exporting");
            if(zip){SessionStore.Session session=new SessionStore.Session(source);session.metadata=store.metadata(source);store.exportZip(session,out);}else try(InputStream in=new FileInputStream(source)){SessionStore.copy(in,out);}
            handler.post(()->message(zip?"Session ZIP exported":"CSV exported"));
        }catch(Exception e){handler.post(()->message("Export failed: "+e.getMessage()));}});
    }
    @Override public void onBackPressed(){if(screenObject instanceof AnalysisScreen||!"Home".equals(tab))navigate("Home");else super.onBackPressed();}
}
