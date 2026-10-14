"""Competing load on the machine: CPU used by processes other than the benchmark's own (a given root process and
its descendants: the sbt server, JMH's launcher and forks, xctrace), measured as the change in cumulative CPU time.

  loadgate.py wait   <root-pid> [max-cores=1.5] [quiet-secs=60]   blocks until other load stays under max-cores
  loadgate.py sample <root-pid> <out.tsv>                          appends "epoch<TAB>cores<TAB>top" every 5 s until killed
  loadgate.py mean   <out.tsv> [since-epoch]                       mean other-load in cores over the samples
"""
import subprocess, sys, time

# macOS daemons that run at background QoS, which Apple Silicon confines to the efficiency cores: reported, but not
# counted against the limit, since the benchmark runs on the performance cores.
BACKGROUND = {"mediaanalysisd", "photoanalysisd", "spotlightknowledged", "spotlightknowledged.updater", "mds", "mds_stores", "mdworker",
              "mdworker_shared", "mdbulkimport", "backupd", "backupd-helper", "corespotlightd", "suggestd",
              "knowledge-agent", "duetexpertd", "triald", "softwareupdated", "fseventsd"}

def cputimes():
    out = subprocess.run(["ps", "-A", "-o", "pid=,ppid=,time=,comm="], capture_output=True, text=True).stdout
    procs = {}
    for line in out.splitlines():
        parts = line.split(None, 3)
        if len(parts) < 4:
            continue
        pid, ppid, t, comm = int(parts[0]), int(parts[1]), parts[2], parts[3]
        secs = 0.0
        d, _, hms = t.rpartition("-")
        for x in hms.split(":"):
            secs = secs * 60 + float(x)
        if d:
            secs += int(d) * 86400
        procs[pid] = (ppid, secs, comm.rsplit("/", 1)[-1])
    return procs

def ours(procs, root):
    # the parent is launchd (pid 1) once a detaching shell has exited: everything would count as ours
    keep = {root, *(p for p in (subprocess.os.getpid(), subprocess.os.getppid()) if p > 1)}
    changed = True
    while changed:
        changed = False
        for pid, (ppid, _, comm) in procs.items():
            if pid not in keep and (ppid in keep or comm == "xctrace"):
                keep.add(pid)
                changed = True
    return keep

def other_load(root, interval=5.0):
    a = cputimes()
    time.sleep(interval)
    b = cputimes()
    mine = ours(b, root)
    per = {}
    for pid, (_, secs, comm) in b.items():
        if pid in mine or pid not in a:
            continue
        d = secs - a[pid][1]
        if d > 0:
            per[comm] = per.get(comm, 0) + d / interval
    total = sum(v for c, v in per.items() if c not in BACKGROUND)
    bg = sum(v for c, v in per.items() if c in BACKGROUND)
    fg = [(c, v) for c, v in sorted(per.items(), key=lambda kv: -kv[1]) if c not in BACKGROUND]
    top = ", ".join(f"{c} {v:.1f}" for c, v in fg[:3])
    if bg >= 0.1:
        top += f"; background daemons {bg:.1f}"
    return total, top

def main():
    cmd = sys.argv[1]
    if cmd == "wait":
        root = int(sys.argv[2])
        limit = float(sys.argv[3]) if len(sys.argv) > 3 else 1.5
        quiet = float(sys.argv[4]) if len(sys.argv) > 4 else 60
        since = None
        while True:
            load, top = other_load(root)
            now = time.time()
            if load <= limit:
                since = since or now
                if now - since >= quiet:
                    print(f"idle: other load {load:.2f} cores", flush=True)
                    return
            else:
                since = None
                print(f"waiting: other load {load:.1f} cores ({top})", flush=True)
    elif cmd == "sample":
        root, path = int(sys.argv[2]), sys.argv[3]
        while True:
            load, top = other_load(root)
            with open(path, "a") as f:
                f.write(f"{time.time():.0f}\t{load:.2f}\t{top}\n")
    elif cmd == "mean":
        path = sys.argv[2]
        since = float(sys.argv[3]) if len(sys.argv) > 3 else 0
        try:
            xs = [float(l.split("\t")[1]) for l in open(path) if l.strip() and float(l.split("\t")[0]) >= since]
        except FileNotFoundError:   # the run ended before the first sample
            xs = [0.0]
        print(f"{sum(xs) / len(xs):.2f}" if xs else "nan")

main()
