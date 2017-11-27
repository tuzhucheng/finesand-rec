#!/usr/bin/env bash

# Args: [n_jobs] [optional commands to parallel]

repos=(../data/community-corpus/*/)

for i in "${!repos[@]}" ; do
    # strip trailing /
    repos[$i]="${repos[$i]::-1}"
done

commands=()
for i in "${!repos[@]}" ; do
    repo="${repos[$i]}"
    if [[ $repo == *"log4j"* ]] ; then
        sbtargs1="runMain finesand.PrepareData --repo $repo --branch trunk"
    else
        sbtargs1="runMain finesand.PrepareData --repo $repo --branch master"
    fi
    command="$sbtargs1"
    commands+=("$command")
done

parallel $2 --eta -j $1 sbt ::: "${commands[@]}"

commands2=()
for i in "${!repos[@]}" ; do
    repo="${repos[$i]}"
    sbtargs2="runMain finesand.BuildCounts --repo $repo --group 1000"
    command="$sbtargs2"
    commands2+=("$command")
done

parallel $2 --eta -j $1 sbt ::: "${commands2[@]}"

