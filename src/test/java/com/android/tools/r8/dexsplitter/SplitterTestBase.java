package com.android.tools.r8.dexsplitter;

import static junit.framework.TestCase.assertTrue;

import com.android.tools.r8.R8FullTestBuilder;
import com.android.tools.r8.R8TestCompileResult;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.ToolHelper.ArtCommandBuilder;
import com.android.tools.r8.ToolHelper.ProcessResult;
import com.android.tools.r8.dexsplitter.DexSplitter.Options;
import com.google.common.collect.ImmutableList;
import dalvik.system.PathClassLoader;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class SplitterTestBase extends TestBase {

  // Compile the passed in classes plus RunInterface and SplitRunner using R8, then split
  // based on the base/feature sets. toRun must implement the BaseRunInterface
  protected ProcessResult testDexSplitter(
      TestParameters parameters,
      Set<Class<?>> baseClasses,
      Set<Class<?>> featureClasses,
      Class toRun,
      String expectedOutput,
      Predicate<R8TestCompileResult> predicate,
      Consumer<R8FullTestBuilder> r8TestConfigurator)
      throws Exception {
    List<Class<?>> baseClassesWithRunner =
        ImmutableList.<Class<?>>builder()
            .add(RunInterface.class, SplitRunner.class)
            .addAll(baseClasses)
            .build();

    Path baseJar = jarTestClasses(baseClassesWithRunner);
    Path featureJar = jarTestClasses(featureClasses);

    Path featureOnly =
        testForR8(parameters.getBackend())
            .addProgramClasses(featureClasses)
            .addClasspathClasses(baseClasses)
            .addClasspathClasses(RunInterface.class)
            .addKeepAllClassesRule()
            .setMinApi(parameters.getRuntime())
            .compile()
            .writeToZip();
    if (parameters.isDexRuntime()) {
      // With D8 this should just work. We compile all of the base classes, then run with the
      // feature loaded at runtime. Since there is no inlining/class merging we don't
      // have any issues.
      testForD8()
          .addProgramClasses(SplitRunner.class, RunInterface.class)
          .addProgramClasses(baseClasses)
          .setMinApi(parameters.getRuntime())
          .compile()
          .run(
              parameters.getRuntime(),
              SplitRunner.class,
              toRun.getName(),
              featureOnly.toAbsolutePath().toString())
          .assertSuccessWithOutput(expectedOutput);
    }

    R8FullTestBuilder builder = testForR8(parameters.getBackend());
    if (parameters.isCfRuntime()) {
      // Compiling to jar we need to support the same way of loading code at runtime as
      // android supports.
      builder
          .addProgramClasses(PathClassLoader.class)
          .addKeepClassAndMembersRules(PathClassLoader.class);
    }

    R8FullTestBuilder r8FullTestBuilder =
        builder
            .setMinApi(parameters.getRuntime())
            .addProgramClasses(SplitRunner.class, RunInterface.class)
            .addProgramClasses(baseClasses)
            .addProgramClasses(featureClasses)
            .addKeepMainRule(SplitRunner.class)
            .addKeepClassRules(toRun);
    r8TestConfigurator.accept(r8FullTestBuilder);
    R8TestCompileResult r8TestCompileResult = r8FullTestBuilder.compile();
    assertTrue(predicate.test(r8TestCompileResult));
    Path fullFiles = r8TestCompileResult.writeToZip();

    // Ensure that we can run the program as a unit (i.e., without splitting)
    r8TestCompileResult
        .run(parameters.getRuntime(), SplitRunner.class, toRun.getName())
        .assertSuccessWithOutput(expectedOutput);

    Path splitterOutput = temp.newFolder().toPath();
    Path splitterBaseDexFile = splitterOutput.resolve("base").resolve("classes.dex");
    Path splitterFeatureDexFile = splitterOutput.resolve("feature").resolve("classes.dex");

    Options options = new Options();
    options.setOutput(splitterOutput.toString());
    options.addBaseJar(baseJar.toString());
    options.addFeatureJar(featureJar.toString(), "feature");

    options.addInputArchive(fullFiles.toString());
    DexSplitter.run(options);

    ArtCommandBuilder commandBuilder = new ArtCommandBuilder();
    commandBuilder.appendClasspath(splitterBaseDexFile.toString());
    commandBuilder.appendProgramArgument(toRun.getName());
    commandBuilder.appendProgramArgument(splitterFeatureDexFile.toString());
    commandBuilder.setMainClass(SplitRunner.class.getName());
    ProcessResult processResult = ToolHelper.runArtRaw(commandBuilder);
    return processResult;
  }

  public interface RunInterface {
    void run();
  }

  static class SplitRunner {
    /* We support two different modes:
     *   - One argument to main:
     *     Pass in the class to be loaded, must implement RunInterface, run will be called
     *   - Two arguments to main:
     *     Pass in the class to be loaded, must implement RunInterface, run will be called
     *     Pass in the feature split that we class load
     *
     */
    public static void main(String[] args) {
      if (args.length < 1 || args.length > 2) {
        throw new RuntimeException("Unsupported number of arguments");
      }
      String classToRun = args[0];
      ClassLoader loader = SplitRunner.class.getClassLoader();
      // In the case where we simulate splits, we pass in the feature as the second argument
      if (args.length == 2) {
        try {
          loader = new PathClassLoader(args[1], SplitRunner.class.getClassLoader());
        } catch (MalformedURLException e) {
          throw new RuntimeException("Failed reading input URL");
        }
      }

      try {
        Class<?> aClass = loader.loadClass(classToRun);
        RunInterface b = (RunInterface) aClass.newInstance();
        b.run();
      } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
        throw new RuntimeException("Failed loading class");
      }
    }
  }
}
