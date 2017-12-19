# finesand-rec

Implementation and Experiments with API Code Recommendation using Statistical Learning from Fine-Grained Changes

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

## Git Notes

To view all commits in a repo, we use:

```
git rev-list --date-order master --reverse
```

where `master` can be replaced by the name of any branch. When `reverse` is set, the most recent commit is at the bottom of the list. Add `--max-count 5` or `-n 5` to limit the number of commits to 5 for example.

To see all files that changed in a commit, we can do:

```
git diff-tree --no-commit-id -r 18f07f090e0d864d3c2c80efbb5ec6ee4019d5dd
```

To see a particular version of a file, we can use the blob id returned from `git diff-tree`. For example:

```
git show d374225473f68ac3f30c8f44f8a6b1d8869ff071
```
