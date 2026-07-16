#!/usr/bin/env bash
#
# Copyright 2026 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -eu

coverage_script="$TEST_SRCDIR/$TEST_WORKSPACE/tools/test/collect_coverage.sh"
root="$TEST_TMPDIR/root"
coverage_dir="$TEST_TMPDIR/absolute-coverage"
coverage_output_file="$TEST_TMPDIR/absolute-output/coverage.dat"

mkdir -p "$root" "$(dirname "$coverage_output_file")"
touch "$root/manifest"

(
  cd "$root"
  COVERAGE_MANIFEST="$root/manifest" \
  COVERAGE_DIR="$coverage_dir" \
  COVERAGE_OUTPUT_FILE="$coverage_output_file" \
  IS_COVERAGE_SPAWN=0 \
  SPLIT_COVERAGE_POST_PROCESSING=0 \
  "$coverage_script" true
)

[[ -d "$coverage_dir" ]] || { echo "COVERAGE_DIR was not created" >&2; exit 1; }
[[ -f "$coverage_output_file" ]] || { echo "COVERAGE_OUTPUT_FILE was not created" >&2; exit 1; }
