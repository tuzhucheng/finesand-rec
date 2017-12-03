#!/usr/bin/env bash

# Args: [main class] [repo] [branch]

set -x

sbtargs="runMain finesand.$1 --repo $2 --branch $3 --group 1000 --split 1.0"
sbt '-J-Xms256m' '-J-Xmx8G' "$memargs $sbtargs"
