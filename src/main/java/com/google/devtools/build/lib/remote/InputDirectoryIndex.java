// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote;

import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.actions.FilesetOutputTree;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** Directories and children implied by an action's checked and discovered input paths. */
final class InputDirectoryIndex {
  private final ActionInputMap inputs;
  @Nullable private volatile PathFragment[] paths;
  @Nullable private volatile ConcurrentHashMap<PathFragment, Set<PathFragment>> discoveredChildren;

  InputDirectoryIndex(ActionInputMap inputs) {
    this.inputs = inputs;
  }

  /** Invalidates checked paths between action phases, when no filesystem operations are running. */
  void clear() {
    paths = null;
  }

  boolean isDirectory(PathFragment execPath) {
    var discovered = discoveredChildren;
    if (discovered != null && discovered.containsKey(execPath)) {
      return true;
    }
    PathFragment[] current = paths();
    int next = upperBound(current, execPath);
    return next < current.length && current[next].startsWith(execPath);
  }

  List<PathFragment> children(PathFragment execPath) {
    PathFragment[] current = paths();
    List<PathFragment> children = new ArrayList<>();
    int next = upperBound(current, execPath);
    while (next < current.length && current[next].startsWith(execPath)) {
      PathFragment child = current[next].subFragment(0, execPath.segmentCount() + 1);
      children.add(child);
      next++;
      // Avoid a binary search for individual files.
      if (next < current.length && current[next].startsWith(child)) {
        next = endOfSubtree(current, child, next);
      }
    }
    var discovered = discoveredChildren;
    Set<PathFragment> added = discovered == null ? null : discovered.get(execPath);
    if (added == null) {
      return children;
    }
    Set<PathFragment> combined = new HashSet<>(children);
    combined.addAll(added);
    return List.copyOf(combined);
  }

  /** Adds a path learned during input discovery without rebuilding the checked-input index. */
  void addDiscoveredPath(PathFragment execPath) {
    var current = discoveredChildren;
    if (current == null) {
      synchronized (this) {
        current = discoveredChildren;
        if (current == null) {
          discoveredChildren = current = new ConcurrentHashMap<>();
        }
      }
    }
    for (PathFragment child = execPath, parent;
        (parent = child.getParentDirectory()) != null;
        child = parent) {
      current.computeIfAbsent(parent, unused -> ConcurrentHashMap.newKeySet()).add(child);
    }
  }

  private PathFragment[] paths() {
    PathFragment[] current = paths;
    if (current == null) {
      synchronized (this) {
        current = paths;
        if (current == null) {
          List<PathFragment> sorted = new ArrayList<>();
          inputs.forEachPresentPath(sorted::add);
          for (FilesetOutputTree fileset : inputs.getFilesets().values()) {
            for (FilesetOutputSymlink link : fileset.symlinks()) {
              sorted.add(link.target().getExecPath());
            }
          }
          sorted.sort(PathFragment.HIERARCHICAL_COMPARATOR);
          paths = current = sorted.toArray(PathFragment[]::new);
        }
      }
    }
    return current;
  }

  /** Returns the first path strictly after the candidate, excluding an exact file match. */
  private static int upperBound(PathFragment[] paths, PathFragment candidate) {
    int low = 0;
    int high = paths.length;
    while (low < high) {
      int mid = (low + high) >>> 1;
      if (PathFragment.HIERARCHICAL_COMPARATOR.compare(paths[mid], candidate) <= 0) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low;
  }

  /** Descendants are contiguous in hierarchical order; skip them without visiting every input. */
  private static int endOfSubtree(PathFragment[] paths, PathFragment parent, int low) {
    int high = paths.length;
    while (low < high) {
      int mid = (low + high) >>> 1;
      if (paths[mid].startsWith(parent)) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low;
  }
}
