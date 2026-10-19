// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.analysis.actions;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLineItem.ExceptionlessMapFn;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.ResourceSetOrBuilder;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.analysis.config.CoreOptions.OutputPathsMode;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.StarlarkAction.Code;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.ByteString;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/** A Starlark specific SpawnAction. */
public class StarlarkAction extends SpawnAction {

  private StarlarkAction(
      ActionOwner owner,
      NestedSet<Artifact> tools,
      NestedSet<Artifact> inputs,
      Iterable<Artifact> outputs,
      ResourceSetOrBuilder resourceSetOrBuilder,
      CommandLines commandLines,
      ActionEnvironment env,
      ImmutableMap<String, String> executionInfo,
      CharSequence progressMessage,
      String mnemonic,
      OutputPathsMode outputPathsMode) {
    super(
        owner,
        tools,
        inputs,
        outputs,
        resourceSetOrBuilder,
        commandLines,
        env,
        executionInfo,
        progressMessage,
        mnemonic,
        outputPathsMode);
  }

  /** Constructor for serialization. */
  private StarlarkAction(
      ActionOwner owner,
      NestedSet<Artifact> tools,
      NestedSet<Artifact> inputs,
      Object rawOutputs,
      ResourceSetOrBuilder resourceSetOrBuilder,
      CommandLines commandLines,
      ActionEnvironment env,
      ImmutableSortedMap<String, String> sortedExecutionInfo,
      CharSequence progressMessage,
      String mnemonic,
      OutputPathsMode outputPathsMode) {
    super(
        owner,
        tools,
        inputs,
        rawOutputs,
        resourceSetOrBuilder,
        commandLines,
        env,
        sortedExecutionInfo,
        progressMessage,
        mnemonic,
        outputPathsMode);
  }

  @VisibleForTesting
  public Optional<Artifact> getUnusedInputsList() {
    return Optional.empty();
  }

  @Override
  public NestedSet<Artifact> getInputFilesForExtraAction(
      ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    return getInputs();
  }

  private static FailureDetail createFailureDetail(String message, Code detailedCode) {
    return FailureDetail.newBuilder()
        .setMessage(message)
        .setStarlarkAction(FailureDetails.StarlarkAction.newBuilder().setCode(detailedCode))
        .build();
  }

  @SafeVarargs
  private static NestedSet<Artifact> createInputs(NestedSet<Artifact>... inputsLists) {
    NestedSetBuilder<Artifact> nestedSetBuilder = NestedSetBuilder.newBuilder(Order.STABLE_ORDER);
    for (NestedSet<Artifact> inputs : inputsLists) {
      nestedSetBuilder.addTransitive(inputs);
    }
    return nestedSetBuilder.build();
  }

  /** Builder class to construct {@link StarlarkAction} instances. */
  public static class Builder extends SpawnAction.Builder {

    private Optional<Artifact> unusedInputsList = Optional.empty();
    private Optional<Artifact> usedInputsList = Optional.empty();
    private NestedSet<Artifact> discoveryInputs = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    private Optional<Action> shadowedAction = Optional.empty();
    @Nullable private Artifact stdoutOutput = null;

    public Builder() {}

    public Builder(SpawnAction.Builder other) {
      super(other);
    }

