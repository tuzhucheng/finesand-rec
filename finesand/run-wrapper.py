#!/usr/bin/env python

import argparse
import json
import os
from pprint import pprint
import subprocess

with open('../data/large-corpus/repositories.json') as f:
    data = json.load(f)
    repositories = data['repositories']
    repo_map = {}
    for repo in repositories:
        name = repo['url'].split('/')[-1][:-4]
        repo_map[name] = repo['branch']

    parser = argparse.ArgumentParser()
    parser.add_argument('repos', nargs='+', type=str)
    args = parser.parse_args()

    pprint(repo_map)
    for repo in args.repos:
        print(repo)
        subprocess.call(['./run-wrapper.sh', 'PrepareData', '../data/large-corpus/{}'.format(repo), repo_map[repo]])
        subprocess.call(['./run-wrapper.sh', 'BuildCounts', '../data/large-corpus/{}'.format(repo), repo_map[repo]])
