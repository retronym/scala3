#!/usr/bin/env python3
# LLM-assisted (Claude), see LLM_POLICY.md.
"""Per-version workspaces for the Greffier compile server: versions/<label>/.

A workspace holds the compiler classpath (coursier-fetched for a released version, copied for a local build),
the agent and the plugin compiled against that compiler, and the join-point table of a warm-up compile.

  versions.py setup <scala-version>                       fetch with coursier, build, warm up
  versions.py add-local <label> <compiler-classes> <cp-file>  snapshot a local nonbootstrapped build
  versions.py rebuild <label>                              rebuild agent+plugin (after editing their sources)
  versions.py list

Two source-level differences between compilers are handled when the agent and plugin are built:
  - `asm`: main uses org.ow2.asm (`org.objectweb.asm`), released 3.x compilers the shaded scala-asm
    (`scala.tools.asm`); the copied sources are rewritten to the compiler's package.
  - `compat`: the plugin/compat/ overlays: `opt` (compilers with the JVM optimiser, `dotty.tools.backend.jvm.opt`,
    3.8+) or `noopt`; `skipIfJava` (`Phase.skipIfJava`, 3.4+) or `noSkipIfJava`.
"""
import json, os, re, shutil, subprocess, sys, time, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPLAY = os.path.dirname(HERE)
VERSIONS = os.path.join(HERE, 'versions')
JAVA_HOME = os.environ.get('JAVA_HOME_REPLAY', os.path.expanduser('~/.sdkman/candidates/java/21.0.12+1.1-tem'))
JAVA = os.path.join(JAVA_HOME, 'bin', 'java')
BB_VERSION = '1.17.5'
# Agent options for every compile. `research` lifts the research-plugin gate on released compilers;
# `lenient` (unpinned versions only) reports a MISSING join point instead of failing the compile.
AGENT_OPTS = ['fixTypeApply', 'rejected', 'tentative', 'research']
WARMUP = 'object Hi:\n  def inc(x: Int): Int =\n    val y = x\n    y + 1\n'


def ws(label): return os.path.join(VERSIONS, label)


def load(label):
    p = os.path.join(ws(label), 'version.json')
    if not os.path.exists(p): return None
    with open(p) as f: return json.load(f)


def save(label, info):
    with open(os.path.join(ws(label), 'version.json'), 'w') as f: json.dump(info, f, indent=1)


def all_labels():
    if not os.path.isdir(VERSIONS): return []
    return sorted(d for d in os.listdir(VERSIONS) if os.path.exists(os.path.join(VERSIONS, d, 'version.json')))


def run(cmd, log, **kw):
    log.write('$ ' + ' '.join(cmd) + '\n'); log.flush()
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, **kw)
    log.write(r.stdout); log.flush()
    if r.returncode != 0: raise RuntimeError(f'{cmd[0]} failed ({r.returncode}):\n{r.stdout[-4000:]}')
    return r.stdout


def coursier(*args, log):
    return [p for p in run(['coursier', 'fetch', '-q', *args], log).split('\n') if p.strip()]


def read_entry(cp, entry):
    """The bytes of a class file (path inside a jar or directory) on the classpath, or None."""
    for p in cp:
        if os.path.isdir(p):
            f = os.path.join(p, entry)
            if os.path.exists(f):
                with open(f, 'rb') as fh: return fh.read()
        elif p.endswith('.jar') and os.path.exists(p):
            with zipfile.ZipFile(p) as z:
                try: return z.read(entry)
                except KeyError: pass
    return None


def detect(cp):
    """Feature tests on the compiler's class files: the ASM package and the plugin/compat/ overlays."""
    asm = 'scala.tools.asm' if read_entry(cp, 'scala/tools/asm/tree/MethodNode.class') else 'org.objectweb.asm'
    # LocalOptimizer on main, LocalOpt on 3.8 to 3.10 (same KnownCallsite/Callee and settings APIs)
    opt = 'opt' if any(read_entry(cp, f'dotty/tools/backend/jvm/opt/{c}.class') for c in ('LocalOptimizer', 'LocalOpt')) else 'noopt'
    skip = 'skipIfJava' if b'skipIfJava' in (read_entry(cp, 'dotty/tools/dotc/core/Phases$Phase.class') or b'') else 'noSkipIfJava'
    return asm, [opt, skip]


def copy_rewrite(srcs, dst, asm):
    """Copy source files into dst (flat for .scala, tree for .java), rewriting the ASM package."""
    for src, rel in srcs:
        out = os.path.join(dst, rel)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(src) as f: s = f.read()
        if asm != 'org.objectweb.asm':
            s = s.replace('org.objectweb.asm', asm).replace('org/objectweb/asm', asm.replace('.', '/'))
        with open(out, 'w') as f: f.write(s)


