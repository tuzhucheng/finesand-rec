#!/usr/bin/env bash

# Args: [n_jobs] [train_ratio] [repo_names] [repo_paths] [repo_branches] [cloud_path]

nJobs=$1
trainRatio=$2
repoNames=( "${@:3:$nJobs}" )
repos=( "${@:3+${nJobs}:${nJobs}}" )
repoBranches=( "${@:3+2*${nJobs}:${nJobs}}" )
cloudDestIdx=$((3+3*${nJobs}))
cloudDest=${!cloudDestIdx}
echo ${repoNames[@]}
echo ${repos[@]}
echo ${repoBranches[@]}
echo ${cloudDest}

commands=()
for i in "${!repos[@]}" ; do
    repo="${repos[$i]}"
    sbtargs1="runMain finesand.PrepareData --repo $repo --branch ${repoBranches[$i]} --split ${trainRatio}"
    command="$sbtargs1"
    commands+=("$command")
done

parallel --eta -j $nJobs sbt ::: "${commands[@]}"

commands2=()
for i in "${!repos[@]}" ; do
    repo="${repos[$i]}"
    sbtargs2="runMain finesand.BuildCounts --repo $repo --group 1000"
    command="$sbtargs2"
    commands2+=("$command")
done

parallel --eta -j $nJobs sbt ::: '-J-Xms256m' ::: '-J-Xmx8G' ::: "${commands2[@]}"

for i in "${!repos[@]}" ; do
    repo="${repos[$i]}"
    countsDir="${repoNames[$i]}-counts"
    countsTar="${repoNames[$i]}-counts.tar.gz"
    cd "$repo/.."
    mkdir -p $countsDir
    mv ${repoNames[$i]}-corpus/*{p,P}art* $countsDir
    tar -cvzf $countsTar $countsDir
    az dls fs upload --account finesand --source-path $countsTar --destination-path $cloudDest --overwrite
    if [ $? -eq 0 ]
    then
        echo "Success, deleting..."
        rm -rf ${repoNames[$i]}-corpus $countsDir $countsTar
    fi
    cd -
done
