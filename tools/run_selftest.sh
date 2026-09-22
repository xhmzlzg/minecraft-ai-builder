#!/usr/bin/env bash
set -e
SRC="/d/一些AI coding的成果/Minecraft AI小组件"
GSONJAR="C:/Users/ASUS/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.13.2/48b8230771e573b54ce6e867a9001e75977fe78e/gson-2.13.2.jar"
files=("$SRC/src/main/java/com/mcai/common/BuildingPlan.java" "$SRC/src/main/java/com/mcai/common/PlanValidator.java")
for f in "$SRC"/src/main/java/com/mcai/common/spec/*.java; do files+=("$f"); done
files+=("$SRC/selftest/SpecSelfTest.java")
javac -encoding UTF-8 -cp "$GSONJAR" -d "$SRC/build/selftest" "${files[@]}"
cd "$SRC/build/selftest"
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp ".;$GSONJAR" SpecSelfTest
