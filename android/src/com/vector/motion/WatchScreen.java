package com.vector.motion;

import android.content.Intent;
import android.widget.*;
import java.io.File;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class WatchScreen {
    final LinearLayout root;private final MainActivity host;private final TextView status;private final LinearLayout list;private final Button refresh,cancel;private String signature="";
    WatchScreen(MainActivity host){this.host=host;root=SportUi.column(host);SportUi.button(root,"← Phone sessions",false,()->host.navigate("Sessions"));SportUi.caption(root,"WATCH → PHONE");SportUi.heading(root,"Watch recordings",30);
        SportUi.caption(root,"Record on the watch without your phone. Come back here afterward to download and analyze saved sessions.");
        LinearLayout card=SportUi.card(root);status=SportUi.text(card,"Connect your watch from Record",16,SportUi.MINT);
        refresh=SportUi.button(card,"Refresh watch recordings",true,()->host.command("WATCH_LIST",null));refresh.setTag("watch-refresh");
        cancel=SportUi.button(card,"Cancel transfer & disconnect",false,()->host.command("CANCEL_SYNC",null));
        SportUi.button(card,"Open connection controls",false,()->host.navigate("Record"));
        list=SportUi.column(host);root.addView(list);update();
    }
    void update(){RecorderService r=host.recorder;status.setText(r==null?"Connect your watch from Record":r.syncStatus);
        boolean busy=r!=null&&r.syncBusy;refresh.setEnabled(r!=null&&r.syncAvailable&&!busy&&!r.recording);refresh.setAlpha(refresh.isEnabled()?1f:.4f);cancel.setVisibility(busy?android.view.View.VISIBLE:android.view.View.GONE);
        List<WatchSync.Item> items=r==null?Collections.emptyList():r.watchSessions;StringBuilder key=new StringBuilder().append(busy);for(WatchSync.Item item:items)key.append(item.id).append(new File(host.store.directory,"watch-"+item.id+".csv").exists());
        if(key.toString().equals(signature))return;signature=key.toString();list.removeAllViews();
        if(items.isEmpty()){LinearLayout empty=SportUi.card(list);SportUi.heading(empty,"Your watch sessions will appear here",20);SportUi.caption(empty,"Connect the updated watch, stop any watch recording, then refresh. No memory card is needed for internal-flash sessions.");}
        for(WatchSync.Item item:items){LinearLayout card=SportUi.card(list);String date="Watch clock not set";try{if(item.started>1577836800L)date=Instant.ofEpochSecond(item.started).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM · HH:mm"));}catch(Exception ignored){}
            SportUi.caption(card,date);SportUi.heading(card,item.sportName(),22);SportUi.caption(card,(item.wrist==0?"Right":"Left")+" wrist · "+item.outcome);
            SportUi.text(card,String.format(Locale.US,"%,d samples · %.1f KB",item.samples,item.bytes/1024.0),16,SportUi.MINT);
            boolean imported=new File(host.store.directory,"watch-"+item.id+".csv").exists();
            Button download=SportUi.button(card,imported?"Imported · open phone Sessions":"Download to phone",!imported,()->{if(imported){host.navigate("Sessions");host.refreshSessions();}else host.command("WATCH_GET",new Intent().putExtra("id",item.id));});download.setTag("watch-download:"+item.id);download.setEnabled(!busy&&(imported||(r!=null&&r.syncAvailable)));download.setAlpha(download.isEnabled()?1f:.4f);
        }
        SportUi.caption(root,"Downloads are verified before import. Interrupted transfers keep their partial data and can resume after reconnecting. Downloading does not delete files from the watch.");
    }
}
