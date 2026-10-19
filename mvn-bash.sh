#!/usr/bin/env bash
# Maven wrapper for Git Bash
#
# 背景：系统的 /e/Maven/apache-maven-3.9.16/bin/mvn 是 Unix shell 脚本，
# 在 Git Bash 里它把 POSIX 路径（/e/Maven/...）直接传给 Windows 版 java.exe，
# java.exe 不认 /e/ 开头这种路径，导致
#   ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher
#
# 这里手工把路径转成 E:/ 形式再调 java，绕开 mvn 脚本的路径转换问题。
# （如果哪天在 Windows 的 cmd/PowerShell 里跑，直接用 mvn.cmd 就行，不需要这个脚本）

set -e

MAVEN_HOME="E:/Maven/apache-maven-3.9.16"
JAVA="C:/Users/mn060/.jdks/ms-21.0.11/bin/java"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR_WIN="$(echo "$PROJECT_DIR" | sed -E 's|^/([a-zA-Z])/|\1:/|')"

exec "$JAVA" \
  -classpath "$MAVEN_HOME/boot/plexus-classworlds-2.11.0.jar" \
  "-Dclassworlds.conf=$MAVEN_HOME/bin/m2.conf" \
  "-Dmaven.home=$MAVEN_HOME" \
  "-Dlibrary.jansi.path=$MAVEN_HOME/lib/jansi-native" \
  "-Dmaven.multiModuleProjectDirectory=$PROJECT_DIR_WIN" \
  -Dfile.encoding=UTF-8 \
  -Dstdout.encoding=UTF-8 \
  org.codehaus.plexus.classworlds.launcher.Launcher \
  "$@"
