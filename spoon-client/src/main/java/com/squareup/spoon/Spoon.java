package com.squareup.spoon;

import static android.content.Context.MODE_WORLD_READABLE;
import static android.graphics.Bitmap.CompressFormat.PNG;
import static android.graphics.Bitmap.Config.ARGB_8888;
import static com.squareup.spoon.Chmod.chmodPlusR;
import static com.squareup.spoon.Chmod.chmodPlusRWX;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

/** Utility class for capturing screenshots for Spoon. */
public final class Spoon {
  static final String SPOON_SCREENSHOTS = "spoon-screenshots";
  static final String NAME_SEPARATOR = "_";
  static final String TEST_CASE_CLASS = "android.test.InstrumentationTestCase";
  static final String TEST_CASE_METHOD = "runMethod";
  private static final String EXTENSION = ".png";
  private static final String TAG = "Spoon";
  private static final Object LOCK = new Object();
  private static final Pattern TAG_VALIDATION = Pattern.compile("[a-zA-Z0-9_-]+");

  private static final int MAX_SCREENSHOT_WAIT = 10000;

  /** Whether or not the screenshot output directory needs cleared. */
  private static boolean outputNeedsClear = true;

  private static class ScreenshotServer implements Runnable {

    private static enum CMD {
      START, START_READY, CAPTURE, CAPTURE_FINISHED, ARGUMENTS, FINISHED, ERROR;
    }

    private final ServerSocket socket;

    private boolean running;

    public String screenshotName;
    public String className;
    public String methodName;
    public int deviceOrientation;

    public ScreenshotServer(int timeout) {
      try {
        socket = new ServerSocket(42042);
        socket.setSoTimeout(timeout);
      } catch (IOException e) {
        throw new RuntimeException("Couldn't create screenshot server.", e);
      }
    }

