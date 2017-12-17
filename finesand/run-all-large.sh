#!/usr/bin/env bash

# Args: [n_jobs] [optional commands to parallel]

repoNames=(accumulo admiral ambry beam bookkeeper)
repos=("${repoNames[@]}")

for i in "${!repos[@]}" ; do
    repos[$i]="../data/large-corpus/${repoNames[$i]}"
done

commands=()
for i in "${!repos[@]}" ; do
    repo="${repos[$i]}"
    sbtargs1="runMain finesand.PrepareData --repo $repo --branch master --split 1.0"
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

parallel $2 --eta -j $1 sbt ::: '-J-Xms256m' ::: '-J-Xmx8G' ::: "${commands2[@]}"

for i in "${!repos[@]}" ; do
    repo="${repos[$i]}"
    countsDir="${repoNames[$i]}-counts"
    countsTar="${repoNames[$i]}-counts.tar.gz"
    cd ../data/large-corpus
    mkdir -p $countsDir
    cp ${repoNames[$i]}-corpus/*{p,P}art* $countsDir
    tar -cvzf $countsTar $countsDir
    az dls fs upload --account finesand --source-path $countsTar --destination-path /large-corpus-counts --overwrite
    if [ $? -eq 0 ]
    then
        echo "Success, deleting..."
        rm -rf ${repoNames[$i]}-corpus $countsDir $countsTar
    fi
    cd -
done
