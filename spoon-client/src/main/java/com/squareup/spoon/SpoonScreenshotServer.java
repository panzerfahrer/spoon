package com.squareup.spoon;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import android.util.Log;

/* package */class SpoonScreenshotServer extends Thread {

  /* package */static final String TAG = "SpoonScreenshotServer";

  private static final Charset UTF_8 = Charset.forName("UTF-8");

  public static enum CMD {
    START, CAPTURE_READY, CAPTURE, CAPTURE_FINISHED, ARGUMENTS, FINISHED, ERROR;
  }

  private final AtomicBoolean isRunning;
  private final AtomicReference<ScreenshotRequest> request;

  private ServerSocketChannel socket;

  /**
   *
   */
  public SpoonScreenshotServer() {
    super("SpoonScreenshotServer");

    this.isRunning = new AtomicBoolean();
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

    final ScreenshotRequest newRequest = new ScreenshotRequest(screenshotName, className,
        methodName, deviceOrientation);
    this.request.set(newRequest);

    try {
      newRequest.waitUntilFinished();
    } catch (InterruptedException e) {
    }

    this.request.set(null);
  }

  public boolean isRunning() {
    return this.isRunning.get();
  }

  @Override
  public void run() {
    this.isRunning.set(true);

    try {
      this.socket = ServerSocketChannel.open();
      this.socket.configureBlocking(true);
      this.socket.socket().setSoTimeout(5000);
      this.socket.socket().bind(new InetSocketAddress("localhost", 0));
      Log.i(TAG, String.format("port:%d", this.socket.socket().getLocalPort()));
    } catch (IOException e) {
      throw new RuntimeException("Couldn't create screenshot server.", e);
    }

    serverMainLoop();
  }

  private void serverMainLoop() {
    Log.i(TAG, "screenshot server started");

    try {
      do {
        try {
          new ServerThread(this.socket.accept()).start();
        } catch (IOException e) {
          // no client connected
        }
      } while (this.isRunning.get());
    } finally {
      try {
        this.socket.close();
      } catch (IOException e) {
      }
    }

    Log.i(TAG, "screenshot server stopped");
  }

  private String readClientRequest(final ObjectInputStream ois) {
    String request = null;

    try {
      request = (String) ois.readObject();
    } catch (ClassNotFoundException e) {
    } catch (IOException e) {
    }

    return request;
  }

  public void finish() {
    this.isRunning.set(false);

    try {
      if (this.socket != null) {
        this.socket.close();
      }
    } catch (IOException e) {
    }
  }

  private class ServerThread extends Thread {

    private final SocketChannel client;
    private final ArrayBlockingQueue<String> pendingLines;

    public ServerThread(SocketChannel socketChannel) throws IOException {
      super("ServerThread-" + System.currentTimeMillis());
      this.client = socketChannel;
      this.client.configureBlocking(false);
      this.pendingLines = new ArrayBlockingQueue<String>(10);
    }

    @Override
    public void run() {
      Log.i(TAG, "client connected");

      byte[] data = new byte[1024];
      ByteBuffer buffer = ByteBuffer.wrap(data);

      String requestCmd;
      ScreenshotRequest request = null;

      try {
        do {

          if (request == null) {
            request = SpoonScreenshotServer.this.request.get();
            if (request != null) {
              write(CMD.START.toString());
            }
          }

          requestCmd = read(buffer);

          if (requestCmd == null) {
            Log.w(TAG, "empty request");
            continue;
          }

          switch (CMD.valueOf(requestCmd)) {
          case CAPTURE_READY:
            write(CMD.CAPTURE.toString());
            break;

          case ARGUMENTS:
            write(CMD.ARGUMENTS.toString());
            write(SpoonScreenshotServer.this.request.get().screenshotName);
            write(SpoonScreenshotServer.this.request.get().className);
            write(SpoonScreenshotServer.this.request.get().methodName);
            write(SpoonScreenshotServer.this.request.get().deviceOrientation.toString());
            break;

          case CAPTURE_FINISHED:
            SpoonScreenshotServer.this.request.get().markFinished();
            write(CMD.FINISHED.toString());
            break;

          case ERROR:
            Log.w(TAG, "error: " + request);
            break;

          default:
            Log.e(TAG, "Unexpected response: " + request);
            break;
          }
        } while (SpoonScreenshotServer.this.isRunning.get());
      } catch (IOException e) {

      } catch (TimeoutException e) {

      } finally {
        if (this.client != null) {
          try {
            this.client.close();
          } catch (IOException e) {
          }
        }
      }

      Log.i(TAG, "client closed");
    }

    private String read(ByteBuffer buffer) throws IOException {
      int readCount;
      readCount = this.client.read(buffer);

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

        count = this.client.write(buf);
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

    private void waitABit() {
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
      }
    }

  }

  private static final class ScreenshotRequest {

    private final CountDownLatch finished;

    public final String screenshotName;
    public final String className;
    public final String methodName;
    public final Integer deviceOrientation;

    public ScreenshotRequest(String screenshotName, String className, String methodName,
        int deviceOrientation) {

      this.screenshotName = screenshotName;
      this.className = className;
      this.methodName = methodName;
      this.deviceOrientation = Integer.valueOf(deviceOrientation);
      this.finished = new CountDownLatch(1);
    }

    public void markFinished() {
      this.finished.countDown();
    }

    public void waitUntilFinished() throws InterruptedException {
      this.finished.await();
    }
  }

}