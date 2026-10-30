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

#include <sstream>
#include <string>

#include "gtest/gtest.h"
#include "tools/test/test_xml.h"

namespace bazel::tools::test_wrapper {
namespace {

void CheckCdata(const std::string& input, const std::string& expected) {
  std::istringstream in(input);
  std::ostringstream out;
  ASSERT_TRUE(WriteCdata(in, out));
  EXPECT_EQ(out.str(), expected);
}

TEST(TestXml, TestCdataEscapeNullTerminator) {
  CheckCdata(std::string("x\0y", 3), "x?y");
}

TEST(TestXml, TestCdataEscapeCdataEndings) {
  CheckCdata(
      // === Input ===
      // CDATA end sequence, followed by some arbitrary octet.
      "]]>x"
      // CDATA end sequence twice.
      "]]>]]>x"
      // CDATA end sequence at the end of the string.
      "]]>",

      // === Expected output ===
      "]]>]]<![CDATA[>x"
      "]]>]]<![CDATA[>]]>]]<![CDATA[>x"
      "]]>]]<![CDATA[>");
}

TEST(TestXml, TestCdataEscapeSingleOctets) {
  CheckCdata(
      // === Input ===
      // Legal single-octets.
      "AB\x9\xA\xD\x20\x7F"
      // Illegal single-octets.
      "\x8\xB\xC\x1F\x80\xFF"
      "x",

      // === Expected output ===
      // Legal single-octets.
      "AB\x9\xA\xD\x20\x7F"
      // Illegal single-octets.
      "??????"
      "x");
}

TEST(TestXml, TestCdataEscapeDoubleOctets) {
  // Overlong encodings are not legal Unicode characters.
  CheckCdata(
      "x"
      // Overlong encoding followed by legal double-octet sequences.
      "\xC0\x80"
      "\xDE\xB0"
      "\xDF\xBF"
      // Illegal double-octet sequences, first octet is bad, second is good.
      "\xBF\x80"  // each are matched as single bad octets
      "\xE0\x80"
      // Illegal double-octet sequences, first octet is good, second is bad.
      "\xC0\x7F"  // 0x7F is legal as a single-octet, retained
      "\xDF\xC0"
      // Illegal double-octet sequences, both octets bad.
      "\xBF\xFF"
      "x",

      // === Expected output ===
      "x"
      // Overlong encoding followed by legal double-octet sequences.
      "??"
      "\xDE\xB0"
      "\xDF\xBF"
      // Illegal double-octet sequences, first octet is bad, second is good.
      "??"
      "??"
      // Illegal double-octet sequences, first octet is good, second is bad.
      "?\x7F"  // 0x7F is legal as a single-octet, retained
      "??"
      // Illegal double-octet sequences, both octets bad.
      "??"
      "x");
}

TEST(TestXml, TestCdataEscapeAndAppend) {
  CheckCdata(
      // === Input ===
      "AB\xA\xC\xD"
      "]]>"
      "]]]>"
      "\xC0\x80"
      "a"
      "\xED\x9F\xBF"
      "b"
      "\xEF\xBF\xB0"
      "c"
      "\xF7\xB0\x80\x81"
      "d"
      "]]>",

      // === Output ===
      "AB\xA?\xD"
      "]]>]]<![CDATA[>"
      "]]]>]]<![CDATA[>"
      "??"
      "a"
      "\xED\x9F\xBF"
      "b"
      "\xEF\xBF\xB0"
      "c"
      "????"
      "d"
      "]]>]]<![CDATA[>");
}

TEST(TestXml, PreservesCdataAndUnicodeAcrossReadBoundaries) {
  for (int tail = 1; tail <= 3; ++tail) {
    const std::string prefix(16384 - tail, 'a');
    CheckCdata(prefix + "]]>", prefix + "]]>]]<![CDATA[>");
    CheckCdata(prefix + "\xF4\x8F\xBF\xBF", prefix + "\xF4\x8F\xBF\xBF");
    CheckCdata(prefix + "\xF0\x90", prefix + "??");
  }
}

TEST(TestXml, PreservesFailedShardedTestIdentity) {
  std::istringstream in("failed\n");
  std::ostringstream out;
  ASSERT_TRUE(WriteTestXml(in, out, XmlTestName("../pkg/a&b\xEF\xBF\xBE", 1, 3),
                           5, 42, ""));
  EXPECT_NE(out.str().find("name=\"pkg/a&amp;b???_shard_2/3\""),
            std::string::npos);
  EXPECT_NE(out.str().find("errors=\"1\""), std::string::npos);
  EXPECT_NE(out.str().find("exited with error code 42"), std::string::npos);
  EXPECT_NE(out.str().find("<![CDATA[failed\n]]>"), std::string::npos);
}

}  // namespace
}  // namespace bazel::tools::test_wrapper
