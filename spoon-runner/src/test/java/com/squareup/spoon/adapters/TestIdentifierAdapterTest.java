package com.squareup.spoon.adapters;

import static org.junit.Assert.*;

import org.junit.Test;

import com.android.ddmlib.testrunner.TestIdentifier;

public class TestIdentifierAdapterTest {

  @Test
  public void otherAdapter() {
    TestIdentifierAdapter adapter = TestIdentifierAdapter
        .fromTestRunner("other.adapter");

    TestIdentifier test = new TestIdentifier("ThisIsATest",
        "testThisIsATestMethod");

    assertEquals(test, adapter.adapt(test));
  }

  @Test
  public void cucumberAdapter() {
    TestIdentifierAdapter adapter = TestIdentifierAdapter
        .fromTestRunner("cucumber.api.android.CucumberInstrumentation");

    assertEquals(new TestIdentifier("ThisIsATest", "testThisIsATestMethod"),
        adapter.adapt(new TestIdentifier("This is a test!",
            "This is a test method!")));
    assertEquals(new TestIdentifier("NonAlphanumericCharactersConvertLookGood",
        "testFor500IGet300"), adapter.adapt(new TestIdentifier(
        "Non alpha-numeric characters convert & look good!",
        "For $500 I get £300")));
    assertEquals(new TestIdentifier("ThisIsAbadtest", "testVeryBadTest"),
        adapter.adapt(new TestIdentifier(
            "This@£$%^&*Is£$%^&*A£$%^&*bad$£%^&*test",
            "Very£$%^&Bad$%^&*:|{}Test")));
  }
}
