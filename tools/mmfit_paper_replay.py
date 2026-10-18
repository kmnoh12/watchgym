#!/usr/bin/env python3
"""MM-Fit §4.4 description-based OFFLINE reproduction; not author code or live haptics.
Requires numpy. Defaults are declared assumptions where the paper omits parameters.
Never selects parameters, modality or PCA polarity from ground-truth counts.
"""
import argparse
import csv
import json
from pathlib import Path
import zipfile
import struct
import numpy as np


def smooth(values, window):
    window = min(window, len(values) if len(values) % 2 else len(values) - 1)
    if window < 5:
        return values.copy()
    half = window // 2
    x = np.arange(-half, half + 1, dtype=float)
    design = np.stack([x ** i for i in range(4)], axis=1)
    weights = np.linalg.pinv(design)[0]
    result = np.stack([np.convolve(values[:, j], weights[::-1], mode='same') for j in range(3)], axis=1)
    # Polynomial interpolation at actual observed boundaries; no invented stationary tail.
    fit_x = np.arange(window, dtype=float)
    for j in range(3):
        result[:half, j] = np.polynomial.polynomial.polyval(fit_x[:half],
            np.polynomial.polynomial.polyfit(fit_x, values[:window, j], 3))
        result[-half:, j] = np.polynomial.polynomial.polyval(fit_x[-half:],
            np.polynomial.polynomial.polyfit(fit_x, values[-window:, j], 3))
    return result


def analyze(times, values, args, polarity=1):
    if len(times) < 7:
        return []
    source_fs = 1e9 / float(np.median(np.diff(times)))
    # Paper preprocessing uses 50 Hz. Select real source samples only, never interpolate.
    stride = max(1, int(round(source_fs / args.input_rate_hz)))
    times, values = times[::stride], values[::stride]
    fs = 1e9 / float(np.median(np.diff(times)))
    standard = (values - values.mean(axis=0)) / np.maximum(values.std(axis=0), 1e-12)
    window = max(5, int(round(args.smoothing_seconds * fs)) | 1)
    filtered = smooth(standard, window)
    centered = filtered - filtered.mean(axis=0)
    _, _, vt = np.linalg.svd(centered, full_matrices=False)
    loading = vt[0]
    # Deterministic sign convention; paper does not state one. Reverse is reported separately.
    if loading[np.argmax(np.abs(loading))] < 0:
        loading = -loading
    signal = (centered @ loading) * polarity
    candidates = np.flatnonzero((signal[1:-1] > signal[:-2]) & (signal[1:-1] >= signal[2:])) + 1
    rank = sorted(candidates.tolist(), key=lambda i: (-signal[i], i))
    keep = []
    for i in rank:
        if all(abs(int(times[i])-int(times[j])) >= args.minimum_rep_seconds*1e9 for j in keep):
            keep.append(i)
    survivors = set(keep)
    minimum_lag = max(1, int(round(args.minimum_rep_seconds*fs)))
    maximum_lag = max(minimum_lag+1, int(round(args.maximum_rep_seconds*fs)))
    for i in rank:
        if i not in survivors:
            continue
        # Declared assumption: centered window spans +/- maximum repetition duration.
        segment = signal[max(0, i-maximum_lag):min(len(signal), i+maximum_lag+1)]
        segment = segment - segment.mean()
        fft_size = 1 << (2*len(segment)-1).bit_length()
        spectrum = np.fft.rfft(segment, fft_size)
        correlations = np.fft.irfft(spectrum*np.conj(spectrum), fft_size)[:len(segment)]
        stop = min(maximum_lag+1, len(correlations))
        if stop <= minimum_lag:
            continue
        period = (minimum_lag + int(np.argmax(correlations[minimum_lag:stop]))) / fs
        for j in list(survivors):
            if j != i and signal[j] < signal[i] and abs(int(times[j])-int(times[i])) < .75*period*1e9:
                survivors.remove(j)
    if not survivors:
        return []
    threshold = .5*float(np.percentile([signal[i] for i in survivors], 40))
    return sorted(int(times[i]) for i in survivors if signal[i] >= threshold)


