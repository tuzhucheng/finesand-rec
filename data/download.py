#!/usr/bin/env python
import argparse
import json
import os
from pprint import pprint
import subprocess


def download(repos_dir):
    with open('{}/repositories.json'.format(repos_dir), 'r') as f:
        data = json.load(f)
        repositories = data['repositories']
        for repo in repositories:
            pprint(repo)
            name = repo['url'].split('/')[-1][:-4]
            if os.path.exists(name):
                print('Skipping..')
                continue
            subprocess.call(['git', 'clone', repo['url'], '{}/{}'.format(repos_dir, name)])

        print('Total repositories:', len(repositories))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('repos_dir', type=str, choices=['large-corpus', 'community-corpus', 'large-corpus-extra', 'community-corpus-extra'])
    args = parser.parse_args()
    download(args.repos_dir)
