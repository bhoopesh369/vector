#include "MotionRecorder.hpp"
#include "motion_service.hpp"
#include "esp_brookesia.hpp"
#include <cstdio>

LV_IMG_DECLARE(img_app_specanalyzer);
namespace esp_brookesia::apps {
namespace {
constexpr uint32_t BG=0x0A0F18, PANEL=0x17232D, TEXT=0xF4F7FA, MUTED=0xA8BACB, MINT=0x63EBBC, CORAL=0xFF8178;
const char* SPORTS[]={"Badminton","Tennis","Running","Other"};
lv_obj_t* label(lv_obj_t* parent,const char* text,const lv_font_t* font,uint32_t color){
    auto* object=lv_label_create(parent);lv_label_set_text(object,text);lv_obj_set_style_text_font(object,font,0);lv_obj_set_style_text_color(object,lv_color_hex(color),0);return object;
}
lv_obj_t* action(lv_obj_t* parent,const char* text,lv_event_cb_t callback,void* user,uint32_t color=PANEL,int height=48){
    auto* object=lv_button_create(parent);lv_obj_set_size(object,LV_PCT(100),height);lv_obj_set_style_radius(object,16,0);lv_obj_set_style_bg_color(object,lv_color_hex(color),0);lv_obj_set_style_shadow_width(object,0,0);
    auto* caption=label(object,text,&lv_font_montserrat_18,TEXT);lv_obj_center(caption);lv_obj_add_event_cb(object,callback,LV_EVENT_CLICKED,user);return object;
}
void enable(lv_obj_t* object,bool enabled){if(enabled)lv_obj_remove_state(object,LV_STATE_DISABLED);else lv_obj_add_state(object,LV_STATE_DISABLED);}
}
static MotionRecorder* instance=nullptr;
MotionRecorder* MotionRecorder::requestInstance(){if(!instance)instance=new MotionRecorder();return instance;}
MotionRecorder::MotionRecorder():App("Motion Recorder",&img_app_specanalyzer,true,true,true){}
bool MotionRecorder::init(){return true;}
bool MotionRecorder::deinit(){destroy();return true;}
bool MotionRecorder::run(){
    root=lv_screen_active();library=false;render();
    refresh=lv_timer_create([](lv_timer_t* t){static_cast<MotionRecorder*>(lv_timer_get_user_data(t))->update();},500,this);return true;
}
void MotionRecorder::render(){
    info=timer=button=sport=wrist=stateLabel=count=nullptr;
    lv_obj_clean(root);lv_obj_set_style_bg_color(root,lv_color_hex(BG),0);lv_obj_set_style_pad_all(root,10,0);lv_obj_set_style_pad_row(root,8,0);lv_obj_set_flex_flow(root,LV_FLEX_FLOW_COLUMN);lv_obj_remove_flag(root,LV_OBJ_FLAG_SCROLLABLE);
    auto st=vector_motion::status();
    if(library){
        action(root,"← Back to recorder",[](lv_event_t* e){auto* self=static_cast<MotionRecorder*>(lv_event_get_user_data(e));self->library=false;self->render();},this,PANEL,40);
        label(root,"Saved on watch",&lv_font_montserrat_24,TEXT);
        char text[100];snprintf(text,sizeof(text),"%.1f MB free · files stay after download",st.free_bytes/1048576.0);label(root,text,&lv_font_montserrat_14,MUTED);
        auto* list=lv_obj_create(root);lv_obj_set_width(list,LV_PCT(100));lv_obj_set_flex_grow(list,1);lv_obj_set_flex_flow(list,LV_FLEX_FLOW_COLUMN);lv_obj_set_style_bg_color(list,lv_color_hex(BG),0);lv_obj_set_style_border_width(list,0,0);lv_obj_set_style_pad_all(list,0,0);
        if(st.recording){auto* l=label(list,"Recording continues. Stop & save before downloading.",&lv_font_montserrat_18,MINT);lv_obj_set_width(l,LV_PCT(100));}
        else {
            auto files=vector_motion::inventory();
            if(files.empty()){auto* l=label(list,"No saved sessions yet.\nStart your first recording.",&lv_font_montserrat_18,MUTED);lv_obj_set_width(l,LV_PCT(100));}
            for(const auto& file:files){auto* card=lv_obj_create(list);lv_obj_set_width(card,LV_PCT(100));lv_obj_set_height(card,LV_SIZE_CONTENT);lv_obj_set_style_bg_color(card,lv_color_hex(PANEL),0);lv_obj_set_style_border_width(card,0,0);lv_obj_set_flex_flow(card,LV_FLEX_FLOW_COLUMN);
                label(card,file.sport<4?SPORTS[file.sport]:"Other",&lv_font_montserrat_18,TEXT);
                snprintf(text,sizeof(text),"%lu samples · %s",static_cast<unsigned long>(file.samples),file.outcome.c_str());auto* l=label(card,text,&lv_font_montserrat_14,MUTED);lv_obj_set_width(l,LV_PCT(100));
            }
        }
        auto* hint=label(root,"Phone: Vector Motion → Watch recordings",&lv_font_montserrat_14,MUTED);lv_obj_set_width(hint,LV_PCT(100));
        auto* clear=action(root,"Free storage…",[](lv_event_t* e){static_cast<MotionRecorder*>(lv_event_get_user_data(e))->confirmDelete();},this,PANEL,40);enable(clear,!st.recording);return;
    }
    auto* header=lv_obj_create(root);lv_obj_set_size(header,LV_PCT(100),28);lv_obj_set_style_bg_opa(header,LV_OPA_TRANSP,0);lv_obj_set_style_border_width(header,0,0);lv_obj_set_style_pad_all(header,0,0);lv_obj_remove_flag(header,LV_OBJ_FLAG_SCROLLABLE);
    auto* brand=label(header,"VECTOR",&lv_font_montserrat_18,MUTED);lv_obj_align(brand,LV_ALIGN_LEFT_MID,0,0);
    stateLabel=label(header,"READY",&lv_font_montserrat_14,MINT);lv_obj_align(stateLabel,LV_ALIGN_RIGHT_MID,0,0);
    auto* hero=lv_obj_create(root);lv_obj_set_width(hero,LV_PCT(100));lv_obj_set_flex_grow(hero,1);lv_obj_set_style_bg_color(hero,lv_color_hex(PANEL),0);lv_obj_set_style_border_width(hero,0,0);lv_obj_set_style_radius(hero,20,0);lv_obj_remove_flag(hero,LV_OBJ_FLAG_SCROLLABLE);
    timer=label(hero,"00:00",&lv_font_montserrat_40,TEXT);lv_obj_align(timer,LV_ALIGN_CENTER,0,-14);
    count=label(hero,"Ready when you are",&lv_font_montserrat_14,MUTED);lv_obj_align(count,LV_ALIGN_CENTER,0,28);
    auto* selectors=lv_obj_create(root);lv_obj_set_size(selectors,LV_PCT(100),46);lv_obj_set_style_bg_opa(selectors,LV_OPA_TRANSP,0);lv_obj_set_style_border_width(selectors,0,0);lv_obj_set_style_pad_all(selectors,0,0);lv_obj_set_style_pad_column(selectors,8,0);lv_obj_set_flex_flow(selectors,LV_FLEX_FLOW_ROW);lv_obj_remove_flag(selectors,LV_OBJ_FLAG_SCROLLABLE);
    sport=lv_dropdown_create(selectors);lv_dropdown_set_options(sport,"Badminton\nTennis\nRunning\nOther");lv_obj_set_width(sport,LV_PCT(58));lv_obj_set_height(sport,46);lv_dropdown_set_selected(sport,selectedSport);
    wrist=lv_dropdown_create(selectors);lv_dropdown_set_options(wrist,"Right wrist\nLeft wrist");lv_obj_set_flex_grow(wrist,1);lv_obj_set_height(wrist,46);lv_dropdown_set_selected(wrist,selectedWrist);
    for(auto* selector:{sport,wrist}){lv_obj_set_style_bg_color(selector,lv_color_hex(PANEL),0);lv_obj_set_style_text_color(selector,lv_color_hex(TEXT),0);lv_obj_set_style_border_width(selector,0,0);lv_obj_set_style_text_font(selector,&lv_font_montserrat_14,0);lv_obj_add_event_cb(selector,[](lv_event_t* e){auto* self=static_cast<MotionRecorder*>(lv_event_get_user_data(e));self->selectedSport=lv_dropdown_get_selected(self->sport);self->selectedWrist=lv_dropdown_get_selected(self->wrist);},LV_EVENT_VALUE_CHANGED,this);}
    button=action(root,"Start recording",[](lv_event_t* e){auto* self=static_cast<MotionRecorder*>(lv_event_get_user_data(e));if(vector_motion::status().recording)vector_motion::stop();else vector_motion::begin(self->selectedSport,self->selectedWrist);self->update();},this,0x246A55,60);
    info=label(root,"",&lv_font_montserrat_14,MUTED);lv_obj_set_width(info,LV_PCT(100));lv_obj_set_height(info,36);lv_label_set_long_mode(info,LV_LABEL_LONG_WRAP);
    action(root,"Saved recordings  →",[](lv_event_t* e){auto* self=static_cast<MotionRecorder*>(lv_event_get_user_data(e));self->library=true;self->render();},this,PANEL,40);update();
}
void MotionRecorder::confirmDelete(){
    if(vector_motion::status().recording)return;
    dialog=lv_msgbox_create(nullptr);lv_msgbox_add_title(dialog,"Delete watch sessions?");lv_msgbox_add_text(dialog,"Download to the phone first. This deletes all motion recordings on the watch. Phone copies are kept.");lv_msgbox_add_close_button(dialog);
    lv_obj_add_event_cb(dialog,[](lv_event_t* e){auto* self=static_cast<MotionRecorder*>(lv_event_get_user_data(e));if(self->dialog==lv_event_get_target(e))self->dialog=nullptr;},LV_EVENT_DELETE,this);
    auto* confirm=lv_msgbox_add_footer_button(dialog,"Delete all");lv_obj_add_event_cb(confirm,[](lv_event_t* e){auto* self=static_cast<MotionRecorder*>(lv_event_get_user_data(e));bool okay=vector_motion::clear_saved();if(self->dialog)lv_msgbox_close(self->dialog);self->dialog=nullptr;self->render();if(!okay){self->dialog=lv_msgbox_create(nullptr);lv_msgbox_add_title(self->dialog,"Storage busy");lv_msgbox_add_text(self->dialog,"Finish recording or downloading, then try again.");lv_msgbox_add_close_button(self->dialog);lv_obj_add_event_cb(self->dialog,[](lv_event_t* event){static_cast<MotionRecorder*>(lv_event_get_user_data(event))->dialog=nullptr;},LV_EVENT_DELETE,self);}},LV_EVENT_CLICKED,this);
}
void MotionRecorder::update(){
    if(library||!timer||!info)return;
    auto st=vector_motion::status();bool busy=st.message=="Starting recording..."||st.message=="Saving watch session...";
    char text[160];unsigned seconds=static_cast<unsigned>(st.seconds);if(seconds>=3600)snprintf(text,sizeof(text),"%u:%02u:%02u",seconds/3600,(seconds/60)%60,seconds%60);else snprintf(text,sizeof(text),"%02u:%02u",seconds/60,seconds%60);lv_label_set_text(timer,text);lv_obj_align(timer,LV_ALIGN_CENTER,0,-14);
    snprintf(text,sizeof(text),"%llu samples%s",static_cast<unsigned long long>(st.samples),st.dropped?" · gaps detected":"");lv_label_set_text(count,st.samples?text:"Ready when you are");lv_obj_align(count,LV_ALIGN_CENTER,0,28);
    const bool saved=st.message.find("saved on watch")!=std::string::npos;
    const char* state=busy?(st.recording?"SAVING":"STARTING"):st.recording?"RECORDING":!st.storage_ready?"STORAGE ERROR":saved?"SAVED":"READY";
    lv_label_set_text(stateLabel,state);lv_obj_set_style_text_color(stateLabel,lv_color_hex(st.recording?CORAL:MINT),0);
    lv_label_set_text(lv_obj_get_child(button,0),busy?"Please wait…":st.recording?"Stop & save":"Start recording");lv_obj_set_style_bg_color(button,lv_color_hex(st.recording?0x9E403D:0x246A55),0);enable(button,!busy&&(st.recording||st.storage_ready));
    if(st.recording){selectedSport=st.sport;selectedWrist=st.wrist;lv_dropdown_set_selected(sport,selectedSport);lv_dropdown_set_selected(wrist,selectedWrist);}enable(sport,!st.recording&&!busy);enable(wrist,!st.recording&&!busy);
    if(st.recording)snprintf(text,sizeof(text),"Saved on watch · %.1f MB free\nYou can return Home while recording",st.free_bytes/1048576.0);
    else if(busy)snprintf(text,sizeof(text),"%s",st.message.c_str());
    else if(st.message=="Ready - internal flash")snprintf(text,sizeof(text),"No phone needed · %.1f MB free",st.free_bytes/1048576.0);
    else snprintf(text,sizeof(text),"%s",st.message.c_str());
    lv_label_set_text(info,text);
}
void MotionRecorder::destroy(){if(refresh){lv_timer_delete(refresh);refresh=nullptr;}if(dialog){lv_msgbox_close(dialog);dialog=nullptr;}root=info=timer=button=sport=wrist=stateLabel=count=nullptr;}
bool MotionRecorder::back(){if(library){library=false;render();return true;}return notifyCoreClosed();}
bool MotionRecorder::close(){destroy();return true;}
bool MotionRecorder::pause(){if(refresh)lv_timer_pause(refresh);return true;}
bool MotionRecorder::resume(){if(refresh)lv_timer_resume(refresh);update();return true;}
}
