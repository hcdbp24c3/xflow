#!/usr/bin/env bash
# Compiles native/core (main + test) with a standalone kotlinc and runs the
# JUnit tests, without Gradle. For machines too small to run the Android
# build; CI runs the real Gradle build.
#   TOOLS=<dir with jdk/, kotlinc/, lib/, xflow-libs/>  tools/check-core.sh [TestClassFilter]
set -euo pipefail
TOOLS="${TOOLS:-$HOME/workspace/tools}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-/tmp/xflow-core-check-$$}"
export JAVA_HOME="$TOOLS/jdk" PATH="$TOOLS/jdk/bin:$PATH"
export JAVA_OPTS="-Xmx640m -Xss4m"
LIBS="$(ls "$TOOLS"/xflow-libs/*.jar | tr '\n' ':')"
TESTLIBS="$TOOLS/lib/junit-4.13.2.jar:$TOOLS/lib/hamcrest-core-1.3.jar:$TOOLS/kotlinc/lib/kotlin-test.jar:$TOOLS/kotlinc/lib/kotlin-test-junit.jar"
PLUGIN="$TOOLS/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar"
rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"
exec 9>/tmp/xflow-kotlinc.lock; flock 9
"$TOOLS/kotlinc/bin/kotlinc" -Xplugin="$PLUGIN" -nowarn -jvm-target 17 -cp "$LIBS" \
  -d "$OUT/main" $(find "$ROOT/core/src/main" -name '*.kt')
TESTS=$(find "$ROOT/core/src/test" -name '*.kt' 2>/dev/null || true)
if [ -n "$TESTS" ]; then
  "$TOOLS/kotlinc/bin/kotlinc" -Xplugin="$PLUGIN" -nowarn -jvm-target 17 \
    -cp "$LIBS$OUT/main:$TESTLIBS" -d "$OUT/test" $TESTS
  [ -d "$ROOT/core/src/test/resources" ] && cp -r "$ROOT/core/src/test/resources/." "$OUT/test/"
  CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | grep -i "${1:-.}" | sort)
  java -Xmx256m -cp "$LIBS$OUT/main:$OUT/test:$TESTLIBS:$TOOLS/kotlinc/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore $CLASSES
fi
flock -u 9
rm -rf "$OUT"
echo "core check OK"