def build(label, log):
    """Build the agent and the plugin against the workspace's compiler."""
    info = load(label); w = ws(label)
    cp = info['compilerCp']
    asm, compat = info['asm'], info['compat']
    src = os.path.join(w, 'src'); shutil.rmtree(src, ignore_errors=True)
    # agent: bootstrap API jar + -javaagent jar (agent/build.sh, with a parameterised classpath)
    adir = os.path.join(REPLAY, 'agent', 'src')
    jsrcs = [(os.path.join(r, f), os.path.relpath(os.path.join(r, f), adir)) for r, _, fs in os.walk(adir) for f in fs if f.endswith('.java')]
    copy_rewrite(jsrcs, os.path.join(src, 'agent'), asm)
    bb = coursier('--intransitive', f'net.bytebuddy:byte-buddy:{BB_VERSION}', log=log)[0]
    bba = coursier('--intransitive', f'net.bytebuddy:byte-buddy-agent:{BB_VERSION}', log=log)[0]
    t = os.path.join(w, 'agent'); shutil.rmtree(t, ignore_errors=True)
    for d in ('api', 'agent'): os.makedirs(os.path.join(t, d))
    shutil.copy(bb, os.path.join(t, 'byte-buddy.jar')); shutil.copy(bba, os.path.join(t, 'byte-buddy-agent.jar'))
    javac, jar = os.path.join(JAVA_HOME, 'bin', 'javac'), os.path.join(JAVA_HOME, 'bin', 'jar')
    def javas(sub): return [os.path.join(r, f) for r, _, fs in os.walk(os.path.join(src, 'agent', sub)) for f in fs if f.endswith('.java')]
    run([javac, '--release', '17', '-d', os.path.join(t, 'api'), *javas('api')], log)
    run([jar, '--create', '--file', os.path.join(t, 'replay-agent-api.jar'), '-C', os.path.join(t, 'api'), '.'], log)
    run([javac, '--release', '17', '-g', '-cp', ':'.join([os.path.join(t, 'api'), bb, *cp]), '-d', os.path.join(t, 'agent'), *javas('agent')], log)
    with open(os.path.join(t, 'MANIFEST.MF'), 'w') as f:
        f.write('Premain-Class: replay.agent.ReplayAgent\nAgent-Class: replay.agent.ReplayAgent\n'
                'Boot-Class-Path: replay-agent-api.jar\nClass-Path: byte-buddy.jar\n'
                'Can-Retransform-Classes: true\nCan-Redefine-Classes: true\n')
    run([jar, '--create', '--file', os.path.join(t, 'replay-agent.jar'), '--manifest', os.path.join(t, 'MANIFEST.MF'), '-C', os.path.join(t, 'agent'), '.'], log)
    # plugin: src/ + src-agent/ + compat/<overlays>/, compiled by this version's dotc (plugin/build.sh)
    p = os.path.join(REPLAY, 'plugin')
    ssrcs = [(os.path.join(p, d, f), f) for d in ('src', 'src-agent', *[f'compat/{c}' for c in compat])
             for f in sorted(os.listdir(os.path.join(p, d))) if f.endswith('.scala')]
    copy_rewrite(ssrcs, os.path.join(src, 'plugin'), asm)
    pc = os.path.join(w, 'plugin'); shutil.rmtree(pc, ignore_errors=True); os.makedirs(pc)
    files = [os.path.join(src, 'plugin', rel) for _, rel in ssrcs]
    run([JAVA, '-Xss8m', '-cp', ':'.join(cp), 'dotty.tools.dotc.Main', '-color:never', '-classpath',
         ':'.join([*cp, os.path.join(t, 'replay-agent-api.jar'), bba]), '-d', pc, *files], log)
    shutil.copy(os.path.join(p, 'plugin.properties'), pc)


def dotc_cmd(label, out, classes, options, files, table=None, extra_agent=()):
    """The command line of one recorded compile: fresh JVM, -javaagent, the plugin."""
    info = load(label); w = ws(label)
    opts = AGENT_OPTS + ([] if info.get('pinned') else ['lenient']) + list(extra_agent)
    cmd = [JAVA, '-Xss8m', '-Xmx3g', f'-javaagent:{w}/agent/replay-agent.jar=' + ','.join(opts)]
    if table: cmd.append(f'-Dreplay.agent.table={table}')
    cmd += ['-cp', ':'.join(info['compilerCp']), 'dotty.tools.dotc.Main', '-color:never',
            '-classpath', ':'.join(info['libraryCp']), f'-Xplugin:{w}/plugin', f'-P:replay-record:out={out}',
            *options, '-d', classes, *files]
    return cmd


