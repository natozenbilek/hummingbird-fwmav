#include <stdio.h>
#include <stdbool.h>
#include <assert.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_system.h"
#include "esp_log.h"
#include "esp_chip_info.h"
#include "esp_flash.h"
#include "esp_timer.h"
#include "esp_random.h"
#include "soc/rtc.h"
#include "driver/ledc.h"
#include "driver/gpio.h"
#include "nvs_flash.h"

#ifdef CONFIG_BT_BLE_ENABLED
#include "esp_bt.h"
#include "esp_bt_main.h"
#include "esp_gap_ble_api.h"
#include "esp_gatts_api.h"
#include "esp_gatt_common_api.h"
#endif

#include "flight_control.h"

static const char *TAG = "FLIGHT_SOFTWARE";

static flight_control_state_t g_flight_control = {0};

#if !defined(CONFIG_IDF_TARGET_ESP32) && !defined(CONFIG_IDF_TARGET_ESP32S3)
// Fiziksel donanım: BLE komut handler'ı ile flight_software_loop arasında paylaşılan motor state.
// 32-bit hizalı yüklemeler/yazımlar ESP32'de zaten atomik; volatile yeterli.
static volatile float g_motor_speed_target = 0.0f;   // -1..+1 (negatif = geri)
static volatile bool  g_motor_emergency = false;
static volatile uint64_t g_last_command_us = 0;
static uint32_t g_physical_loop_counter = 0;
#endif

static void comm_apply_command(const flight_command_t *cmd);
static void comm_publish_telemetry(const flight_telemetry_t *telemetry);
static void flight_control_trigger_emergency(void);
static void flight_control_clear_emergency(void);

#ifdef CONFIG_BT_BLE_ENABLED
static void ble_transport_init(void);
static void ble_transport_notify(const flight_telemetry_t *telemetry);
#endif

static float random_uniform_noise(float amplitude)
{
    uint32_t rnd = esp_random();
    float normalized = ((int32_t)(rnd & 0xFFFF) / 32768.0f) - 1.0f; // approx. [-1, 1)
    return normalized * amplitude;
}

static void run_control_math_self_test(void)
{
    ESP_LOGI(TAG, "Running control-math self-test...");

    if (!(fabsf(clampf(0.5f, 0.0f, 1.0f) - 0.5f) < 1e-6f &&
          fabsf(clampf(-1.0f, 0.0f, 1.0f) - 0.0f) < 1e-6f &&
          fabsf(clampf(2.0f, 0.0f, 1.0f) - 1.0f) < 1e-6f)) {
        ESP_LOGE(TAG, "clampf() self-test failed");
        abort();
    }

    imu_raw_data_t valid = {
        .ax = 0.0f,
        .ay = 0.0f,
        .az = FLIGHT_GRAVITY,
        .gx = 0.0f,
        .gy = 0.0f,
        .gz = 0.0f,
    };
    if (!imu_validate_reading(&valid)) {
        ESP_LOGE(TAG, "imu_validate_reading() rejected nominal data");
        abort();
    }

    imu_raw_data_t accel_out_of_range = valid;
    accel_out_of_range.ax = FLIGHT_GRAVITY * 5.0f;
    if (imu_validate_reading(&accel_out_of_range)) {
        ESP_LOGE(TAG, "imu_validate_reading() accepted invalid accel data");
        abort();
    }

    complementary_filter_t filter = {0};
    complementary_filter_reset(&filter, 0.98f);
    imu_raw_data_t raw = valid;
    const float dt = 0.01f;
    attitude_t att = complementary_filter_update(&filter, &raw, dt);
    if (!(fabsf(att.roll) < 1e-3f && fabsf(att.pitch) < 1e-3f)) {
        ESP_LOGE(TAG, "complementary_filter_update() baseline drift");
        abort();
    }

    raw.gx = 10.0f;
    att = complementary_filter_update(&filter, &raw, dt);
    if (!(att.roll > 0.0f)) {
        ESP_LOGE(TAG, "complementary_filter_update() gyro contribution failed");
        abort();
    }

    pid_controller_t pid = {0};
    pid_reset(&pid, 0.9f, 0.1f, 0.2f);
    (void)pid_update(&pid, 5.0f, 0.02f, 1.0f);
    (void)pid_update(&pid, 5.0f, 0.5f, 1.0f);
    if (fabsf(pid.integral) > 1.0001f) {
        ESP_LOGE(TAG, "pid_update() integral wind-up limit failed");
        abort();
    }

    ESP_LOGI(TAG, "Control-math self-test passed.");
}

static void imu_mock_read(imu_raw_data_t *out)
{
    static const float roll_amplitude_deg = 5.0f;
    static const float pitch_amplitude_deg = 3.0f;
    static const float frequency_hz = 0.5f;
    static const float noise_accel = 0.05f;
    static const float noise_gyro = 0.2f;

    uint64_t now_us = esp_timer_get_time();
    float t = (float)now_us / 1000000.0f;
    float omega = 2.0f * (float)M_PI * frequency_hz;

    float roll_deg = roll_amplitude_deg * sinf(omega * t);
    float pitch_deg = pitch_amplitude_deg * sinf(omega * t + (float)M_PI / 4.0f);

    float roll_rad = roll_deg * DEG_TO_RAD;
    float pitch_rad = pitch_deg * DEG_TO_RAD;

    float roll_rate_deg_s = roll_amplitude_deg * omega * cosf(omega * t);
    float pitch_rate_deg_s = pitch_amplitude_deg * omega * cosf(omega * t + (float)M_PI / 4.0f);

    out->ax = -sinf(roll_rad) * FLIGHT_GRAVITY + random_uniform_noise(noise_accel);
    out->ay = sinf(pitch_rad) * cosf(roll_rad) * FLIGHT_GRAVITY + random_uniform_noise(noise_accel);
    out->az = cosf(pitch_rad) * cosf(roll_rad) * FLIGHT_GRAVITY + random_uniform_noise(noise_accel);

    out->gx = roll_rate_deg_s + random_uniform_noise(noise_gyro);
    out->gy = pitch_rate_deg_s + random_uniform_noise(noise_gyro);
    out->gz = 0.0f;
}

