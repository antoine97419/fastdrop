#!/bin/bash
export JAVA_HOME=/tmp/jdk-17.0.10+7 && export PATH=$JAVA_HOME/bin:$PATH && export LD_LIBRARY_PATH=/tmp/jdk-17.0.10+7/lib

CP=$(./gradlew -q printClasspath)
java -cp "shared/build/classes/kotlin/desktop/main:$CP" -Dfastdrop.home="$1" com.fastdrop.MainKt
