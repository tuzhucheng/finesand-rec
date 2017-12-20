# finesand-rec

Implementation and Experiments with API Code Recommendation using Statistical Learning from Fine-Grained Changes

See technical notes on performance tuning to make BuildModel run faster [here](https://github.com/tuzhucheng/finesand-rec/wiki/BuildModel-Performance-Tuning).
In progress: Reproduction of Large Corpus results..

## Downloading Data

To see popular repositories on GitHub, you can run a query like the following.
```
curl -G https://api.github.com/search/repositories       \
    --data-urlencode "q=created:<2014-09-01" \
    --data-urlencode "sort=stars"                          \
    --data-urlencode "order=desc"                          \
    -H "Accept: application/vnd.github.preview"            \
    | jq '.items[] | select(.language == "JavaScript") | {name, description, language, stargazers_count, watchers_count, forks_count, html_url}'
```

### Community Edition

Creating testing set (run inside finesand directory):

```
./run-group-parallel.py 3 antlr4 itextpdf jgit log4j spring-framework --train-ratio 0.0 --dir ../data/community-corpus --cloud-dest /community-corpus-all-test-counts
```

## Building Corpus

```
sbt "runMain finesand.PrepareData --repo <path to repo> --branch <branch of repo> --split 0.9 <ratio of data to keep for training>"
sbt "runMain finesand.BuildCounts --repo <path to repo> --group 1000"
```

You may need to increase Java heap size for some large repos. To do this, add `'-J-Xms256m' ::: '-J-Xmx8G'` after `sbt` and before `"runMain..."`.

## Building Model

```
/usr/bin/time spark-submit --driver-memory 8G --executor-memory 4G --total-executor-cores 8 --class finesand.BuildModel target/scala-2.11/finesand-assembly-0.1.0-SNAPSHOT.jar --repo ../data/community-corpus/log4j
```
