#!/usr/bin/env bash

for d in ../data/community-corpus/*/ ; do
    arg="${d::-1}"
    if [[ $arg == *"log4j"* ]] ; then
        sbtargs1="runMain finesand.PrepareData $arg trunk"
    else
        sbtargs1="runMain finesand.PrepareData $arg master"
    fi
    sbt "$sbtargs1"

    sbtargs2="runMain finesand.BuildCounts $arg"
    sbt "$sbtargs2"
done
