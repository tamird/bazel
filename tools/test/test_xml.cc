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

#include <cstring>
#include <istream>
#include <ostream>
#include <string>

#include "tools/test/test_xml.h"

namespace bazel::tools::test_wrapper {
namespace {

// Number of bytes in the next UTF-8 encoded XML 1.0 Char, or zero if invalid.
// Callers keep up to three lookahead bytes, including across input chunks.
size_t XmlCharSize(const unsigned char* s, size_t n) {
  const unsigned int c = s[0];
  if (c < 0x80) {
    return c == 9 || c == 10 || c == 13 || c >= 0x20 ? 1 : 0;
  }
  if (c < 0xc2 || c > 0xf4) return 0;
  const size_t size = c < 0xe0 ? 2 : c < 0xf0 ? 3 : 4;
  if (n < size) return 0;
  for (size_t i = 1; i < size; ++i) {
    if ((s[i] & 0xc0) != 0x80) return 0;
  }
  // Exclude overlong encodings, surrogates, U+FFFE/U+FFFF, and > U+10FFFF.
  if ((c == 0xe0 && s[1] < 0xa0) || (c == 0xed && s[1] >= 0xa0) ||
      (c == 0xef && s[1] == 0xbf && s[2] >= 0xbe) ||
      (c == 0xf0 && s[1] < 0x90) || (c == 0xf4 && s[1] >= 0x90)) {
    return 0;
  }
  return size;
}

std::string EscapeAttribute(const std::string& value) {
  std::string escaped;
  for (size_t pos = 0; pos < value.size();) {
    const size_t size =
        XmlCharSize(reinterpret_cast<const unsigned char*>(value.data() + pos),
                    value.size() - pos);
    if (size == 0) {
      escaped += '?';
      ++pos;
      continue;
    }
    switch (value[pos]) {
      case '&':
        escaped += "&amp;";
        break;
      case '<':
        escaped += "&lt;";
        break;
      case '"':
        escaped += "&quot;";
        break;
      default:
        escaped.append(value, pos, size);
        break;
    }
    pos += size;
  }
  return escaped;
}

}  // namespace

bool WriteCdata(std::istream& input, std::ostream& output) {
  unsigned char data[16 * 1024];
  size_t tail = 0;
  do {
    input.read(reinterpret_cast<char*>(data + tail), sizeof(data) - tail);
    if (input.bad() || (input.fail() && !input.eof())) return false;
    const size_t count = tail + input.gcount();
    size_t pos = 0;
    size_t start = 0;
    while (pos < count && (input.eof() || count - pos >= 4)) {
      const bool end =
          count - pos >= 3 && std::memcmp(data + pos, "]]>", 3) == 0;
      const size_t size = XmlCharSize(data + pos, count - pos);
      if (size && !end) {
        pos += size;
        continue;
      }
      output.write(reinterpret_cast<const char*>(data + start), pos - start);
      if (end) {
        output << "]]>]]<![CDATA[>";
        pos += 3;
      } else {
        output.put('?');
        ++pos;
      }
      start = pos;
    }
    output.write(reinterpret_cast<const char*>(data + start), pos - start);
    if (!output) return false;
    tail = count - pos;
    std::memmove(data, data + pos, tail);
  } while (!input.eof());
  return true;
}

std::string XmlTestName(std::string test, int shard, int total) {
  if (test.compare(0, 2, "./") == 0) test.erase(0, 2);
  if (test.compare(0, 3, "../") == 0) test.erase(0, 3);
  if (total > 0) {
    test += "_shard_" + std::to_string(shard + 1) + "/" + std::to_string(total);
  }
  return test;
}

bool WriteTestXml(std::istream& log, std::ostream& out, const std::string& name,
                  int seconds, int exit_code, const char* log_heading) {
  const std::string escaped = EscapeAttribute(name);
  out << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<testsuites>\n<testsuite name=\""
      << escaped << "\" tests=\"1\" failures=\"0\" errors=\""
      << (exit_code != 0) << "\">\n<testcase name=\"" << escaped
      << "\" classname=\"\" status=\"run\" duration=\"" << seconds
      << "\" time=\"" << seconds << "\">";
  if (exit_code != 0) {
    out << "<error message=\"exited with error code " << exit_code
        << "\"></error>";
  }
  out << "</testcase>\n<system-out>" << log_heading << "<![CDATA[";
  if (!out || !WriteCdata(log, out)) return false;
  out << "]]></system-out>\n</testsuite>\n</testsuites>\n";
  return out.good();
}

}  // namespace bazel::tools::test_wrapper