def warmup(label, log):
    """Compile a tiny file with the recorder: checks the gate, the plugin and the join points, and keeps the table."""
    w = ws(label); d = os.path.join(w, 'warmup'); shutil.rmtree(d, ignore_errors=True); os.makedirs(os.path.join(d, 'classes'))
    with open(os.path.join(d, 'Hi.scala'), 'w') as f: f.write(WARMUP)
    out, table = os.path.join(d, 'Hi.jsonl'), os.path.join(w, 'jointable.txt')
    t0 = time.time()
    run(dotc_cmd(label, out, os.path.join(d, 'classes'), [], [os.path.join(d, 'Hi.scala')], table=table), log)
    ms = int((time.time() - t0) * 1000)
    if not os.path.exists(out): raise RuntimeError('warm-up compile wrote no recording (plugin not run: research-plugin gate?)')
    if not os.path.exists(table): raise RuntimeError('warm-up compile wrote no join-point table')
    return ms


def jointable(label):
    p = os.path.join(ws(label), 'jointable.txt')
    if not os.path.exists(p): return None
    rows = []
    with open(p) as f:
        next(f, None)
        for line in f:
            parts = line.split(None, 4)
            if len(parts) >= 4: rows.append({'group': parts[0], 'status': parts[1], 'class': parts[2], 'method': parts[3],
                                             'rest': parts[4].strip() if len(parts) > 4 else ''})
    return rows


def finish(label, log):
    info = load(label)
    info['asm'], info['compat'] = detect(info['compilerCp'])
    info['state'] = 'building'; save(label, info)
    try:
        build(label, log)
        info['warmupMs'] = warmup(label, log)
        rows = jointable(label) or []
        missing = [f"{r['class'].rsplit('.', 1)[-1]}.{r['method']}" for r in rows if r['status'] != 'FOUND']
        info['joinPoints'] = {'found': sum(r['status'] == 'FOUND' for r in rows), 'total': len(rows), 'missing': missing}
        info['state'] = 'ready'; info['error'] = None
        info['notes'] = (f"{info['asm']}, compat {'+'.join(info['compat'])}" + (f"; {len(missing)} join points missing: " + ', '.join(missing) if missing else '; all join points found'))
    except Exception as e:
        info['state'] = 'error'; info['error'] = str(e)[-2000:]
    info['built'] = time.strftime('%Y-%m-%dT%H:%M:%S')
    save(label, info)
    return info


def setup(version, log=sys.stderr):
    """A released Scala 3 version: fetch the compiler and library with coursier, then build and warm up."""
    if not re.fullmatch(r'3\.\d+\.\d+(-RC\d+)?', version): raise ValueError(f'not a Scala 3 version: {version}')
    os.makedirs(ws(version), exist_ok=True)
    cp = coursier(f'org.scala-lang:scala3-compiler_3:{version}', log=log)
    lib = coursier(f'org.scala-lang:scala3-library_3:{version}', log=log)
    asm, compat = detect(cp)
    save(version, {'scala': version, 'kind': 'released', 'compilerCp': cp, 'libraryCp': lib, 'asm': asm, 'compat': compat,
                   'pinned': False, 'state': 'fetched'})
    return finish(version, log)


def add_local(label, classes, cpfile, log=sys.stderr):
    """A local nonbootstrapped compiler: copy its classes, and every directory entry of its classpath, into the
    workspace (jars stay where coursier put them). The classpath file is plugin/compiler.classpath's format."""
    w = ws(label); os.makedirs(w, exist_ok=True)
    with open(cpfile) as f: entries = [e for e in f.read().strip().split(':') if e]
    snap = os.path.join(w, 'cp'); shutil.rmtree(snap, ignore_errors=True); os.makedirs(snap)
    cp, lib = [], []
    for i, e in enumerate(entries):
        src = classes if '/compiler/target/' in e else e
        if os.path.isdir(src):
            dst = os.path.join(snap, f'{i:02d}-' + re.sub(r'[^A-Za-z0-9.-]+', '_', e.split('/target/')[0].rsplit('/', 1)[-1] + '-' + os.path.basename(e)))
            shutil.copytree(src, dst); src = dst
        elif not os.path.exists(src):
            log.write(f'skipping missing classpath entry {e}\n'); continue
        cp.append(src)
        if '/library/target/' in e: lib.append(src)
    asm, compat = detect(cp)
    save(label, {'scala': label, 'kind': 'local', 'compilerCp': cp, 'libraryCp': lib, 'asm': asm, 'compat': compat,
                 'pinned': True, 'state': 'fetched', 'source': classes})
    return finish(label, log)


if __name__ == '__main__':
    a = sys.argv[1:]
    if a[:1] == ['setup'] and len(a) == 2: i = setup(a[1])
    elif a[:1] == ['add-local'] and len(a) == 4: i = add_local(a[1], a[2], a[3])
    elif a[:1] == ['rebuild'] and len(a) == 2: i = finish(a[1], sys.stderr)
    elif a[:1] == ['list']:
        for l in all_labels(): i = load(l); print(l, i['state'], i.get('notes', ''))
        sys.exit(0)
    else: print(__doc__); sys.exit(2)
    print(json.dumps({k: v for k, v in i.items() if k not in ('compilerCp', 'libraryCp')}, indent=1))
    sys.exit(0 if i['state'] == 'ready' else 1)