    @Override
    public void run() {
      try {
        Socket client = socket.accept();
        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(client.getOutputStream()));

        writer.println(CMD.START);
        this.running = true;
        while (this.running) {
          String response = reader.readLine();

          switch (CMD.valueOf(response)) {
          case START_READY:
            writer.println(CMD.CAPTURE);
            break;

          case ARGUMENTS:
            writer.println(CMD.ARGUMENTS);
            writer.println(this.screenshotName);
            writer.println(this.className);
            writer.println(this.methodName);
            writer.println(this.deviceOrientation);
            break;

          case CAPTURE_FINISHED:
            writer.println(CMD.FINISHED);
            this.running = false;
            break;

          case ERROR:
            this.running = false;
            break;

          default:
            throw new RuntimeException("Unexpected response: " + response);
            // TODO retry strategy?
          }
        }
        
        writer.close();
        reader.close();
        client.close();

      } catch (SocketTimeoutException e) {
        throw new RuntimeException("No screenshot client connected", e);
      } catch (IOException e) {
        throw new RuntimeException("Unable to capture screenshot.", e);
      }
    }

  }

  /**
   * Let DDMS take a screenshot with the specified tag.
   * 
   * @param activity Activity with which to capture a screenshot.
   * @param tag Unique tag to further identify the screenshot. Must match [a-zA-Z0-9_-]+.
   */
  public static void screenshotDDMS(Instrumentation instrumentation, String tag) {
    screenshotDDMS(instrumentation, tag, MAX_SCREENSHOT_WAIT);
  }

  /**
   * Let DDMS take a screenshot with the specified tag.
   * 
   * @param activity Activity with which to capture a screenshot.
   * @param tag Unique tag to further identify the screenshot. Must match [a-zA-Z0-9_-]+.
   * @param timeout time to wait for DDMS to finish taking the screenshot
   */
  public static void screenshotDDMS(Instrumentation instrumentation, String tag, final int timeout) {
    if (!TAG_VALIDATION.matcher(tag).matches()) {
      throw new IllegalArgumentException("Tag must match " + TAG_VALIDATION.pattern() + ".");
    }

    final Context activity = instrumentation.getTargetContext();

    try {
      ScreenshotServer server = new ScreenshotServer(timeout);

      StackTraceElement testClass = findTestClassTraceElement(Thread.currentThread()
          .getStackTrace());

      server.screenshotName = System.currentTimeMillis() + NAME_SEPARATOR + tag + EXTENSION;
      server.className = testClass.getClassName().replaceAll("[^A-Za-z0-9._-]", "_");
      server.methodName = testClass.getMethodName();
      server.deviceOrientation = getDeviceOrientation(activity);

      instrumentation.runOnMainSync(server);

    } catch (Exception e) {
      throw new RuntimeException("Unable to capture screenshot.", e);
    }
  }

  private static int getDeviceOrientation(Context context) {

    final WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    final Display defaultDisplay = wm.getDefaultDisplay();
    final int rotation = defaultDisplay.getRotation();
    final int orientation = defaultDisplay.getOrientation();

    // Copied from Android docs, since we don't have these values in Froyo 2.2
    int SCREEN_ORIENTATION_REVERSE_LANDSCAPE = 8;
    int SCREEN_ORIENTATION_REVERSE_PORTRAIT = 9;

    if (Build.VERSION.SDK_INT <= 8 /* Build.VERSION_CODES.FROYO */) {
      SCREEN_ORIENTATION_REVERSE_LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
      SCREEN_ORIENTATION_REVERSE_PORTRAIT = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    }

    switch (orientation) {
    case Configuration.ORIENTATION_PORTRAIT:
      if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_90) {
        return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
      } else {
        return SCREEN_ORIENTATION_REVERSE_PORTRAIT;
      }

    case Configuration.ORIENTATION_LANDSCAPE:
      if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_90) {
        return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
      } else {
        return SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
      }

    default:
      return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    }

  }

  /**
   * Take a screenshot with the specified tag.
   * 
   * @param activity Activity with which to capture a screenshot.
   * @param tag Unique tag to further identify the screenshot. Must match [a-zA-Z0-9_-]+.
   */
  public static void screenshot(Activity activity, String tag) {
    if (!TAG_VALIDATION.matcher(tag).matches()) {
      throw new IllegalArgumentException("Tag must match " + TAG_VALIDATION.pattern() + ".");
    }
    try {
      File screenshotDirectory = obtainScreenshotDirectory(activity);
      String screenshotName = System.currentTimeMillis() + NAME_SEPARATOR + tag + EXTENSION;
      takeScreenshot(new File(screenshotDirectory, screenshotName), activity);
      Log.d(TAG, "Captured screenshot '" + tag + "'.");
    } catch (Exception e) {
      throw new RuntimeException("Unable to capture screenshot.", e);
    }
  }

  private static void takeScreenshot(File file, final Activity activity) throws IOException {
    DisplayMetrics dm = activity.getResources().getDisplayMetrics();
    final Bitmap bitmap = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, ARGB_8888);

    if (Looper.myLooper() == Looper.getMainLooper()) {
      // On main thread already, Just Do It™.
      drawDecorViewToBitmap(activity, bitmap);
    } else {
      // On a background thread, post to main.
      final CountDownLatch latch = new CountDownLatch(1);
      activity.runOnUiThread(new Runnable() {
        @Override
        public void run() {
          try {
            drawDecorViewToBitmap(activity, bitmap);
          } finally {
            latch.countDown();
          }
        }
      });
      try {
        latch.await();
      } catch (InterruptedException e) {
        String msg = "Unable to get screenshot " + file.getAbsolutePath();
        Log.e(TAG, msg, e);
        throw new RuntimeException(msg, e);
      }
    }

    OutputStream fos = null;
    try {
      fos = new BufferedOutputStream(new FileOutputStream(file));
      bitmap.compress(PNG, 100 /* quality */, fos);

      chmodPlusR(file);
    } finally {
      bitmap.recycle();
      if (fos != null) {
        fos.close();
      }
    }
  }

  private static void drawDecorViewToBitmap(Activity activity, Bitmap bitmap) {
    Canvas canvas = new Canvas(bitmap);
    activity.getWindow().getDecorView().draw(canvas);
  }

  private static File obtainScreenshotDirectory(Context context) throws IllegalAccessException {
    File screenshotsDir = context.getDir(SPOON_SCREENSHOTS, MODE_WORLD_READABLE);

    synchronized (LOCK) {
      if (outputNeedsClear) {
        deletePath(screenshotsDir, false);
        outputNeedsClear = false;
      }
    }

    StackTraceElement testClass = findTestClassTraceElement(Thread.currentThread().getStackTrace());
    String className = testClass.getClassName().replaceAll("[^A-Za-z0-9._-]", "_");
    File dirClass = new File(screenshotsDir, className);
    File dirMethod = new File(dirClass, testClass.getMethodName());
    createDir(dirMethod);
    return dirMethod;
  }

  /**
   * Returns the test class element by looking at the method InstrumentationTestCase invokes.
   */
  static StackTraceElement findTestClassTraceElement(StackTraceElement[] trace) {
    for (int i = trace.length - 1; i >= 0; i--) {
      StackTraceElement element = trace[i];
      if (TEST_CASE_CLASS.equals(element.getClassName()) //
          && TEST_CASE_METHOD.equals(element.getMethodName())) {
        return trace[i - 3];
      }
    }

    throw new IllegalArgumentException("Could not find test class!");
  }

  private static void createDir(File dir) throws IllegalAccessException {
    File parent = dir.getParentFile();
    if (!parent.exists()) {
      createDir(parent);
    }
    if (!dir.exists() && !dir.mkdirs()) {
      throw new IllegalAccessException("Unable to create output dir: " + dir.getAbsolutePath());
    }
    chmodPlusRWX(dir);
  }

  private static void deletePath(File path, boolean inclusive) {
    if (path.isDirectory()) {
      File[] children = path.listFiles();
      if (children != null) {
        for (File child : children) {
          deletePath(child, true);
        }
      }
    }
    if (inclusive) {
      path.delete();
    }
  }

  private Spoon() {
    // No instances.
  }
}
