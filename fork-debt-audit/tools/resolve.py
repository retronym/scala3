import re, subprocess, json, sys
html = open(sys.argv[1]).read()
block = html
shas = sorted(set(re.findall(r'\b[0-9a-f]{10}\b', block)))
S3='/Users/jzaugg-virtus/code/.worktrees/scala3/scala3-untracked-fork-debt-803909'
S2='/Users/jzaugg-virtus/code/scala/scala'
def run(repo, *args):
    r = subprocess.run(['git','-C',repo]+list(args), capture_output=True, text=True); return r.stdout.strip() if r.returncode==0 else None
def pr_for(repo, sha, tip):
    subj = run(repo,'log','-1','--format=%s',sha) or ''
    m = re.search(r'\(#(\d+)\)\s*$', subj)
    if m: return m.group(1)
    merges = run(repo,'log','--merges','--ancestry-path','--format=%s',f'{sha}..{tip}') or ''
    for line in reversed(merges.splitlines()):
        m = re.search(r'Merge pull request #(\d+)', line)
        if m: return m.group(1)
    return None
out={}
for sha in shas:
    # context decides repo when ambiguous
    i = block.index(sha); ctx = block[max(0,i-120):i]
    in3 = run(S3,'cat-file','-e',sha+'^{commit}') is not None
    in2 = run(S2,'cat-file','-e',sha+'^{commit}') is not None
    prefer3 = bool(re.search(r'scala3|Scala 3|#2\d{4}', ctx)) and not re.search(r'scala/scala \w*$', ctx)
    if in3 and (prefer3 or not in2): repo, name, tip = S3, 'scala/scala3', 'HEAD'
    elif in2: repo, name, tip = S2, 'scala/scala', 'origin/2.13.x'
    else: out[sha]=None; continue
    full = run(repo,'rev-parse',sha)
    pr = pr_for(repo, sha, tip)
    out[sha] = {'repo':name, 'url':f'https://github.com/{name}/commit/{full}', 'pr':pr, 'prurl': f'https://github.com/{name}/pull/{pr}' if pr else None, 'subject': run(repo,'log','-1','--format=%s',sha)}
json.dump(out, open(sys.argv[2],'w'), indent=1)
for k,v in out.items(): print(k, v and v['repo'], v and v['pr'], v and v['subject'][:60])
