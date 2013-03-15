package com.squareup.spoon;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.RawImage;

/**
 * Listens for screenshot requests during a test run and takes screenshots via DDMS.
 * 
 * @author brho
 * 
 */
public class SpoonScreenshotClient implements Runnable {

  public static final String ARG_ORIENTATION = "orientation";
  public static final String ARG_SCREENSHOT_NAME = "screenshotname";
  public static final String ARG_CLASSNAME = "classname";
  public static final String ARG_METHODNAME = "methodname";
  
  private static enum CMD {
    START, START_READY, CAPTURE, CAPTURE_FINISHED, ARGUMENTS, FINISHED, ERROR;
  }

  private final int devicePort;
  private final IDevice device;
  private final List<ScreenshotProcessor> processors;
  private final Map<String, Object> argumentsMap;

  private boolean running;
  private Socket socket;
  private boolean isConnected;

  /**
   * Create a new screenshot service
   * 
   * @param device the device to take screenshots from
   * @param processor a {@link SpoonScreenshotProcessor} that will get the images handed through
   */
  public SpoonScreenshotClient(IDevice device, ScreenshotProcessor... processors) {
    this.device = device;
    this.processors = Arrays.asList(processors);
    this.argumentsMap = new HashMap<String, Object>(5);

    this.devicePort = 0;
    // TODO adb port forwarding
  }

  @Override
  public void run() {
    this.running = true;

    connectToDevice();

    try {
      BufferedReader reader = new BufferedReader(
          new InputStreamReader(this.socket.getInputStream()));
      PrintWriter writer = new PrintWriter(new OutputStreamWriter(this.socket.getOutputStream()));

      while (this.running) {
        String response = reader.readLine();

        switch (CMD.valueOf(response)) {
        case START:
          writer.println(CMD.START_READY);
          break;

        case ARGUMENTS:
          if(CMD.valueOf(reader.readLine()) == CMD.ARGUMENTS){
            String screenshotName = reader.readLine();
            String className = reader.readLine();
            String methodName = reader.readLine();
            Integer orientation = Integer.valueOf(reader.readLine());
            
            this.argumentsMap.put(ARG_SCREENSHOT_NAME, screenshotName);
            this.argumentsMap.put(ARG_CLASSNAME, className);
            this.argumentsMap.put(ARG_METHODNAME, methodName);
            this.argumentsMap.put(ARG_ORIENTATION, orientation);
          } else {
            writer.println(CMD.ERROR);
          }
          break;
          
        case CAPTURE:
          takeScreenshot();
          writer.println(CMD.CAPTURE_FINISHED);
          break;

        case FINISHED:
          this.running = false;
          break;
          
        }
      }
      
      writer.close();
      reader.close();
      this.socket.close();
      
    } catch (IOException e) {

    }

  }

  private void connectToDevice() {
    while (this.running) {
      try {
        this.socket = new Socket("localhost", this.devicePort);
        this.isConnected = true;
      } catch (UnknownHostException e) {
        throw new RuntimeException("Couldn't set up screenshot client.", e);
      } catch (IOException e) {
        this.isConnected = false;
      }

      if (!this.isConnected) {
        try {
          Thread.sleep(250);
        } catch (InterruptedException e) {
        }
      } else {
        break;
      }
    }
  }

  public void finish() {
    this.running = false;
  }

  /**
   * @param logLine
   * @author https://github.com/rtyley
   * @see com.github.rtyley.android.screenshot.paparazzo.OnDemandScreenshotService
   */
  private void takeScreenshot() {
    RawImage rawImage;
    try {
      rawImage = this.device.getScreenshot();
    } catch (Exception e) {
      // log.warn("Exception getting raw image data for screenshot", e);
      return;
    }

    if (rawImage == null) {
      // log.warn("No image data returned for screenshot");
      return;
    }

    BufferedImage image = bufferedImageFrom(rawImage);

    for (ScreenshotProcessor screenshotProcessor : this.processors) {
      screenshotProcessor.process(image, this.argumentsMap);
    }
  }

  /**
   * @param rawImage
   * @return
   * @author https://github.com/rtyley
   * @see com.github.rtyley.android.screenshot.paparazzo.OnDemandScreenshotService
   */
  private static BufferedImage bufferedImageFrom(RawImage rawImage) {
    BufferedImage image = new BufferedImage(rawImage.width, rawImage.height,
        BufferedImage.TYPE_INT_ARGB);

    int index = 0;
    int bytesPerPixel = rawImage.bpp >> 3;
    for (int y = 0; y < rawImage.height; y++) {
      for (int x = 0; x < rawImage.width; x++) {
        image.setRGB(x, y, rawImage.getARGB(index) | 0xff000000);
        index += bytesPerPixel;
      }
    }
    return image;
  }

}
