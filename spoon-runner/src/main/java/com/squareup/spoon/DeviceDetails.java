package com.squareup.spoon;

import com.android.ddmlib.IDevice;
import org.apache.commons.lang3.builder.ToStringBuilder;

import static com.google.common.base.Strings.emptyToNull;

/** Device configuration and hardware information. */
public final class DeviceDetails {
  private final String model;
  private final String manufacturer;
  private final String version;
  private final int apiLevel;
  private final String language;
  private final String region;
  private final boolean isEmulator;
  private final String avdName;
  private final String serialNumber;

  private DeviceDetails(String model, String manufacturer, String version, int apiLevel,
      String language, String region, boolean emulator, String avdName, String serialNumber) {
    this.model = model;
    this.manufacturer = manufacturer;
    this.version = version;
    this.apiLevel = apiLevel;
    this.language = language;
    this.region = region;
    this.isEmulator = emulator;
    this.avdName = avdName;
    this.serialNumber = serialNumber;
  }

  /** Product manufacturer and model, or AVD name and serial number if an emulator. */
  public String getName() {
    if (isEmulator) {
      return avdName + " " + serialNumber;
    } else {
      return manufacturer + " " + model;
    }
  }

  /** Product model. */
  public String getModel() {
    return model;
  }

  /** Produce manufacturer. */
  public String getManufacturer() {
    return manufacturer;
  }

  /** Android version. */
  public String getVersion() {
    return version;
  }

  /** Android API level. */
  public int getApiLevel() {
    return apiLevel;
  }

  /** Device language. */
  public String getLanguage() {
    return language;
  }

  /** Device region. */
  public String getRegion() {
    return region;
  }

  /** Is emulator. */
  public boolean isEmulator() {
    return isEmulator;
  }

  /** AVD name. */
  public String getAvdName() {
    return avdName;
  }

  /** Serial number. */
  public String getSerialNumber() {
    return serialNumber;
  }

  static DeviceDetails createForDevice(IDevice device) {
    String manufacturer = emptyToNull(device.getProperty("ro.product.manufacturer"));
    String model = emptyToNull(device.getProperty("ro.product.model"));
    model = DeviceUtils.scrubModel(manufacturer, model);

    String version = emptyToNull(device.getProperty("ro.build.version.release"));
    String api = emptyToNull(device.getProperty("ro.build.version.sdk"));
    int apiLevel = api != null ? Integer.parseInt(api) : 0;

    String language = emptyToNull(device.getProperty("ro.product.locale.language"));
    language = DeviceUtils.scrubLanguage(language);

    String region = emptyToNull(device.getProperty("ro.product.locale.region"));

    boolean emulator = device.isEmulator();
    String avdName = emptyToNull(device.getAvdName());
    String serialNumber = emptyToNull(device.getSerialNumber());

    return new DeviceDetails(model, manufacturer, version, apiLevel, language, region, emulator,
        avdName, serialNumber);
  }

  @Override public String toString() {
    return ToStringBuilder.reflectionToString(this);
  }
}
