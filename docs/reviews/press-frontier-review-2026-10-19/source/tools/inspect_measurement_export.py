#!/usr/bin/env python3
"""Read one app-exported capture without extracting files or approving an engine."""
import argparse
import base64
import hashlib
import json
import math
from pathlib import Path
import struct
import zipfile


def _json(data):
    def invalid(value):
        raise ValueError(f"Non-finite JSON value: {value}")
    return json.loads(data, parse_constant=invalid)


def _text(encoded):
    return base64.b64decode(encoded + "=" * (-len(encoded) % 4), altchars=b"-_", validate=True).decode("utf-8")


def _manifest(data):
    fields = data.decode("utf-8").split(".")
    if len(fields) != 12 or fields[:3] != ["KCP3", "3", "CAPTURE_MANIFEST"] or fields[8] != "WATCH":
        raise ValueError("Expected a WATCH KCP3 capture manifest")
    payload = {}
    if fields[11]:
        for item in fields[11].split(","):
            key, value = item.split("=", 1)
            if key in payload:
                raise ValueError("Duplicate wire payload key")
            payload[key] = _text(value)
    return _text(fields[4]), _text(fields[6]), payload


def _sensor(data):
    if len(data) < 20:
        raise ValueError("Missing KCS header")
    magic, version, count, requested = struct.unpack_from(">4siid", data)
    if magic != b"KCS1" or version not in (1, 2) or not 1 <= count <= 60_000 or not math.isfinite(requested) or requested <= 0:
        raise ValueError("Invalid KCS header")
    size = 40 if version == 1 else 76
    if len(data) != 20 + size * count:
        raise ValueError("KCS byte length does not match its declared sample count")
    times = []
    unreliable = missing_clocks = callback_before_sample = 0
    join_ages = []
    missing_accel = missing_gyro = missing_callback = future_accel = stale_accel = 0
    gyro_accuracy = {-1: 0, 0: 0, 1: 0, 2: 0, 3: 0}
    accel_accuracy = gyro_accuracy.copy()
    invalid_gyro_accuracy = invalid_accel_accuracy = 0
    # Same explicit freshness boundary as the app's SensorRecordQuality. No inferred clocks.
    stale_threshold_ms = 40
    for index in range(count):
        offset = 20 + size * index
        timestamp, *rest = struct.unpack_from(">q6fii", data, offset)
        if timestamp < 0 or (times and timestamp <= times[-1]) or not all(math.isfinite(x) for x in rest[:6]):
            raise ValueError("Invalid sensor values or non-increasing sensor time")
        times.append(timestamp)
        unreliable += rest[6] == 0
        if rest[6] in gyro_accuracy:
            gyro_accuracy[rest[6]] += 1
        else:
            invalid_gyro_accuracy += 1
        if version == 2:
            accel, gyro, received, accuracy, heart_time = struct.unpack_from(">qqqiq", data, offset + 40)
            if any(t < -1 for t in [accel, gyro, received, heart_time]) or gyro not in (-1, timestamp):
                raise ValueError("Invalid original sensor clock")
            missing_clocks += any(t == -1 for t in [accel, gyro, received])
            callback_before_sample += received != -1 and received < timestamp
            missing_accel += accel == -1
            missing_gyro += gyro == -1
            missing_callback += received == -1
            future_accel += accel != -1 and accel > timestamp
            if accel != -1 and gyro != -1:
                age_ms = (gyro - accel) / 1e6
                join_ages.append(age_ms)
                stale_accel += age_ms > stale_threshold_ms
            if accuracy in accel_accuracy:
                accel_accuracy[accuracy] += 1
            else:
                invalid_accel_accuracy += 1
        else:
            missing_clocks += 1
            missing_accel += 1
            missing_gyro += 1
            missing_callback += 1
            accel_accuracy[-1] += 1
    duration = (times[-1] - times[0]) / 1e9
    def accuracy_report(values, invalid):
        return {"unknownSamples": values[-1], "unreliableSamples": values[0],
                "lowAccuracySamples": values[1], "mediumAccuracySamples": values[2],
                "highAccuracySamples": values[3], "unrecognizedAccuracySamples": invalid}
    issues = []
    for number, reason in [(version == 1, "legacy_original_metadata_unavailable"),
                           (missing_accel, "acceleration_clock_missing"), (missing_gyro, "gyroscope_clock_missing"),
                           (missing_callback, "callback_clock_missing"), (callback_before_sample, "callback_before_sample"),
                           (future_accel, "acceleration_from_future"), (stale_accel, "acceleration_stale_at_join"),
                           (gyro_accuracy[-1], "gyroscope_accuracy_unknown"), (accel_accuracy[-1], "acceleration_accuracy_unknown"),
                           (gyro_accuracy[0], "gyroscope_unreliable"), (accel_accuracy[0], "acceleration_unreliable"),
                           (gyro_accuracy[1], "gyroscope_accuracy_low"), (accel_accuracy[1], "acceleration_accuracy_low"),
                           (invalid_gyro_accuracy, "gyroscope_accuracy_unrecognized"),
                           (invalid_accel_accuracy, "acceleration_accuracy_unrecognized")]:
        if number:
            issues.append(reason)
    return {"formatVersion": version, "sampleCount": count, "requestedHz": requested,
            "observedHz": (count - 1) / duration if duration > 0 else None,
            "durationSeconds": duration,
            "longestGapMs": max((b - a for a, b in zip(times, times[1:])), default=0) / 1e6,
            "unreliableSamples": unreliable, "samplesMissingOriginalClocks": missing_clocks,
            "callbacksBeforeSample": callback_before_sample,
            "accelerationJoin": {"ageBasis": "original_gyroscope_ns_minus_original_acceleration_ns",
                                 "ageObservedSamples": len(join_ages),
                                 "minimumAgeMs": min(join_ages) if join_ages else None,
                                 "maximumAgeMs": max(join_ages) if join_ages else None,
                                 "staleThresholdMs": stale_threshold_ms, "staleSamples": stale_accel,
                                 "futureAccelerationSamples": future_accel,
                                 "missingAccelerationClockSamples": missing_accel,
                                 "missingGyroscopeClockSamples": missing_gyro,
                                 "missingCallbackClockSamples": missing_callback},
            "gyroscopeAccuracy": accuracy_report(gyro_accuracy, invalid_gyro_accuracy),
            "accelerationAccuracy": accuracy_report(accel_accuracy, invalid_accel_accuracy),
            "eligibleForSensorMetadataReview": not issues, "sensorMetadataIssues": issues}


