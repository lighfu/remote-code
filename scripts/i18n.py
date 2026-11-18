import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / 'android/app/src/main/res'
LOCALES = {'en': 'values-en', 'zh-CN': 'values-zh-rCN', 'zh-Hant': 'values-b+zh+Hant', 'ko': 'values-ko'}
FORMAT = re.compile(r'%(?:[1-9][0-9]*\$)?[-#+ 0,(<]*[0-9]*(?:\.[0-9]+)?[a-zA-Z%]')


def decode_android(value):
    if value.startswith('"') and value.endswith('"'):
        value = value[1:-1]
    return re.sub(r'\\(u[0-9a-fA-F]{4}|[ntr\\\'"@?])', lambda match: {
        'n': '\n', 't': '\t', 'r': '\r', '\\': '\\', "'": "'", '"': '"', '@': '@', '?': '?',
    }.get(match[1], chr(int(match[1][1:], 16)) if match[1].startswith('u') else match[1]), value)


def encode_android(value):
    value = value.replace('\\', '\\\\').replace('\n', '\\n').replace('\t', '\\t').replace('\r', '\\r')
    value = value.replace("'", "\\'").replace('"', '\\"')
    if value.startswith(('@', '?')):
        value = '\\' + value
    return value


def source_catalog():
    catalog = {}
    for path in sorted((RES / 'values').glob('strings*.xml')):
        for item in ET.parse(path).getroot():
            if item.tag != 'string' or item.get('translatable') == 'false':
                continue
            key = item.attrib['name']
            if key in catalog:
                raise ValueError('Duplicate resource: ' + key)
            catalog[key] = {'text': decode_android(''.join(item.itertext())), 'file': path.name,
                            'formatted': item.get('formatted', 'true') != 'false'}
    return catalog


def read_translations(directory, catalog):
    result = {}
    for locale in LOCALES:
        values = json.loads((directory / f'translations_{locale}.json').read_text(encoding='utf-8'))
        if set(values) != set(catalog):
            raise ValueError(f'{locale}: missing {sorted(set(catalog) - set(values))}, extra {sorted(set(values) - set(catalog))}')
        for key, item in catalog.items():
            value = values[key]
            if not isinstance(value, str) or not value.strip():
                raise ValueError(f'{locale}: empty translation {key}')
            if item['formatted'] and Counter(FORMAT.findall(item['text'])) != Counter(FORMAT.findall(value)):
                raise ValueError(f'{locale}: format mismatch {key}: {FORMAT.findall(item["text"])} != {FORMAT.findall(value)}')
        result[locale] = values
    return result


def check_resources(catalog):
    for locale, folder in LOCALES.items():
        translated = {}
        for path in sorted((RES / folder).glob('*.xml')):
            for item in ET.parse(path).getroot():
                if item.tag != 'string':
                    continue
                key = item.attrib['name']
                if key in translated:
                    raise ValueError(f'{locale}: duplicate {key}')
                translated[key] = decode_android(''.join(item.itertext()))
        if set(translated) != set(catalog):
            raise ValueError(f'{locale}: missing {sorted(set(catalog) - set(translated))}, extra {sorted(set(translated) - set(catalog))}')
        for key, item in catalog.items():
            if item['formatted'] and Counter(FORMAT.findall(item['text'])) != Counter(FORMAT.findall(translated[key])):
                raise ValueError(f'{locale}: format mismatch {key}')
    print(json.dumps({'languages': ['ja', *LOCALES], 'stringsPerLanguage': len(catalog), 'formats': 'passed'}))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['catalog', 'apply', 'check'])
    parser.add_argument('--directory', type=Path, default=ROOT / 'data/i18n-batch')
    args = parser.parse_args()
    catalog = source_catalog()
    if args.command == 'catalog':
        args.directory.mkdir(parents=True, exist_ok=True)
        body = json.dumps(catalog, ensure_ascii=False, separators=(',', ':')) + '\n'
        (args.directory / 'catalog.json').write_text(body, encoding='utf-8')
        print(json.dumps({'strings': len(catalog), 'sha256': hashlib.sha256(body.encode()).hexdigest()}))
    elif args.command == 'apply':
        translations = read_translations(args.directory, catalog)
        for locale, folder in LOCALES.items():
            target = ET.Element('resources')
            for key, item in catalog.items():
                attrs = {'name': key}
                if not item['formatted']:
                    attrs['formatted'] = 'false'
                ET.SubElement(target, 'string', attrs).text = '"' + encode_android(translations[locale][key]) + '"'
            ET.indent(target, space='    ')
            (RES / folder).mkdir(parents=True, exist_ok=True)
            ET.ElementTree(target).write(RES / folder / 'strings.xml', encoding='utf-8', xml_declaration=True)
        check_resources(catalog)
    else:
        check_resources(catalog)


if __name__ == '__main__':
    main()
