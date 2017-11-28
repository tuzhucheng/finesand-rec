#!/usr/bin/env python

import json
import os
from pprint import pprint
import subprocess

with open('repositories.json') as f:
    data = json.load(f)
    repositories = data['repositories']
    for repo in repositories:
        pprint(repo)
        name = repo['url'].split('/')[-1][:-4]
        if os.path.exists(name):
            print('Skipping..')
            continue
        subprocess.call(['git', 'clone', repo['url']])

    print('Total repositories:', len(repositories))