static void flight_control_init_simulation(void)
{
    memset(&g_flight_control, 0, sizeof(g_flight_control));
    complementary_filter_reset(&g_flight_control.filter, 0.98f);
    pid_reset(&g_flight_control.pid_roll, 0.9f, 0.0f, 0.15f);
    pid_reset(&g_flight_control.pid_pitch, 0.8f, 0.0f, 0.12f);

    // TODO: Populate calibration offsets using real MPU-6050 measurements on hardware
    g_flight_control.calibration.accel_x_offset = 0.0f;
    g_flight_control.calibration.accel_y_offset = 0.0f;
    g_flight_control.calibration.accel_z_offset = 0.0f;
    g_flight_control.calibration.gyro_x_offset = 0.0f;
    g_flight_control.calibration.gyro_y_offset = 0.0f;
    g_flight_control.calibration.gyro_z_offset = 0.0f;

    g_flight_control.integral_roll_limit = 20.0f;
    g_flight_control.integral_pitch_limit = 20.0f;
    g_flight_control.throttle_mix_gain = 0.005f;
    g_flight_control.emergency_active = false;
    g_flight_control.target_override_active = false;
    g_flight_control.base_throttle_setpoint = MOTOR_BASE_THROTTLE;
    g_flight_control.yaw_trim_deg = 0.0f;
    g_flight_control.target_attitude.roll = 0.0f;
    g_flight_control.target_attitude.pitch = 0.0f;
    g_flight_control.target_attitude.yaw = 0.0f;
    g_flight_control.motor_throttle = MOTOR_BASE_THROTTLE;
    g_flight_control.last_update_us = esp_timer_get_time();
    g_flight_control.loop_counter = 0;
    g_flight_control.rise_active = false;
    g_flight_control.rise_start_us = 0;
}

static void flight_control_step_simulation(void)
{
    if (g_flight_control.emergency_active) {
        g_flight_control.servo_roll_deg = 0.0f;
        g_flight_control.servo_pitch_deg = 0.0f;
        g_flight_control.servo_yaw_deg = 0.0f;
        g_flight_control.motor_throttle = MOTOR_MIN_THROTTLE;
        vTaskDelay(pdMS_TO_TICKS(50));
        return;
    }

    imu_raw_data_t raw = {0};
    imu_mock_read(&raw);
    imu_apply_calibration(&raw, &g_flight_control.calibration);
    if (!imu_validate_reading(&raw)) {
        ESP_LOGE(TAG, "IMU reading invalid, entering emergency mode");
        flight_control_trigger_emergency();
        return;
    }

    // Rise command timeout check (3 seconds = 3,000,000 microseconds)
    if (g_flight_control.rise_active) {
        uint64_t now_us = esp_timer_get_time();
        uint64_t rise_duration_us = now_us - g_flight_control.rise_start_us;

        if (rise_duration_us >= 3000000ULL) { // 3 seconds
            g_flight_control.rise_active = false;
            g_flight_control.base_throttle_setpoint = MOTOR_BASE_THROTTLE; // Return to normal throttle
            g_flight_control.target_override_active = true;
            ESP_LOGI(TAG, "Rise command completed - returning to normal throttle: %.1f%%",
                    g_flight_control.base_throttle_setpoint * 100.0f);
        }
    }

    uint64_t now_us = esp_timer_get_time();
    float dt = (float)(now_us - g_flight_control.last_update_us) / 1000000.0f;
    if (dt <= 0.0f || dt > 0.1f) {
        ESP_LOGE(TAG, "Control loop timing violation (dt=%.3f s), entering emergency mode", dt);
        flight_control_trigger_emergency();
        return;
    }
    g_flight_control.last_update_us = now_us;

    g_flight_control.current_attitude = complementary_filter_update(&g_flight_control.filter, &raw, dt);

    const float roll_error = g_flight_control.target_attitude.roll - g_flight_control.current_attitude.roll;
    const float pitch_error = g_flight_control.target_attitude.pitch - g_flight_control.current_attitude.pitch;

    float roll_output = pid_update(&g_flight_control.pid_roll, roll_error, dt, g_flight_control.integral_roll_limit);
    float pitch_output = pid_update(&g_flight_control.pid_pitch, pitch_error, dt, g_flight_control.integral_pitch_limit);

    g_flight_control.servo_roll_deg = clampf(roll_output, -SERVO_MAX_DEFLECTION_DEG, SERVO_MAX_DEFLECTION_DEG);
    g_flight_control.servo_pitch_deg = clampf(pitch_output, -SERVO_MAX_DEFLECTION_DEG, SERVO_MAX_DEFLECTION_DEG);
    g_flight_control.servo_yaw_deg = clampf(g_flight_control.yaw_trim_deg, -SERVO_MAX_DEFLECTION_DEG, SERVO_MAX_DEFLECTION_DEG);

    float base_throttle = g_flight_control.target_override_active ? g_flight_control.base_throttle_setpoint : MOTOR_BASE_THROTTLE;
    float throttle_adjust = clampf(pitch_output * g_flight_control.throttle_mix_gain, -0.2f, 0.2f);
    g_flight_control.motor_throttle = clampf(base_throttle - throttle_adjust, MOTOR_MIN_THROTTLE, MOTOR_MAX_THROTTLE);

    g_flight_control.loop_counter++;
}

static void flight_control_trigger_emergency(void)
{
    g_flight_control.emergency_active = true;
    pid_reset(&g_flight_control.pid_roll, g_flight_control.pid_roll.kp, g_flight_control.pid_roll.ki, g_flight_control.pid_roll.kd);
    pid_reset(&g_flight_control.pid_pitch, g_flight_control.pid_pitch.kp, g_flight_control.pid_pitch.ki, g_flight_control.pid_pitch.kd);
    ESP_LOGE(TAG, "Emergency stop engaged: motor and servos set to safe state");
}

static void flight_control_clear_emergency(void)
{
    if (!g_flight_control.emergency_active) {
        return;
    }
    g_flight_control.emergency_active = false;
    g_flight_control.servo_roll_deg = 0.0f;
    g_flight_control.servo_pitch_deg = 0.0f;
    g_flight_control.servo_yaw_deg = g_flight_control.yaw_trim_deg;
    g_flight_control.motor_throttle = MOTOR_BASE_THROTTLE;
    ESP_LOGW(TAG, "Emergency state cleared");
}

