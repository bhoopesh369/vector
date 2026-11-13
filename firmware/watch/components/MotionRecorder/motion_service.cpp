#include "motion_service.hpp"
#include "record_format.hpp"
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
struct Queued{vector_format::Packet packet;uint64_t us;};
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
    fclose(f);out.id=key;out.bytes=good;out.crc=sum;out.samples=(good-64)/26;out.started=vector_format::u64(header.data()+16);out.sport=header[12];out.wrist=header[13];return true;
}
static bool save_info(const Saved&s){
    cJSON *j=cJSON_CreateObject();cJSON_AddStringToObject(j,"id",s.id.c_str());cJSON_AddNumberToObject(j,"bytes",s.bytes);cJSON_AddNumberToObject(j,"crc",s.crc);cJSON_AddNumberToObject(j,"samples",s.samples);
    cJSON_AddNumberToObject(j,"start",s.started);cJSON_AddNumberToObject(j,"sport",s.sport);cJSON_AddNumberToObject(j,"wrist",s.wrist);cJSON_AddStringToObject(j,"outcome",s.outcome.c_str());
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
    if(!file){return;}{std::lock_guard<std::mutex> guard(enqueue_lock);pending=true;recording=false;}bool durable=fflush(file)==0&&fsync(fileno(file))==0;durable=fclose(file)==0&&durable;file=nullptr;
    Saved saved;bool okay=scan_file(id,saved,!durable);if(okay){saved.outcome=durable?reason:"Write interrupted";okay=save_info(saved);}
    recording=false;pending=false;refresh_space();std::lock_guard<std::mutex> g(state_lock);state.recording=false;state.message=okay?std::string(reason)+" - saved on watch":"Save error; retain watch files for recovery";
}
static void start_file(uint8_t sport,uint8_t wrist){
    std::lock_guard<std::mutex> disk(fs_lock);refresh_space();Status st=status();if(!st.storage_ready||recording||transfer_active()||st.free_bytes<256*1024){pending=false;message("Cannot start: storage low or transfer active");return;}
    char name[17];for(int attempt=0;attempt<10&&!file;attempt++){snprintf(name,sizeof(name),"%08lx%08lx",static_cast<unsigned long>(esp_random()),static_cast<unsigned long>(esp_random()));id=name;struct stat prior{};if(stat(path(id,".vmo").c_str(),&prior)==0)continue;file=fopen(path(id,".vmo").c_str(),"wb");}
    if(!file){pending=false;message("Could not create recording");return;}setvbuf(file,nullptr,_IOFBF,8192);started_us=esp_timer_get_time();time_t now=time(nullptr);uint64_t utc=now>1577836800?now:0;
    auto header=vector_format::header(id,sport,wrist,utc,started_us);written=64;limit=st.free_bytes-128*1024;crc=esp_rom_crc32_le(0,header.data(),64);dropped=0;xQueueReset(sample_queue);
    if(fwrite(header.data(),1,64,file)!=64){finish("Storage write failed");return;}
    {std::lock_guard<std::mutex> g(state_lock);state.recording=true;state.sport=sport;state.wrist=wrist;state.samples=0;state.seconds=0;state.dropped=0;state.message="Recording on internal flash";}recording=true;pending=false;
}
static void write_sample(const Queued& sample){
    std::lock_guard<std::mutex> disk(fs_lock);
    if(!file||sample.us<started_us)return;
    if(written+26>limit){finish("Storage full");return;}
    if(fwrite(sample.packet.data(),1,26,file)!=26){finish("Storage write failed");return;}
    written+=26;crc=esp_rom_crc32_le(crc,sample.packet.data(),26);
    {std::lock_guard<std::mutex> g(state_lock);state.samples=(written-64)/26;state.seconds=(sample.us-started_us)/1e6;state.dropped=dropped;}
    static uint64_t last_flush=0;
    if(sample.us-last_flush>1000000){last_flush=sample.us;if(fflush(file)!=0||fsync(fileno(file))!=0){finish("Storage write failed");return;}refresh_space();}
}
static void writer_task(void*){
    while(true){
        Command command{};
        while(xQueueReceive(command_queue,&command,0)==pdTRUE){
            if(command.action==1)start_file(command.sport,command.wrist);
            else if(command.action==2){
                // stop() already barred new enqueues; preserve every sample acquired before it.
                Queued queued{};while(xQueueReceive(sample_queue,&queued,0)==pdTRUE)write_sample(queued);
                std::lock_guard<std::mutex> disk(fs_lock);finish("Stopped");
            }
        }
        Queued sample{};if(xQueueReceive(sample_queue,&sample,pdMS_TO_TICKS(10))==pdTRUE)write_sample(sample);
    }
}
static void sensor_task(void*){
    uint32_t seq=0;while(true){if(imu.getDataReady()){
        std::array<float,6>a{};if(imu.getAccelerometer(a[0],a[1],a[2])&&imu.getGyroscope(a[3],a[4],a[5])){
            uint64_t us=esp_timer_get_time();bool clipped=false;for(int i=0;i<6;i++)if(std::fabs(a[i])>=(i<3?15.8:1010))clipped=true;
            auto packet=vector_format::packet(seq++,us,a,clipped);{std::lock_guard<std::mutex> g(state_lock);current=a;has_sample=true;}
            {std::lock_guard<std::mutex> guard(enqueue_lock);if(recording){Queued q{packet,us};if(xQueueSend(sample_queue,&q,0)!=pdTRUE)dropped++;}}
            ble_live(packet.data(),packet.size());
        }
    }vTaskDelay(pdMS_TO_TICKS(1));}
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
    if(!imu.begin(bsp_i2c_get_handle(),QMI8658_L_SLAVE_ADDRESS)&&!imu.begin(bsp_i2c_get_handle(),QMI8658_H_SLAVE_ADDRESS))return ESP_ERR_NOT_FOUND;
    if(imu.configAccelerometer(SensorQMI8658::ACC_RANGE_16G,SensorQMI8658::ACC_ODR_125Hz,SensorQMI8658::LPF_MODE_3)!=0||imu.configGyroscope(SensorQMI8658::GYR_RANGE_1024DPS,SensorQMI8658::GYR_ODR_112_1Hz,SensorQMI8658::LPF_MODE_3)!=0)return ESP_FAIL;
    if(!imu.enableGyroscope()||!imu.enableAccelerometer())return ESP_FAIL;
    ESP_LOGI("VectorMotion","Internal heap free/largest: %u/%u",static_cast<unsigned>(heap_caps_get_free_size(MALLOC_CAP_INTERNAL)),static_cast<unsigned>(heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL)));
    // Only task contexts touch this queue. Flash operations pause the other core while PSRAM is inaccessible.
    auto *sample_buffer=static_cast<uint8_t*>(heap_caps_malloc(512*sizeof(Queued),MALLOC_CAP_SPIRAM|MALLOC_CAP_8BIT));
    if(!sample_buffer){message("Recorder unavailable: sample buffer allocation failed");return ESP_ERR_NO_MEM;}
    sample_queue=xQueueCreateStatic(512,sizeof(Queued),sample_buffer,&sample_queue_control);command_queue=xQueueCreate(8,sizeof(Command));if(!sample_queue||!command_queue){message("Recorder unavailable: queue allocation failed");return ESP_ERR_NO_MEM;}
    if(xTaskCreatePinnedToCore(writer_task,"motion-store",6144,nullptr,4,nullptr,0)!=pdPASS||xTaskCreatePinnedToCore(sensor_task,"motion-sensor",6144,nullptr,5,nullptr,1)!=pdPASS){message("Recorder unavailable: task stack allocation failed");return ESP_ERR_NO_MEM;}
    service_ready=true;return ESP_OK;
}
bool latest(float axes[6]){std::lock_guard<std::mutex>g(state_lock);if(!has_sample)return false;std::copy(current.begin(),current.end(),axes);return true;}
Status status(){std::lock_guard<std::mutex>g(state_lock);return state;}
bool begin(uint8_t sport,uint8_t wrist){if(!service_ready){message("Recorder not ready; check device diagnostics");return false;}if(recording||pending.exchange(true))return false;Command c{1,sport,wrist};if(sport>3||wrist>1||xQueueSend(command_queue,&c,0)!=pdTRUE){pending=false;return false;}message("Starting recording...");return true;}
void stop(){
    if(!service_ready)return;
    {std::lock_guard<std::mutex> guard(enqueue_lock);if(!recording.exchange(false))return;pending=true;}
    message("Saving watch session...");Command c{2,0,0};xQueueSend(command_queue,&c,portMAX_DELAY);
}
std::vector<Saved> inventory(){std::lock_guard<std::mutex>disk(fs_lock);std::vector<Saved>out;if(recording||pending)return out;for(const auto&key:keys()){Saved item;if(read_info(key,item))out.push_back(item);}return out;}
bool transfer_active(){return esp_timer_get_time()<transfer_deadline.load();}
bool clear_saved(){std::lock_guard<std::mutex>disk(fs_lock);if(recording||pending||transfer_active())return false;if(download){fclose(download);download=nullptr;}bool okay=true;for(const auto&key:keys()){okay=unlink(path(key,".vmo").c_str())==0&&okay;unlink(path(key,".info").c_str());}refresh_space();message(okay?"Saved recordings deleted":"Some files could not be deleted");return okay;}
std::string control(const std::string&command,std::vector<uint8_t>&chunk,size_t capacity){
    if(command.rfind("TIME ",0)==0){uint64_t value=strtoull(command.c_str()+5,nullptr,10);if(value<1577836800||value>4102444800ULL)return "{\"kind\":\"error\",\"message\":\"Invalid time\"}";timeval tv{static_cast<time_t>(value),0};settimeofday(&tv,nullptr);return "{\"kind\":\"time\"}";}
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
