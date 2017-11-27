#!/usr/bin/env bash

git clone https://github.com/apache/log4j.git
git clone https://github.com/spring-projects/spring-framework.git
git clone https://github.com/antlr/antlr4.git
git clone https://github.com/eclipse/jgit.git
git clone https://github.com/itext/itextpdf.git

echo "Checking out master branch.."
for d in */ ; do
    echo "Processing $d"
    cd "$d"
    git fetch
    if [[ "$d" == *"log4j"* ]] ; then
        args="trunk"
    else
        args="master"
    fi
    git checkout "$args"
    cd ..
done
