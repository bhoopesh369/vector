#include "motion_service.hpp"
#include <atomic>
#include <cstring>
#include <mutex>
#include "stream_batch.hpp"
#include "esp_heap_caps.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "esp_log.h"
extern "C" {
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
}
namespace vector_motion {
// NimBLE UUID arrays are little-endian, including the service suffix.
#define VECTOR_UUID(n) BLE_UUID128_INIT(0x20,0x19,0xee,0x05,0xd4,0x3c,0xa1,0xa9,0x8e,0x4b,0x27,0x6f,n,0x00,0x8e,0x7b)
static const ble_uuid128_t service_uuid=VECTOR_UUID(1),live_uuid=VECTOR_UUID(2),control_uuid=VECTOR_UUID(3),file_uuid=VECTOR_UUID(4),batch_uuid=VECTOR_UUID(5);
static uint16_t live_handle,file_handle,batch_handle;static std::atomic<uint16_t> connection{BLE_HS_CONN_HANDLE_NONE},mtu{23};
static std::atomic<bool> subscribed{false},file_subscribed{false};static uint8_t address_type;
static QueueHandle_t stream_queue=nullptr;static StaticQueue_t stream_queue_control;static std::mutex producer_lock;
static std::atomic<bool> full_subscribed{false},download_paused{false};static std::atomic<uint32_t> stream_epoch{0},stream_dropped{0};
static void reset_stream(){std::lock_guard<std::mutex> guard(producer_lock);full_subscribed=false;download_paused=false;stream_epoch++;stream_dropped=0;if(stream_queue)xQueueReset(stream_queue);}
void ble_frames(const uint8_t* frames,size_t count){
 if(!full_subscribed||!stream_queue||download_paused||transfer_active())return;
 std::lock_guard<std::mutex> guard(producer_lock);
 if(!full_subscribed)return;
 for(size_t i=0;i<count;i++)if(xQueueSend(stream_queue,frames+i*26,0)!=pdTRUE)stream_dropped++;
}
static void stream_task(void*){
 vector_format::Packet packets[vector_stream::MAX_FRAMES];uint8_t output[8+26*vector_stream::MAX_FRAMES];
 while(true){
  if(!full_subscribed||download_paused||transfer_active()){vTaskDelay(pdMS_TO_TICKS(10));continue;}
  uint32_t epoch=stream_epoch;uint16_t conn=connection;size_t max=vector_stream::capacity(mtu.load()),count=0;
  if(!max){vTaskDelay(pdMS_TO_TICKS(10));continue;}
  if(xQueueReceive(stream_queue,&packets[count],pdMS_TO_TICKS(10))!=pdTRUE)continue;
  count++;
  while(count<max&&xQueueReceive(stream_queue,&packets[count],0)==pdTRUE)count++;
  while(full_subscribed&&epoch==stream_epoch&&conn==connection&&!download_paused&&!transfer_active()){
   size_t bytes=vector_stream::encode(packets,count,stream_dropped.load(),output,sizeof(output));
   os_mbuf* mbuf=ble_hs_mbuf_from_flat(output,bytes);
   if(mbuf&&ble_gatts_notify_custom(conn,batch_handle,mbuf)==0)break;
   vTaskDelay(pdMS_TO_TICKS(2));
  }
 }
}
static std::string reply="{\"kind\":\"idle\"}";
static int access(uint16_t conn,uint16_t,ble_gatt_access_ctxt *context,void*){
    if(context->op==BLE_GATT_ACCESS_OP_READ_CHR){return os_mbuf_append(context->om,reply.data(),reply.size())==0?0:BLE_ATT_ERR_INSUFFICIENT_RES;}
    if(context->op!=BLE_GATT_ACCESS_OP_WRITE_CHR)return BLE_ATT_ERR_UNLIKELY;
    uint16_t bytes=OS_MBUF_PKTLEN(context->om);if(bytes>80)return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    char command[81]{};if(os_mbuf_copydata(context->om,0,bytes,command)!=0)return BLE_ATT_ERR_UNLIKELY;
    std::vector<uint8_t> chunk;std::string request(command,bytes);
    if(request.rfind("GET ",0)==0||request=="CLOSE"){
        std::lock_guard<std::mutex> guard(producer_lock);download_paused=true;stream_epoch++;if(stream_queue)xQueueReset(stream_queue);
        reply=control(request,chunk,std::min(size_t(200),static_cast<size_t>(mtu.load()-3)));download_paused=transfer_active();
    }else reply=control(request,chunk,std::min(size_t(200),static_cast<size_t>(mtu.load()-3)));
    if(!chunk.empty()){
        if(!file_subscribed)return BLE_ATT_ERR_UNLIKELY;
        os_mbuf*mbuf=ble_hs_mbuf_from_flat(chunk.data(),chunk.size());if(!mbuf)return BLE_ATT_ERR_INSUFFICIENT_RES;
        if(ble_gatts_notify_custom(conn,file_handle,mbuf)!=0)return BLE_ATT_ERR_INSUFFICIENT_RES;
    }return 0;
}
static ble_gatt_chr_def chars[5];static ble_gatt_svc_def services[2];
static void advertise();
static int gap(ble_gap_event*event,void*){
    if(event->type==BLE_GAP_EVENT_CONNECT){if(event->connect.status==0){connection=event->connect.conn_handle;ble_gap_upd_params params{};params.itvl_min=6;params.itvl_max=12;params.supervision_timeout=400;ble_gap_update_params(event->connect.conn_handle,&params);ble_gap_set_data_len(event->connect.conn_handle,251,2120);ble_gap_set_prefered_le_phy(event->connect.conn_handle,BLE_GAP_LE_PHY_1M_MASK|BLE_GAP_LE_PHY_2M_MASK,BLE_GAP_LE_PHY_1M_MASK|BLE_GAP_LE_PHY_2M_MASK,0);}else advertise();}
    else if(event->type==BLE_GAP_EVENT_DISCONNECT){reset_stream();connection=BLE_HS_CONN_HANDLE_NONE;subscribed=false;file_subscribed=false;mtu=23;std::vector<uint8_t> unused;control("CLOSE",unused,23);restore_display();advertise();}
    else if(event->type==BLE_GAP_EVENT_SUBSCRIBE){if(event->subscribe.attr_handle==live_handle)subscribed=event->subscribe.cur_notify;if(event->subscribe.attr_handle==file_handle)file_subscribed=event->subscribe.cur_notify;if(event->subscribe.attr_handle==batch_handle){reset_stream();full_subscribed=event->subscribe.cur_notify&&stream_queue;}}
    else if(event->type==BLE_GAP_EVENT_MTU){mtu=event->mtu.value;ESP_LOGI("VectorMotion","Negotiated BLE MTU %u",unsigned(mtu.load()));}
    return 0;
}
static void advertise(){ble_hs_adv_fields fields{};fields.flags=BLE_HS_ADV_F_DISC_GEN|BLE_HS_ADV_F_BREDR_UNSUP;fields.uuids128=&service_uuid;fields.num_uuids128=1;fields.uuids128_is_complete=1;
    ble_gap_adv_set_fields(&fields);ble_hs_adv_fields response{};response.name=reinterpret_cast<const uint8_t*>("Vector Motion");response.name_len=13;response.name_is_complete=1;ble_gap_adv_rsp_set_fields(&response);
    ble_gap_adv_params params{};params.conn_mode=BLE_GAP_CONN_MODE_UND;params.disc_mode=BLE_GAP_DISC_MODE_GEN;ble_gap_adv_start(address_type,nullptr,BLE_HS_FOREVER,&params,gap,nullptr);
}
static void synced(){if(ble_hs_id_infer_auto(0,&address_type)==0)advertise();}
static void host_task(void*){nimble_port_run();nimble_port_freertos_deinit();}
void ble_start(){
 auto* buffer=static_cast<uint8_t*>(heap_caps_malloc(4096*26,MALLOC_CAP_SPIRAM|MALLOC_CAP_8BIT));
 if(buffer){stream_queue=xQueueCreateStatic(4096,26,buffer,&stream_queue_control);if(xTaskCreatePinnedToCore(stream_task,"motion-ble",4096,nullptr,4,nullptr,0)!=pdPASS){stream_queue=nullptr;ESP_LOGE("VectorMotion","Full stream task allocation failed");}}
 esp_err_t result=nimble_port_init();if(result!=ESP_OK){ESP_LOGE("VectorMotion","Bluetooth unavailable: %s",esp_err_to_name(result));return;}ble_hs_cfg.sync_cb=synced;ble_svc_gap_init();ble_svc_gatt_init();ble_svc_gap_device_name_set("Vector Motion");ble_att_set_preferred_mtu(517);
    chars[0].uuid=&live_uuid.u;chars[0].access_cb=access;chars[0].flags=BLE_GATT_CHR_F_NOTIFY;chars[0].val_handle=&live_handle;
    chars[1].uuid=&control_uuid.u;chars[1].access_cb=access;chars[1].flags=BLE_GATT_CHR_F_READ|BLE_GATT_CHR_F_WRITE;
    chars[2].uuid=&file_uuid.u;chars[2].access_cb=access;chars[2].flags=BLE_GATT_CHR_F_NOTIFY;chars[2].val_handle=&file_handle;
    if(stream_queue){chars[3].uuid=&batch_uuid.u;chars[3].access_cb=access;chars[3].flags=BLE_GATT_CHR_F_NOTIFY;chars[3].val_handle=&batch_handle;}
    services[0].type=BLE_GATT_SVC_TYPE_PRIMARY;services[0].uuid=&service_uuid.u;services[0].characteristics=chars;
    ESP_ERROR_CHECK(ble_gatts_count_cfg(services));ESP_ERROR_CHECK(ble_gatts_add_svcs(services));nimble_port_freertos_init(host_task);
}
void ble_live(const uint8_t*packet,size_t size){uint16_t conn=connection;if(conn==BLE_HS_CONN_HANDLE_NONE||!subscribed||mtu<29||transfer_active())return;os_mbuf*buffer=ble_hs_mbuf_from_flat(packet,size);if(buffer)ble_gatts_notify_custom(conn,live_handle,buffer);}
}