    @CanIgnoreReturnValue
    public Builder setUsedInputsList(Artifact usedInputsList, NestedSet<Artifact> discoveryInputs) {
      this.usedInputsList = Optional.of(usedInputsList);
      this.discoveryInputs = discoveryInputs;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setUnusedInputsList(Optional<Artifact> unusedInputsList) {
      this.unusedInputsList = unusedInputsList;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setShadowedAction(Optional<Action> shadowedAction) {
      this.shadowedAction = shadowedAction;
      return this;
    }

    /**
     * Redirects the spawn's standard output into the given output artifact instead of reporting it
     * as regular action output. The artifact must also be registered as an output of the action.
     */
    @CanIgnoreReturnValue
    public Builder setStdoutOutput(Artifact stdoutOutput) {
      this.stdoutOutput = stdoutOutput;
      return this;
    }

    /** Creates a SpawnAction. */
    @Override
    protected SpawnAction createSpawnAction(
        ActionOwner owner,
        NestedSet<Artifact> tools,
        NestedSet<Artifact> inputsAndTools,
        ImmutableSet<Artifact> outputs,
        ResourceSetOrBuilder resourceSetOrBuilder,
        CommandLines commandLines,
        ActionEnvironment env,
        ImmutableMap<String, String> executionInfo,
        CharSequence progressMessage,
        String mnemonic,
        OutputPathsMode outputPathsMode) {
      checkArgument(
          usedInputsList.isEmpty() || (unusedInputsList.isEmpty() && shadowedAction.isEmpty()));
      Optional<Artifact> inputList = usedInputsList.or(() -> unusedInputsList);
      if (inputList.isPresent()) {
        // Always download the input list from remote execution/cache.
        executionInfo =
            ImmutableMap.<String, String>builderWithExpectedSize(executionInfo.size() + 1)
                .putAll(executionInfo)
                .put(
                    ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS,
                    inputList.get().getExecPathString())
                .buildOrThrow();
      }
      return unusedInputsList.isPresent()
              || usedInputsList.isPresent()
              || shadowedAction.isPresent()
              || stdoutOutput != null
          ? new EnhancedStarlarkAction(
              owner,
              tools,
              inputsAndTools,
              outputs,
              resourceSetOrBuilder,
              commandLines,
              env,
              executionInfo,
              progressMessage,
              mnemonic,
              outputPathsMode,
              unusedInputsList,
              usedInputsList,
              discoveryInputs,
              shadowedAction,
              stdoutOutput)
          : new StarlarkAction(
              owner,
              tools,
              inputsAndTools,
              outputs,
              resourceSetOrBuilder,
              commandLines,
              env,
              executionInfo,
              progressMessage,
              mnemonic,
              outputPathsMode);
    }
  }

  /**
   * A {@link StarlarkAction} with an input list, a shadowed action, and/or a {@code stdout} output
   * present.
   */
  @AutoCodec
  @VisibleForSerialization
  static final class EnhancedStarlarkAction extends StarlarkAction {
    // The ordinary inputs (including tools). In used-list mode these are mandatory; discovery
    // candidates and shadowed-action inputs are stored separately.
    private final NestedSet<Artifact> allStarlarkActionInputs;
    // allStarlarkActionInputs plus discovery candidates or shadowed inputs, if present.
    private final NestedSet<Artifact> originalInputs;

    // Null when there is neither a used-input list nor a shadowed action.
    @Nullable private final NestedSet<Artifact> mandatoryInputs;

    private final Optional<Artifact> unusedInputsList;
    private final Optional<Artifact> usedInputsList;
    private final NestedSet<Artifact> discoveryInputs;
    private final Optional<Action> shadowedAction;
    @Nullable private final Artifact stdoutOutput;
    private boolean inputsDiscovered = false;
    private boolean prunedInputs = false;

    EnhancedStarlarkAction(
        ActionOwner owner,
        NestedSet<Artifact> tools,
        NestedSet<Artifact> inputs,
        Iterable<Artifact> outputs,
        ResourceSetOrBuilder resourceSetOrBuilder,
        CommandLines commandLines,
        ActionEnvironment env,
        ImmutableMap<String, String> executionInfo,
        CharSequence progressMessage,
        String mnemonic,
        OutputPathsMode outputPathsMode,
        Optional<Artifact> unusedInputsList,
        Optional<Artifact> usedInputsList,
        NestedSet<Artifact> discoveryInputs,
        Optional<Action> shadowedAction,
        @Nullable Artifact stdoutOutput) {
      super(
          owner,
          tools,
          shadowedAction.isPresent()
              ? createInputs(shadowedAction.get().getInputs(), inputs)
              : createInputs(inputs, discoveryInputs),
          outputs,
          resourceSetOrBuilder,
          commandLines,
          env,
          executionInfo,
          progressMessage,
          mnemonic,
          outputPathsMode);
      this.allStarlarkActionInputs = inputs;
      this.originalInputs = getInputs();
      this.mandatoryInputs =
          shadowedAction.isPresent()
              ? createInputs(shadowedAction.get().getMandatoryInputs(), inputs)
              : usedInputsList.isPresent() ? inputs : null;
      this.unusedInputsList = unusedInputsList;
      this.usedInputsList = usedInputsList;
      this.discoveryInputs = discoveryInputs;
      this.shadowedAction = shadowedAction;
      this.stdoutOutput = stdoutOutput;
    }

    @AutoCodec.Instantiator
    @VisibleForSerialization
    EnhancedStarlarkAction(
        ActionOwner owner,
        NestedSet<Artifact> tools,
        NestedSet<Artifact> allStarlarkActionInputs,
        Object rawOutputs,
        ResourceSetOrBuilder resourceSetOrBuilder,
        CommandLines commandLines,
        ActionEnvironment environment,
        ImmutableSortedMap<String, String> sortedExecutionInfo,
        CharSequence progressMessage,
        String mnemonic,
        OutputPathsMode outputPathsMode,
        Optional<Artifact> unusedInputsList,
        Optional<Artifact> usedInputsList,
        NestedSet<Artifact> discoveryInputs,
        Optional<Action> shadowedAction,
        @Nullable Artifact stdoutOutput) {
      super(
          owner,
          tools,
          shadowedAction.isPresent()
              ? createInputs(shadowedAction.get().getInputs(), allStarlarkActionInputs)
              : createInputs(allStarlarkActionInputs, discoveryInputs),
          rawOutputs,
          resourceSetOrBuilder,
          commandLines,
          environment,
          sortedExecutionInfo,
          progressMessage,
          mnemonic,
          outputPathsMode);
      this.allStarlarkActionInputs = allStarlarkActionInputs;
      this.originalInputs = getInputs();
      this.mandatoryInputs =
          shadowedAction.isPresent()
              ? createInputs(shadowedAction.get().getMandatoryInputs(), allStarlarkActionInputs)
              : usedInputsList.isPresent() ? allStarlarkActionInputs : null;
      this.unusedInputsList = unusedInputsList;
      this.usedInputsList = usedInputsList;
      this.discoveryInputs = discoveryInputs;
      this.shadowedAction = shadowedAction;
      this.stdoutOutput = stdoutOutput;
    }

    @Nullable
    @Override
    public Artifact getStdoutOutput() {
      return stdoutOutput;
    }

    @Override
    public NestedSet<Artifact> getSchedulingDependencies() {
      return shadowedAction.isPresent()
          ? shadowedAction.get().getSchedulingDependencies()
          : NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

    @Override
    public Optional<Artifact> getUnusedInputsList() {
      return unusedInputsList;
    }

    @Override
    public boolean isShareable() {
      return unusedInputsList.isEmpty() && usedInputsList.isEmpty();
    }

    @Override
    public boolean discoversInputs() {
      return unusedInputsList.isPresent()
          || usedInputsList.isPresent()
          || (shadowedAction.isPresent() && shadowedAction.get().discoversInputs());
    }

    @Override
    public boolean prunedInputs() {
      return prunedInputs;
    }

    @Override
    public NestedSet<Artifact> getOriginalInputs() {
      return originalInputs;
    }

    @Override
    protected boolean inputsDiscovered() {
      return inputsDiscovered;
    }

    @Override
    protected void setInputsDiscovered(boolean inputsDiscovered) {
      this.inputsDiscovered = inputsDiscovered;
    }

    @Override
    public NestedSet<Artifact> getMandatoryInputs() {
      return mandatoryInputs != null ? mandatoryInputs : getInputs();
    }

    @Override
    public NestedSet<Artifact> getAllowedDerivedInputs() {
      if (usedInputsList.isPresent()) {
        return originalInputs;
      }
      if (shadowedAction.isPresent()) {
        return createInputs(shadowedAction.get().getAllowedDerivedInputs(), getInputs());
      }
      return getInputs();
    }

    @Nullable
    @Override
    public NestedSet<Artifact> discoverInputs(ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException, InterruptedException {
      // If the Starlark action shadows another action and the shadowed action discovers its inputs,
      // we get the shadowed action's discovered inputs and append it to the Starlark action inputs.
      if (shadowedAction.isPresent() && shadowedAction.get().discoversInputs()) {
        Action shadowedActionObj = shadowedAction.get();

        NestedSet<Artifact> oldInputs = getInputs();
        NestedSet<Artifact> inputFilesForExtraAction =
            shadowedActionObj.getInputFilesForExtraAction(actionExecutionContext);
        if (inputFilesForExtraAction == null) {
          return null;
        }
        updateInputs(
            createInputs(
                shadowedActionObj.getInputs(), inputFilesForExtraAction, allStarlarkActionInputs));
        return NestedSetBuilder.wrap(
            Order.STABLE_ORDER, Sets.difference(getInputs().toSet(), oldInputs.toSet()));
      }
      // Otherwise, we need to "re-discover" all the original inputs: the unused ones that were
      // removed might now be needed.
      NestedSet<Artifact> inputs =
          usedInputsList.isPresent() ? originalInputs : allStarlarkActionInputs;
      updateInputs(inputs);
      return inputs;
    }

    private InputStream getInputListInputStream(
        Artifact inputList,
        ActionExecutionContext actionExecutionContext,
        List<SpawnResult> spawnResults)
        throws IOException, ExecException {

      // Check if the file is in-memory.
      // Note: SpawnActionContext guarantees that the first list entry exists and corresponds to the
      // executed spawn.
      ByteString content = spawnResults.get(0).getInMemoryOutput(inputList);
      if (content != null) {
        return content.newInput();
      }
      // Fallback to reading from disk.
      try {
        return actionExecutionContext.getPathResolver().toPath(inputList).getInputStream();
      } catch (FileNotFoundException e) {
        boolean used = usedInputsList.isPresent();
        String message =
            "Action did not create expected output file listing "
                + (used ? "used" : "unused")
                + " inputs: "
                + inputList.getExecPathString();
        throw new UserExecException(
            e,
            createFailureDetail(
                message,
                used
                    ? Code.USED_INPUT_LIST_FILE_NOT_FOUND
                    : Code.UNUSED_INPUT_LIST_FILE_NOT_FOUND));
      }
    }

    @Override
    protected void afterExecute(
        ActionExecutionContext actionExecutionContext,
        List<SpawnResult> spawnResults,
        PathMapper pathMapper)
        throws ExecException {
      if (usedInputsList.isPresent()) {
        pruneDiscoveryInputs(actionExecutionContext, spawnResults, pathMapper);
        return;
      }
      if (unusedInputsList.isEmpty()) {
        return;
      }

      // Initialized lazily in case there are no unused inputs.
      Map<String, Artifact> usedInputsByMappedPath = null;

      boolean sawUnusedInput = false;

      // Bazel encodes file system paths as raw bytes stored in a Latin-1 encoded string, so we need
      // to make sure to also decode the unused input list as Latin-1.
      try (BufferedReader br =
          new BufferedReader(
              new InputStreamReader(
                  getInputListInputStream(
                      unusedInputsList.get(), actionExecutionContext, spawnResults),
                  ISO_8859_1))) {
        String line;
        while ((line = br.readLine()) != null) {
          if (line.isEmpty()) {
            continue;
          }
          if (usedInputsByMappedPath == null) {
            // Get all the action's inputs after execution which will include the shadowed action
            // discovered inputs.
            ImmutableList<Artifact> allInputs = getInputs().toList();
            usedInputsByMappedPath = Maps.newHashMapWithExpectedSize(allInputs.size());
            for (Artifact input : allInputs) {
              usedInputsByMappedPath.put(pathMapper.getMappedExecPathString(input), input);
            }
          }
          if (usedInputsByMappedPath.remove(line) != null) {
            sawUnusedInput = true;
          }
        }
      } catch (IOException e) {
        throw new EnvironmentalExecException(
            e,
            createFailureDetail("Unused inputs read failure", Code.UNUSED_INPUT_LIST_READ_FAILURE));
      }

      prunedInputs = sawUnusedInput;
      if (sawUnusedInput) {
        updateInputs(NestedSetBuilder.wrap(Order.STABLE_ORDER, usedInputsByMappedPath.values()));
      }
    }

    private void pruneDiscoveryInputs(
        ActionExecutionContext actionExecutionContext,
        List<SpawnResult> spawnResults,
        PathMapper pathMapper)
        throws ExecException {
      Set<String> usedPaths = new HashSet<>();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(
                  getInputListInputStream(
                      usedInputsList.get(), actionExecutionContext, spawnResults),
                  ISO_8859_1))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.isEmpty()) {
            usedPaths.add(line);
          }
        }
      } catch (IOException e) {
        throw new EnvironmentalExecException(
            e, createFailureDetail("Used inputs read failure", Code.USED_INPUT_LIST_READ_FAILURE));
      }

      // Keep only O(used inputs) path strings, rather than building a map of every candidate.
      // Do not consume usedPaths while walking: path mapping can give multiple inputs the same
      // path.
      Set<String> unmatchedPaths = new HashSet<>(usedPaths);
      NestedSetBuilder<Artifact> retained =
          NestedSetBuilder.<Artifact>stableOrder().addTransitive(mandatoryInputs);
      for (Artifact input : mandatoryInputs.toList()) {
        unmatchedPaths.remove(pathMapper.getMappedExecPathString(input));
      }
      boolean pruned = false;
      for (Artifact input : discoveryInputs.toList()) {
        String path = pathMapper.getMappedExecPathString(input);
        if (usedPaths.contains(path)) {
          retained.add(input);
          unmatchedPaths.remove(path);
        } else {
          pruned = true;
        }
      }
      if (!unmatchedPaths.isEmpty()) {
        throw new UserExecException(
            createFailureDetail(
                "Used inputs list contains an undeclared input: "
                    + unmatchedPaths.iterator().next(),
                Code.USED_INPUT_LIST_UNDECLARED_INPUT));
      }
      prunedInputs = pruned;
      updateInputs(retained.build());
    }

