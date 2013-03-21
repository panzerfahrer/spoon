package com.squareup.spoon;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import android.util.Log;

/*package*/class SpoonScreenshotServer extends Thread {

  private static final String TAG = "SpoonScreenshotServer";

  public static enum CMD {
    START, CAPTURE_READY, CAPTURE, CAPTURE_FINISHED, ARGUMENTS, FINISHED, ERROR;
  }

  private final AtomicBoolean isRunning;
  private final AtomicReference<ScreenshotRequest> request;

  private ServerSocket socket;

  /**
   * @param timeout
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

    final CountDownLatch finishedLatch = new CountDownLatch(1);
    this.request.set(new ScreenshotRequest(screenshotName, className, methodName, Integer
        .valueOf(deviceOrientation), finishedLatch));

    try {
      finishedLatch.await();
    } catch (InterruptedException e) {
    }
  }

  public boolean isRunning() {
    return this.isRunning.get();
  }

  @Override
  public void run() {
    this.isRunning.set(true);

    try {
      this.socket = new ServerSocket(42042);
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
          // error while waiting for client to connect
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

    private final Socket client;

    public ServerThread(Socket client) {
      super("ServerThread");

      this.client = client;
    }

    @Override
    public void run() {
      Log.i(TAG, "client connected");

      ObjectInputStream ois = null;
      ObjectOutputStream oos = null;
      ScreenshotRequest request = null;

      do {
        try {

          if (ois == null || oos == null) {
            try {
              oos = new ObjectOutputStream(new BufferedOutputStream(this.client.getOutputStream()));
              ois = new ObjectInputStream(new BufferedInputStream(this.client.getInputStream()));
            } catch (IOException e) {
              // error with setting up streams
              continue;
            }
          }

          if (request == null) {
            request = SpoonScreenshotServer.this.request.get();
            oos.writeObject(CMD.START.toString());
            SpoonScreenshotServer.this.request.set(null);
          }

          Log.i(TAG, "reading client request");
          String requestCmd = readClientRequest(ois);

          if (request == null) {
            Log.i(TAG, "empty request");
            continue;
          }

          switch (CMD.valueOf(requestCmd)) {
          case CAPTURE_READY:
            oos.writeObject(CMD.CAPTURE.toString());
            break;

          case ARGUMENTS:
            oos.writeObject(CMD.ARGUMENTS.toString());
            oos.writeObject(request.screenshotName);
            oos.writeObject(request.className);
            oos.writeObject(request.methodName);
            oos.writeObject(request.deviceOrientation);
            request = null;
            break;

          case CAPTURE_FINISHED:
            if (request.finished != null) {
              request.finished.countDown();
            }
            request = null;
            oos.writeObject(CMD.FINISHED.toString());
            break;

          case ERROR:
            Log.w(TAG, "error: " + request);
            break;

          default:
            Log.e(TAG, "Unexpected response: " + request);
            break;
          }
        } catch (IOException e) {
          Log.e(TAG, "error reading/sending commands", e);
        }
      } while (SpoonScreenshotServer.this.isRunning.get());

      try {
        ois.close();
        oos.close();
        client.close();
      } catch (IOException e) {
      }

      Log.i(TAG, "client closed");
    }

  }

  private static final class ScreenshotRequest {
    public final String screenshotName;
    public final String className;
    public final String methodName;
    public final Integer deviceOrientation;
    public final CountDownLatch finished;

    public ScreenshotRequest(String screenshotName, String className, String methodName,
        Integer deviceOrientation, CountDownLatch finished) {

      this.screenshotName = screenshotName;
      this.className = className;
      this.methodName = methodName;
      this.deviceOrientation = deviceOrientation;
      this.finished = finished;
    }
  }

}