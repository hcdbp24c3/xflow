#!/usr/bin/env bash
# Type-checks native/app (and core main) with a standalone kotlinc against the
# compile classpath exported from CI (artifact "compile-classpath"), without
# Gradle. No APK, no R class, no tests: it only answers "does it compile".
#   TOOLS=<dir with jdk/, kotlinc/, xflow-cp/>  tools/check-app.sh [path-filter-regex]
# With a filter only errors in matching files are printed (all are compiled).
set -uo pipefail
TOOLS="${TOOLS:-$HOME/workspace/tools}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="/tmp/xflow-app-check-$$"
export JAVA_HOME="$TOOLS/jdk" PATH="$TOOLS/jdk/bin:$PATH"
export JAVA_OPTS="-Xmx900m -Xss4m"
CP="$(ls "$TOOLS"/xflow-cp/*.jar | grep -v '000-build-core.jar' | tr '\n' ':')"
mkdir -p "$OUT/stub/com/alchemistaloha/xflow"
cat > "$OUT/stub/com/alchemistaloha/xflow/BuildConfig.java" <<'J'
package com.alchemistaloha.xflow;
public final class BuildConfig {
  public static final boolean DEBUG = false;
  public static final String APPLICATION_ID = "com.alchemistaloha.xflow";
  public static final String BUILD_TYPE = "release";
  public static final int VERSION_CODE = 1;
  public static final String VERSION_NAME = "0";
}
J
exec 9>/tmp/xflow-kotlinc.lock; flock 9
"$TOOLS/kotlinc/bin/kotlinc" \
  -Xplugin="$TOOLS/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar" \
  -Xplugin="$TOOLS/kotlinc/lib/compose-compiler-plugin.jar" \
  -Xskip-metadata-version-check -nowarn -jvm-target 17 -cp "$CP" -d "$OUT/classes" \
  $(find "$ROOT/core/src/main" "$ROOT/app/src/main" -name '*.kt') "$OUT/stub" > "$OUT/log" 2>&1
RC=$?
flock -u 9
grep -E "error:" "$OUT/log" | sed "s#$ROOT/##" | grep -E "${1:-.}" | head -${MAXERR:-80}
echo "errors total: $(grep -c 'error:' "$OUT/log")  (kotlinc exit $RC)"
rm -rf "$OUT"
[ $RC -eq 0 ] && echo "app check OK"
exit $RC
