#pragma once

#include <stdbool.h>
#include <stdint.h>

#define DEG_TO_RAD        (0.0174532925f)
#define RAD_TO_DEG        (57.2957795131f)
#define FLIGHT_GRAVITY    (9.80665f)
#define SERVO_MAX_DEFLECTION_DEG  (30.0f)
#define MOTOR_MIN_THROTTLE        (0.0f)
#define MOTOR_MAX_THROTTLE        (1.0f)
#define MOTOR_BASE_THROTTLE       (0.50f)

typedef struct {
    float ax;
    float ay;
    float az;
    float gx;
    float gy;
    float gz;
} imu_raw_data_t;

typedef struct {
    float accel_x_offset;
    float accel_y_offset;
    float accel_z_offset;
    float gyro_x_offset;
    float gyro_y_offset;
    float gyro_z_offset;
} imu_calibration_t;

typedef struct {
    float roll;
    float pitch;
    float yaw;
} attitude_t;

typedef struct {
    float alpha;
    attitude_t attitude;
} complementary_filter_t;

typedef struct {
    float kp;
    float ki;
    float kd;
    float integral;
    float prev_error;
} pid_controller_t;

typedef struct {
    complementary_filter_t filter;
    pid_controller_t pid_roll;
    pid_controller_t pid_pitch;
    imu_calibration_t calibration;
    float integral_roll_limit;
    float integral_pitch_limit;
    float throttle_mix_gain;
    bool emergency_active;
    bool target_override_active;
    float base_throttle_setpoint;
    float yaw_trim_deg;
    attitude_t target_attitude;
    attitude_t current_attitude;
    float servo_roll_deg;
    float servo_pitch_deg;
    float servo_yaw_deg;
    float motor_throttle;
    uint64_t last_update_us;
    uint32_t loop_counter;
    bool rise_active;
    uint64_t rise_start_us;
} flight_control_state_t;

typedef struct {
    float roll_deg;
    float pitch_deg;
    float throttle;
    float yaw_trim_deg;
    bool emergency_stop;
    bool clear_emergency;
    bool rise_command;
} flight_command_t;

typedef struct {
    attitude_t attitude_deg;
    float servo_roll_deg;
    float servo_pitch_deg;
    float servo_yaw_deg;
    float motor_throttle;
    bool emergency_active;
    uint32_t loop_counter;
} flight_telemetry_t;

float clampf(float value, float min, float max);
bool imu_validate_reading(const imu_raw_data_t *data);
void imu_apply_calibration(imu_raw_data_t *data, const imu_calibration_t *cal);
void complementary_filter_reset(complementary_filter_t *filter, float alpha);
attitude_t complementary_filter_update(complementary_filter_t *filter, const imu_raw_data_t *raw, float dt);
void pid_reset(pid_controller_t *pid, float kp, float ki, float kd);
float pid_update(pid_controller_t *pid, float error, float dt, float integral_limit);


