#!/bin/bash
# Runs the Android parser tests on a plain JVM (no SDK needed).
set -euo pipefail
export JAVA_TOOL_OPTIONS=
cd "$(dirname "$0")/.."
B=build/test
rm -rf $B && mkdir -p $B
javac -nowarn -encoding UTF-8 --release 8 -Xlint:-options -d $B \
  src/app/dash/{SubParser,Server,Json,XrayJson}.java test/app/dash/SubParserTest.java
java -cp $B app.dash.SubParserTest testdata/sub-cases.json
