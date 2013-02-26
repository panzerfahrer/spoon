package com.squareup.spoon;

import com.android.ddmlib.IDevice;
import com.github.rtyley.android.screenshot.paparazzo.OnDemandScreenshotService;

/**
 * Listens for screenshot requests during a test run and takes screenshots via DDMS.
 * 
 * @author brho
 * 
 */
public class SpoonScreenshotService extends OnDemandScreenshotService {

  /**
   * Create a new screenshot service
   * 
   * @param device the device to take screenshots from
   * @param processor a {@link SpoonScreenshotProcessor} that will get the images handed through
   */
  public SpoonScreenshotService(IDevice device, SpoonScreenshotProcessor processor) {
    super(device, processor);
  }

  @Override
  public void start() {
    super.start();
    SpoonLogger.logInfo("SpoonScreenshotService started");
  }

  @Override
  public void finish() {
    super.finish();
    SpoonLogger.logInfo("SpoonScreenshotService finished");
  }

}
