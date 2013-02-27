package com.squareup.spoon;

import static android.content.Context.MODE_WORLD_READABLE;
import static android.graphics.Bitmap.CompressFormat.PNG;
import static android.graphics.Bitmap.Config.ARGB_8888;
import static com.squareup.spoon.Chmod.chmodPlusR;
import static com.squareup.spoon.Chmod.chmodPlusRWX;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
  private static final String TAG = "SpoonScreenshot";
  private static final Object LOCK = new Object();
  private static final Pattern TAG_VALIDATION = Pattern.compile("[a-zA-Z0-9_-]+");

  private static final int MAX_SCREENSHOT_WAIT = 10000;

  /** Whether or not the screenshot output directory needs cleared. */
  private static boolean outputNeedsClear = true;

  private static class ScreenshotReceiver extends BroadcastReceiver {

    static final String EXTRA_FILE = "com.squareup.spoon.ScreenshotTaken.File";
    static final String EXTRA_SUCCESS = "com.squareup.spoon.ScreenshotTaken.Success";
    static final IntentFilter FILTER = new IntentFilter();

    static {
      FILTER.addAction("com.squareup.spoon.ScreenshotTaken");
    }

    private final CountDownLatch latch;
    private final String filePath;

    public boolean received;
    public boolean success;

    public ScreenshotReceiver(final CountDownLatch latch, final String filePath) {
      super();
      this.latch = latch;
      this.filePath = filePath;
    }
    
    @Override
    public void onReceive(Context context, Intent intent) {
      final String action = intent.getAction();

      Log.i(TAG, "receiving " + action);

      if (FILTER.hasAction(action) && this.filePath.equals(intent.getStringExtra(EXTRA_FILE))) {
        this.success = intent.getBooleanExtra(EXTRA_SUCCESS, false);
        this.received = true;
        this.latch.countDown();
      }
    }
  }

  private static class ScreenshotThread extends Thread {

    private boolean isFinished;
    public Handler handler;

    public ScreenshotThread() {
      super("ScreenshotThread");
    }
    
    @Override
    public void run() {
      Looper.prepare();
      this.handler = new Handler(Looper.myLooper());
      
      while (!isFinished) {
        Looper.loop();
      }
    }

    public void finish() {
      this.isFinished = true;
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
  public static void screenshotDDMS(Instrumentation instrumentation, String tag, final long timeout) {
    if (!TAG_VALIDATION.matcher(tag).matches()) {
      throw new IllegalArgumentException("Tag must match " + TAG_VALIDATION.pattern() + ".");
    }

    final Context activity = instrumentation.getTargetContext();

    try {
      final File screenshotDirectory = obtainScreenshotDirectory(activity);
      final String screenshotName = System.currentTimeMillis() + NAME_SEPARATOR + tag + EXTENSION;
      final String filePath = new File(screenshotDirectory, screenshotName).getAbsolutePath();

      // set up broadcast receiver to listen for results
      final CountDownLatch done = new CountDownLatch(1);
      final ScreenshotThread screenshotThread = new ScreenshotThread();
      screenshotThread.start();

      ScreenshotReceiver shotReceiver = new ScreenshotReceiver(done, filePath);
      activity.getApplicationContext().registerReceiver(shotReceiver, ScreenshotReceiver.FILTER,
          null, screenshotThread.handler);

      int deviceOrientation = getDeviceOrientation(activity);

      // requesting android-screenshot-paparazzo to take a screenshot
      final String args = String.format("{file=%s,orientation=%d}", filePath, deviceOrientation);
      Log.i("screenshot_request", args);

      instrumentation.runOnMainSync(new Runnable() {
        @Override
        public void run() {
          waitForDdmsScreenshot(done, timeout);
        }
      });
      
      screenshotThread.finish();
      activity.getApplicationContext().unregisterReceiver(shotReceiver);

      if (!shotReceiver.success || !shotReceiver.received) {
        throw new RuntimeException("Taking screenshot failed.");
      }

    } catch (Exception e) {
      throw new RuntimeException("Unable to capture screenshot.", e);
    }
  }

  private static void waitForDdmsScreenshot(final CountDownLatch done, final long timeout) {
    final long startTime = System.currentTimeMillis();
    final long maxWaitUntil = startTime + timeout;

    while (maxWaitUntil > System.currentTimeMillis()) {
      long timeToWait = maxWaitUntil - System.currentTimeMillis();
      Log.i(TAG, String.format("Waiting %dms for screenshot on thread %s ...", timeToWait, Thread
          .currentThread().getName()));

      try {
        done.await(500, TimeUnit.MILLISECONDS);
        Thread.yield();
      } catch (InterruptedException e) {
        Log.w(TAG, e);
      }
    }

    Log.i(TAG, String.format("... waited %dms", System.currentTimeMillis() - startTime));
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
