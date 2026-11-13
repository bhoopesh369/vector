package com.vector.motion;

import android.widget.*;
import java.util.*;

final class HomeScreen {
    final LinearLayout root;private final TextView connection;
    HomeScreen(MainActivity host){root=SportUi.column(host);SportUi.caption(root,"VECTOR / MOTION LAB");SportUi.heading(root,"Make movement\nmeasurable.",34);
        SportUi.caption(root,"Your training. Your raw data. Stored on your phone.");
        LinearLayout hero=SportUi.card(root);connection=SportUi.heading(hero,"Watch disconnected",16);
        SportUi.button(hero,"Start a session  →",true,()->host.navigate("Record")).setTag("start-session");
        double duration=0;int valid=0;for(SessionStore.Session s:host.sessions)if(s.stats!=null){duration+=s.stats.durationSeconds;valid++;}
        SportUi.metric(root,"YOUR RECORDINGS",String.valueOf(host.sessions.size()),String.format(Locale.US,"%s total captured time · %d readable sessions",SportUi.duration(duration),valid));
        SportUi.heading(root,"Recent sessions",21);
        if(host.sessions.isEmpty()){LinearLayout empty=SportUi.card(root);SportUi.heading(empty,"Start with a short drill",20);SportUi.caption(empty,"Record a rest period and a few deliberate movements. Add markers so each drill is easy to find later.");}
        for(int i=0;i<Math.min(3,host.sessions.size());i++) {
            SessionStore.Session s=host.sessions.get(i);LinearLayout card=SportUi.card(root);SportUi.caption(card,SportUi.date(s));SportUi.heading(card,s.sport(),21);
            SportUi.caption(card,s.stats==null?s.error:SportUi.duration(s.stats.durationSeconds)+"  ·  "+s.stats.count+" samples");
            SportUi.button(card,"Review session",false,()->host.detail(s));
        }
        SportUi.button(root,"Download watch recordings",false,host::watchLibrary).setTag("watch-library");
        SportUi.button(root,"Browse all sessions",false,()->host.navigate("Sessions"));
        LinearLayout note=SportUi.card(root);SportUi.heading(note,"Understand what you capture",18);SportUi.caption(note,"Acceleration includes gravity. Rotation is angular velocity at the wrist. These measurements describe motion; they are not racket speed or fitness scores.");update(host.recorder);
    }
    void update(RecorderService r){connection.setText(r!=null?r.status:"Watch disconnected · connect from Record");}
}