static void flight_control_trigger_rise(void)
{
    if (g_flight_control.emergency_active) {
        return; // Ignore rise command in emergency state
    }

    g_flight_control.rise_active = true;
    g_flight_control.rise_start_us = esp_timer_get_time();
    g_flight_control.base_throttle_setpoint = 0.8f; // High throttle (80%)
    g_flight_control.target_override_active = true;
    ESP_LOGI(TAG, "Rise command activated - target throttle: %.1f%%", g_flight_control.base_throttle_setpoint * 100.0f);
}

static void flight_control_set_targets(float roll_deg, float pitch_deg, float throttle)
{
    g_flight_control.target_attitude.roll = clampf(roll_deg, -15.0f, 15.0f);
    g_flight_control.target_attitude.pitch = clampf(pitch_deg, -15.0f, 15.0f);
    g_flight_control.base_throttle_setpoint = clampf(throttle, MOTOR_MIN_THROTTLE, MOTOR_MAX_THROTTLE);
    g_flight_control.target_override_active = true;
}

static void flight_control_set_yaw_trim(float yaw_trim_deg)
{
    g_flight_control.yaw_trim_deg = clampf(yaw_trim_deg, -10.0f, 10.0f);
}

static void flight_control_process_command(const flight_command_t *cmd)
{
    if (cmd == NULL) {
        return;
    }
    if (cmd->emergency_stop) {
        flight_control_trigger_emergency();
        return;
    }
    if (cmd->clear_emergency) {
        flight_control_clear_emergency();
    }

    flight_control_set_targets(cmd->roll_deg, cmd->pitch_deg, cmd->throttle);
    flight_control_set_yaw_trim(cmd->yaw_trim_deg);

    if (cmd->rise_command) {
        flight_control_trigger_rise();
    }
}

static flight_telemetry_t flight_control_snapshot(void)
{
    flight_telemetry_t out = {
        .attitude_deg = g_flight_control.current_attitude,
        .servo_roll_deg = g_flight_control.servo_roll_deg,
        .servo_pitch_deg = g_flight_control.servo_pitch_deg,
        .servo_yaw_deg = g_flight_control.servo_yaw_deg,
        .motor_throttle = g_flight_control.motor_throttle,
        .emergency_active = g_flight_control.emergency_active,
        .loop_counter = g_flight_control.loop_counter,
    };
    return out;
}

static void comm_apply_command(const flight_command_t *cmd)
{
    if (cmd == NULL) {
        return;
    }

    // Simülasyon state'ini her durumda güncel tut (QEMU + ileride PID/IMU için).
    flight_control_process_command(cmd);

#if !defined(CONFIG_IDF_TARGET_ESP32) && !defined(CONFIG_IDF_TARGET_ESP32S3)
    // Fiziksel donanım (ESP32-C3): BLE komutunu doğrudan motor target'ına yönlendir.
    // throttle (0..1) hız, pitch işareti yön belirler.
    if (cmd->emergency_stop) {
        g_motor_emergency = true;
        g_motor_speed_target = 0.0f;
    } else {
        if (cmd->clear_emergency) {
            g_motor_emergency = false;
        }
        if (!g_motor_emergency) {
            float speed = clampf(cmd->throttle, 0.0f, 1.0f);
            float direction = (cmd->pitch_deg < -3.0f) ? -1.0f : 1.0f;
            g_motor_speed_target = speed * direction;
        }
    }
    g_last_command_us = esp_timer_get_time();
#endif
}

static void comm_publish_telemetry(const flight_telemetry_t *telemetry)
{
    if (telemetry == NULL) {
        return;
    }
#ifdef CONFIG_BT_BLE_ENABLED
    ble_transport_notify(telemetry);
#endif
    ESP_LOGD(TAG,
             "TELEM loop=%lu roll=%.2f pitch=%.2f motor=%.0f%% emergency=%d",
             telemetry->loop_counter,
             telemetry->attitude_deg.roll,
             telemetry->attitude_deg.pitch,
             telemetry->motor_throttle * 100.0f,
             telemetry->emergency_active);
}

#ifdef CONFIG_BT_BLE_ENABLED

// ESP-IDF BLE UUID128 dizileri LITTLE-ENDIAN (LSB önce) sırada bekler — Bluetooth spec'ine göre
// 128-bit UUID kablo formatı little-endian. Android tarafı UUID'yi standart string olarak
// "12345678-1234-5678-90ab-cdef..." şeklinde tutuyor; bu diziler aynı UUID'nin ters byte sırasıdır.
#define BLE_SERVICE_UUID128   {0xBE,0xBA,0xFE,0xCA,0xEF,0xCD,0xAB,0x90,0x78,0x56,0x34,0x12,0x78,0x56,0x34,0x12}
#define BLE_COMMAND_UUID128   {0x01,0x00,0x0D,0xC0,0xEF,0xCD,0xAB,0x90,0x78,0x56,0x34,0x12,0x78,0x56,0x34,0x12}
#define BLE_TELEMETRY_UUID128 {0x02,0xAD,0xDE,0xC0,0xEF,0xCD,0xAB,0x90,0x78,0x56,0x34,0x12,0x78,0x56,0x34,0x12}

enum {
    BLE_IDX_SVC,
    BLE_IDX_CMD_CHAR,
    BLE_IDX_CMD_VAL,
    BLE_IDX_TELEM_CHAR,
    BLE_IDX_TELEM_VAL,
    BLE_IDX_TELEM_CFG,
    BLE_IDX_NB,
};

typedef struct __attribute__((packed)) {
    int16_t roll_deg_x10;
    int16_t pitch_deg_x10;
    int16_t yaw_trim_deg_x10;
    uint8_t throttle_percent;
    uint8_t flags;
    uint8_t crc;
} ble_command_packet_t;

typedef struct __attribute__((packed)) {
    int16_t roll_deg_x10;
    int16_t pitch_deg_x10;
    int16_t servo_roll_deg_x10;
    int16_t servo_pitch_deg_x10;
    int16_t servo_yaw_deg_x10;
    int16_t yaw_deg_x10;
    uint8_t motor_throttle_percent;
    uint8_t flags;
    uint32_t loop_counter;
    uint8_t status_flags;
    uint8_t crc;
} ble_telemetry_packet_t;

