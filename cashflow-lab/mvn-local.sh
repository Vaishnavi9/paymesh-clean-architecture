#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export JAVA_HOME="${CASHFLOW_JAVA_HOME:-/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home}"
cd "$project_dir"
exec mvn "-Dmaven.repo.local=$project_dir/.maven-repository" "$@"