    private static final ExceptionlessMapFn<Artifact> INPUT_PATH =
        (input, consumer) -> {
          consumer.accept(input.isSourceArtifact() ? "source" : "derived");
          consumer.accept(input.getExecPathString());
        };

    private static final ExceptionlessMapFn<Artifact> MAPPED_INPUT_PATH =
        (input, consumer) -> {
          consumer.accept(input.isSourceArtifact() ? "source" : "derived");
          consumer.accept(
              PathMapper.forActionKey(OutputPathsMode.STRIP).getMappedExecPathString(input));
        };

    @Override
    protected void computeKey(
        ActionKeyContext actionKeyContext,
        @Nullable InputMetadataProvider inputMetadataProvider,
        Fingerprint fp)
        throws CommandLineExpansionException, InterruptedException {
      super.computeKey(actionKeyContext, inputMetadataProvider, fp);
      if (usedInputsList.isEmpty()) {
        return;
      }
      OutputPathsMode mode =
          PathMappers.getEffectiveOutputPathsMode(
              getOutputPathsMode(), getMnemonic(), getExecutionInfo());
      ExceptionlessMapFn<Artifact> pathFn =
          mode == OutputPathsMode.OFF ? INPUT_PATH : MAPPED_INPUT_PATH;
      fp.addString("used_inputs_list");
      fp.addString(PathMapper.forActionKey(mode).getMappedExecPathString(usedInputsList.get()));
      // A pruned cache entry restores only its recorded inputs. Membership changes must invalidate
      // it too, including a new mandatory input or a newly available header that shadows an old
      // one.
      // Reuse nested-set fingerprints across children sharing a large candidate namespace.
      fp.addString("mandatory_inputs");
      actionKeyContext.addNestedSetToFingerprint(pathFn, fp, mandatoryInputs);
      fp.addString("discovery_inputs");
      actionKeyContext.addNestedSetToFingerprint(pathFn, fp, discoveryInputs);
    }