static esp_gatt_if_t ble_gatts_if = ESP_GATT_IF_NONE;
static uint16_t ble_conn_id = 0;
static bool ble_is_connected = false;
static bool ble_notify_enabled = false;
static uint16_t ble_handle_table[BLE_IDX_NB];

static const uint16_t primary_service_uuid = ESP_GATT_UUID_PRI_SERVICE;
static const uint16_t character_declaration_uuid = ESP_GATT_UUID_CHAR_DECLARE;
static const uint16_t client_char_config_uuid = ESP_GATT_UUID_CHAR_CLIENT_CONFIG;
static const uint8_t char_prop_write_nr = ESP_GATT_CHAR_PROP_BIT_WRITE_NR;
static const uint8_t char_prop_notify = ESP_GATT_CHAR_PROP_BIT_NOTIFY;
static const uint8_t notify_ccc_default[2] = {0x00, 0x00};

static const uint8_t service_uuid128[16] = BLE_SERVICE_UUID128;
static const uint8_t command_uuid128[16] = BLE_COMMAND_UUID128;
static const uint8_t telemetry_uuid128[16] = BLE_TELEMETRY_UUID128;

static const esp_gatts_attr_db_t ble_gatt_db[BLE_IDX_NB] = {
    [BLE_IDX_SVC] = {
        .attr_control = {.auto_rsp = ESP_GATT_AUTO_RSP},
        .att_desc = {
            .uuid_length = ESP_UUID_LEN_16,
            .uuid_p = (uint8_t *)&primary_service_uuid,
            .perm = ESP_GATT_PERM_READ,
            .max_length = sizeof(service_uuid128),
            .length = sizeof(service_uuid128),
            .value = (uint8_t *)service_uuid128,
        },
    },
    [BLE_IDX_CMD_CHAR] = {
        .attr_control = {.auto_rsp = ESP_GATT_AUTO_RSP},
        .att_desc = {
            .uuid_length = ESP_UUID_LEN_16,
            .uuid_p = (uint8_t *)&character_declaration_uuid,
            .perm = ESP_GATT_PERM_READ,
            .max_length = sizeof(uint8_t),
            .length = sizeof(uint8_t),
            .value = (uint8_t *)&char_prop_write_nr,
        },
    },
    [BLE_IDX_CMD_VAL] = {
        .attr_control = {.auto_rsp = ESP_GATT_RSP_BY_APP},
        .att_desc = {
            .uuid_length = ESP_UUID_LEN_128,
            .uuid_p = (uint8_t *)command_uuid128,
            .perm = ESP_GATT_PERM_WRITE,
            .max_length = sizeof(ble_command_packet_t),
            .length = 0,
            .value = NULL,
        },
    },
    [BLE_IDX_TELEM_CHAR] = {
        .attr_control = {.auto_rsp = ESP_GATT_AUTO_RSP},
        .att_desc = {
            .uuid_length = ESP_UUID_LEN_16,
            .uuid_p = (uint8_t *)&character_declaration_uuid,
            .perm = ESP_GATT_PERM_READ,
            .max_length = sizeof(uint8_t),
            .length = sizeof(uint8_t),
            .value = (uint8_t *)&char_prop_notify,
        },
    },
    [BLE_IDX_TELEM_VAL] = {
        .attr_control = {.auto_rsp = ESP_GATT_RSP_BY_APP},
        .att_desc = {
            .uuid_length = ESP_UUID_LEN_128,
            .uuid_p = (uint8_t *)telemetry_uuid128,
            .perm = ESP_GATT_PERM_READ,
            .max_length = sizeof(ble_telemetry_packet_t),
            .length = 0,
            .value = NULL,
        },
    },
    [BLE_IDX_TELEM_CFG] = {
        .attr_control = {.auto_rsp = ESP_GATT_AUTO_RSP},
        .att_desc = {
            .uuid_length = ESP_UUID_LEN_16,
            .uuid_p = (uint8_t *)&client_char_config_uuid,
            .perm = ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE,
            .max_length = sizeof(uint16_t),
            .length = sizeof(uint16_t),
            .value = (uint8_t *)notify_ccc_default,
        },
    },
};

static esp_ble_adv_params_t ble_adv_params = {
    .adv_int_min = 0x20,
    .adv_int_max = 0x40,
    .adv_type = ADV_TYPE_IND,
    .own_addr_type = BLE_ADDR_TYPE_PUBLIC,
    .channel_map = ADV_CHNL_ALL,
    .adv_filter_policy = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY,
};

// Primary advertising packet: name'i çıkardık çünkü 128-bit UUID + flags + name
// 31 byte sınırını aşıyor ("Partial data write into ADV" uyarısı). Service UUID
// olduğu için Android tarayıcısı yine bulur, sadece adı liste'de görünmez.
static esp_ble_adv_data_t ble_adv_data = {
    .set_scan_rsp = false,
    .include_name = false,
    .include_txpower = false,
    .min_interval = 0x0006,
    .max_interval = 0x0010,
    .appearance = 0x00,
    .manufacturer_len = 0,
    .p_manufacturer_data = NULL,
    .service_data_len = 0,
    .p_service_data = NULL,
    .service_uuid_len = sizeof(service_uuid128),
    .p_service_uuid = service_uuid128,
    .flag = (ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT),
};

static void ble_transport_start_advertising(void)
{
    esp_ble_gap_start_advertising(&ble_adv_params);
}

static uint8_t ble_transport_crc8(const uint8_t *data, size_t len)
{
    const uint8_t poly = 0x07;
    uint8_t crc = 0x00;
    for (size_t i = 0; i < len; ++i) {
        crc ^= data[i];
        for (int bit = 0; bit < 8; ++bit) {
            if (crc & 0x80) {
                crc = (uint8_t)((crc << 1) ^ poly);
            } else {
                crc <<= 1;
            }
        }
    }
    return crc;
}

