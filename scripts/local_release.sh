#!/usr/bin/env bash

set -e

export MAVEN_OPTS="$MAVEN_OPTS -Xms2048m -Xmx8192m"
export JAVA_OPTS="$JAVA_OPTS -Xms2048m -Xmx8192m"
export JAVA_HOME=`/usr/libexec/java_home -v 17`

cd mockserver

echo
java -version
echo
./mvnw -version
echo

export GPG_TTY=$(tty)

./mvnw release:clean -Ddeploy.plugin.repository.url=https://oss.sonatype.org/service/local/staging/deploy/maven2/ -P release -Drelease.arguments="-Dmaven.test.skip=true -DskipTests=true -Ddeploy.plugin.repository.url=https://oss.sonatype.org/service/local/staging/deploy/maven2/" && \
./mvnw release:prepare -Ddeploy.plugin.repository.url=https://oss.sonatype.org/service/local/staging/deploy/maven2/ -P release -Drelease.arguments="-Dmaven.test.skip=true -DskipTests=true -Ddeploy.plugin.repository.url=https://oss.sonatype.org/service/local/staging/deploy/maven2/" && \
./mvnw release:perform -Ddeploy.plugin.repository.url=https://oss.sonatype.org/service/local/staging/deploy/maven2/ -P release -Drelease.arguments="-Dmaven.test.skip=true -DskipTests=true -Ddeploy.plugin.repository.url=https://oss.sonatype.org/service/local/staging/deploy/maven2/"