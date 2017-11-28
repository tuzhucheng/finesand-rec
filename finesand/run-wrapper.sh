#!/usr/bin/env bash

# Args: [main class] [repo] [branch]

sbtargs="runMain finesand.$1 --repo $2 --branch $3 --group 1000"
sbt "$sbtargs"
