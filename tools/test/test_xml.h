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

#ifndef BAZEL_TOOLS_TEST_TEST_XML_H_
#define BAZEL_TOOLS_TEST_TEST_XML_H_

#include <iosfwd>
#include <string>

namespace bazel::tools::test_wrapper {

std::string XmlTestName(std::string test, int shard, int total);
bool WriteCdata(std::istream& input, std::ostream& output);
bool WriteTestXml(std::istream& log, std::ostream& output,
                  const std::string& name, int seconds, int exit_code,
                  const char* log_heading);

}  // namespace bazel::tools::test_wrapper

#endif  // BAZEL_TOOLS_TEST_TEST_XML_H_
