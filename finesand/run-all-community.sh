#!/usr/bin/env bash

# Takes one command line arg - the number of jobs to use

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

parallel --eta -j $1 sbt ::: "${commands[@]}"

#for i in "${!repos[@]}" ; do
    #echo "${repos[$i]}"
    #echo "${commands[$i]}"

    #sbtargs2="runMain finesand.BuildCounts $arg"
    #sbt "$sbtargs2"
#done
