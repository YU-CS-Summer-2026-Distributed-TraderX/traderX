#!/usr/bin/env python3
"""Dated observation custody and explicit as-of selection; no curve or pricing model."""
import argparse
from datetime import date, datetime
from pathlib import Path
import re
import bundle
from instrument_terms import decode

SCHEMA = 'traderx.market-input-package.v1'
OBSERVATIONS = 'traderx.market-observations.v1'
IDENTITY = {'source', 'dataset', 'series', 'instrument', 'observationDate'}
PROVENANCE = {'synthetic', 'assumed', 'observed'}
QUOTE_UNITS = {'par-yield': {'percent', 'decimal-fraction'},
               'overnight-fixing': {'percent', 'decimal-fraction'}}


def fields(value, expected):
    bundle.require(isinstance(value, dict) and set(value) == expected, 'unexpected fields')


def day(value):
    bundle.require(isinstance(value, str) and re.fullmatch(r'\d{4}-\d{2}-\d{2}', value), 'invalid date')
    return date.fromisoformat(value)


def timestamp(value):
    bundle.require(isinstance(value, str) and 'T' in value, 'timestamp required')
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    bundle.require(result.tzinfo is not None and result.utcoffset() is not None, 'timestamp requires offset')
    return result


def identity(value):
    fields(value, IDENTITY)
    for key in IDENTITY - {'observationDate'}:
        # Identifiers, not URLs, credentials, arbitrary metadata or source payloads.
        bundle.require(isinstance(value[key], str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}', value[key]), 'invalid identifier')
    day(value['observationDate'])
    return tuple(value[k] for k in sorted(IDENTITY))


def observations(data):
    doc = decode(data)
    fields(doc, {'schema', 'observations'})
    bundle.require(doc['schema'] == OBSERVATIONS and isinstance(doc['observations'], list), 'invalid observation schema')
    seen = {}
    for item in doc['observations']:
        fields(item, {'identity', 'observationTime', 'publicationTime', 'retrievalTime', 'value', 'units', 'quoteType', 'provenance'})
        key = identity(item['identity'])
        bundle.require(key not in seen, 'duplicate observation identity')
        bundle.require(isinstance(item['quoteType'], str) and item['quoteType'] in QUOTE_UNITS, 'invalid quote type')
        bundle.require(isinstance(item['units'], str) and item['units'] in QUOTE_UNITS[item['quoteType']], 'invalid quote units')
        bundle.require(isinstance(item['provenance'], str) and item['provenance'] in PROVENANCE, 'invalid provenance')
        bundle.require(isinstance(item['value'], str) and re.fullmatch(r'-?\d+(\.\d+)?', item['value']), 'raw decimal string required')
        bundle.decimal(item['value'], 'observation value')
        retrieved = timestamp(item['retrievalTime'])
        if item['observationTime'] is not None:
            observed = timestamp(item['observationTime'])
            bundle.require(observed.date() == day(item['identity']['observationDate']), 'observation date/time disagree')
            bundle.require(observed <= retrieved, 'observation after retrieval')
        if item['publicationTime'] is not None:
            published = timestamp(item['publicationTime'])
            bundle.require(published <= retrieved, 'publication after retrieval')
            if item['observationTime'] is not None:
                bundle.require(timestamp(item['observationTime']) <= published, 'publication before observation')
        seen[key] = item
    return seen


def manifest_for(metadata, data):
    fields(metadata, {'businessDate', 'valuationTime', 'availabilityCutoff', 'selection'})
    business = day(metadata['businessDate'])
    valuation = timestamp(metadata['valuationTime'])
    cutoff = timestamp(metadata['availabilityCutoff'])
    bundle.require(valuation.date() == business, 'valuation business date mismatch')
    bundle.require(cutoff <= valuation, 'availability cutoff after valuation')
    policy = metadata['selection']
    fields(policy, {'selected', 'required', 'maxAgeCalendarDays', 'allowedProvenance'})
    bundle.require(type(policy['maxAgeCalendarDays']) is int and policy['maxAgeCalendarDays'] >= 0, 'invalid maximum age')
    allowed = policy['allowedProvenance']
    bundle.require(isinstance(allowed, list) and bool(allowed) and all(isinstance(v,str) and v in PROVENANCE for v in allowed)
                   and len(set(allowed)) == len(allowed), 'invalid allowed provenance')
    keys = {}
    for name in ('selected', 'required'):
        bundle.require(isinstance(policy[name], list), 'selection must be an array')
        values = [identity(v) for v in policy[name]]
        bundle.require(len(values) == len(set(values)), 'duplicate selection identity')
        keys[name] = set(values)
    bundle.require(bool(keys['selected']) and bool(keys['required']), 'explicit nonempty selected and required inputs needed')
    bundle.require(keys['required'] <= keys['selected'], 'required inputs must be selected')
    rows = observations(data)
    doc = {'schema': SCHEMA, **metadata, 'artifact': {'path': 'observations.json', 'schema': OBSERVATIONS,
            'sha256': bundle.digest(data), 'observations': len(rows)}}
    doc['packageId'] = bundle.digest(bundle.encoded(doc))
    return doc


def suitability(manifest, data):
    rows = observations(data)
    policy = manifest['selection']
    errors = []
    required = {identity(v) for v in policy['required']}
    cutoff = timestamp(manifest['availabilityCutoff'])
    business = day(manifest['businessDate'])
    for ref in policy['selected']:
        key = identity(ref)
        item = rows.get(key)
        reasons = []
        if item is None:
            reasons.append('MISSING_REQUIRED' if key in required else 'MISSING_SELECTED')
        else:
            age = (business - day(ref['observationDate'])).days
            if age < 0 or day(ref['observationDate']) > cutoff.date():
                reasons.append('FUTURE_OBSERVATION')
            elif age > policy['maxAgeCalendarDays']:
                reasons.append('STALE_OBSERVATION')
            if item['provenance'] not in policy['allowedProvenance']:
                reasons.append('PROVENANCE_NOT_ALLOWED')
            if item['publicationTime'] is None:
                reasons.append('PUBLICATION_UNKNOWN')
            for field in ('observationTime', 'publicationTime', 'retrievalTime'):
                if item[field] is not None and timestamp(item[field]) > cutoff:
                    reasons.append(field.upper() + '_AFTER_CUTOFF')
        if reasons:
            errors.append({'identity': ref, 'reasons': reasons})
    return {'structurallyValid': True, 'suitableForSelection': not errors,
            'pricingReadiness': 'NOT_ASSESSED', 'usableForRisk': False, 'issues': errors}


def build(metadata_path, observations_path, output):
    metadata = decode(Path(metadata_path).read_bytes())
    data = Path(observations_path).read_bytes()
    manifest = manifest_for(metadata, data)
    bundle.publish_directory(output, {'manifest.json': bundle.encoded(manifest), 'observations.json': data})
    return manifest, suitability(manifest, data)


def validate(directory):
    root = Path(directory)
    bundle.require(root.is_dir() and not root.is_symlink(), 'invalid package directory')
    bundle.require({p.name for p in root.iterdir()} == {'manifest.json', 'observations.json'}, 'unexpected package files')
    for name in ('manifest.json', 'observations.json'):
        bundle.require((root/name).is_file() and not (root/name).is_symlink(), 'invalid package file')
    manifest = decode((root/'manifest.json').read_bytes())
    fields(manifest, {'schema', 'packageId', 'artifact', 'businessDate', 'valuationTime', 'availabilityCutoff', 'selection'})
    data = (root/'observations.json').read_bytes()
    metadata = {k:manifest[k] for k in ('businessDate', 'valuationTime', 'availabilityCutoff', 'selection')}
    bundle.require(manifest == manifest_for(metadata, data), 'package hash, identity or schema mismatch')
    return manifest, suitability(manifest, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    build_parser = commands.add_parser('build')
    build_parser.add_argument('--metadata', required=True)
    build_parser.add_argument('--observations', required=True)
    build_parser.add_argument('--output', required=True)
    commands.add_parser('validate').add_argument('directory')
    args = parser.parse_args()
    try:
        manifest, report = (build(args.metadata, args.observations, args.output) if args.command == 'build' else validate(args.directory))
        print(bundle.encoded({'packageId': manifest['packageId'], **report}).decode(), end='')
        return 0 if report['suitableForSelection'] else 2
    except (ValueError, OSError, TypeError, KeyError) as exc:
        parser.exit(1, f'error: {exc}\n')


if __name__ == '__main__':
    raise SystemExit(main())