static void ble_transport_handle_command_packet(const uint8_t *data, uint16_t len)
{
    if (len < sizeof(ble_command_packet_t)) {
        ESP_LOGW(TAG, "BLE command packet too short (%u)", len);
        return;
    }

    const uint8_t expected_crc = ble_transport_crc8(data, sizeof(ble_command_packet_t) - 1);
    const ble_command_packet_t *pkt = (const ble_command_packet_t *)data;

    if (expected_crc != pkt->crc) {
        ESP_LOGW(TAG, "BLE command CRC mismatch (got 0x%02X, expected 0x%02X)", pkt->crc, expected_crc);
        flight_control_trigger_emergency();
        return;
    }

    flight_command_t cmd = {
        .roll_deg = pkt->roll_deg_x10 / 10.0f,
        .pitch_deg = pkt->pitch_deg_x10 / 10.0f,
        .throttle = pkt->throttle_percent / 100.0f,
        .yaw_trim_deg = pkt->yaw_trim_deg_x10 / 10.0f,
        .emergency_stop = (pkt->flags & 0x01) != 0,
        .clear_emergency = (pkt->flags & 0x02) != 0,
        .rise_command = (pkt->flags & 0x04) != 0,
    };

    comm_apply_command(&cmd);
}

static void ble_transport_gap_event_handler(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param)
{
    switch (event) {
    case ESP_GAP_BLE_ADV_DATA_SET_COMPLETE_EVT:
        ble_transport_start_advertising();
        break;
    case ESP_GAP_BLE_ADV_START_COMPLETE_EVT:
        if (param->adv_start_cmpl.status != ESP_BT_STATUS_SUCCESS) {
            ESP_LOGE(TAG, "Failed starting BLE advertising: %d", param->adv_start_cmpl.status);
        }
        break;
    case ESP_GAP_BLE_ADV_STOP_COMPLETE_EVT:
        break;
    default:
        break;
    }
}

static void ble_transport_gatts_event_handler(esp_gatts_cb_event_t event, esp_gatt_if_t gatts_if, esp_ble_gatts_cb_param_t *param)
{
    switch (event) {
    case ESP_GATTS_REG_EVT:
        ble_gatts_if = gatts_if;
        esp_ble_gap_set_device_name("ESP32 Flight");
        esp_ble_gap_config_adv_data(&ble_adv_data);
        esp_ble_gatts_create_attr_tab(ble_gatt_db, gatts_if, BLE_IDX_NB, 0);
        break;
    case ESP_GATTS_CREAT_ATTR_TAB_EVT:
        if (param->add_attr_tab.status != ESP_GATT_OK) {
            ESP_LOGE(TAG, "Attribute table creation failed, error 0x%x", param->add_attr_tab.status);
            break;
        }
        memcpy(ble_handle_table, param->add_attr_tab.handles, sizeof(uint16_t) * BLE_IDX_NB);
        esp_ble_gatts_start_service(ble_handle_table[BLE_IDX_SVC]);
        break;
    case ESP_GATTS_CONNECT_EVT:
        ble_is_connected = true;
        ble_conn_id = param->connect.conn_id;
        ESP_LOGI(TAG, "BLE client connected, conn_id=%d", ble_conn_id);
        break;
    case ESP_GATTS_DISCONNECT_EVT:
        ESP_LOGI(TAG, "BLE client disconnected");
        ble_is_connected = false;
        ble_notify_enabled = false;
        ble_transport_start_advertising();
        ESP_LOGW(TAG, "BLE disconnect detected, engaging emergency stop");
        flight_control_trigger_emergency();
#if !defined(CONFIG_IDF_TARGET_ESP32) && !defined(CONFIG_IDF_TARGET_ESP32S3)
        // Fiziksel donanım: motoru hemen durdur.
        g_motor_emergency = true;
        g_motor_speed_target = 0.0f;
#endif
        break;
    case ESP_GATTS_WRITE_EVT:
        if (param->write.handle == ble_handle_table[BLE_IDX_CMD_VAL]) {
            ble_transport_handle_command_packet(param->write.value, param->write.len);
        } else if (param->write.handle == ble_handle_table[BLE_IDX_TELEM_CFG]) {
            if (param->write.len == 2) {
                uint16_t cfg = param->write.value[0] | (param->write.value[1] << 8);
                ble_notify_enabled = (cfg & 0x0001) != 0;
            }
        }
        break;
    default:
        break;
    }
}

static void ble_transport_init(void)
{
#ifdef CONFIG_IDF_TARGET_ESP32
    // Classic BT belleği sadece klasik ESP32'de var. C3/S3 üzerinde bu çağrı hata verir.
    esp_bt_controller_mem_release(ESP_BT_MODE_CLASSIC_BT);
#endif

    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    if (esp_bt_controller_init(&bt_cfg) != ESP_OK) {
        ESP_LOGE(TAG, "Bluetooth controller init failed");
        return;
    }
    if (esp_bt_controller_enable(ESP_BT_MODE_BLE) != ESP_OK) {
        ESP_LOGE(TAG, "Bluetooth controller enable failed");
        return;
    }
    if (esp_bluedroid_init() != ESP_OK) {
        ESP_LOGE(TAG, "Bluedroid init failed");
        return;
    }
    if (esp_bluedroid_enable() != ESP_OK) {
        ESP_LOGE(TAG, "Bluedroid enable failed");
        return;
    }

    esp_ble_gap_register_callback(ble_transport_gap_event_handler);
    esp_ble_gatts_register_callback(ble_transport_gatts_event_handler);
    esp_ble_gatts_app_register(0x55);
}

