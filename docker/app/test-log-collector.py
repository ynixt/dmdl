"""Focused capture fixtures. Run with python3 docker/app/test-log-collector.py."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name('log-collector.py')
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('collector', SCRIPT)
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class CaptureTest(unittest.TestCase):
    def test_startup_rotation_forwarding_and_restart(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            collector.initialize(directory)
            run = (directory / 'run-id').read_text()
            payload = b'startup output\n' + (b'x' * 1000 + b'\n') * 2300
            result = subprocess.run([
                sys.executable, str(SCRIPT), '--directory', temporary,
                '--source', 'aria2', '--segment-bytes', str(512 * 1024),
            ], input=payload, capture_output=True, check=True)
            self.assertEqual(payload, result.stdout)
            segments = sorted((directory / 'aria2').glob('*.jsonl'))
            self.assertEqual(3, len(segments))
            self.assertTrue(all(segment.stat().st_size <= 512 * 1024 for segment in segments))
            records = [json.loads(line) for segment in segments for line in segment.read_text().splitlines()]
            self.assertGreater(records[0]['sequence'], 1)
            self.assertEqual(2301, records[-1]['sequence'])
            self.assertEqual('stopped', json.loads((directory / 'aria2/status.json').read_text())['state'])
            collector.initialize(directory)
            self.assertNotEqual(run, (directory / 'run-id').read_text())
            self.assertFalse((directory / 'aria2').exists())

    def test_early_output_oversized_lines_and_final_line(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            collector.initialize(directory)
            payload = b'early startup\n' + b'x' * 100000 + b'\nfinal quiet line'
            result = subprocess.run([
                sys.executable, str(SCRIPT), '--directory', temporary, '--source', 'backend',
            ], input=payload, capture_output=True, check=True)
            self.assertEqual(payload, result.stdout)
            records = [json.loads(line) for line in next((directory / 'backend').glob('*.jsonl')).read_text().splitlines()]
            self.assertEqual('early startup', records[0]['message'])
            self.assertEqual(65536, len(records[1]['message']))
            self.assertTrue(records[1]['truncated'])
            self.assertEqual('final quiet line', records[-1]['message'])


if __name__ == '__main__':
    unittest.main()
