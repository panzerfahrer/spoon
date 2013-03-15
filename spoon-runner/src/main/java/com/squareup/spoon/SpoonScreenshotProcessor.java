package com.squareup.spoon;

import static com.squareup.spoon.SpoonLogger.logError;
import static com.squareup.spoon.SpoonLogger.logInfo;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.commons.io.FileUtils;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

/**
 * Processes screenshots taken by {@link SpoonScreenshotClient}.
 * 
 * @author brho
 * 
 */
public class SpoonScreenshotProcessor implements ScreenshotProcessor {

  private static final int SCREEN_ORIENTATION_LANDSCAPE = 0;
  private static final int SCREEN_ORIENTATION_PORTRAIT = 1;
  private static final int SCREEN_ORIENTATION_REVERSE_LANDSCAPE = 8;
  private static final int SCREEN_ORIENTATION_REVERSE_PORTRAIT = 9;

  private final File outputDir;
  private final Multimap<DeviceTest, File> screenshotMap;

  /**
   * Create a new processor.
   * 
   * @param device the device screenshots belong to
   * @param output the local directory (not on the remote device!) where screenshots will be saved
   *          to
   */
  public SpoonScreenshotProcessor(IDevice device, File output) {
    this.outputDir = output;
    this.screenshotMap = HashMultimap.create();
  }

  @Override
  public void process(BufferedImage image, Map<String, Object> requestData) {
    logInfo("processing screenshot");

    int deviceOrientation = ((Integer) requestData.get(SpoonScreenshotClient.ARG_ORIENTATION)).intValue();
    String screenshotName = (String) requestData.get(SpoonScreenshotClient.ARG_SCREENSHOT_NAME);
    String methodName = (String) requestData.get(SpoonScreenshotClient.ARG_METHODNAME);
    String className = (String) requestData.get(SpoonScreenshotClient.ARG_METHODNAME);

    try {
      image = rotateScreenshot(image, deviceOrientation);

      File screenshot = FileUtils.getFile(outputDir, className, methodName, screenshotName);
      screenshot.getParentFile().mkdirs();
      screenshot.createNewFile();

      ImageIO.write(image, "png", screenshot);

      // Add screenshot to appropriate method result.
      DeviceTest testIdentifier = new DeviceTest(className, methodName);
      screenshotMap.put(testIdentifier, screenshot);

    } catch (IOException e) {
      logError("Couldn't write screenshot file: %s", e);
    }

  }

  private BufferedImage rotateScreenshot(BufferedImage image, int orienation) {
    int quadrantNum = 0;

    switch (orienation) {
    case SCREEN_ORIENTATION_PORTRAIT:
      quadrantNum = 0;
      break;

    case SCREEN_ORIENTATION_LANDSCAPE:
      if (image.getHeight() < image.getWidth()) {
        quadrantNum = 3;
      }
      break;

    case SCREEN_ORIENTATION_REVERSE_PORTRAIT:
      quadrantNum = 2;
      break;

    case SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
      quadrantNum = 1;
      break;

    }

    if (quadrantNum > 0) {
      AffineTransform transform = AffineTransform.getQuadrantRotateInstance(quadrantNum);
      AffineTransformOp op = new AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR);
      return op.filter(image, null);
    }

    return image;
  }

  /**
   * @return a map containing the processed screenshots
   */
  public Multimap<DeviceTest, File> getScreenshots() {
    return this.screenshotMap;
  }

}
