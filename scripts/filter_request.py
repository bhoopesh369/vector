#!/usr/bin/env python3
"""JSON stdin/stdout bridge for the local-only visualizer filter endpoint."""
import json
import sys
import numpy as np
from filter_imu import process


def main():
    try:
        request = json.load(sys.stdin)
        times = np.asarray(request['times'], dtype=float)
        values = np.asarray(request['values'], dtype=float)
        if len(times) > 500000:
            raise ValueError('At most 500,000 readings can be filtered per request.')
        stationary = request.get('stationary')
        if stationary is not None and (not isinstance(stationary, list) or len(stationary) != 2):
            raise ValueError('Stationary interval must have start and end seconds.')
        filtered, report = process(times, values, float(request.get('cutoff', 40)), stationary)
        json.dump({'values': filtered.tolist(), 'report': report}, sys.stdout, allow_nan=False)
    except (ValueError, KeyError, TypeError) as error:
        json.dump({'error': str(error)}, sys.stdout)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
