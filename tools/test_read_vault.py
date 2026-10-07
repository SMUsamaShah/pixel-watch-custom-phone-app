import importlib.util
import json
import pathlib
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('reader', pathlib.Path(__file__).with_name('read-vault-export.py'))
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)


class ReaderTest(unittest.TestCase):
    def fixture(self, wrong_count=False):
        tmp = tempfile.NamedTemporaryFile(suffix='.zip', delete=False)
        tmp.close()
        self.addCleanup(pathlib.Path(tmp.name).unlink)
        rows = [{'startTime': '2026-09-22T09:00:00Z', 'endTime': '2026-10-06T00:00:00Z',
                 'metadata': {'dataOrigin': {'packageName': source}, 'lastModifiedTime': '2026-10-06T08:00:00Z'},
                 'samples': [{'time': '2026-09-22T09:26:59Z', 'beatsPerMinute': 72}]} for source in ('phone', 'fitbit')]
        with zipfile.ZipFile(tmp.name, 'w') as z:
            z.writestr('health-connect/HeartRateRecord.ndjson', '\n'.join(json.dumps(r) for r in rows))
            z.writestr('manifest.json', json.dumps({'schemaVersion': 2, 'coverage': [
                {'feed': 'health-connect', 'type': 'HeartRateRecord', 'status': 'read_complete', 'records': 3 if wrong_count else 2},
                {'feed': 'google-health', 'type': 'sleep', 'status': 'not_connected', 'records': 0}]}))
        return pathlib.Path(tmp.name)

    def test_actual_samples_ignore_export_and_parent_end_times(self):
        report = reader.inspect(self.fixture())
        row = report['types'][0]
        self.assertEqual('2026-09-22T10:26:59+01:00', row['latestMeasurement'])
        self.assertEqual({'phone': 1, 'fitbit': 1}, row['origins'])
        self.assertEqual('not_connected', report['types'][1]['status'])

    def test_count_mismatch_is_rejected(self):
        with self.assertRaises(ValueError):
            reader.inspect(self.fixture(True))


if __name__ == '__main__':
    unittest.main()
