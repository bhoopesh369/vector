package com.vector.motion;

import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.Locale;

final class SportUi {
    static final int BG=Color.rgb(10,15,24), CARD=Color.rgb(20,29,43), TEXT=Color.rgb(237,243,250), MUTED=Color.rgb(157,176,199), MINT=Color.rgb(99,235,188), CORAL=Color.rgb(255,137,123), BLUE=Color.rgb(112,175,255);
    static int dp(Context c,float v){return Math.round(v*c.getResources().getDisplayMetrics().density);}
    static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(1);return l;}
    static LinearLayout row(Context c){LinearLayout l=new LinearLayout(c);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    static GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    static LinearLayout card(LinearLayout parent){Context c=parent.getContext();LinearLayout l=column(c);int d=dp(c,18);l.setPadding(d,d,d,d);l.setBackground(background(CARD,dp(c,20)));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(c,14);parent.addView(l,p);return l;}
    static TextView text(LinearLayout parent,String value,int size,int color){TextView t=new TextView(parent.getContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(parent.getContext(),4),0,dp(parent.getContext(),6));parent.addView(t);return t;}
    static TextView heading(LinearLayout parent,String value,int size){TextView t=text(parent,value,size,TEXT);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    static void caption(LinearLayout parent,String value){text(parent,value,13,MUTED);}
    static Button button(LinearLayout parent,String label,boolean primary,Runnable action){Context c=parent.getContext();Button b=new Button(c);b.setText(label);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(primary?BG:TEXT);b.setBackground(background(primary?MINT:Color.rgb(35,48,67),dp(c,14)));b.setMinHeight(dp(c,50));b.setPadding(dp(c,14),dp(c,10),dp(c,14),dp(c,10));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(c,8);parent.addView(b,p);b.setOnClickListener(v->action.run());return b;}
    static Spinner choices(LinearLayout parent,String[] items){Context c=parent.getContext();Spinner spinner=new Spinner(c);ArrayAdapter<String> adapter=new ArrayAdapter<String>(c,android.R.layout.simple_spinner_dropdown_item,items){
        @Override public View getView(int p,View view,ViewGroup group){TextView t=(TextView)super.getView(p,view,group);t.setTextColor(TEXT);t.setTextSize(16);t.setPadding(dp(c,12),dp(c,14),dp(c,12),dp(c,14));return t;}
    };spinner.setAdapter(adapter);spinner.setBackground(background(CARD,dp(c,12)));parent.addView(spinner,new LinearLayout.LayoutParams(-1,dp(c,54)));return spinner;}
    static String duration(double s){return String.format(Locale.US,"%02d:%02d",(long)s/60,(long)s%60);}
    static String number(double v){return String.format(Locale.US,"%.2f",v);}
    static void metric(LinearLayout parent,String label,String value,String unit){LinearLayout c=card(parent);caption(c,label);heading(c,value,30);caption(c,unit);}
    static String date(SessionStore.Session s){String utc=s.metadata.optString("startedUtc","");try{return java.time.Instant.parse(utc).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM · HH:mm"));}catch(Exception e){return "Saved "+java.time.Instant.ofEpochMilli(s.csv.lastModified()).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM · HH:mm"));}}
}