static void ble_transport_notify(const flight_telemetry_t *telemetry)
{
    if (!ble_is_connected || !ble_notify_enabled || ble_gatts_if == ESP_GATT_IF_NONE) {
        return;
    }

    ble_telemetry_packet_t pkt = {
        .roll_deg_x10 = (int16_t)(telemetry->attitude_deg.roll * 10.0f),
        .pitch_deg_x10 = (int16_t)(telemetry->attitude_deg.pitch * 10.0f),
        .servo_roll_deg_x10 = (int16_t)(telemetry->servo_roll_deg * 10.0f),
        .servo_pitch_deg_x10 = (int16_t)(telemetry->servo_pitch_deg * 10.0f),
        .servo_yaw_deg_x10 = (int16_t)(telemetry->servo_yaw_deg * 10.0f),
        .yaw_deg_x10 = (int16_t)(telemetry->attitude_deg.yaw * 10.0f),
        .motor_throttle_percent = (uint8_t)(clampf(telemetry->motor_throttle * 100.0f, 0.0f, 100.0f)),
        .flags = telemetry->emergency_active ? 0x01 : 0x00,
        .loop_counter = telemetry->loop_counter,
        .status_flags = 0x00,
        .crc = 0x00,
    };

    if (telemetry->emergency_active) {
        pkt.status_flags |= 0x01;
    }
    if (ble_is_connected) {
        pkt.status_flags |= 0x02;
    }
    if (ble_notify_enabled) {
        pkt.status_flags |= 0x04;
    }

    pkt.crc = ble_transport_crc8((const uint8_t *)&pkt, sizeof(pkt) - 1);

    esp_err_t err = esp_ble_gatts_send_indicate(
        ble_gatts_if,
        ble_conn_id,
        ble_handle_table[BLE_IDX_TELEM_VAL],
        sizeof(pkt),
        (uint8_t *)&pkt,
        false);
    // Disconnect anında 1-2 paket cache'lenmiş olabilir; bunlar başarısız olursa
    // log spam'lemesin diye ESP_FAIL / invalid-state hatalarını yutuyoruz.
    if (err != ESP_OK && err != ESP_FAIL && err != ESP_ERR_INVALID_STATE) {
        ESP_LOGW(TAG, "Failed to send BLE telemetry: %s", esp_err_to_name(err));
    }
}

#endif // CONFIG_BT_BLE_ENABLED

// ============================================================================
// QEMU TEST FUNCTIONS
// NOTE: You can delete this section when moving to physical hardware
// ============================================================================
#if defined(CONFIG_IDF_TARGET_ESP32) || defined(CONFIG_IDF_TARGET_ESP32S3)  // QEMU test mode (to be removed on physical board)

/**
 * @brief QEMU test mode initialization and system diagnostics
 * 
 * This function is only used for QEMU simulator testing.
 * Delete this function when moving to physical ESP32 hardware.
 */
void qemu_test_init(void)
{
    // Get ESP32-specific chip information
    esp_chip_info_t chip_info;
    esp_chip_info(&chip_info);

    run_control_math_self_test();
    
    printf("\n");
    printf("===========================================\n");
    printf("   ESP32 QEMU Simulator STARTED!         \n");
    printf("   (Test Mode - To be removed on physical board)\n");
    printf("===========================================\n");
    printf("\n");
    
    // Real ESP32 hardware information - read from API
    ESP_LOGI(TAG, "--- CHIP INFORMATION (ESP32 HAL API) ---");
    ESP_LOGI(TAG, "Chip Model: ESP32");
    ESP_LOGI(TAG, "CPU Cores: %d (REAL HARDWARE)", chip_info.cores);
    ESP_LOGI(TAG, "CPU Frequency: %lu MHz (REAL CLOCK)", rtc_clk_apb_freq_get() * 2 / 1000000);
    ESP_LOGI(TAG, "Chip Revision: v%d.%d", chip_info.revision / 100, chip_info.revision % 100);
    
    // Get flash size - ESP32 Flash API
    uint32_t flash_size = 0;
    if (esp_flash_get_size(NULL, &flash_size) == ESP_OK) {
        ESP_LOGI(TAG, "Flash Size: %lu MB (REAL SPI FLASH)", flash_size / (1024 * 1024));
    }
    
    // WiFi/BT features - from hardware feature register
    ESP_LOGI(TAG, "WiFi/BT: %s (HARDWARE FEATURE)", 
             chip_info.features & CHIP_FEATURE_WIFI_BGN ? "AVAILABLE" : "NOT AVAILABLE");
    ESP_LOGI(TAG, "Bluetooth Classic: %s (HARDWARE FEATURE)", 
             chip_info.features & CHIP_FEATURE_BT ? "AVAILABLE" : "NOT AVAILABLE");
    ESP_LOGI(TAG, "Bluetooth LE: %s (HARDWARE FEATURE)", 
             chip_info.features & CHIP_FEATURE_BLE ? "AVAILABLE" : "NOT AVAILABLE");
    
    // Memory information - real values from FreeRTOS heap manager
    ESP_LOGI(TAG, "--- BELLEK (FREERTOS HEAP) ---");
    ESP_LOGI(TAG, "Free Heap: %lu bytes (%lu KB) - REAL HEAP", 
             esp_get_free_heap_size(), 
             esp_get_free_heap_size() / 1024);
    ESP_LOGI(TAG, "Min Free Heap Ever: %lu bytes - TRACKED", 
             esp_get_minimum_free_heap_size());
    
    // IDF version
    ESP_LOGI(TAG, "--- SOFTWARE ---");
    ESP_LOGI(TAG, "ESP-IDF Version: %s", esp_get_idf_version());
    
    printf("\n");
    ESP_LOGW(TAG, "This data comes from ESP32 HAL APIs!");
    ESP_LOGW(TAG, "QEMU simulates these values like a REAL ESP32!");
    printf("\n");
}

/**
 * @brief QEMU test loop - Gerçek ESP32 API'lerini test eder
 * 
 * Bu fonksiyon ESP32'ye özgü donanım fonksiyonlarını test eder:
 * - FreeRTOS Task yönetimi
 * - Timer fonksiyonları
 * - Heap memory yönetimi
 * - System time (uptime)
 * 
 * Fiziksel ESP32 kartına geçerken bu fonksiyon silinebilir.
 */
