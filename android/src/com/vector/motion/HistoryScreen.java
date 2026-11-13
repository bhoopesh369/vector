package com.vector.motion;

import android.app.AlertDialog;
import android.widget.*;
import java.time.Instant;
import java.util.*;

final class HistoryScreen {
    final LinearLayout root;private final MainActivity host;private final LinearLayout list;private final Spinner sport,date;
    HistoryScreen(MainActivity host){this.host=host;root=SportUi.column(host);SportUi.caption(root,"YOUR LOCAL LIBRARY");SportUi.heading(root,"Sessions",32);SportUi.caption(root,"Review motion, annotate drills, or compare two recordings.");
        SportUi.button(root,"Download watch recordings",false,host::watchLibrary).setTag("watch-library");
        sport=SportUi.choices(root,new String[]{"All sports","Badminton","Tennis","Running","Other","Not specified"});sport.setSelection(host.filterSport);
        date=SportUi.choices(root,new String[]{"All dates","Last 7 days","Last 30 days"});date.setSelection(host.filterDate);
        SportUi.button(root,"Compare selected sessions",true,()->{
            List<SessionStore.Session> selected=new ArrayList<>();for(SessionStore.Session s:host.sessions)if(host.comparing.contains(s.csv.getName()))selected.add(s);
            if(selected.size()!=2){host.message("Select two readable sessions to compare");return;}host.compare(selected.get(0),selected.get(1));
        }).setTag("compare");
        list=SportUi.column(host);root.addView(list);
        AdapterView.OnItemSelectedListener change=new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,android.view.View v,int pos,long id){host.filterSport=sport.getSelectedItemPosition();host.filterDate=date.getSelectedItemPosition();render();}public void onNothingSelected(AdapterView<?> p){}};
        sport.setOnItemSelectedListener(change);date.setOnItemSelectedListener(change);render();
    }
    private void render(){list.removeAllViews();int count=0;
        for(SessionStore.Session s:host.sessions){if(host.filterSport>0&&!sport.getSelectedItem().equals(s.sport()))continue;
            if(host.filterDate>0){long started;try{started=Instant.parse(s.metadata.optString("startedUtc")).toEpochMilli();}catch(Exception e){started=s.csv.lastModified();}
                if(started<System.currentTimeMillis()-(host.filterDate==1?7L:30L)*86400000)continue;}
            count++;LinearLayout card=SportUi.card(list);SportUi.caption(card,SportUi.date(s));SportUi.heading(card,s.sport(),23);SportUi.caption(card,s.wrist()+" wrist · "+s.outcome());
            if(s.stats!=null){SportUi.text(card,SportUi.duration(s.stats.durationSeconds)+"  /  "+s.stats.count+" samples",17,SportUi.MINT);
                CheckBox select=new CheckBox(host);select.setText("Compare this session");select.setTag("select:"+s.csv.getName());select.setTextColor(SportUi.TEXT);select.setChecked(host.comparing.contains(s.csv.getName()));card.addView(select);
                select.setOnCheckedChangeListener((b,checked)->{if(checked&&host.comparing.size()>=2){select.setChecked(false);host.message("Choose at most two sessions");}else if(checked)host.comparing.add(s.csv.getName());else host.comparing.remove(s.csv.getName());});
                SportUi.button(card,"Open analysis  →",false,()->host.detail(s)).setTag("detail:"+s.csv.getName());
            }else SportUi.text(card,"Could not analyze: "+s.error,14,SportUi.CORAL);
            if(!s.notes().isEmpty())SportUi.caption(card,s.notes());
            SportUi.button(card,"Edit notes",false,()->notes(s));
            SportUi.button(card,"Export CSV",false,()->host.export(s,false));
            if(s.stats!=null)SportUi.button(card,"Export complete session ZIP",false,()->host.export(s,true));
        }
        if(count==0){LinearLayout empty=SportUi.card(list);SportUi.heading(empty,"No sessions here yet",20);SportUi.caption(empty,host.sessions.isEmpty()?"Stop and save your first recording. It will appear here with its analysis.":"Try another sport or date filter.");}
    }
    private void notes(SessionStore.Session s){EditText text=new EditText(host);text.setText(s.notes());text.setMinLines(3);text.setHint("Drills, conditions, or observations");new AlertDialog.Builder(host).setTitle("Session notes").setView(text).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{String notes=text.getText().toString();host.io.execute(()->{
        try{host.store.notes(s,notes);host.handler.post(()->{if(host.isDestroyed())return;host.refreshSessions();host.message("Notes saved");});}catch(Exception e){host.handler.post(()->{if(!host.isDestroyed())host.message("Notes not saved: "+e.getMessage());});}
    });}).show();}
}
