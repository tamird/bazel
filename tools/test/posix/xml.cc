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

#include <cerrno>
#include <charconv>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>

#include "tools/test/test_xml.h"

namespace bazel::tools::test_wrapper {
namespace {

bool ParseInt(const char* value, int* result) {
  if (value == nullptr) return false;
  const char* end = value + std::strlen(value);
  auto parsed = std::from_chars(value, end, *result);
  return parsed.ec == std::errc{} && parsed.ptr == end;
}

}  // namespace

int XmlWriterMain(int argc, char** argv) {
  int seconds, exit_code;
  if (argc != 5 || !ParseInt(argv[3], &seconds) ||
      !ParseInt(argv[4], &exit_code)) {
    std::fprintf(stderr, "Usage: %s test.log test.xml duration exit_code\n",
                 argv[0]);
    return 1;
  }
  const char* test = std::getenv("TEST_BINARY");
  const char* total_env = std::getenv("TEST_TOTAL_SHARDS");
  int total = 0, shard = 0;
  if (!test || (total_env && !ParseInt(total_env, &total)) ||
      (total > 0 && !ParseInt(std::getenv("TEST_SHARD_INDEX"), &shard))) {
    std::fprintf(stderr, "Invalid test name or shard environment\n");
    return 1;
  }
  // An empty or missing test log is a valid result for a silent test.
  std::ifstream log(argv[1], std::ios::binary);
  if (!log && errno != ENOENT) {
    std::perror(argv[1]);
    return 1;
  }
  std::istringstream empty;
  std::ofstream out(argv[2], std::ios::binary);
  if (!out || !WriteTestXml(log ? static_cast<std::istream&>(log) : empty, out,
                            XmlTestName(test, shard, total), seconds, exit_code,
                            "\nGenerated test.log (if the file is not UTF-8, "
                            "then this may be unreadable):\n")) {
    std::fprintf(stderr, "Could not write test XML from %s to %s\n", argv[1],
                 argv[2]);
    return 1;
  }
  out.close();
  return out.fail() ? 1 : 0;
}

}  // namespace bazel::tools::test_wrapper
