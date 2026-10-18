#!/usr/bin/env python3
"""Convert MM-Fit left-wrist smartwatch data into 워치짐 probe CSVs.

Input:  <mm-fit>/wNN/wNN_sw_l_{acc,gyr}.npy and wNN_labels.csv
Output: <out>/wNN.csv         sensor,t_ns,x,y,z (0 = accel, 1 = gyro)
        <out>/wNN_labels.csv  start_ns,end_ns,reps,exercise

npy rows are (video_frame, timestamp_ms, x, y, z); labels use video frames.
Run with `python3 -I`; no numpy needed.
"""
import ast
import os
import struct
import sys


def load_npy(path):
    with open(path, "rb") as f:
        assert f.read(6) == b"\x93NUMPY"
        major = f.read(2)[0]
        header_len = struct.unpack("<H" if major == 1 else "<I", f.read(2 if major == 1 else 4))[0]
        header = ast.literal_eval(f.read(header_len).decode())
        assert header["descr"] == "<f8" and not header["fortran_order"]
        rows, cols = header["shape"]
        data = f.read()
    row_fmt = "<" + "d" * cols
    size = 8 * cols
    return [struct.unpack_from(row_fmt, data, i * size) for i in range(rows)]


def convert(session_dir, out_dir):
    name = os.path.basename(session_dir.rstrip("/"))
    acc = load_npy(os.path.join(session_dir, f"{name}_sw_l_acc.npy"))
    gyr = load_npy(os.path.join(session_dir, f"{name}_sw_l_gyr.npy"))

    frame_time = {}
    for frame, t_ms, *_ in acc:
        frame_time.setdefault(int(frame), t_ms)
    frames = sorted(frame_time)

    def time_of(frame):
        # Nearest recorded frame at or after the label frame.
        for f in frames:
            if f >= frame:
                return frame_time[f]
        return frame_time[frames[-1]]

    samples = [(0, r[1], r[2], r[3], r[4]) for r in acc] + [(1, r[1], r[2], r[3], r[4]) for r in gyr]
    samples.sort(key=lambda s: (s[1], s[0]))
    with open(os.path.join(out_dir, f"{name}.csv"), "w") as out:
        out.write("sensor,t_ns,x,y,z\n")
        for sensor, t_ms, x, y, z in samples:
            out.write(f"{sensor},{int(round(t_ms * 1_000_000))},{x:.6f},{y:.6f},{z:.6f}\n")

    with open(os.path.join(session_dir, f"{name}_labels.csv")) as src, \
            open(os.path.join(out_dir, f"{name}_labels.csv"), "w") as out:
        out.write("start_ns,end_ns,reps,exercise\n")
        for line in src:
            start, end, reps, exercise = line.strip().split(",")
            out.write(f"{int(time_of(int(start)) * 1_000_000)},{int(time_of(int(end)) * 1_000_000)},{reps},{exercise}\n")
    return name, len(samples)


def main():
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for entry in sorted(os.listdir(src)):
        session = os.path.join(src, entry)
        if os.path.isfile(os.path.join(session, f"{entry}_sw_l_acc.npy")):
            print(*convert(session, out))


if __name__ == "__main__":
    main()
