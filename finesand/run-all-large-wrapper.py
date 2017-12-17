#!/usr/bin/env python

import argparse
import json
import os
from pprint import pprint
import subprocess

def chunks(l, n):
    """Yield successive n-sized chunks from l."""
    for i in range(0, len(l), n):
        yield l[i:i + n]


parser = argparse.ArgumentParser()
parser.add_argument('group', type=int)
parser.add_argument('repos', nargs='+', type=str)
parser.add_argument('--dir', type=str, default='../data/large-corpus')
parser.add_argument('--cloud-dest', type=str, default='/large-corpus-counts')
args = parser.parse_args()

with open('{}/repositories.json'.format(args.dir)) as f:
    data = json.load(f)
    repositories = data['repositories']
    repo_map = {}
    for repo in repositories:
        name = repo['url'].split('/')[-1][:-4]
        repo_map[name] = repo['branch']

    repo_groups = chunks(args.repos, args.group)
    for group in repo_groups:
        shell_args = ['./run-group-parallel.sh', str(args.group)]
        shell_args.extend(group)
        shell_args.extend(list(map(lambda r: '{}/{}'.format(args.dir, r), group)))
        shell_args.extend(list(map(lambda r: repo_map[r], group)))
        shell_args.append(args.cloud_dest)
        subprocess.call(shell_args)
