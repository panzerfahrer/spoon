package com.squareup.spoon;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.BufType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundMessageHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

  private int adbPort;
  private Socket socket;
  private boolean isConnected;

  private Channel clientChannel;

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

  private void waitABit() {
    try {
      Thread.sleep(DEVICE_POLL_INTERVAL_MSEC);
    } catch (InterruptedException e) {
    }
  }

  @Override
  public void run() {

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

    Bootstrap bootstrap = new Bootstrap();
    try {
      bootstrap.group(new NioEventLoopGroup());
      bootstrap.channel(NioSocketChannel.class);
      bootstrap.handler(new ClientInitializer());

      ChannelFuture client = bootstrap.connect("localhost", this.adbPort);
      clientChannel = client.sync().channel();

    } catch (InterruptedException e) {
    } finally {
      bootstrap.shutdown();
    }

  }

  private class ClientInitializer extends ChannelInitializer<SocketChannel> {

    private final StringDecoder DECODER = new StringDecoder(CharsetUtil.UTF_8);
    private final StringEncoder ENCODER = new StringEncoder(BufType.BYTE, CharsetUtil.UTF_8);
    private final ClientHandler HANDLER = new ClientHandler();

    @Override
    protected void initChannel(SocketChannel channel) throws Exception {
      ChannelPipeline pipeline = channel.pipeline();

      pipeline.addLast("framer", new DelimiterBasedFrameDecoder(512, Delimiters.lineDelimiter()));
      pipeline.addLast("decoder", DECODER);
      pipeline.addLast("encoder", ENCODER);
      pipeline.addLast("handler", HANDLER);
    }

  }

  private class ClientHandler extends ChannelOutboundMessageHandlerAdapter<String> {

    @Override
    public void flush(ChannelHandlerContext ctx, String response) throws Exception {

      if (response == null) {
        SpoonLogger.logInfo("empty server response");
        return;
      }

      String[] argsStripped = null;
      if (response.startsWith(CMD.ARGUMENTS.toString())) {
        argsStripped = response.split("|");
        response = argsStripped[0];
      }

      switch (CMD.valueOf(response)) {
      case START:
        ctx.write(CMD.ARGUMENTS.toString());
        break;

      case ARGUMENTS:
        SpoonScreenshotClient.this.argumentsMap.put(ARG_SCREENSHOT_NAME, argsStripped[1]);
        SpoonScreenshotClient.this.argumentsMap.put(ARG_CLASSNAME, argsStripped[2]);
        SpoonScreenshotClient.this.argumentsMap.put(ARG_METHODNAME, argsStripped[3]);
        Integer orientation = Integer.valueOf(argsStripped[4]);
        SpoonScreenshotClient.this.argumentsMap.put(ARG_ORIENTATION, orientation);
        ctx.write(CMD.CAPTURE_READY.toString());
        break;

      case CAPTURE:
        SpoonScreenshotClient.this.takeScreenshot();
        ctx.write(CMD.CAPTURE_FINISHED.toString());
        break;

      case FINISHED:
        // clean up
        SpoonScreenshotClient.this.argumentsMap.clear();
        break;

      default:
        SpoonLogger.logError("Unexpected command: %s", response);
        break;

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

    } while (!this.isConnected || this.socket == null);
  }

  public void finish() {
    this.clientChannel.closeFuture().syncUninterruptibly();
  }

  /**
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
