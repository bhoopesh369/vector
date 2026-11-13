#include "motion_service.hpp"
#include <atomic>
#include <cstring>
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
static const ble_uuid128_t service_uuid=VECTOR_UUID(1),live_uuid=VECTOR_UUID(2),control_uuid=VECTOR_UUID(3),file_uuid=VECTOR_UUID(4);
static uint16_t live_handle,file_handle;static std::atomic<uint16_t> connection{BLE_HS_CONN_HANDLE_NONE},mtu{23};
static std::atomic<bool> subscribed{false},file_subscribed{false};static uint8_t address_type;
static std::string reply="{\"kind\":\"idle\"}";
static int access(uint16_t conn,uint16_t,ble_gatt_access_ctxt *context,void*){
    if(context->op==BLE_GATT_ACCESS_OP_READ_CHR){return os_mbuf_append(context->om,reply.data(),reply.size())==0?0:BLE_ATT_ERR_INSUFFICIENT_RES;}
    if(context->op!=BLE_GATT_ACCESS_OP_WRITE_CHR)return BLE_ATT_ERR_UNLIKELY;
    uint16_t bytes=OS_MBUF_PKTLEN(context->om);if(bytes>80)return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    char command[81]{};if(os_mbuf_copydata(context->om,0,bytes,command)!=0)return BLE_ATT_ERR_UNLIKELY;
    std::vector<uint8_t> chunk;reply=control(std::string(command,bytes),chunk,std::min(size_t(200),static_cast<size_t>(mtu.load()-3)));
    if(!chunk.empty()){
        if(!file_subscribed)return BLE_ATT_ERR_UNLIKELY;
        os_mbuf*mbuf=ble_hs_mbuf_from_flat(chunk.data(),chunk.size());if(!mbuf)return BLE_ATT_ERR_INSUFFICIENT_RES;
        if(ble_gatts_notify_custom(conn,file_handle,mbuf)!=0)return BLE_ATT_ERR_INSUFFICIENT_RES;
    }return 0;
}
static ble_gatt_chr_def chars[4];static ble_gatt_svc_def services[2];
static void advertise();
static int gap(ble_gap_event*event,void*){
    if(event->type==BLE_GAP_EVENT_CONNECT){if(event->connect.status==0)connection=event->connect.conn_handle;else advertise();}
    else if(event->type==BLE_GAP_EVENT_DISCONNECT){connection=BLE_HS_CONN_HANDLE_NONE;subscribed=false;file_subscribed=false;mtu=23;std::vector<uint8_t> unused;control("CLOSE",unused,23);advertise();}
    else if(event->type==BLE_GAP_EVENT_SUBSCRIBE){if(event->subscribe.attr_handle==live_handle)subscribed=event->subscribe.cur_notify;if(event->subscribe.attr_handle==file_handle)file_subscribed=event->subscribe.cur_notify;}
    else if(event->type==BLE_GAP_EVENT_MTU)mtu=event->mtu.value;
    return 0;
}
static void advertise(){ble_hs_adv_fields fields{};fields.flags=BLE_HS_ADV_F_DISC_GEN|BLE_HS_ADV_F_BREDR_UNSUP;fields.uuids128=&service_uuid;fields.num_uuids128=1;fields.uuids128_is_complete=1;
    ble_gap_adv_set_fields(&fields);ble_hs_adv_fields response{};response.name=reinterpret_cast<const uint8_t*>("Vector Motion");response.name_len=13;response.name_is_complete=1;ble_gap_adv_rsp_set_fields(&response);
    ble_gap_adv_params params{};params.conn_mode=BLE_GAP_CONN_MODE_UND;params.disc_mode=BLE_GAP_DISC_MODE_GEN;ble_gap_adv_start(address_type,nullptr,BLE_HS_FOREVER,&params,gap,nullptr);
}
static void synced(){if(ble_hs_id_infer_auto(0,&address_type)==0)advertise();}
static void host_task(void*){nimble_port_run();nimble_port_freertos_deinit();}
void ble_start(){esp_err_t result=nimble_port_init();if(result!=ESP_OK){ESP_LOGE("VectorMotion","Bluetooth unavailable: %s",esp_err_to_name(result));return;}ble_hs_cfg.sync_cb=synced;ble_svc_gap_init();ble_svc_gatt_init();ble_svc_gap_device_name_set("Vector Motion");ble_att_set_preferred_mtu(247);
    chars[0].uuid=&live_uuid.u;chars[0].access_cb=access;chars[0].flags=BLE_GATT_CHR_F_NOTIFY;chars[0].val_handle=&live_handle;
    chars[1].uuid=&control_uuid.u;chars[1].access_cb=access;chars[1].flags=BLE_GATT_CHR_F_READ|BLE_GATT_CHR_F_WRITE;
    chars[2].uuid=&file_uuid.u;chars[2].access_cb=access;chars[2].flags=BLE_GATT_CHR_F_NOTIFY;chars[2].val_handle=&file_handle;
    services[0].type=BLE_GATT_SVC_TYPE_PRIMARY;services[0].uuid=&service_uuid.u;services[0].characteristics=chars;
    ESP_ERROR_CHECK(ble_gatts_count_cfg(services));ESP_ERROR_CHECK(ble_gatts_add_svcs(services));nimble_port_freertos_init(host_task);
}
void ble_live(const uint8_t*packet,size_t size){uint16_t conn=connection;if(conn==BLE_HS_CONN_HANDLE_NONE||!subscribed||mtu<29||transfer_active())return;os_mbuf*buffer=ble_hs_mbuf_from_flat(packet,size);if(buffer)ble_gatts_notify_custom(conn,live_handle,buffer);}
}