void qemu_test_loop(void)
{
    flight_control_init_simulation();

    flight_command_t default_command = {
        .roll_deg = 0.0f,
        .pitch_deg = 0.0f,
        .throttle = MOTOR_BASE_THROTTLE,
        .yaw_trim_deg = 0.0f,
        .emergency_stop = false,
        .clear_emergency = true,
        .rise_command = false,
    };
    comm_apply_command(&default_command);

    uint32_t last_heap = esp_get_free_heap_size();
    const TickType_t control_delay = pdMS_TO_TICKS(20);
    const char *self_test_once_flag = getenv("FLIGHT_SELF_TEST_ONCE");
    const bool exit_after_self_test = (self_test_once_flag != NULL && self_test_once_flag[0] == '1');

    while (true) {
        // Test: Automatically send rise command at loop 100
        if (g_flight_control.loop_counter == 100) {
            flight_command_t rise_test_cmd = {
                .roll_deg = 0.0f,
                .pitch_deg = 0.0f,
                .throttle = MOTOR_BASE_THROTTLE,
                .yaw_trim_deg = 0.0f,
                .emergency_stop = false,
                .clear_emergency = false,
                .rise_command = true,
            };
            comm_apply_command(&rise_test_cmd);
            ESP_LOGI(TAG, "TEST: Automatic rise command sent at loop %lu", g_flight_control.loop_counter);
        }

        flight_control_step_simulation();
        flight_telemetry_t telemetry = flight_control_snapshot();
        comm_publish_telemetry(&telemetry);

        uint32_t loop = g_flight_control.loop_counter;
        if (loop == 0) {
            vTaskDelay(control_delay);
            continue;
        }

        if (loop % 50 == 0) { // Approximately 1 second (20 ms loop)
            uint32_t uptime_ms = esp_timer_get_time() / 1000;
            uint32_t current_heap = esp_get_free_heap_size();
            int heap_diff = (int)current_heap - (int)last_heap;
            last_heap = current_heap;

            printf("\r[%lus] Uptime: %lu ms | Heap: %lu bytes (%+d) | Roll: %+6.2f deg | Pitch: %+6.2f deg | Throttle: %.2f   ",
                   loop / 50,
                   uptime_ms,
                   current_heap,
                   heap_diff,
                   g_flight_control.current_attitude.roll,
                   g_flight_control.current_attitude.pitch,
                   g_flight_control.motor_throttle);
            fflush(stdout);

            if ((loop / 50) % 5 == 0) {
                printf("\n");
                ESP_LOGI(TAG, "=== ESP32 HARDWARE + CONTROL TEST ===");
                ESP_LOGI(TAG, "System Uptime: %lu ms (%.2f seconds)", uptime_ms, uptime_ms / 1000.0f);
                ESP_LOGI(TAG, "FreeRTOS Tick Count: %lu", xTaskGetTickCount());
                ESP_LOGI(TAG, "Current Heap: %lu bytes", current_heap);
                ESP_LOGI(TAG, "Min Free Heap Ever: %lu bytes", esp_get_minimum_free_heap_size());
                ESP_LOGI(TAG, "Heap Change: %+d bytes", heap_diff);
                ESP_LOGI(TAG, "Attitude (deg): roll=%.2f pitch=%.2f yaw=%.2f",
                         g_flight_control.current_attitude.roll,
                         g_flight_control.current_attitude.pitch,
                         g_flight_control.current_attitude.yaw);
                ESP_LOGI(TAG, "Calibration offsets: ax=%.3f ay=%.3f az=%.3f gx=%.3f gy=%.3f gz=%.3f",
                         g_flight_control.calibration.accel_x_offset,
                         g_flight_control.calibration.accel_y_offset,
                         g_flight_control.calibration.accel_z_offset,
                         g_flight_control.calibration.gyro_x_offset,
                         g_flight_control.calibration.gyro_y_offset,
                         g_flight_control.calibration.gyro_z_offset);
                ESP_LOGI(TAG, "Servo Cmd (deg): roll=%.2f pitch=%.2f yaw=%.2f",
                         g_flight_control.servo_roll_deg,
                         g_flight_control.servo_pitch_deg,
                         g_flight_control.servo_yaw_deg);
                ESP_LOGI(TAG, "Motor Throttle: %.2f", g_flight_control.motor_throttle);
                ESP_LOGI(TAG, "Hardware RNG Test: 0x%08lX", esp_random());
                ESP_LOGI(TAG, "Reset Reason: %d", esp_reset_reason());
                ESP_LOGI(TAG, "======================================");
                printf("\n");
            }

            if ((loop / 50) % 15 == 0) {
                ESP_LOGI(TAG, "*** FreeRTOS TASK INFORMATION ***");
                ESP_LOGI(TAG, "Task Count: %d", uxTaskGetNumberOfTasks());
                ESP_LOGI(TAG, "Current Task: %s", pcTaskGetName(NULL));
                ESP_LOGI(TAG, "******************************");
                printf("\n");
            }
        }

        vTaskDelay(control_delay);

        if (exit_after_self_test && loop >= 150) {
            ESP_LOGI(TAG, "Self-test loop limit reached, exiting as requested");
            abort();
        }
    }
}

#endif // CONFIG_IDF_TARGET_ESP32 || CONFIG_IDF_TARGET_ESP32S3

// ============================================================================
// REAL APPLICATION CODE
// NOTE: Your actual code that will run on physical hardware goes here
// ============================================================================

// DRV8833 motor sürücü pin haritası (ESP32-C3 Super Mini).
// nSLEEP pini 3.3V'a sabitlenmiş kabul edilir; aksi halde sürücü uyur.
#define MOTOR_AIN1_GPIO        GPIO_NUM_3
#define MOTOR_AIN2_GPIO        GPIO_NUM_4
#define MOTOR_PWM_FREQ_HZ      20000
#define MOTOR_PWM_RESOLUTION   LEDC_TIMER_10_BIT
#define MOTOR_PWM_DUTY_MAX     1023
#define MOTOR_PWM_TIMER        LEDC_TIMER_0
#define MOTOR_PWM_SPEED_MODE   LEDC_LOW_SPEED_MODE
#define MOTOR_PWM_CHANNEL_A    LEDC_CHANNEL_0
#define MOTOR_PWM_CHANNEL_B    LEDC_CHANNEL_1

// ESP32-C3 Super Mini onboard LED — active LOW (LOW = açık).
// Motor durumu aynalanıyor: motor sürücü/motor bağlı olmasa bile telefon→ESP zincirini
// gözle doğrulamak için. LED bu pinde değilse pasif kalır, çalışma etkilenmez.
#define ONBOARD_LED_GPIO       GPIO_NUM_8

static void led_init(void)
{
    gpio_config_t cfg = {
        .pin_bit_mask = 1ULL << ONBOARD_LED_GPIO,
        .mode         = GPIO_MODE_OUTPUT,
        .pull_up_en   = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type    = GPIO_INTR_DISABLE,
    };
    gpio_config(&cfg);
    gpio_set_level(ONBOARD_LED_GPIO, 1); // başlangıç: LED kapalı (active LOW)
}

static inline void led_set(bool on)
{
    gpio_set_level(ONBOARD_LED_GPIO, on ? 0 : 1);
}

