#!/usr/bin/env bash
set -euo pipefail

# Exercise the actual chat text readers in JVM regression tests. Android .so
# files cannot be loaded by the Linux host JVM, so compile the same parser sources.
streamnative_host_output="${1:?Usage: build_streamnative_host.sh OUTPUT_DIRECTORY}"
streamnative_repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$streamnative_repository"
mkdir -p "$streamnative_host_output"

g++ -std=c++17 -O0 -fPIC -shared -pthread -Wl,--no-undefined \
  -I"${JAVA_HOME:?JAVA_HOME must point to a JDK}/include" \
  -I"$JAVA_HOME/include/linux" -Iapp/src/main/cpp \
  app/src/main/cpp/streamnative/native_xml_splitter.cpp \
  app/src/main/cpp/streamnative/native_markdown_splitter.cpp \
  app/src/main/cpp/streamnative/StreamOperators.cpp \
  app/src/main/cpp/streamnative/plugins/StreamXmlPlugin.cpp \
  app/src/main/cpp/streamnative/plugins/BaseJsonPlugin.cpp \
  app/src/main/cpp/streamnative/plugins/StreamJsonPlugin.cpp \
  app/src/main/cpp/streamnative/plugins/StreamPureJsonPlugin.cpp \
  app/src/main/cpp/streamnative/plugins/StreamMarkdownPlugin.cpp \
  app/src/main/cpp/streamnative/StreamBuilders.cpp \
  app/src/main/cpp/streamnative/HotStream.cpp \
  app/src/main/cpp/streamnative/StringExtensions.cpp \
  -o "$streamnative_host_output/libstreamnative.so"
