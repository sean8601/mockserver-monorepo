#!/usr/bin/env bash

set -e

export MAVEN_OPTS="$MAVEN_OPTS -Xmx2048m"
export JAVA_OPTS="$JAVA_OPTS -Xmx2048m"
export JAVA_HOME=`/usr/libexec/java_home -v 17`
echo
java -version
echo
./mvnw -version
echo

# to run from specific module use argument in quotes "-rf mockserver-war"
./mvnw -T 1C clean install -offline $1 -Djava.security.egd=file:/dev/urandom -DskipAssembly=true