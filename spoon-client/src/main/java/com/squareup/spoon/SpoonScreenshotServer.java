package com.squareup.spoon;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.BufType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundMessageHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;

import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import android.util.Log;

/*package*/class SpoonScreenshotServer extends Thread {

  private static final String TAG = "SpoonScreenshotServer";

  public static enum CMD {
    START, CAPTURE_READY, CAPTURE, CAPTURE_FINISHED, ARGUMENTS, FINISHED, ERROR, SHUTDOWN;
  }

  private final AtomicReference<ScreenshotRequest> request;
  private Channel serverChannel;

  /**
   * @param timeout
   */
  public SpoonScreenshotServer() {
    super("SpoonScreenshotServer");

    this.request = new AtomicReference<ScreenshotRequest>();
  }

  /**
   * Blocks until the screenshot is taken.
   * 
   * @param screenshotName
   * @param className
   * @param methodName
   * @param deviceOrientation
   */
  public void requestScreenshot(String screenshotName, String className, String methodName,
      int deviceOrientation) {

    ScreenshotRequest newReq = new ScreenshotRequest(screenshotName, className, methodName,
        deviceOrientation);

    this.request.set(newReq);
    this.serverChannel.write(CMD.START.toString());

    while (!newReq.isFinished()) {
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
      }
    }

    this.request.set(null);
  }

  @Override
  public void run() {

    ServerBootstrap bootstrap = new ServerBootstrap();
    try {
      bootstrap.group(new NioEventLoopGroup(), new NioEventLoopGroup());
      bootstrap.channel(NioServerSocketChannel.class);
      bootstrap.localAddress(42042);

      ServerInitializer.init(this.request);
      bootstrap.childHandler(ServerInitializer.instance());

      serverChannel = bootstrap.bind().sync().channel();
      // Wait until the server socket is closed.
      serverChannel.closeFuture().sync();

    } catch (Exception e) {
      Log.w(TAG, e);
    } finally {
      bootstrap.shutdown();
    }

  }

  private static class ServerInitializer extends ChannelInitializer<SocketChannel> {

    private static final Charset UTF8 = CharsetUtil.UTF_8;
    private static final StringDecoder DECODER = new StringDecoder(UTF8);
    private static final StringEncoder ENCODER = new StringEncoder(BufType.BYTE, UTF8);

    private static ServerHandler handler;
    private static ServerInitializer instance;

    public static void init(AtomicReference<ScreenshotRequest> request) {
      handler = new ServerHandler(request);
      instance = new ServerInitializer();
    }

    public static ServerInitializer instance() {
      return instance;
    }

    @Override
    protected void initChannel(SocketChannel channel) throws Exception {
      ChannelPipeline pipeline = channel.pipeline();

      pipeline.addLast("framer", new DelimiterBasedFrameDecoder(512, Delimiters.lineDelimiter()));
      pipeline.addLast("decoder", DECODER);
      pipeline.addLast("encoder", ENCODER);
      pipeline.addLast("handler", handler);      
    }

  }

  private static class ServerHandler extends ChannelInboundMessageHandlerAdapter<String> {

    private final AtomicReference<ScreenshotRequest> request;

    public ServerHandler(AtomicReference<ScreenshotRequest> request) {
      super();

      this.request = request;
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, String msg) throws Exception {
      if (msg == null) {
        Log.i(TAG, "empty request");
        return;
      }

      switch (CMD.valueOf(msg)) {
      case CAPTURE_READY:
        ctx.write(CMD.CAPTURE.toString());
        break;

      case ARGUMENTS:
        ScreenshotRequest screenshotRequest = request.get();

        StringBuilder sb = new StringBuilder();
        sb.append(CMD.ARGUMENTS.toString());
        sb.append('|');
        sb.append(screenshotRequest.screenshotName);
        sb.append('|');
        sb.append(screenshotRequest.className);
        sb.append('|');
        sb.append(screenshotRequest.methodName);
        sb.append('|');
        sb.append(screenshotRequest.deviceOrientation);

        ctx.write(sb.toString());
        break;

      case CAPTURE_FINISHED:
        request.get().markFinished();
        ctx.write(CMD.FINISHED.toString());
        break;

      case ERROR:
        Log.w(TAG, "error: " + request);
        break;

      default:
        Log.e(TAG, "Unexpected response: " + request);
        break;
      }
    }

  }

  public void finish() {
    serverChannel.closeFuture().awaitUninterruptibly();
  }

  private static final class ScreenshotRequest {

    public final String screenshotName;
    public final String className;
    public final String methodName;
    public final Integer deviceOrientation;

    private AtomicBoolean isFinished;

    public ScreenshotRequest(String screenshotName, String className, String methodName,
        int deviceOrientation) {

      this.screenshotName = screenshotName;
      this.className = className;
      this.methodName = methodName;
      this.deviceOrientation = Integer.valueOf(deviceOrientation);
      this.isFinished = new AtomicBoolean();
    }

    public void markFinished() {
      this.isFinished.set(true);
    }

    public boolean isFinished() {
      return this.isFinished.get();
    }
  }

}