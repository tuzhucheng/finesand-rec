#!/usr/bin/env bash

az dls fs list --account finesand --path /community-corpus-all-test-counts | jq '.[] | .name' > community-corpus-tars.list
sed -i "s/^.//g" community-corpus-tars.list
sed -i "s/.$//g" community-corpus-tars.list

while read p; do
  az dls fs download --account finesand --source-path /$p --destination-path .
done <community-corpus-tars.list

for i in *.tar.gz; do
    [ -f "$i" ] || break
    tar -xvzf $i
done
