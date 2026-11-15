#include "motion_service.hpp"
#include "record_format.hpp"
#include "high_rate.hpp"
#include "qmi_fifo.hpp"
#include <SensorQMI8658.hpp>
#include "bsp/esp-bsp.h"
#include "esp_vfs_fat.h"
#include "esp_partition.h"
#include "esp_rom_crc.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "esp_log.h"
#include "esp_heap_caps.h"
#include "wear_levelling.h"
#include "cJSON.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include <atomic>
#include <mutex>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>
#include <algorithm>

namespace vector_motion {
static constexpr const char* ROOT="/records";
static std::mutex state_lock,fs_lock,enqueue_lock;
static Status state;static std::array<float,6> current{};static bool has_sample=false;
static std::atomic<bool> recording{false},pending{false};
static QueueHandle_t sample_queue,command_queue;
static StaticQueue_t sample_queue_control;static std::atomic<bool> service_ready{false};static SensorQMI8658 imu;
struct Command{uint8_t action,sport,wrist;};
struct Queued{vector_format::Packet packets[128];uint16_t count;};
static Queued *sensor_batch,*writer_batch;static QmiFifo fifo;
static std::atomic<uint32_t> fifo_overflows{0},read_errors{0};static std::atomic<uint64_t> fifo_pause_us{0};
static uint8_t* file_buffer=nullptr;
static FILE *file=nullptr;static std::string id;static uint64_t written=0,limit=0,started_us=0;static uint32_t crc=0;static std::atomic<uint32_t> dropped{0};
static FILE *download=nullptr;static std::string download_id;static std::atomic<int64_t> transfer_deadline{0};
static void message(const std::string&text){std::lock_guard<std::mutex> guard(state_lock);state.message=text;}
static std::string path(const std::string&key,const char*suffix){return std::string(ROOT)+"/"+key+suffix;}
static void refresh_space(){uint64_t total=0,free=0;if(esp_vfs_fat_info(ROOT,&total,&free)==ESP_OK){std::lock_guard<std::mutex> g(state_lock);state.total_bytes=total;state.free_bytes=free;}}
static bool scan_file(const std::string&key,Saved &out,bool repair){
    std::string name=path(key,".vmo");FILE *f=std::fopen(name.c_str(),repair?"r+b":"rb");if(!f)return false;
    vector_format::Header header{};bool okay=fread(header.data(),1,64,f)==64&&vector_format::valid_header(header.data(),64);
    if(!okay){fclose(f);return false;}
    fseek(f,0,SEEK_END);long size=ftell(f);size_t good=vector_format::complete_size(size<0?0:static_cast<size_t>(size));
    if(good<64 || (!repair&&good!=static_cast<size_t>(size))){fclose(f);return false;}
    if(good!=static_cast<size_t>(size)&&ftruncate(fileno(f),good)!=0){fclose(f);return false;}
    rewind(f);uint8_t block[512];uint32_t sum=0;size_t remaining=good;
    while(remaining){size_t n=fread(block,1,std::min(remaining,sizeof(block)),f);if(!n){fclose(f);return false;}sum=esp_rom_crc32_le(sum,block,n);remaining-=n;}
    if(good>=64+52){uint8_t first[26],last[26];if(fseek(f,64,SEEK_SET)!=0||fread(first,1,26,f)!=26||fseek(f,good-26,SEEK_SET)!=0||fread(last,1,26,f)!=26){fclose(f);return false;}uint64_t begin=vector_format::u64(first+6),end=vector_format::u64(last+6);if(end>begin)out.seconds=(end-begin)/1e6;}
    fclose(f);out.id=key;out.bytes=good;out.crc=sum;out.samples=(good-64)/26;out.started=vector_format::u64(header.data()+16);out.sport=header[12];out.wrist=header[13];std::memcpy(&out.nominal_hz,header.data()+48,4);out.timing_mode=header[14];return true;
}
static bool save_info(const Saved&s){
    cJSON *j=cJSON_CreateObject();cJSON_AddStringToObject(j,"id",s.id.c_str());cJSON_AddNumberToObject(j,"bytes",s.bytes);cJSON_AddNumberToObject(j,"crc",s.crc);cJSON_AddNumberToObject(j,"samples",s.samples);
    cJSON_AddNumberToObject(j,"start",s.started);cJSON_AddNumberToObject(j,"sport",s.sport);cJSON_AddNumberToObject(j,"wrist",s.wrist);cJSON_AddStringToObject(j,"outcome",s.outcome.c_str());
    cJSON_AddNumberToObject(j,"nominalHz",s.nominal_hz);cJSON_AddNumberToObject(j,"timingMode",s.timing_mode);cJSON_AddNumberToObject(j,"fifoOverflows",s.fifo_overflows);cJSON_AddNumberToObject(j,"readErrors",s.read_errors);cJSON_AddNumberToObject(j,"fifoReadPauseUs",s.fifo_pause_us);cJSON_AddNumberToObject(j,"queueDroppedSamples",s.queue_dropped);
    char *text=cJSON_PrintUnformatted(j);cJSON_Delete(j);if(!text)return false;std::string tmp=path(s.id,".tmp"),dest=path(s.id,".info");FILE*f=fopen(tmp.c_str(),"wb");bool okay=false;
    if(f){okay=fwrite(text,1,strlen(text),f)==strlen(text)&&fflush(f)==0&&fsync(fileno(f))==0;okay=fclose(f)==0&&okay;}cJSON_free(text);if(okay)okay=rename(tmp.c_str(),dest.c_str())==0;if(!okay)unlink(tmp.c_str());return okay;
}
static bool read_info(const std::string&key,Saved&out){
    FILE*f=fopen(path(key,".info").c_str(),"rb");if(!f)return false;char buffer[512]{};size_t n=fread(buffer,1,sizeof(buffer)-1,f);fclose(f);if(!n)return false;cJSON*j=cJSON_Parse(buffer);if(!j)return false;
    const cJSON *bytes=cJSON_GetObjectItem(j,"bytes"),*sum=cJSON_GetObjectItem(j,"crc"),*samples=cJSON_GetObjectItem(j,"samples"),*date=cJSON_GetObjectItem(j,"start"),*sport=cJSON_GetObjectItem(j,"sport"),*wrist=cJSON_GetObjectItem(j,"wrist"),*outcome=cJSON_GetObjectItem(j,"outcome");
    struct stat st{};bool okay=cJSON_IsNumber(bytes)&&cJSON_IsNumber(sum)&&cJSON_IsNumber(samples)&&cJSON_IsNumber(date)&&cJSON_IsNumber(sport)&&cJSON_IsNumber(wrist)&&cJSON_IsString(outcome)&&stat(path(key,".vmo").c_str(),&st)==0&&st.st_size==bytes->valuedouble;
    if(okay){out.id=key;out.bytes=bytes->valuedouble;out.crc=sum->valuedouble;out.samples=samples->valuedouble;out.started=date->valuedouble;out.sport=sport->valueint;out.wrist=wrist->valueint;out.outcome=outcome->valuestring;}cJSON_Delete(j);return okay;
}
static std::vector<std::string> keys(){std::vector<std::string> result;DIR *dir=opendir(ROOT);if(!dir)return result;dirent *entry;while((entry=readdir(dir))){std::string name=entry->d_name;if(name.size()==20&&name.substr(16)==".vmo"&&vector_format::valid_id(name.substr(0,16)))result.push_back(name.substr(0,16));}closedir(dir);std::sort(result.begin(),result.end());return result;}
static void finish(const char*reason){
    if(!file){return;}{std::lock_guard<std::mutex> guard(enqueue_lock);pending=true;recording=false;}
    uint8_t quality[6]{};vector_format::put(quality,std::min(uint32_t(65535),fifo_overflows.load()),2);vector_format::put(quality+2,std::min(uint32_t(65535),read_errors.load()),2);vector_format::put(quality+4,std::min(uint32_t(65535),dropped.load()),2);
    const uint8_t finalized=1;bool quality_saved=fseek(file,58,SEEK_SET)==0&&fwrite(quality,1,6,file)==6&&fflush(file)==0&&fsync(fileno(file))==0;
    quality_saved=quality_saved&&fseek(file,15,SEEK_SET)==0&&fwrite(&finalized,1,1,file)==1;
    bool durable=quality_saved&&fflush(file)==0&&fsync(fileno(file))==0;durable=fclose(file)==0&&durable;file=nullptr;
    Saved saved;bool okay=scan_file(id,saved,!durable);if(okay){saved.outcome=durable?reason:"Write interrupted";saved.fifo_overflows=fifo_overflows;saved.read_errors=read_errors;saved.fifo_pause_us=fifo_pause_us;saved.queue_dropped=dropped;if(fifo_overflows||read_errors)saved.outcome+="; acquisition loss detected";okay=save_info(saved);ESP_LOGI("VectorMotion","Saved session %s: %llu samples / %.6f seconds = %.2f Hz; FIFO=%lu read_errors=%lu queue_drops=%lu CRC=%08lx",saved.id.c_str(),static_cast<unsigned long long>(saved.samples),saved.seconds,saved.seconds>0?(saved.samples-1)/saved.seconds:0,static_cast<unsigned long>(saved.fifo_overflows),static_cast<unsigned long>(saved.read_errors),static_cast<unsigned long>(saved.queue_dropped),static_cast<unsigned long>(saved.crc));}
    recording=false;pending=false;refresh_space();std::lock_guard<std::mutex> g(state_lock);state.recording=false;state.message=okay?std::string(reason)+" - saved on watch":"Save error; retain watch files for recovery";
}
static void start_file(uint8_t sport,uint8_t wrist){
    std::lock_guard<std::mutex> disk(fs_lock);refresh_space();Status st=status();if(!st.storage_ready||recording||transfer_active()||st.free_bytes<256*1024){pending=false;message("Cannot start: storage low or transfer active");return;}
    char name[17];for(int attempt=0;attempt<10&&!file;attempt++){snprintf(name,sizeof(name),"%08lx%08lx",static_cast<unsigned long>(esp_random()),static_cast<unsigned long>(esp_random()));id=name;struct stat prior{};if(stat(path(id,".vmo").c_str(),&prior)==0)continue;file=fopen(path(id,".vmo").c_str(),"wb");}
    if(!file){pending=false;message("Could not create recording");return;}setvbuf(file,reinterpret_cast<char*>(file_buffer),_IOFBF,32768);started_us=esp_timer_get_time();time_t now=time(nullptr);uint64_t utc=now>1577836800?now:0;
    auto header=vector_format::header(id,sport,wrist,utc,started_us,vector_high_rate::RATE,1);written=64;limit=st.free_bytes-128*1024;crc=esp_rom_crc32_le(0,header.data(),64);dropped=0;fifo_overflows=0;read_errors=0;fifo_pause_us=0;xQueueReset(sample_queue);
    if(fwrite(header.data(),1,64,file)!=64){finish("Storage write failed");return;}
    {std::lock_guard<std::mutex> g(state_lock);state.recording=true;state.sport=sport;state.wrist=wrist;state.samples=0;state.seconds=0;state.dropped=0;state.message="Recording on internal flash";}recording=true;pending=false;
}
static void write_batch(const Queued& batch){
    std::lock_guard<std::mutex> disk(fs_lock);
    if(!file)return;
    size_t first=0;while(first<batch.count&&vector_format::u64(batch.packets[first].data()+6)<started_us)first++;
    if(first==batch.count)return;
    size_t count=std::min(size_t(batch.count-first),size_t((limit-written)/26));
    if(count){size_t bytes=count*26;const uint8_t* data=batch.packets[first].data();
        if(fwrite(data,1,bytes,file)!=bytes){finish("Storage write failed");return;}
        written+=bytes;crc=esp_rom_crc32_le(crc,data,bytes);uint64_t us=vector_format::u64(batch.packets[first+count-1].data()+6);
        {std::lock_guard<std::mutex> g(state_lock);state.samples=(written-64)/26;state.seconds=(us-started_us)/1e6;state.dropped=dropped;}
        static uint64_t last_flush=0;
        if(us-last_flush>2000000){last_flush=us;if(fflush(file)!=0||fsync(fileno(file))!=0){finish("Storage write failed");return;}refresh_space();}
    }
    if(count<batch.count-first)finish("Storage full");
}
static void writer_task(void*){
    while(true){Command command{};
        while(xQueueReceive(command_queue,&command,0)==pdTRUE){
            if(command.action==1)start_file(command.sport,command.wrist);
            else if(command.action==2){while(xQueueReceive(sample_queue,writer_batch,0)==pdTRUE)write_batch(*writer_batch);std::lock_guard<std::mutex> disk(fs_lock);finish("Stopped");}
        }
        if(xQueueReceive(sample_queue,writer_batch,pdMS_TO_TICKS(10))==pdTRUE)write_batch(*writer_batch);
    }
}
static void sensor_task(void*){
    uint32_t seq=0,preview_seq=0;uint32_t consecutive_errors=0;uint64_t last_preview=0,diagnostic_start=esp_timer_get_time(),frames=0;vector_high_rate::BatchClock clock;uint8_t raw[1536];
    while(true){uint64_t anchor=0,pause=0;bool overflow=false;int count=fifo.drain(raw,anchor,overflow,pause);
        if(count<0){consecutive_errors++;if(recording){read_errors++;if(consecutive_errors>=3)stop();}ESP_LOGE("VectorMotion","FIFO acquisition error %d",count);vTaskDelay(pdMS_TO_TICKS(10));continue;}
        if(count>0){consecutive_errors=0;frames+=count;uint64_t first=clock.first(anchor,count);if(!first){if(recording)read_errors++;ESP_LOGE("VectorMotion","Rejected overlapping FIFO timestamp anchor");continue;}sensor_batch->count=count;
            if(recording){fifo_pause_us+=pause;if(overflow)fifo_overflows++;}
            for(int i=0;i<count;i++){auto axes=vector_high_rate::decode(raw+i*12);uint64_t us=clock.at(first,i);bool clipped=false;for(int k=0;k<6;k++)if(std::fabs(axes[k])>=(k<3?15.8:1010))clipped=true;
                sensor_batch->packets[i]=vector_format::packet(seq++,us,axes,clipped);
                if(i==count-1){std::lock_guard<std::mutex> g(state_lock);current=axes;has_sample=true;}
            }
            {std::lock_guard<std::mutex> guard(enqueue_lock);if(recording&&xQueueSend(sample_queue,sensor_batch,0)!=pdTRUE)dropped+=count;}
            ble_frames(sensor_batch->packets[0].data(),count);
            // A separate preview sequence avoids misreporting intentional decimation as transport loss.
            if(anchor-last_preview>=10000){last_preview=anchor;auto packet=sensor_batch->packets[count-1];packet[0]=2;vector_format::put(packet.data()+2,preview_seq++,4);ble_live(packet.data(),packet.size());}
        }
        int64_t now=esp_timer_get_time();if(now-diagnostic_start>=5000000){ESP_LOGI("VectorMotion","FIFO capture: %llu frames / %lld us, recording=%d, queue drops=%lu",static_cast<unsigned long long>(frames),static_cast<long long>(now-diagnostic_start),int(recording.load()),static_cast<unsigned long>(dropped.load()));frames=0;diagnostic_start=now;}
        vTaskDelay(pdMS_TO_TICKS(2));
    }
}
static esp_err_t storage_init(){
    const esp_partition_t*part=esp_partition_find_first(ESP_PARTITION_TYPE_DATA,ESP_PARTITION_SUBTYPE_DATA_FAT,"records");if(!part)return ESP_ERR_NOT_FOUND;
    // Wear levelling itself writes metadata during mount. Check erasure BEFORE mounting.
    uint8_t bytes[4096];bool blank=true;
    for(size_t offset=0;offset<part->size&&blank;offset+=sizeof(bytes)){
        if(esp_partition_read(part,offset,bytes,sizeof(bytes))!=ESP_OK){return ESP_FAIL;}
        for(uint8_t value:bytes)if(value!=0xff){blank=false;break;}
    }
    esp_vfs_fat_mount_config_t config{};config.format_if_mount_failed=blank;config.max_files=8;config.allocation_unit_size=4096;wl_handle_t handle=WL_INVALID_HANDLE;
    esp_err_t result=esp_vfs_fat_spiflash_mount_rw_wl(ROOT,"records",&config,&handle);
    if(result==ESP_OK){for(const auto&key:keys()){Saved item;if(!read_info(key,item)&&scan_file(key,item,true)){item.outcome="Interrupted";save_info(item);}}state.storage_ready=true;refresh_space();}
    return result;
}
esp_err_t start(){
    esp_err_t storage=storage_init();message(storage==ESP_OK?"Ready - internal flash":"Storage unavailable; no automatic erase");
    uint8_t address=QMI8658_L_SLAVE_ADDRESS;
    if(!imu.begin(bsp_i2c_get_handle(),address)){address=QMI8658_H_SLAVE_ADDRESS;if(!imu.begin(bsp_i2c_get_handle(),address))return ESP_ERR_NOT_FOUND;}
    // ODR code2 is the datasheet's 1793.6Hz 6DOF mode (absent from this vendor AccelODR enum).
    if(imu.configAccelerometer(SensorQMI8658::ACC_RANGE_16G,static_cast<SensorQMI8658::AccelODR>(2),SensorQMI8658::LPF_MODE_3)!=0||imu.configGyroscope(SensorQMI8658::GYR_RANGE_1024DPS,SensorQMI8658::GYR_ODR_1793_6Hz,SensorQMI8658::LPF_MODE_3)!=0)return ESP_FAIL;
    if(!imu.enableGyroscope()||!imu.enableAccelerometer())return ESP_FAIL;
    if(imu.configFIFO(SensorQMI8658::FIFO_MODE_STREAM,SensorQMI8658::FIFO_SAMPLES_128,SensorQMI8658::INTERRUPT_PIN_DISABLE,64)!=0||!fifo.begin(bsp_i2c_get_handle(),address))return ESP_FAIL;
    ESP_LOGI("VectorMotion","Internal heap free/largest: %u/%u",static_cast<unsigned>(heap_caps_get_free_size(MALLOC_CAP_INTERNAL)),static_cast<unsigned>(heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL)));
    // Only task contexts touch this queue. Flash operations pause the other core while PSRAM is inaccessible.
    auto *sample_buffer=static_cast<uint8_t*>(heap_caps_malloc(32*sizeof(Queued),MALLOC_CAP_SPIRAM|MALLOC_CAP_8BIT));
    sensor_batch=static_cast<Queued*>(heap_caps_malloc(sizeof(Queued),MALLOC_CAP_SPIRAM|MALLOC_CAP_8BIT));writer_batch=static_cast<Queued*>(heap_caps_malloc(sizeof(Queued),MALLOC_CAP_SPIRAM|MALLOC_CAP_8BIT));
    file_buffer=static_cast<uint8_t*>(heap_caps_malloc(32768,MALLOC_CAP_SPIRAM|MALLOC_CAP_8BIT));
    if(!sample_buffer||!sensor_batch||!writer_batch||!file_buffer){message("Recorder unavailable: sample buffer allocation failed");return ESP_ERR_NO_MEM;}
    sample_queue=xQueueCreateStatic(32,sizeof(Queued),sample_buffer,&sample_queue_control);command_queue=xQueueCreate(8,sizeof(Command));if(!sample_queue||!command_queue){message("Recorder unavailable: queue allocation failed");return ESP_ERR_NO_MEM;}
    if(xTaskCreatePinnedToCore(writer_task,"motion-store",6144,nullptr,4,nullptr,0)!=pdPASS||xTaskCreatePinnedToCore(sensor_task,"motion-sensor",6144,nullptr,5,nullptr,1)!=pdPASS){message("Recorder unavailable: task stack allocation failed");return ESP_ERR_NO_MEM;}
    service_ready=true;return ESP_OK;
}
bool latest(float axes[6]){std::lock_guard<std::mutex>g(state_lock);if(!has_sample)return false;std::copy(current.begin(),current.end(),axes);return true;}
Status status(){std::lock_guard<std::mutex>g(state_lock);return state;}
bool begin(uint8_t sport,uint8_t wrist){if(!service_ready){message("Recorder not ready; check device diagnostics");return false;}float axes[6];if(!latest(axes)){message("Sensor not producing samples; cannot record");return false;}if(recording||pending.exchange(true))return false;Command c{1,sport,wrist};if(sport>3||wrist>1||xQueueSend(command_queue,&c,0)!=pdTRUE){pending=false;return false;}message("Starting recording...");return true;}
void stop(){
    if(!service_ready)return;
    {std::lock_guard<std::mutex> guard(enqueue_lock);if(!recording.exchange(false))return;pending=true;}
    message("Saving watch session...");Command c{2,0,0};xQueueSend(command_queue,&c,portMAX_DELAY);
}
std::vector<Saved> inventory(){std::lock_guard<std::mutex>disk(fs_lock);std::vector<Saved>out;if(recording||pending)return out;for(const auto&key:keys()){Saved item;if(read_info(key,item))out.push_back(item);}return out;}
bool transfer_active(){return esp_timer_get_time()<transfer_deadline.load();}
bool clear_saved(){std::lock_guard<std::mutex>disk(fs_lock);if(recording||pending||transfer_active())return false;if(download){fclose(download);download=nullptr;}bool okay=true;for(const auto&key:keys()){okay=unlink(path(key,".vmo").c_str())==0&&okay;unlink(path(key,".info").c_str());}refresh_space();message(okay?"Saved recordings deleted":"Some files could not be deleted");return okay;}
// Called by the BLE host for display commands and disconnect cleanup.
static int brightness_before_dim=-1;

