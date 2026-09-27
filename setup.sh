#!/bin/bash
set -e

mkdir -p app/src/main/java/com/example/wordpopuptest
mkdir -p app/src/main/res/values

mv app-build.gradle app/build.gradle
mv AndroidManifest.xml app/src/main/AndroidManifest.xml
mv strings.xml app/src/main/res/values/strings.xml
mv MainActivity.java app/src/main/java/com/example/wordpopuptest/MainActivity.java

echo "Project structure ready."
