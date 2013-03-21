package com.squareup.spoon;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import android.text.TextUtils;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.RawImage;
import com.squareup.spoon.SpoonScreenshotServer.CMD;

/**
 * Listens for screenshot requests during a test run and takes screenshots via DDMS.
 * 
 * @author brho
 * 
 */
public class SpoonScreenshotClient extends Thread {

  private static final int DEVICE_POLL_INTERVAL_MSEC = 1000;

  /**
   * Device orientation during screenshot<br>
   * Type: Integer
   */
  public static final String ARG_ORIENTATION = "orientation";

  /**
   * Name of the screenshot<br>
   * Type: String
   */
  public static final String ARG_SCREENSHOT_NAME = "screenshotname";

  /**
   * Name of test class<br>
   * Type: String
   */
  public static final String ARG_CLASSNAME = "classname";

  /**
   * Name of the test method<br>
   * Type: String
   */
  public static final String ARG_METHODNAME = "methodname";

  private final IDevice device;
  private final List<ScreenshotProcessor> processors;
  private final Map<String, Object> argumentsMap;

  private boolean isRunning;
  private int adbPort;
  private Socket socket;
  private boolean isConnected;

  /**
   * Create a new screenshot service
   * 
   * @param device the device to take screenshots from
   * @param processor a {@link SpoonScreenshotProcessor} that will get the images handed through
   */
  public SpoonScreenshotClient(IDevice device, ScreenshotProcessor... processors) {
    super("SpoonScreenshotClient");

    this.device = device;
    this.processors = Arrays.asList(processors);
    this.argumentsMap = new HashMap<String, Object>(5);
  }

  @Override
  public void run() {
    this.isRunning = true;

    // Wait while the device comes online.
    while (device.isOffline()) {
      waitABit();
    }

    int setupTries = 0;
    boolean setupSuccess = false;
    while (setupTries <= 3 && !setupSuccess) {
      setupTries++;
      setupSuccess = setupPortForwarding();
    }

    if (!setupSuccess) {
      SpoonLogger.logError("Unable to set up port forwarding. Check connection.");
      return;
    }

    try {
      clientMainLoop();
    } catch (Exception e) {
      SpoonLogger.logError("Error interacting with screenshot server: %s", e);
      e.printStackTrace(System.err);
    }

  }

  private void waitABit() {
    try {
      Thread.sleep(DEVICE_POLL_INTERVAL_MSEC);
    } catch (InterruptedException e) {
    }
  }

  private void clientMainLoop() throws IOException {
    ObjectInputStream ois = null;
    ObjectOutputStream oos = null;

    do {

      if (this.socket == null) {
        connectToDevice();
      }

      if (ois == null || oos == null) {
        try {
          oos = new ObjectOutputStream(new BufferedOutputStream(this.socket.getOutputStream()));
          ois = new ObjectInputStream(new BufferedInputStream(this.socket.getInputStream()));
        } catch (IOException e) {
          waitABit();
          continue;
        }
      }

      String response = readServerResponse(ois);

      if (response == null) {
        SpoonLogger.logInfo("empty server response");
        continue;
      }

      switch (CMD.valueOf(response)) {
      case START:
        oos.writeObject(CMD.ARGUMENTS.toString());
        break;

      case ARGUMENTS:
        try {
          String screenshotName = (String) ois.readObject();
          String className = (String) ois.readObject();
          String methodName = (String) ois.readObject();
          Integer orientation = (Integer) ois.readObject();

          this.argumentsMap.put(ARG_SCREENSHOT_NAME, screenshotName);
          this.argumentsMap.put(ARG_CLASSNAME, className);
          this.argumentsMap.put(ARG_METHODNAME, methodName);
          this.argumentsMap.put(ARG_ORIENTATION, orientation);

          oos.writeObject(CMD.CAPTURE_READY.toString());
        } catch (ClassNotFoundException e) {
          oos.writeObject(CMD.ERROR.toString());
        }

        break;

      case CAPTURE:
        takeScreenshot();
        oos.writeObject(CMD.CAPTURE_FINISHED.toString());
        break;

      case FINISHED:
        // clean up
        this.argumentsMap.clear();
        break;

      default:
        SpoonLogger.logError("Unexpected command: %s", response);
        break;

      }
    } while (this.isRunning);

    this.isConnected = false;

    if (this.socket != null) {
      this.socket.shutdownInput();
      this.socket.shutdownOutput();
      this.socket.close();
    }
  }

  private String readServerResponse(final ObjectInputStream ois) {
    String response = null;

    try {
      response = (String) ois.readObject();
    } catch (ClassNotFoundException e) {
    } catch (IOException e) {
    }

    return response;
  }

  /**
   * Find a free port and set up ADB port forwarding. If successful, the port will be set to
   * {@link #adbPort}.
   * 
   * @return <code>true</code> if port forwarding has been established successfully,
   *         <code>false</code> otherwise
   * 
   */
  private boolean setupPortForwarding() {

    if (this.device != null) {
      ServerSocket tmpSocket = null;

      try {
        tmpSocket = new ServerSocket(0);
        this.adbPort = tmpSocket.getLocalPort();
      } catch (IOException e) {
        throw new RuntimeException("Couldn't select a free port.", e);
      } finally {
        if (tmpSocket != null) {
          try {
            tmpSocket.close();
          } catch (IOException e) {
          }
        }
      }

      SpoonLogger.logInfo("port forwarding %d -> 42042", this.adbPort);

      try {
        this.device.createForward(this.adbPort, 42042);
        return true;
      } catch (Exception e) {
        return false;
      }
    }

    return false;
  }

  private void connectToDevice() {
    do {
      try {
        this.socket = new Socket("localhost", this.adbPort);
        this.isConnected = true;
      } catch (IOException e) {
        SpoonLogger.logError("failed to connect to device: %s", e);
        this.isConnected = false;
      }

      if (!this.isConnected || this.socket == null) {
        this.isConnected = false;

        SpoonLogger.logInfo("waiting for screenshot server");
        waitABit();
      }

    } while (this.isRunning && (!this.isConnected || this.socket == null));
  }

  public void finish() {
    this.isRunning = false;
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
      SpoonLogger.logError("Exception getting raw image data for screenshot: %s", e);
      return;
    }

    if (rawImage == null) {
      SpoonLogger.logError("No image data returned for screenshot");
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
