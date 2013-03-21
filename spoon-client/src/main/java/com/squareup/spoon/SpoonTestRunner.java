package com.squareup.spoon;

import android.os.Bundle;
import android.test.InstrumentationTestRunner;

/**
 * @author brho
 * 
 */
public class SpoonTestRunner extends InstrumentationTestRunner {

  private SpoonScreenshotServer screenshotServer;

  @Override
  public void onCreate(Bundle arguments) {
    screenshotServer = new SpoonScreenshotServer();
    screenshotServer.start();

    super.onCreate(arguments);
  }

  @Override
  public void onDestroy() {
    this.screenshotServer.finish();

    super.onDestroy();
  }

  /* package */void requestScreenshot(final String screenshotName, final String className,
      final String methodName, final int deviceOrientation) {

    screenshotServer.requestScreenshot(screenshotName, className, methodName, deviceOrientation);
  }

}
