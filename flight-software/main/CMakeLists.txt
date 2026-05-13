# See the build system documentation in IDF programming guide
# for more information about component CMakeLists.txt files.

idf_component_register(
    SRCS main.c flight_control_math.c
    INCLUDE_DIRS .
    REQUIRES freertos spi_flash nvs_flash esp_timer esp_system driver bt
)