    @Override
    Spawn getSpawnForExtraActionSpawnInfo()
        throws CommandLineExpansionException, InterruptedException {
      if (shadowedAction.isPresent()) {
        return this.getSpawnForExtraActionSpawnInfo(
            createInputs(shadowedAction.get().getInputs(), allStarlarkActionInputs));
      }
      return this.getSpawnForExtraActionSpawnInfo(originalInputs);
    }

    @Nullable
    @Override
    public NestedSet<Artifact> getInputFilesForExtraAction(
        ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException, InterruptedException {
      if (shadowedAction.isEmpty()) {
        return originalInputs;
      }
      NestedSet<Artifact> inputFilesForExtraAction =
          shadowedAction.get().getInputFilesForExtraAction(actionExecutionContext);
      if (inputFilesForExtraAction == null) {
        return null;
      }
      return createInputs(inputFilesForExtraAction, allStarlarkActionInputs);
    }

    @Override
    public ImmutableMap<String, String> getEffectiveEnvironment(
        Map<String, String> clientEnv, PathMapper pathMapper) throws CommandLineExpansionException {
      ActionEnvironment env = getEnvironment();
      Map<String, String> environment = Maps.newLinkedHashMapWithExpectedSize(env.estimatedSize());

      if (shadowedAction.isPresent()) {
        // Put all the variables of the shadowed action's environment
        environment.putAll(shadowedAction.get().getEffectiveEnvironment(clientEnv, pathMapper));
      }

      // This order guarantees that the Starlark action can overwrite any variable in its shadowed
      // action environment with a new value.
      env.resolve(environment, clientEnv);
      return ImmutableMap.copyOf(environment);
    }
  }
}