def read_csv(path):
    dtype = np.dtype([('sensor', 'i4'), ('time', 'i8'), ('x', 'f8'), ('y', 'f8'), ('z', 'f8')])
    data = np.loadtxt(path, delimiter=',', skiprows=1, dtype=dtype)
    acc, gyro = data[data['sensor']==0], data[data['sensor']==1]
    preceding = np.searchsorted(acc['time'], gyro['time'], side='right')-1
    valid = (preceding >= 0)
    preceding = np.maximum(preceding, 0)
    valid &= gyro['time']-acc['time'][preceding] <= 100_000_000
    gyro, preceding = gyro[valid], preceding[valid]
    xyz = lambda d: np.stack([d[k] for k in ('x','y','z')], axis=1)
    return gyro['time'], xyz(acc)[preceding], xyz(gyro)


def read_zip(path):
    with zipfile.ZipFile(path) as z:
        data = z.read('watch.kcs')
    magic, version, count, _ = struct.unpack_from('>4siid', data)
    assert magic == b'KCS1' and version in (1,2)
    records = [struct.unpack_from('>q6fii', data, 20+i*(76 if version==2 else 40)) for i in range(count)]
    return (np.array([r[0] for r in records],dtype=np.int64),
            np.array([r[1:4] for r in records]), np.array([r[4:7] for r in records]))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--dataset',type=Path,required=True)
    p.add_argument('--evidence',type=Path,action='append',default=[])
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--input-rate-hz',type=float,default=50.)
    p.add_argument('--smoothing-seconds',type=float,default=.5)
    p.add_argument('--minimum-rep-seconds',type=float,default=.6)
    p.add_argument('--maximum-rep-seconds',type=float,default=8.)
    args=p.parse_args()
    rows=[]
    for file in sorted(args.dataset.glob('w[0-9][0-9].csv')):
        times,acc,gyro=read_csv(file)
        with file.with_name(file.stem+'_labels.csv').open() as f:
            labels=[r for r in csv.DictReader(f) if r['exercise']=='dumbbell_shoulder_press']
        for index,label in enumerate(labels,1):
            selected=(times>=int(label['start_ns'])) & (times<=int(label['end_ns']))
            row=dict(session=file.stem,set=index,expected=int(label['reps']))
            for name,values in [('acc',acc),('gyro',gyro)]:
                for polarity,suffix in [(1,'positive'),(-1,'negative')]:
                    row[name+'_'+suffix]=len(analyze(times[selected],values[selected],args,polarity))
            rows.append(row)
        print(file.stem,'completed',flush=True)
    assert len(rows)==60, f'Expected same 60 sets; found {len(rows)}'
    args.output.mkdir(parents=True,exist_ok=True)
    with (args.output/'sets.csv').open('w',newline='') as f:
        writer=csv.DictWriter(f,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
    summary={'scope':'DESCRIPTION_BASED_OFFLINE_REPRODUCTION_NOT_AUTHOR_CODE',
        'settings':{k:getattr(args,k) for k in ['input_rate_hz','smoothing_seconds','minimum_rep_seconds','maximum_rep_seconds']},
        'assumptions':['SG window, rep-duration bounds, autocorrelation window length and PCA sign are not specified in section 4.4.',
            'Uses the existing same 60 label intervals and gyro/latest-preceding-acceleration adapter, selecting actual source samples near the published 50 Hz preprocessing rate.',
            'Both PCA signs and both modalities are reported without picking winners per set.',
            'Whole-set standardization, PCA, amplitude ranking and percentile thresholding are noncausal.'], 'metrics':{}}
    for key in ['acc_positive','acc_negative','gyro_positive','gyro_negative']:
        errors=[abs(r[key]-r['expected']) for r in rows]
        summary['metrics'][key]={'sets':len(rows),'exact':sum(e==0 for e in errors),
            'within_one':sum(e<=1 for e in errors),'mae':sum(errors)/len(errors)}
    summary['evidence']=[]
    for path in args.evidence:
        t,a,g=read_zip(path)
        summary['evidence'].append({'file':path.name,'counts':{name+'_'+suffix:len(analyze(t,v,args,polarity))
            for name,v in [('acc',a),('gyro',g)] for polarity,suffix in [(1,'positive'),(-1,'negative')]}})
    (args.output/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary,indent=2))

if __name__=='__main__':main()
