// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputSink;
import com.android.tools.r8.StringConsumer;
import com.android.tools.r8.origin.Origin;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

public class AndroidAppOutputSink extends ForwardingOutputSink {

  private final AndroidApp.Builder builder = AndroidApp.builder();
  private final TreeMap<String, DescriptorsWithContents> dexFilesWithPrimary = new TreeMap<>();
  private final TreeMap<Integer, DescriptorsWithContents> dexFilesWithId = new TreeMap<>();
  private final List<DescriptorsWithContents> classFiles = new ArrayList<>();
  private boolean closed = false;

  private StringConsumer proguardMapConsumer = null;

  public AndroidAppOutputSink(OutputSink forwardTo, InternalOptions options) {
    super(forwardTo);
    options.proguardMapConsumer = wrapProguardMapConsumer(options.proguardMapConsumer);
  }

  public AndroidAppOutputSink() {
    super(new IgnoreContentsOutputSink());
  }

  private StringConsumer wrapProguardMapConsumer(StringConsumer consumer) {
    assert proguardMapConsumer == null;
    if (consumer != null) {
      proguardMapConsumer =
          new StringConsumer.ForwardingConsumer(consumer) {
            @Override
            public void accept(String data, DiagnosticsHandler handler) {
              super.accept(data, handler);
              builder.setProguardMapData(data);
            }
          };
    }
    return proguardMapConsumer;
  }

  @Override
  public synchronized void writeDexFile(byte[] contents, Set<String> classDescriptors, int fileId)
      throws IOException {
    assert dexFilesWithPrimary.isEmpty() && classFiles.isEmpty();
    // Sort the files by id so that their order is deterministic. Some tests depend on this.
    dexFilesWithId.put(fileId, new DescriptorsWithContents(classDescriptors, contents));
    super.writeDexFile(contents, classDescriptors, fileId);
  }

  @Override
  public synchronized void writeDexFile(byte[] contents, Set<String> classDescriptors,
      String primaryClassName)
      throws IOException {
    assert dexFilesWithId.isEmpty() && classFiles.isEmpty();
    // Sort the files by their name for good measure.
    dexFilesWithPrimary
        .put(primaryClassName, new DescriptorsWithContents(classDescriptors, contents));
    super.writeDexFile(contents, classDescriptors, primaryClassName);
  }

  @Override
  public synchronized void writeClassFile(
      byte[] contents, Set<String> classDescriptors, String primaryClassName) throws IOException {
    assert dexFilesWithPrimary.isEmpty() && dexFilesWithId.isEmpty();
    classFiles.add(new DescriptorsWithContents(classDescriptors, contents));
    super.writeClassFile(contents, classDescriptors, primaryClassName);
  }

  @Override
  public void writePrintUsedInformation(byte[] contents) throws IOException {
    builder.setDeadCode(contents);
    super.writePrintUsedInformation(contents);
  }

  @Override
  public void writeProguardSeedsFile(byte[] contents) throws IOException {
    builder.setProguardSeedsData(contents);
    super.writeProguardSeedsFile(contents);
  }

  @Override
  public void writeMainDexListFile(byte[] contents) throws IOException {
    builder.setMainDexListOutputData(contents);
    super.writeMainDexListFile(contents);
  }

  @Override
  public void close() throws IOException {
    assert !closed;
    if (!dexFilesWithPrimary.isEmpty()) {
      assert dexFilesWithId.isEmpty() && classFiles.isEmpty();
      dexFilesWithPrimary.forEach(
          (v, d) -> builder.addDexProgramData(d.contents, d.descriptors, v));
    } else if (!dexFilesWithId.isEmpty()) {
      assert dexFilesWithPrimary.isEmpty() && classFiles.isEmpty();
      dexFilesWithId.forEach((v, d) -> builder.addDexProgramData(d.contents, d.descriptors));
    } else if (!classFiles.isEmpty()) {
      assert dexFilesWithPrimary.isEmpty() && dexFilesWithId.isEmpty();
      classFiles.forEach(
          d -> builder.addClassProgramData(d.contents, Origin.unknown(), d.descriptors));
    }
    closed = true;
    super.close();
  }

  public AndroidApp build() {
    assert closed;
    return builder.build();
  }

  private static class DescriptorsWithContents {

    final Set<String> descriptors;
    final byte[] contents;

    private DescriptorsWithContents(Set<String> descriptors, byte[] contents) {
      this.descriptors = descriptors;
      this.contents = contents;
    }
  }
}
