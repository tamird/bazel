// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlarkbuildapi;

import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

/**
 * Context object that is passed to the ctx.actions.map_directory implementation to allow for the
 * creation of actions.
 */
@StarlarkBuiltin(
    name = "template_ctx",
    category = DocCategory.BUILTIN,
    doc = "A context object that is passed to the action template expansion function.")
public interface StarlarkTemplateContextApi extends StarlarkValue {

  @StarlarkMethod(
      name = "declare_file",
      doc =
          "Declares that implementation creates a file with the given filename within the specified"
              + " directory.<p>Remember that in addition to declaring a file, you must separately"
              + " create an action that emits the file. Creating that action will require passing"
              + " the returned <code>File</code> object to the action's construction"
              + " function.",
      parameters = {
        @Param(name = "filename", doc = "The relative path of the file within the directory."),
        @Param(
            name = "directory",
            doc = "The directory in which the file should be created.",
            named = true,
            positional = false)
      })
  FileApi declareFile(String filename, FileApi directory) throws EvalException;

  @StarlarkMethod(
      name = "declare_subdirectory",
      doc =
          "Declares that implementation creates a subdirectory with the given name within the"
              + " specified directory.<p>Remember that in addition to declaring a subdirectory,"
              + " you must separately create an action that emits the subdirectory. Creating that"
              + " action will require passing the returned <code>File</code> object to the"
              + " action's construction function.",
      parameters = {
        @Param(
            name = "subdirectory",
            doc = "The relative path of the subdirectory within the directory."),
        @Param(
            name = "directory",
            doc = "The directory in which the subdirectory should be created.",
            named = true,
            positional = false)
      })
  FileApi declareSubdirectory(String subdirectory, FileApi directory) throws EvalException;

  @StarlarkMethod(
      name = "args",
      doc = "Returns an Args object that can be used to build memory-efficient command lines.",
      useStarlarkThread = true)
  CommandLineArgsApi args(StarlarkThread thread);

  @StarlarkMethod(
      name = "run",
      doc = "Creates an action that runs an executable.",
      parameters = {
        @Param(
            name = "outputs",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = FileApi.class),
            },
            named = true,
            positional = false,
            doc = "List of the output files of the action."),
        @Param(
            name = "inputs",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = FileApi.class),
              @ParamType(type = Depset.class),
            },
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = "List or depset of the input files of the action."),
        @Param(
            name = "executable",
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = String.class),
              @ParamType(type = FilesToRunProviderApi.class),
            },
            named = true,
            positional = false,
            doc = "The executable file to be called by the action."),
        @Param(
            name = "tools",
            allowedTypes = {
              @ParamType(type = Sequence.class),
              @ParamType(type = Depset.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc = StarlarkActionFactoryApi.TOOLS_ARG_DOC),
        @Param(
            name = "arguments",
            allowedTypes = {@ParamType(type = Sequence.class)},
            defaultValue = "[]",
            named = true,
            positional = false,
            doc =
                "Command line arguments of the action. "
                    + "Must be a list of strings or "
                    + "<a href=\"#args\"><code>actions.args()</code></a> objects."),
        @Param(
            name = "progress_message",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            named = true,
            positional = false,
            defaultValue = "None",
            doc = "Progress message to show to the user during the build."),
        @Param(
            name = "used_inputs_list",
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = NoneType.class),
            },
            named = true,
            positional = false,
            defaultValue = "None",
            doc =
                "An output file listing the files from <code>discovery_inputs</code> that the"
                    + " action used, one execution-root-relative path per line. Paths must use the"
                    + " action's path mapping, if enabled. The file must be declared in"
                    + " <code>outputs</code> and produced even when empty. A missing file or a path"
                    + " not declared in the action's inputs is an error. Exact paths of mandatory"
                    + " Files are accepted; those inputs are always retained. Children of mandatory"
                    + " directory artifacts must be omitted from the list: the directory is"
                    + " retained in full. After execution, changes"
                    + " to unlisted discovery inputs do not invalidate the cached action. An empty"
                    + " file retains only mandatory inputs and tools. Must be specified together"
                    + " with <code>discovery_inputs</code>."),
        @Param(
            name = "discovery_inputs",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = FileApi.class),
              @ParamType(type = Depset.class),
              @ParamType(type = NoneType.class),
            },
            named = true,
            positional = false,
            defaultValue = "None",
            doc =
                "List or depset of candidate input Files, in addition to the mandatory"
                    + " <code>inputs</code> and <code>tools</code>. All candidates are available"
                    + " when the action executes. Directory artifacts are not supported: use"
                    + " individual files from an expanded input directory. Adding or removing a"
                    + " candidate invalidates the action, even if it was not previously used."
                    + " The action must report every candidate that can affect its outputs in"
                    + " <code>used_inputs_list</code>. Search order, namespace membership, and"
                    + " other information needed to detect newly shadowing inputs must remain"
                    + " mandatory inputs or arguments. Cached discovery is an optimization, not"
                    + " a guarantee that a fresh client avoids reading all candidate inputs."),
      })
  void run(
      Sequence<?> outputs,
      Object inputs,
      Object executableUnchecked,
      Object toolsUnchecked,
      Sequence<?> arguments,
      Object progressMessage,
      Object usedInputsList,
      Object discoveryInputs)
      throws EvalException, InterruptedException;
}
