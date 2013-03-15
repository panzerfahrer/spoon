package com.squareup.spoon;

import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * Process a newly taken screenshot.
 * 
 * @author brho
 * @author https://github.com/rtyley
 * @see com.github.rtyley.android.screenshot.paparazzo.processors
 */
public interface ScreenshotProcessor {

  /**
   * Process the screenshot!
   * 
   * @param image the screenshot
   * @param arguments collection of arguments
   */
  public void process(BufferedImage image, Map<String, Object> arguments);

}
