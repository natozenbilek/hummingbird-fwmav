#include <math.h>
#include <stddef.h>
#include "flight_control.h"

float clampf(float value, float min, float max)
{
    if (value < min) {
        return min;
    }
    if (value > max) {
        return max;
    }
    return value;
}

bool imu_validate_reading(const imu_raw_data_t *data)
{
    if (data == NULL) {
        return false;
    }

    if (!isfinite(data->ax) || !isfinite(data->ay) || !isfinite(data->az) ||
        !isfinite(data->gx) || !isfinite(data->gy) || !isfinite(data->gz)) {
        return false;
    }

    const float accel_limit = FLIGHT_GRAVITY * 4.0f;
    const float gyro_limit = 1000.0f;

    if (fabsf(data->ax) > accel_limit || fabsf(data->ay) > accel_limit || fabsf(data->az) > accel_limit) {
        return false;
    }
    if (fabsf(data->gx) > gyro_limit || fabsf(data->gy) > gyro_limit || fabsf(data->gz) > gyro_limit) {
        return false;
    }

    return true;
}

void imu_apply_calibration(imu_raw_data_t *data, const imu_calibration_t *cal)
{
    if (data == NULL || cal == NULL) {
        return;
    }

    data->ax -= cal->accel_x_offset;
    data->ay -= cal->accel_y_offset;
    data->az -= cal->accel_z_offset;
    data->gx -= cal->gyro_x_offset;
    data->gy -= cal->gyro_y_offset;
    data->gz -= cal->gyro_z_offset;
}

void complementary_filter_reset(complementary_filter_t *filter, float alpha)
{
    if (filter == NULL) {
        return;
    }

    filter->alpha = clampf(alpha, 0.0f, 1.0f);
    filter->attitude.roll = 0.0f;
    filter->attitude.pitch = 0.0f;
    filter->attitude.yaw = 0.0f;
}

attitude_t complementary_filter_update(complementary_filter_t *filter, const imu_raw_data_t *raw, float dt)
{
    if (filter == NULL || raw == NULL) {
        attitude_t zero = {0};
        return zero;
    }

    const float accel_roll_rad = atan2f(-raw->ax, sqrtf(raw->ay * raw->ay + raw->az * raw->az));
    const float accel_pitch_rad = atan2f(raw->ay, raw->az);

    const float accel_roll_deg = accel_roll_rad * RAD_TO_DEG;
    const float accel_pitch_deg = accel_pitch_rad * RAD_TO_DEG;

    const float gyro_roll_delta = raw->gx * dt;
    const float gyro_pitch_delta = raw->gy * dt;

    filter->attitude.roll = filter->alpha * (filter->attitude.roll + gyro_roll_delta) + (1.0f - filter->alpha) * accel_roll_deg;
    filter->attitude.pitch = filter->alpha * (filter->attitude.pitch + gyro_pitch_delta) + (1.0f - filter->alpha) * accel_pitch_deg;
    filter->attitude.yaw += raw->gz * dt;

    return filter->attitude;
}

void pid_reset(pid_controller_t *pid, float kp, float ki, float kd)
{
    if (pid == NULL) {
        return;
    }

    pid->kp = kp;
    pid->ki = ki;
    pid->kd = kd;
    pid->integral = 0.0f;
    pid->prev_error = 0.0f;
}

float pid_update(pid_controller_t *pid, float error, float dt, float integral_limit)
{
    if (pid == NULL) {
        return 0.0f;
    }

    pid->integral += error * dt;
    pid->integral = clampf(pid->integral, -integral_limit, integral_limit);
    float derivative = 0.0f;
    if (dt > 0.0f) {
        derivative = (error - pid->prev_error) / dt;
    }

    float output = pid->kp * error + pid->ki * pid->integral + pid->kd * derivative;
    pid->prev_error = error;
    return output;
}


