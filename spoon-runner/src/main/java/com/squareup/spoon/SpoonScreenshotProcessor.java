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
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.github.rtyley.android.screenshot.paparazzo.processors.ScreenshotProcessor;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

/**
 * Processes screenshots taken by {@link SpoonScreenshotService}.
 * 
 * @author brho
 * 
 */
public class SpoonScreenshotProcessor implements ScreenshotProcessor, IShellOutputReceiver {

  private static final int SCREEN_ORIENTATION_LANDSCAPE = 0;
  private static final int SCREEN_ORIENTATION_PORTRAIT = 1;
  private static final int SCREEN_ORIENTATION_REVERSE_LANDSCAPE = 8;
  private static final int SCREEN_ORIENTATION_REVERSE_PORTRAIT = 9;

  private final IDevice device;
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
    this.device = device;
    this.outputDir = output;
    this.screenshotMap = HashMultimap.create();
  }

  @Override
  public void process(BufferedImage image, Map<String, String> requestData) {
    logInfo("processing screenshot");

    final int deviceOrientation = Integer.valueOf(requestData.get("orientation")).intValue();

    final String remoteFilePath = requestData.get("file");
    File remoteFile = new File(remoteFilePath);

    String methodName = remoteFile.getParentFile().getName();
    String className = remoteFile.getParentFile().getParentFile().getName();

    try {
      rotateScreenshot(image, deviceOrientation);
      
      File screenshot = FileUtils.getFile(outputDir, className, methodName, remoteFile.getName());
      screenshot.getParentFile().mkdirs();
      screenshot.createNewFile();

      ImageIO.write(image, "png", screenshot);

      // broadcast that we're finished
      broadcastFinished(remoteFilePath, true);

      // Add screenshot to appropriate method result.
      DeviceTest testIdentifier = new DeviceTest(className, methodName);
      screenshotMap.put(testIdentifier, screenshot);

    } catch (IOException e) {
      broadcastFinished(remoteFilePath, false);
      logError("Couldn't write screenshot file: %s", e);
    }

  }

  private void broadcastFinished(final String remoteFilePath, final boolean success) {
    StringBuilder cmd = new StringBuilder();
    cmd.append("am broadcast");
    cmd.append(" -a com.squareup.spoon.ScreenshotTaken");
    cmd.append(" --es com.squareup.spoon.ScreenshotTaken.File ");
    cmd.append(remoteFilePath);
    cmd.append(" --ez com.squareup.spoon.ScreenshotTaken.Success ");
    cmd.append(success);

    logInfo("executing on device: %s", cmd);

    try {
      device.executeShellCommand(cmd.toString(), this);
      // TODO how to handle exceptions here? retry?
    } catch (TimeoutException e) {
    } catch (AdbCommandRejectedException e) {
    } catch (ShellCommandUnresponsiveException e) {
    } catch (IOException e) {
    }
  }

  private void rotateScreenshot(BufferedImage image, int orienation) {
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
      image = op.filter(image, null);
    }

  }

  /**
   * @return a map containing the processed screenshots
   */
  public Multimap<DeviceTest, File> getScreenshots() {
    return this.screenshotMap;
  }

  @Override
  public void finish() {
  }

  @Override
  public void addOutput(byte[] data, int offset, int length) {
    logInfo("shell response: %s", new String(data));
  }

  @Override
  public void flush() {
  }

  @Override
  public boolean isCancelled() {
    return false;
  }

}
