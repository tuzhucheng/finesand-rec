import requests
import re

from bs4 import BeautifulSoup

URL_BASE = "https://docs.oracle.com/javase/8/docs/api"


def mine():
    r = requests.get('{}/allclasses-noframe.html'.format(URL_BASE))
    soup = BeautifulSoup(r.text, 'html.parser')
    list_items = soup.find_all('li')
    methods = set()
    for i, java_class in enumerate(list_items):
        class_name, link = java_class.a.text, '{}/{}'.format(URL_BASE, java_class.a['href'])
        r = requests.get(link)
        soup = BeautifulSoup(r.text, 'html.parser')
        member_links = soup.find_all('span', class_='memberNameLink')
        methods |= set(map(lambda m: m.a.text, member_links))
        if i % 10 == 0:
            print('{}/{}'.format(i+1, len(list_items)))

    with open('jdk8_methods.txt', 'w') as f:
        methods_sorted = sorted(methods)
        for m in methods_sorted:
            f.write(m + '\n')

if __name__ == '__main__':
    mine()
