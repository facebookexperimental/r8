// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.analysis.fieldaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.ClassFileConsumer;
import com.android.tools.r8.DexIndexedConsumer;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.dex.ApplicationReader;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.optimize.info.OptimizationFeedbackIgnore;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.BitUtils;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.Reporter;
import com.android.tools.r8.utils.Timing;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class FieldBitAccessInfoTest extends TestBase {

  private final TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimes().build();
  }

  public FieldBitAccessInfoTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void test() throws Exception {
    testForR8(parameters.getBackend())
        .addProgramClasses(TestClass.class)
        .addKeepMainRule(TestClass.class)
        .setMinApi(parameters.getRuntime())
        .compile()
        .run(parameters.getRuntime(), TestClass.class)
        .assertSuccessWithOutputLines(
            "getFirst()=false",
            "setFirst()",
            "getFirst()=true",
            "clearFirst()",
            "getFirst()=false",
            "getSecond()=false",
            "setSecond()",
            "getSecond()=true",
            "clearSecond()",
            "getSecond()=false",
            "getAllFromOther()=0",
            "0");
  }

  @Test
  public void testOptimizationInfo() throws Exception {
    AppView<AppInfoWithSubtyping> appView = buildApp();
    OptimizationFeedbackMock feedback = new OptimizationFeedbackMock();
    FieldBitAccessAnalysis fieldBitAccessAnalysis = new FieldBitAccessAnalysis(appView);

    DexProgramClass clazz = appView.appInfo().classes().iterator().next();
    assertEquals(TestClass.class.getTypeName(), clazz.type.toSourceString());

    for (DexEncodedMethod method : clazz.methods()) {
      IRCode code = method.buildIR(appView, Origin.unknown());
      fieldBitAccessAnalysis.recordFieldAccesses(code, feedback);
    }

    int bitsReadInBitField = feedback.bitsReadPerField.getInt(uniqueFieldByName(clazz, "bitField"));
    assertTrue(BitUtils.isBitSet(bitsReadInBitField, 1));
    assertTrue(BitUtils.isBitSet(bitsReadInBitField, 2));
    for (int i = 3; i <= 32; i++) {
      assertFalse(BitUtils.isBitSet(bitsReadInBitField, i));
    }

    int bitsReadInOtherBitField =
        feedback.bitsReadPerField.getInt(uniqueFieldByName(clazz, "otherBitField"));
    for (int i = 1; i <= 32; i++) {
      assertTrue(BitUtils.isBitSet(bitsReadInOtherBitField, i));
    }

    int bitsReadInThirdBitField =
        feedback.bitsReadPerField.getInt(uniqueFieldByName(clazz, "thirdBitField"));
    for (int i = 1; i <= 32; i++) {
      assertTrue(BitUtils.isBitSet(bitsReadInThirdBitField, i));
    }
  }

  private AppView<AppInfoWithSubtyping> buildApp() throws IOException, ExecutionException {
    DexItemFactory dexItemFactory = new DexItemFactory();
    InternalOptions options = new InternalOptions(dexItemFactory, new Reporter());
    options.programConsumer =
        parameters.isCfRuntime()
            ? ClassFileConsumer.emptyConsumer()
            : DexIndexedConsumer.emptyConsumer();
    Timing timing = new Timing("FieldBitAccessInfoTest");
    DexApplication application =
        new ApplicationReader(
                AndroidApp.builder()
                    .addClassProgramData(
                        ToolHelper.getClassAsBytes(TestClass.class), Origin.unknown())
                    .addLibraryFiles(ToolHelper.getDefaultAndroidJar())
                    .build(),
                options,
                timing)
            .read()
            .toDirect();
    return AppView.createForR8(new AppInfoWithSubtyping(application), options);
  }

  private DexEncodedField uniqueFieldByName(DexProgramClass clazz, String name) {
    DexEncodedField result = null;
    for (DexEncodedField field : clazz.fields()) {
      if (field.field.name.toSourceString().equals(name)) {
        assertNull(result);
        result = field;
      }
    }
    return result;
  }

  static class TestClass {

    static int bitField = 0;
    static int otherBitField = 0;
    static int thirdBitField = 0;

    public static void main(String[] args) {
      // Use first bit of `bitField`.
      System.out.println("getFirst()=" + getFirst());
      System.out.println("setFirst()");
      setFirst();
      System.out.println("getFirst()=" + getFirst());
      System.out.println("clearFirst()");
      clearFirst();
      System.out.println("getFirst()=" + getFirst());

      // Use second bit of `bitField`.
      System.out.println("getSecond()=" + getSecond());
      System.out.println("setSecond()");
      setSecond();
      System.out.println("getSecond()=" + getSecond());
      System.out.println("clearSecond()");
      clearSecond();
      System.out.println("getSecond()=" + getSecond());

      // Use all bits of `otherBitField`.
      System.out.println("getAllFromOther()=" + getAllFromOther());

      // Use all bits of `thirdBitField`.
      System.out.println(thirdBitField);
    }

    static boolean getFirst() {
      return (bitField & 0x00000001) != 0;
    }

    static void setFirst() {
      bitField |= 0x00000001;
    }

    static void clearFirst() {
      bitField &= ~0x00000001;
    }

    static boolean getSecond() {
      return (bitField & 0x00000002) != 0;
    }

    static void setSecond() {
      bitField |= 0x00000002;
    }

    static void clearSecond() {
      bitField &= ~0x00000002;
    }

    static int getAllFromOther() {
      return otherBitField;
    }
  }

  static class OptimizationFeedbackMock extends OptimizationFeedbackIgnore {

    Reference2IntMap<DexEncodedField> bitsReadPerField = new Reference2IntOpenHashMap<>();

    @Override
    public void markFieldBitsRead(DexEncodedField field, int bitsRead) {
      bitsReadPerField.put(field, bitsReadPerField.getInt(field) | bitsRead);
    }
  }
}
