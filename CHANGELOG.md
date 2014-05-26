Change Log
==========

Version 1.1.1 *(2014-02-11)*
----------------------------

 * Use emulator name instead of serial number in output HTML.
 * Update to latest Maven Android plugin.


Version 1.1.0 *(2013-11-24)*
----------------------------

 * Add preliminary TV display output which cycles through tests and screenshots.
 * Old APKs are no longer uninstalled.
 * All strings are sanitized for use on the filesystem.
 * Support exceptions whose header has no message.
 * `--no-animations` argument disables GIF generation.
 * `--size` argument allows specifying which test size to run. Default is to run all tests.
 * `--adb-timeout` argument controls maximum time per test. Default is 10 minutes.
 * `--fail-if-no-device-connected` argument causes failure indication when no devices are found.
   Default is to succeed.


Version 1.0.5 *(2013-06-05)*
----------------------------

 * Generate JUnit-compatible XML reports for each device.
 * Add timeout for stalled tests and flaky devices.
 * Add `spoon:open` Maven command to open the output web page.


Version 1.0.4 *(2013-05-23)*
----------------------------

 * Support for GIFs of tests in multiple orientations.
 * Fix: Prevent Java from showing a window while running tests on some OSs.
 * Fix: Prevent screenshots from being listed out of order on some OSs.


Version 1.0.3 *(2013-04-04)*
----------------------------

 * Display OS properties on the top of device page.
 * Fix: Prevent exception when `ANDROID_SDK` not set.


Version 1.0.2 *(2013-03-14)*
----------------------------

 * Devices without names are properly sorted.
 * Fix: App and instrumentation APK now resolves using Aether.


Version 1.0.1 *(2013-02-26)*
----------------------------

 * Improve classpath detection inside Maven plugin.
 * Screenshot tags are now logged and displayed as tooltips.
 * Fix: Generating output on Windows no longer throws exception.
 * Fix: Screenshots in base test classes no longer throws exception.
 * Fix: Lack of `ANDROID_SDK` environment variable no longer throws inadvertent exception.
 * Fix: Device run failure is now correctly indicated in output.


Version 1.0.0 *(2012-02-13)*
----------------------------

Initial release.
