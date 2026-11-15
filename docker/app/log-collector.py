#!/usr/bin/env python3
"""Capture bounded JSONL segments and forward the original service output."""
import argparse
import datetime
import json
import os
from pathlib import Path
import shutil
import sys
import uuid


def atomic_json(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value), encoding='utf-8')
    temporary.replace(path)


def initialize(directory):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    # This directory is dedicated to capture. Do not follow links during cleanup.
    for child in directory.iterdir():
        if child.is_dir() and not child.is_symlink():
            shutil.rmtree(child)
        else:
            child.unlink()
    (directory / 'run-id').write_text(str(uuid.uuid4()), encoding='ascii')


def collect(directory, source, segment_bytes):
    target = directory / source
    target.mkdir(mode=0o700)
    status = target / 'status.json'
    sequence = 0
    segment = 0
    output = None
    size = 0
    failed = False
    console = True
    pending = bytearray()
    oversized = False

    def emit(line, truncated):
        nonlocal sequence, segment, output, size, failed
        sequence += 1
        record = json.dumps({
            'sequence': sequence,
            'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'message': line.decode('utf-8', errors='replace'),
            'truncated': truncated,
        }, ensure_ascii=False).encode('utf-8') + b'\n'
        if failed:
            return
        try:
            if output is None or size + len(record) > segment_bytes:
                if output:
                    output.close()
                segment += 1
                output = (target / f'{segment:020d}.jsonl').open('ab', buffering=0)
                size = 0
                for old in sorted(target.glob('*.jsonl'))[:-3]:
                    old.unlink()
            output.write(record)
            size += len(record)
        except OSError:
            failed = True
            try:
                atomic_json(status, {'state': 'failed'})
            except OSError:
                pass
            print(f'[logs] {source} capture failed', file=sys.stderr, flush=True)

    atomic_json(status, {'state': 'available'})
    try:
        while True:
            data = os.read(sys.stdin.fileno(), 8192)
            if not data:
                break
            if console:
                try:
                    sys.stdout.buffer.write(data)
                    sys.stdout.buffer.flush()
                except BrokenPipeError:
                    console = False
            parts = data.split(b'\n')
            for part_index, part in enumerate(parts):
                remaining = 65536 - len(pending)
                pending.extend(part[:remaining])
                oversized = oversized or len(part) > remaining
                if part_index < len(parts) - 1:
                    emit(bytes(pending).rstrip(b'\r'), oversized)
                    pending.clear()
                    oversized = False
        if pending or oversized:
            emit(bytes(pending), oversized)
    finally:
        if output:
            output.close()
        if not failed:
            atomic_json(status, {'state': 'stopped'})


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, default=Path('/run/dmdl/logs'))
    parser.add_argument('--initialize', action='store_true')
    parser.add_argument('--source', choices=['backend', 'nginx', 'aria2'])
    parser.add_argument('--segment-bytes', type=int, default=10 * 1024 * 1024)
    args = parser.parse_args()
    if args.initialize:
        initialize(args.directory)
    else:
        if not args.source or args.segment_bytes < 512 * 1024:
            parser.error('a source and segment size of at least 512 KiB are required')
        collect(args.directory, args.source, args.segment_bytes)