static bool set_display_dimmed(bool dimmed){
    if(!dimmed && brightness_before_dim<0)return true;
    if(!service_ready || !bsp_display_lock(100))return false;

    esp_err_t result=ESP_OK;
    if(dimmed && brightness_before_dim<0){
        int brightness=bsp_display_brightness_get();
        if(brightness<0)result=ESP_ERR_INVALID_STATE;
        else{
            result=bsp_display_brightness_set(0);
            if(result==ESP_OK)brightness_before_dim=brightness;
        }
    }else if(!dimmed){
        result=bsp_display_brightness_set(brightness_before_dim);
        if(result==ESP_OK)brightness_before_dim=-1;
    }
    bsp_display_unlock();
    return result==ESP_OK;
}

void restore_display(){
    if(!set_display_dimmed(false))ESP_LOGW("VectorMotion","Could not restore display on disconnect");
}

std::string control(const std::string&command,std::vector<uint8_t>&chunk,size_t capacity){
    if(command=="DISPLAY DIM"||command=="DISPLAY RESTORE"){
        if(!set_display_dimmed(command=="DISPLAY DIM"))
            return "{\"kind\":\"error\",\"message\":\"Could not change watch display\"}";
        return brightness_before_dim>=0
            ? "{\"kind\":\"display\",\"dimmed\":true}"
            : "{\"kind\":\"display\",\"dimmed\":false}";
    }
    if(command.rfind("TIME ",0)==0){uint64_t value=strtoull(command.c_str()+5,nullptr,10);if(value<1577836800||value>4102444800ULL)return "{\"kind\":\"error\",\"message\":\"Invalid time\"}";timeval tv{static_cast<time_t>(value),0};settimeofday(&tv,nullptr);
        return brightness_before_dim>=0
            ? "{\"kind\":\"time\",\"display_dimmed\":true}"
            : "{\"kind\":\"time\",\"display_dimmed\":false}";}
    if(command=="CLOSE"){std::lock_guard<std::mutex> disk(fs_lock);if(download){fclose(download);download=nullptr;}transfer_deadline=0;return "{\"kind\":\"closed\"}";}
    if(recording||pending)return "{\"kind\":\"error\",\"message\":\"Stop watch recording before transfer\"}";
    if(command.rfind("LIST ",0)==0){auto items=inventory();unsigned index=strtoul(command.c_str()+5,nullptr,10);if(index>=items.size())return "{\"kind\":\"end\"}";
        const auto&s=items[index];cJSON*j=cJSON_CreateObject();cJSON_AddStringToObject(j,"kind","item");cJSON_AddStringToObject(j,"id",s.id.c_str());cJSON_AddNumberToObject(j,"bytes",s.bytes);cJSON_AddNumberToObject(j,"crc",s.crc);cJSON_AddNumberToObject(j,"samples",s.samples);cJSON_AddNumberToObject(j,"start",s.started);cJSON_AddNumberToObject(j,"sport",s.sport);cJSON_AddNumberToObject(j,"wrist",s.wrist);cJSON_AddStringToObject(j,"outcome",s.outcome.c_str());char*text=cJSON_PrintUnformatted(j);std::string result=text?text:"{}";cJSON_free(text);cJSON_Delete(j);return result;}
    std::lock_guard<std::mutex> disk(fs_lock);
    if(command.rfind("GET ",0)==0){std::string key=command.substr(4);Saved saved;if(!vector_format::valid_id(key)||!read_info(key,saved))return "{\"kind\":\"error\",\"message\":\"Session unavailable\"}";if(download)fclose(download);download=fopen(path(key,".vmo").c_str(),"rb");download_id=key;transfer_deadline=esp_timer_get_time()+10000000;return download?"{\"kind\":\"ready\"}":"{\"kind\":\"error\"}";}
    if(command.rfind("NEXT ",0)==0&&download){uint64_t offset=strtoull(command.c_str()+5,nullptr,10);Saved saved;if(!read_info(download_id,saved)||offset>saved.bytes||capacity<=8)return "{\"kind\":\"error\"}";
        chunk.resize(std::min(static_cast<size_t>(saved.bytes-offset),capacity-8)+8);chunk[0]=0xa1;chunk[1]=1;vector_format::put(chunk.data()+2,offset,4);vector_format::put(chunk.data()+6,chunk.size()-8,2);fseek(download,offset,SEEK_SET);if(fread(chunk.data()+8,1,chunk.size()-8,download)!=chunk.size()-8){chunk.clear();return "{\"kind\":\"error\"}";}transfer_deadline=esp_timer_get_time()+10000000;return "{\"kind\":\"chunk\"}";}
    if(command=="CLOSE"){if(download){fclose(download);download=nullptr;}transfer_deadline=0;return "{\"kind\":\"closed\"}";}
    return "{\"kind\":\"error\",\"message\":\"Unsupported command\"}";
}
}
