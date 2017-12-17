#!/usr/bin/env bash

# Args: [n_jobs] [optional commands to parallel]

repoNames=(antlr4 itextpdf jgit log4j spring-framework)
repos=("${repoNames[@]}")

for i in "${!repos[@]}" ; do
    repos[$i]="../data/community-corpus/${repoNames[$i]}"
done

commands=()
for i in "${!repos[@]}" ; do
    repo="${repos[$i]}"
    if [[ $repo == *"log4j"* ]] ; then
        sbtargs1="runMain finesand.PrepareData --repo $repo --branch trunk --split 0.9"
    else
        sbtargs1="runMain finesand.PrepareData --repo $repo --branch master --split 0.9"
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

parallel $2 --eta -j $1 sbt ::: '-J-Xms256m' ::: '-J-Xmx8G' ::: "${commands2[@]}"

for i in "${!repos[@]}" ; do
    repo="${repos[$i]}"
    countsDir="${repoNames[$i]}-counts"
    countsTar="${repoNames[$i]}-counts.tar.gz"
    cd ../data/community-corpus
    mkdir -p $countsDir
    cp ${repoNames[$i]}-corpus/*{p,P}art* $countsDir
    tar -cvzf $countsTar $countsDir
    az dls fs upload --account finesand --source-path $countsTar --destination-path /community-corpus-counts --overwrite
    if [ $? -eq 0 ]
    then
        echo "Success, deleting..."
        rm -rf ${repoNames[$i]}-corpus $countsDir $countsTar
    fi
    cd -
done
