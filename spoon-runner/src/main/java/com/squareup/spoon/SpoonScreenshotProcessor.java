package com.squareup.spoon;

import static com.squareup.spoon.SpoonLogger.logError;
import static com.squareup.spoon.SpoonLogger.logInfo;

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

    final String remoteFilePath = requestData.get("file");
    File remoteFile = new File(remoteFilePath);

    String methodName = remoteFile.getParentFile().getName();
    String className = remoteFile.getParentFile().getParentFile().getName();

    try {
      File screenshot = FileUtils.getFile(outputDir, className, methodName, remoteFile.getName());
      screenshot.getParentFile().mkdirs();
      screenshot.createNewFile();

      ImageIO.write(image, "png", screenshot);

      // delete file on remote device
      String cmd = String.format("rm %s", remoteFilePath);
      logInfo("executing on device: %s", cmd);
      device.executeShellCommand(cmd, this);

      // Add screenshot to appropriate method result.
      DeviceTest testIdentifier = new DeviceTest(className, methodName);
      screenshotMap.put(testIdentifier, screenshot);

    } catch (IOException e) {
      logError("Couldn't write screenshot file: %s", e);
    } catch (TimeoutException e) {
      logError("Couldn't delete temp-screenshot file on the device: %s", e);
    } catch (AdbCommandRejectedException e) {
      logError("Couldn't delete temp-screenshot file on the device: %s", e);
    } catch (ShellCommandUnresponsiveException e) {
      logError("Couldn't delete temp-screenshot file on the device: %s", e);
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
    logInfo("shell response from delete: %s", new String(data));
  }

  @Override
  public void flush() {
  }

  @Override
  public boolean isCancelled() {
    return false;
  }

}