def inspect_archive(path):
    path = Path(path)
    if path.stat().st_size > 360_000_000:
        raise ValueError("Archive exceeds the selected-capture size limit")
    required = {"watch.kcs", "watch.manifest", "capture.json", "camera-frames.jsonl", "review.json", "clocks.json"}
    allowed = required | {"reference.mp4", "performance.json", "evidence-manifest.json"}
    with zipfile.ZipFile(path) as archive:
        files = archive.infolist()
        names = [f.filename for f in files]
        if len(names) != len(set(names)) or set(names) - allowed or not required <= set(names) or "evidence-manifest.json" not in names:
            raise ValueError("Unexpected, duplicate, or missing capture archive entries")
        if sum(f.file_size for f in files) > 360_000_000 or archive.getinfo("evidence-manifest.json").file_size > 4_000_000:
            raise ValueError("Expanded archive exceeds export bounds")
        limits = {"watch.kcs": 32 * 1024 * 1024, "watch.manifest": 100_000, "camera-frames.jsonl": 120_000_000,
                  "reference.mp4": 200_000_000}
        if any(f.file_size > limits.get(f.filename, 4_000_000) for f in files):
            raise ValueError("Capture entry exceeds the app's export bounds")
        index = _json(archive.read("evidence-manifest.json"))
        if index.get("schemaVersion") != 1 or index.get("scope") != "SELECTED_CAPTURE_DIAGNOSTIC_EVIDENCE":
            raise ValueError("Unsupported evidence index")
        expected = index.get("entries", [])
        if len(expected) != len(names) - 1 or len({r["path"] for r in expected}) != len(expected):
            raise ValueError("Incomplete or duplicate evidence hash index")
        if {r["path"] for r in expected} != set(names) - {"evidence-manifest.json"}:
            raise ValueError("Hash index does not cover the archive")
        for row in expected:
            digest = hashlib.sha256()
            size = 0
            with archive.open(row["path"]) as source:
                while chunk := source.read(8192):
                    size += len(chunk)
                    digest.update(chunk)
            if size != row["bytes"] or digest.hexdigest() != row["sha256"]:
                raise ValueError("Evidence entry changed: " + row["path"])
        capture = _json(archive.read("capture.json"))
        review = _json(archive.read("review.json"))
        clocks = _json(archive.read("clocks.json"))
        session, set_id, manifest = _manifest(archive.read("watch.manifest"))
        raw = archive.read("watch.kcs")
        raw_hash = hashlib.sha256(raw).hexdigest()
        if raw_hash != manifest.get("sha256", "").lower():
            raise ValueError("Sensor original SHA does not match the wire manifest")
        if not (index["captureId"] == capture["id"] == review["captureId"] == session and
                capture["setId"] == set_id and capture["attemptId"] == manifest.get("attemptId")):
            raise ValueError("Capture, review, or attempt identity mismatch")
        sensor = _sensor(raw)
        if sensor["sampleCount"] != int(manifest["sampleCount"]) or sensor["formatVersion"] != int(manifest["formatVersion"]):
            raise ValueError("Sensor header differs from its manifest")
        count = review.get("count")
        actual_reps = None if count is None else count.get("reps")
        if actual_reps is not None and (type(actual_reps) is not int or not 0 <= actual_reps <= 9_999):
            raise ValueError("Invalid reviewed count")
        frame_count = written_frames = 0
        previous_time = None
        with archive.open("camera-frames.jsonl") as source:
            for line in source:
                if not line.strip():
                    continue
                if frame_count >= 6000 or len(line) > 1_000_000:
                    raise ValueError("Camera frame export exceeds bounds")
                frame = _json(line)
                timestamp = frame["cameraNs"]
                if type(timestamp) is not int or timestamp < 0 or previous_time is not None and timestamp <= previous_time:
                    raise ValueError("Invalid camera frame time")
                previous_time = timestamp
                frame_count += 1
                written_frames += frame.get("videoFrameWritten") is True
        video_included = "reference.mp4" in names
        if index.get("videoIncluded") is not video_included:
            raise ValueError("Video inclusion differs from the evidence index")
        reasons = ["actual_device_install_and_operations_not_observed", "engine_accuracy_and_native_latency_not_evaluated",
                   "video_codec_and_frame_pts_not_evaluated"]
        if actual_reps is None:
            reasons.append("count_not_reviewed")
        if not review.get("references"):
            reasons.append("rep_boundaries_not_reviewed")
        if not capture.get("videoComplete") or not video_included:
            reasons.append("complete_reference_video_not_available_in_archive")
        if not frame_count:
            reasons.append("camera_frames_missing")
        windows = {w: len(clocks.get(w, [])) for w in ["START", "MIDDLE", "END"]}
        if any(n == 0 for n in windows.values()):
            reasons.append("clock_alignment_windows_missing")
        if sensor["sensorMetadataIssues"]:
            reasons.append("sensor_metadata_incomplete_or_degraded")
        return {"schemaVersion": 1, "scope": "EXPORT_INTEGRITY_AND_OBSERVED_SENSOR_METADATA_ONLY",
                "captureId": session, "integrityVerified": True, "rawSha256": raw_hash,
                "reviewedReps": actual_reps, "reviewedBoundaryCount": len(review.get("references", [])),
                "sensor": sensor, "cameraFrames": frame_count, "framesDeclaredWritten": written_frames,
                "clockSamplesByWindow": windows, "videoIncluded": video_included,
                "hardwareAcceptanceVerified": False, "accuracyImprovementVerified": False,
                "notVerified": reasons}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(inspect_archive(args.archive), ensure_ascii=False, indent=2, allow_nan=False))
    except (ValueError, KeyError, TypeError, AttributeError, RuntimeError, OSError, struct.error, zipfile.BadZipFile) as error:
        print(json.dumps({"integrityVerified": False, "hardwareAcceptanceVerified": False,
                          "error": str(error)}, ensure_ascii=False))
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
