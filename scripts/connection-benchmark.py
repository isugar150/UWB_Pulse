#!/usr/bin/env python3
"""Opt-in real-radio trials using the debug APK; stop monitoring first, restore it afterwards.

No automation is dispatched. Only controlled BENCH journal lines are saved.
The app's own repository supplies the selected tag credentials in memory; vaults are never exported.
Example: python3 scripts/connection-benchmark.py delayed warm_scan --output /tmp/radio-trials.txt
"""
import argparse
from pathlib import Path
import subprocess
import time


MODES = ("baseline", "keep_scan", "auto", "auto_scan", "settle", "native", "native_scan",
         "delayed", "warm_scan", "warm_cancel", "high", "advertising")


def journal():
    return subprocess.check_output([
        "adb", "shell", "run-as", "dev.uwbpulse.monitor", "cat", "files/probe-journal.txt"
    ], text=True).splitlines()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("modes", nargs="+", choices=MODES)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    for index, mode in enumerate(args.modes):
        if index:
            time.sleep(8)
        old = journal()
        marker = old[-1] if old else ""
        subprocess.run(["adb", "shell", "am", "start-foreground-service", "-n",
                        "dev.uwbpulse.monitor/.debug.ConnectionBenchmarkService", "--es", "mode", mode], check=True)
        for _ in range(47):
            time.sleep(1)
            new = journal()
            tail = new[new.index(marker)+1:] if marker in new else new
            if any(" BENCH_END " in line for line in tail):
                evidence = "\n".join(line for line in tail if " BENCH_" in line) + "\n"
                if args.output:
                    with args.output.open("a") as output:
                        output.write(evidence)
                print(evidence, end="", flush=True)
                break
        else:
            raise SystemExit(f"Benchmark did not finish: {mode}; check the device before starting another trial")


if __name__ == "__main__":
    main()
