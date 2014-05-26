package com.squareup.spoon;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.MultiLineReceiver;
import com.android.ddmlib.RawImage;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.squareup.spoon.SpoonScreenshotServer.CMD;

/**
 * Listens for screenshot requests during a test run and takes screenshots via DDMS.
 * 
 * @author brho
 * 
 */
public class SpoonScreenshotClient extends Thread {

  private static final String LOG_CMD = "logcat -v raw -b main %s:I *:S";
  private static final Charset UTF_8 = Charset.forName("UTF-8");
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
  private final ArrayBlockingQueue<String> pendingLines;

  private boolean isRunning;
  private int adbPort;
  private ServerInfoReceiver serverInfoReceiver;
  private SocketChannel socket;

  /**
   * Create a new screenshot service
   * 
   * @param device the device to take screenshots from
   * @param processor a {@link SpoonScreenshotProcessor} that will get the images handed through
   */
  public SpoonScreenshotClient(final IDevice device, ScreenshotProcessor... processors) {
    super("SpoonScreenshotClient");

    this.device = device;
    this.processors = Arrays.asList(processors);
    this.argumentsMap = new HashMap<String, Object>(5);
    this.serverInfoReceiver = new ServerInfoReceiver();
    this.pendingLines = new ArrayBlockingQueue<String>(10);

    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          String logcat = String.format(LOG_CMD, SpoonScreenshotServer.TAG);
          device.executeShellCommand(logcat, serverInfoReceiver);
        } catch (TimeoutException e) {
          SpoonLogger.logError("Unable to get server info. Check connection.");
        } catch (AdbCommandRejectedException e) {
          SpoonLogger.logError("Unable to get server info. Check connection.");
        } catch (ShellCommandUnresponsiveException e) {
          SpoonLogger.logError("Unable to get server info. Check connection.");
        } catch (IOException e) {
          SpoonLogger.logError("Unable to get server info. Check connection.");
        }
      }
    }).start();
  }

  @Override
  public void run() {
    this.isRunning = true;

    while (this.serverInfoReceiver.getServerPort() < 0) {
      SpoonLogger.logError("Waiting for server information");
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

  private void clientMainLoop() throws IOException, TimeoutException {
    InetSocketAddress server = new InetSocketAddress("localhost", this.adbPort);
    this.socket = SocketChannel.open(server);
    this.socket.configureBlocking(false);
    this.socket.socket().setTcpNoDelay(true);

    byte[] data = new byte[1024];
    ByteBuffer buffer = ByteBuffer.wrap(data);

    try {
      do {
        String response = read(buffer);

        if (response == null) {
          SpoonLogger.logInfo("empty server response");
          continue;
        }

        switch (CMD.valueOf(response)) {
        case START:
          write(CMD.ARGUMENTS.toString());
          break;

        case ARGUMENTS:
          String screenshotName = read(buffer);
          String className = read(buffer);
          String methodName = read(buffer);
          Integer orientation = Integer.parseInt(read(buffer));

          this.argumentsMap.put(ARG_SCREENSHOT_NAME, screenshotName);
          this.argumentsMap.put(ARG_CLASSNAME, className);
          this.argumentsMap.put(ARG_METHODNAME, methodName);
          this.argumentsMap.put(ARG_ORIENTATION, orientation);

          write(CMD.CAPTURE_READY.toString());
          break;

        case CAPTURE:
          takeScreenshot();
          write(CMD.CAPTURE_FINISHED.toString());
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
      
    } finally {
      if (this.socket != null) {
        this.socket.close();
      }
    }

  }

  private String read(ByteBuffer buffer) throws IOException {
    int readCount;
    readCount = this.socket.read(buffer);

    if (readCount < 0) {
      // connection gone
      throw new IOException("channel EOF");
    } else if (readCount == 0) {
      waitABit();
    } else {
      String newLines = new String(buffer.array(), buffer.arrayOffset(), buffer.position(), UTF_8);
      buffer.rewind();

      for (String line : newLines.split("\n")) {
        pendingLines.add(line);
      }
    }

    return pendingLines.poll();
  }

  private void write(String data) throws IOException, TimeoutException {
    StringBuilder sb = new StringBuilder(data);
    sb.append("\n");
    
    byte[] dataBytes = sb.toString().getBytes(UTF_8);
    ByteBuffer buf = ByteBuffer.wrap(dataBytes, 0, dataBytes.length);
    int numWaits = 0;

    while (buf.position() != buf.limit()) {
      int count;

      count = this.socket.write(buf);
      if (count < 0) {
        throw new IOException("channel EOF");
      } else if (count == 0) {
        // TODO: need more accurate timeout?
        if (numWaits > 5) {
          throw new TimeoutException();
        }
        // non-blocking spin
        waitABit();
        numWaits++;
      } else {
        numWaits = 0;
      }
    }
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

      SpoonLogger.logInfo("port forwarding %d -> %d", this.adbPort,
          this.serverInfoReceiver.getServerPort());

      try {
        this.device.createForward(this.adbPort, this.serverInfoReceiver.getServerPort());
        return true;
      } catch (Exception e) {
        return false;
      }
    }

    return false;
  }

  public void finish() {
    this.serverInfoReceiver.cancel();
    this.isRunning = false;
  }

  /**
   * @author https://github.com/rtyley
   * @see com.github.rtyley.android.screenshot.paparazzo.OnDemandScreenshotService
   */
  private void takeScreenshot() {
    SpoonLogger.logInfo("taking screenshot from %s", this.device.getSerialNumber());

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

  private static final class ServerInfoReceiver extends MultiLineReceiver {

    private boolean isCancelled;
    private int serverPort = -1;

    public ServerInfoReceiver() {
      setTrimLine(false);
    }

    @Override
    public void processNewLines(String[] lines) {
      List<String> lineList = Arrays.asList(lines);
      Collections.reverse(lineList); // process list beginning with the last entry

      for (String data : lineList) {
        if (data.startsWith("port:")) {
          SpoonLogger.logInfo("server info: %s", data);
          String port = data.split(":")[1];
          try {
            this.serverPort = Integer.parseInt(port);
            this.cancel();
            return;
          } catch (NumberFormatException e) {
          }
        }
      }
    }

    @Override
    public boolean isCancelled() {
      return this.isCancelled;
    }

    public int getServerPort() {
      return serverPort;
    }

    public void cancel() {
      this.isCancelled = true;
    }

  }

}