static void motor_init(void)
{
    ledc_timer_config_t timer_cfg = {
        .speed_mode      = MOTOR_PWM_SPEED_MODE,
        .timer_num       = MOTOR_PWM_TIMER,
        .duty_resolution = MOTOR_PWM_RESOLUTION,
        .freq_hz         = MOTOR_PWM_FREQ_HZ,
        .clk_cfg         = LEDC_AUTO_CLK,
    };
    ESP_ERROR_CHECK(ledc_timer_config(&timer_cfg));

    ledc_channel_config_t channel_cfg = {
        .gpio_num   = MOTOR_AIN1_GPIO,
        .speed_mode = MOTOR_PWM_SPEED_MODE,
        .channel    = MOTOR_PWM_CHANNEL_A,
        .timer_sel  = MOTOR_PWM_TIMER,
        .duty       = 0,
        .hpoint     = 0,
        .intr_type  = LEDC_INTR_DISABLE,
    };
    ESP_ERROR_CHECK(ledc_channel_config(&channel_cfg));

    channel_cfg.gpio_num = MOTOR_AIN2_GPIO;
    channel_cfg.channel  = MOTOR_PWM_CHANNEL_B;
    ESP_ERROR_CHECK(ledc_channel_config(&channel_cfg));
}

// throttle: -1.0 (geri) .. 0 (boşta) .. +1.0 (ileri).
// DRV8833 sign-magnitude (fast decay) modu: bir IN PWM, diğeri LOW.
static void motor_set(float throttle)
{
    throttle = clampf(throttle, -1.0f, 1.0f);
    int duty = (int)(fabsf(throttle) * MOTOR_PWM_DUTY_MAX);

    int duty_a = (throttle >= 0.0f) ? duty : 0;
    int duty_b = (throttle <  0.0f) ? duty : 0;

    ledc_set_duty(MOTOR_PWM_SPEED_MODE, MOTOR_PWM_CHANNEL_A, duty_a);
    ledc_set_duty(MOTOR_PWM_SPEED_MODE, MOTOR_PWM_CHANNEL_B, duty_b);
    ledc_update_duty(MOTOR_PWM_SPEED_MODE, MOTOR_PWM_CHANNEL_A);
    ledc_update_duty(MOTOR_PWM_SPEED_MODE, MOTOR_PWM_CHANNEL_B);
}

void flight_software_init(void)
{
    motor_init();
    motor_set(0.0f);
    led_init();
    ESP_LOGI(TAG, "Motor driver ready: AIN1=GPIO%d AIN2=GPIO%d freq=%d Hz",
             MOTOR_AIN1_GPIO, MOTOR_AIN2_GPIO, MOTOR_PWM_FREQ_HZ);
    ESP_LOGI(TAG, "Onboard LED: GPIO%d (active LOW, mirrors motor state)", ONBOARD_LED_GPIO);
}

void flight_software_loop(void)
{
#if !defined(CONFIG_IDF_TARGET_ESP32) && !defined(CONFIG_IDF_TARGET_ESP32S3)
    uint64_t now_us = esp_timer_get_time();
    bool no_command_yet = (g_last_command_us == 0);
    bool timed_out = !no_command_yet && (now_us - g_last_command_us) > 500000ULL;
    bool inhibit = g_motor_emergency || no_command_yet || timed_out;

    float effective = inhibit ? 0.0f : g_motor_speed_target;
    motor_set(effective);

    bool motor_active = fabsf(effective) > 0.001f;
    led_set(motor_active);

    static bool prev_motor_active = false;
    if (motor_active != prev_motor_active) {
        ESP_LOGI(TAG, "MOTOR %s (target=%.2f)", motor_active ? "ON" : "OFF", effective);
        prev_motor_active = motor_active;
    }

    g_physical_loop_counter++;
    if ((g_physical_loop_counter % 5) == 0) {
        flight_telemetry_t telemetry = {
            .attitude_deg = { .roll = 0.0f, .pitch = 0.0f, .yaw = 0.0f },
            .servo_roll_deg = 0.0f,
            .servo_pitch_deg = 0.0f,
            .servo_yaw_deg = 0.0f,
            .motor_throttle = fabsf(effective),
            .emergency_active = inhibit,
            .loop_counter = g_physical_loop_counter,
        };
        comm_publish_telemetry(&telemetry);
    }

    vTaskDelay(pdMS_TO_TICKS(20));
#else
    vTaskDelay(pdMS_TO_TICKS(100));
#endif
}

// ============================================================================
// MAIN APPLICATION ENTRY POINT
// ============================================================================

void app_main(void)
{
    ESP_LOGI(TAG, "========================================");
    ESP_LOGI(TAG, "  ESP32 Application Starting...");
    ESP_LOGI(TAG, "========================================");
    
#if defined(CONFIG_IDF_TARGET_ESP32) || defined(CONFIG_IDF_TARGET_ESP32S3)  // QEMU test mode
    ESP_LOGW(TAG, ">>> QEMU Test Mode Active! <<<");
    ESP_LOGW(TAG, ">>> This is VIRTUAL ESP32, not real hardware <<<");
    ESP_LOGW(TAG, ">>> Flight Software implementation will come later <<<");
    printf("\n");
    
    // QEMU test initialization - runs system diagnostics
    qemu_test_init();
    
    ESP_LOGI(TAG, "Starting QEMU test loop...");
    ESP_LOGI(TAG, "Press Ctrl+] to exit");
    printf("\n");
    
    // QEMU test loop - continuous hardware testing
    qemu_test_loop();
    
#else
    // Physical hardware mode - initializes peripherals when board is available
    ESP_LOGI(TAG, "Physical Hardware Mode");
    flight_software_init();
#ifdef CONFIG_BT_BLE_ENABLED
    // Bluedroid NVS'i bağlanma/eşleşme bilgisi için kullanır; init etmeden BLE
    // "config_save: NVS not initialized" hatası verir ve advertise stabil değildir.
    esp_err_t nvs_ret = nvs_flash_init();
    if (nvs_ret == ESP_ERR_NVS_NO_FREE_PAGES || nvs_ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ESP_ERROR_CHECK(nvs_flash_init());
    }
    ble_transport_init();
#endif

    while (true) {
        flight_software_loop();
    }
#endif
}
