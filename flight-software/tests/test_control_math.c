#include <assert.h>
#include <math.h>
#include <stdio.h>

#include "flight_control.h"

static void test_clampf(void)
{
    assert(clampf(0.5f, 0.0f, 1.0f) == 0.5f);
    assert(clampf(-1.0f, 0.0f, 1.0f) == 0.0f);
    assert(clampf(2.0f, 0.0f, 1.0f) == 1.0f);
}

static void test_imu_validate_reading(void)
{
    imu_raw_data_t valid = {
        .ax = 0.0f, .ay = 0.0f, .az = FLIGHT_GRAVITY,
        .gx = 0.0f, .gy = 0.0f, .gz = 0.0f,
    };
    assert(imu_validate_reading(&valid));

    imu_raw_data_t accel_out_of_range = valid;
    accel_out_of_range.ax = FLIGHT_GRAVITY * 5.0f;
    assert(!imu_validate_reading(&accel_out_of_range));

    imu_raw_data_t gyro_out_of_range = valid;
    gyro_out_of_range.gx = 1200.0f;
    assert(!imu_validate_reading(&gyro_out_of_range));
}

static void test_complementary_filter(void)
{
    complementary_filter_t filter = {0};
    complementary_filter_reset(&filter, 0.98f);
    assert(fabsf(filter.alpha - 0.98f) < 1e-6f);

    imu_raw_data_t raw = {
        .ax = 0.0f,
        .ay = 0.0f,
        .az = FLIGHT_GRAVITY,
        .gx = 0.0f,
        .gy = 0.0f,
        .gz = 0.0f,
    };

    const float dt = 0.01f;
    attitude_t att = complementary_filter_update(&filter, &raw, dt);
    assert(fabsf(att.roll) < 1e-3f);
    assert(fabsf(att.pitch) < 1e-3f);

    raw.gx = 10.0f; // deg/s
    att = complementary_filter_update(&filter, &raw, dt);
    assert(att.roll > 0.0f);
}

static void test_pid_controller(void)
{
    pid_controller_t pid = {0};
    pid_reset(&pid, 0.9f, 0.1f, 0.2f);

    float out1 = pid_update(&pid, 5.0f, 0.02f, 1.0f);
    (void)out1;

    // Integral should clamp to ±1.0
    float out2 = pid_update(&pid, 5.0f, 0.5f, 1.0f);
    (void)out2;
    assert(fabsf(pid.integral) <= 1.0001f);

    float prev_output = pid_update(&pid, 0.0f, 0.02f, 1.0f);
    (void)prev_output;
}

int main(void)
{
    test_clampf();
    test_imu_validate_reading();
    test_complementary_filter();
    test_pid_controller();

    printf("All control math tests passed.\n");
    return 0;
}


