#!/usr/bin/env python3
"""Validate a Pixel Data Vault v2 ZIP and report actual coverage without summing sources."""
import argparse
import collections
import datetime as dt
import json
import pathlib
import sys
import zipfile
from zoneinfo import ZoneInfo


def time_values(row, feed):
    if feed == 'health-connect':
        value = row
        if row.get('objectType') == 'PlannedExerciseSessionRecord':
            return [], None
    elif feed == 'google-health':
        value = next((v for k, v in row.items() if k not in ('name', 'dataSource') and isinstance(v, dict)), {})
    else:
        return [], None  # FHIR clinical times need resource-specific interpretation.
    timestamps = []
    nested = value.get('samples', value.get('deltas'))
    if nested is not None:
        timestamps.extend(s.get('time') for s in nested if isinstance(s, dict))
    else:
        timestamps.extend(value.get(k) for k in ('time', 'startTime', 'endTime'))
    interval = value.get('interval') or {}
    timestamps.extend(interval.get(k) for k in ('startTime', 'endTime'))
    timestamps.append((value.get('sampleTime') or {}).get('physicalTime'))
    parsed = []
    for v in timestamps:
        if not v:
            continue
        try:
            t = dt.datetime.fromisoformat(v.replace('Z', '+00:00'))
            if t.tzinfo is None:
                continue
            parsed.append(t.astimezone(dt.timezone.utc))
        except (ValueError, TypeError, AttributeError):
            pass
    date = value.get('date')
    if isinstance(date, dict):
        try:
            date = dt.date(date['year'], date['month'], date['day']).isoformat()
        except (ValueError, KeyError, TypeError):
            date = None
    elif isinstance(date, str):
        try:
            date = dt.date.fromisoformat(date).isoformat()
        except ValueError:
            date = None
    else:
        date = None
    return parsed, date


def inspect(path, timezone='Europe/London'):
    zone = ZoneInfo(timezone)
    now = dt.datetime.now(dt.timezone.utc)
    result = {'timezone': timezone, 'reviewedAt': now.astimezone(zone).isoformat(), 'types': [],
              'limitations': ['Sources and overlapping records are not added together.',
                              'An exhausted API page sequence does not prove original wearable completeness.']}
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate ZIP entries')
        manifest = json.loads(z.read('manifest.json'))
        if manifest.get('schemaVersion') != 2:
            raise ValueError('Expected Pixel Data Vault schemaVersion 2')
        result['generatedAt'] = manifest.get('generatedAt')
        result['feeds'] = manifest.get('feeds')
        result['notes'] = manifest.get('notes')
        for c in manifest['coverage']:
            feed, kind = c['feed'], c['type']
            entry = f'{feed}/{kind}.ndjson'
            status = c['status']
            total, samples = 0, 0
            origins = collections.Counter()
            first = latest = None
            latest_date = None
            per_origin_latest = {}
            if entry in names:
                with z.open(entry) as f:
                    for line in f:
                        if not line.strip():
                            continue
                        row = json.loads(line)
                        total += 1
                        if feed == 'health-connect':
                            origin = ((row.get('metadata') or {}).get('dataOrigin') or {}).get('packageName', 'source_not_reported')
                            samples += len(row.get('samples', row.get('deltas', [])))
                        elif feed == 'google-health':
                            origin = json.dumps(row.get('dataSource', 'source_not_reported'), sort_keys=True)
                        else:
                            origin = row.get('dataSourceId', row.get('id', 'source_not_reported'))
                        origins[str(origin)] += 1
                        times, date = time_values(row, feed)
                        if times:
                            lo, hi = min(times), max(times)
                            first = lo if first is None else min(first, lo)
                            latest = hi if latest is None else max(latest, hi)
                            prior = per_origin_latest.get(str(origin))
                            per_origin_latest[str(origin)] = hi if prior is None else max(prior, hi)
                        if date:
                            latest_date = date if latest_date is None else max(latest_date, date)
            elif status in ('read_complete', 'incomplete'):
                raise ValueError(f'{feed}/{kind}: expected data entry missing')
            if total != c['records']:
                raise ValueError(f'{feed}/{kind}: {total} actual records differ from manifest {c["records"]}')
            row = {'feed': feed, 'type': kind, 'status': status, 'records': total,
                   'nestedSamples': samples, 'origins': dict(origins),
                   'firstMeasurement': first.astimezone(zone).isoformat() if first else None,
                   'latestMeasurement': latest.astimezone(zone).isoformat() if latest else None,
                   'latestMeasurementDate': latest_date,
                   'latestByOrigin': {k: v.astimezone(zone).isoformat() for k, v in per_origin_latest.items()},
                   'ageHours': round((now - latest).total_seconds() / 3600, 1) if latest else None,
                   'error': c.get('error')}
            result['types'].append(row)
        bad = z.testzip()
        if bad:
            raise ValueError(f'ZIP integrity failure: {bad}')
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('zip', type=pathlib.Path)
    p.add_argument('--timezone', default='Europe/London')
    p.add_argument('--out', type=pathlib.Path)
    args = p.parse_args()
    report = inspect(args.zip, args.timezone)
    text = json.dumps(report, indent=2, ensure_ascii=False)
    if args.out:
        args.out.write_text(text + '\n')
    else:
        print(text)


if __name__ == '__main__':
    main()
