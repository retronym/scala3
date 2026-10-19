#!/usr/bin/env python3
"""Generates fixture.jsonl (v0 schema, see PLAN-replayable-compiler.md) modelled on corpus/Givens.scala.

Trees are built as Python objects; a node that is shared between phases is literally the same object,
so persistent-tree sharing falls out. Printed text and [id,start,end] offsets are computed by rendering.
Also writes fixture.js so the page works from file:// (fetch() of local files is blocked there).
"""
import json, os, random

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = open(os.path.join(HERE, '../corpus/Givens.scala')).read()
BASE = SRC.index('object Givens')

def sp(a, b=None):
    s = SRC.index(a, BASE)
    b = b or a
    e = SRC.index(b, s) + len(b)
    return (s, e)

def sps(ctx, sub):
    s0 = SRC.index(ctx, BASE)
    s = SRC.index(sub, s0)
    return (s, s + len(sub))

def z(ctx):  # zero-width span: synthetic node anchored at a source position
    s = SRC.index(ctx, BASE)
    return (s, s)

TY, INL, PM, ER, LL, CT = 4, 8, 13, 17, 22, 25
PHASES = [(TY, 'typer'), (INL, 'inlining'), (PM, 'patternMatcher'), (ER, 'erasure'), (LL, 'lambdaLift'), (CT, 'constructors')]

types = {}
def ty(t):
    if t not in types: types[t] = len(types) + 1
    return types[t]

allnodes = []
class N:
    def __init__(s, kind, *parts, sym=None, tpe=None, name=None, span=(0, 0)):
        s.id = len(allnodes) + 1; allnodes.append(s)
        s.kind, s.parts, s.sym, s.name, s.span = kind, parts, sym, name, span
        s.tpe = ty(tpe) if isinstance(tpe, str) else tpe
    def like(s, *parts, tpe=None, name=None, sym=None):
        return N(s.kind, *parts, sym=sym or s.sym, tpe=ty(tpe) if tpe else s.tpe, name=name or s.name, span=s.span)
    @property
    def children(s): return [p.id for p in s.parts if isinstance(p, N)]

def render(n, buf, offs):
    start = sum(map(len, buf))
    i = len(offs); offs.append(None)
    for p in n.parts:
        if isinstance(p, N): render(p, buf, offs)
        else: buf.append(p)
    offs[i] = [n.id, start, sum(map(len, buf))]

def version(root):
    buf, offs = [], []
    render(root, buf, offs)
    return ''.join(buf), offs

derives = []
def derive(phase, new, old, how):
    derives.append(dict(t='derive', phase=phase, new=[n.id for n in new], old=[n.id for n in old], how=how))

# ---- symbols -------------------------------------------------------------------------------------
S = {}
syms, syminfo = [], []
def sym(key, name, owner, created=TY, by=None):
    S[key] = len(S) + 1
    syms.append(dict(t='sym', id=S[key], name=name, owner=owner, createdPhase=created, createdBy=by))
    return S[key]
def info(key, phase, text, flags, owner):
    syminfo.append(dict(t='syminfo', sym=S[key], fromPhase=phase, info=text, flags=flags, owner=S[owner] if isinstance(owner, str) else owner))

sym('pkg', '<empty>', None)
sym('Givens', 'Givens', S['pkg'])
sym('render', 'render', S['Givens']); sym('pickLocal', 'pickLocal', S['Givens']); sym('viaConversion', 'viaConversion', S['Givens'])
sym('shown', 'shown', S['pkg']); sym('Show', 'Show', S['pkg'])
sym('gInt', 'given_Show_Int', S['Show']); sym('gStr', 'given_Show_String', S['Show']); sym('gList', 'given_Show_List', S['Show'])
sym('gConv', 'given_Conversion_Int_String', S['pkg'])
sym('lcls', 'given_Show_Int', S['pickLocal']); sym('lval', 'given_Show_Int', S['pickLocal'])
sym('listApply', 'apply', S['pkg']); sym('plus', '+', S['pkg']); sym('i', 'i', S['viaConversion'])
sym('GivensInit', '<init>', S['Givens'], CT); sym('show', 'show', S['lcls']); sym('bridge', 'show', S['lcls'], ER)
sym('clsInit', '<init>', S['lcls'], CT); sym('box', 'box', S['pkg'], ER)

info('Givens', TY, 'module class Givens extends Object', 'module', 'pkg')
info('Givens', CT, 'module class Givens extends Object { <init>(): Unit }', 'module', 'pkg')
info('render', TY, 'def render: String', 'method', 'Givens')
info('viaConversion', TY, 'def viaConversion(i: Int): String', 'method', 'Givens')
info('pickLocal', TY, 'def pickLocal: String', 'method', 'Givens')
info('shown', TY, 'extension [A](a: A)(using s: Show[A]): String', 'method|extension', 'pkg')
info('shown', ER, '(a: Object)(s: Show): String', 'method|extension', 'pkg')
info('Show', TY, 'trait Show[A] { def show(a: A): String }', 'trait', 'pkg')
info('gInt', TY, 'given Show[Int]', 'given|module', 'Show')
info('gStr', TY, 'given Show[String]', 'given|module', 'Show')
info('gList', TY, '[A](using sa: Show[A]): Show[List[A]]', 'given|method', 'Show')
info('gList', ER, '(sa: Show): Show', 'given|method', 'Show')
info('gConv', TY, 'given Conversion[Int, String]', 'given|lazy', 'pkg')
info('lcls', TY, 'class given_Show_Int extends Object, Show[Int]', 'given|local', 'pickLocal')
info('lcls', ER, 'class given_Show_Int extends Object, Show', 'given|local', 'pickLocal')
info('lcls', LL, 'class given_Show_Int$1 extends Object, Show', 'given|lifted', 'Givens')
info('lval', TY, 'lazy val given_Show_Int: given_Show_Int', 'given|lazy|local', 'pickLocal')
info('lval', LL, 'lazy val given_Show_Int: given_Show_Int$1', 'given|lazy|local', 'pickLocal')
info('listApply', TY, '[A](elems: A*): List[A]', 'method', 'pkg')
info('listApply', ER, '(elems: Seq): List', 'method', 'pkg')
info('plus', TY, '(x: Any): String', 'method', 'pkg')
info('i', TY, 'val i: Int', 'param', 'viaConversion')
info('show', TY, 'def show(a: Int): String', 'method', 'lcls')
info('bridge', ER, 'def show(a: Object): String', 'method|bridge', 'lcls')
info('GivensInit', CT, 'def <init>(): Unit', 'method|constructor', 'Givens')
info('clsInit', CT, 'def <init>(): Unit', 'method|constructor', 'lcls')
info('box', ER, 'def box(x: Int): Integer', 'method', 'pkg')

# ---- typer-phase tree (printed like -Xprint:typer, simplified) -------------------------------------------------
l1 = N('Literal', '1', tpe='Int(1)', span=sps('List(1, 2, 3)', '1'))
l2 = N('Literal', '2', tpe='Int(2)', span=sps('List(1, 2, 3)', '2'))
l3 = N('Literal', '3', tpe='Int(3)', span=sps('List(1, 2, 3)', '3'))
selList = N('Select', 'List.apply', sym=S['listApply'], tpe='[A](elems: A*): List[A]', name='apply', span=sps('List(1, 2, 3)', 'List'))
ttInt1 = N('TypeTree', 'Int', tpe='Int', span=z('List(1, 2, 3)'))
taList = N('TypeApply', selList, '[', ttInt1, ']', tpe='(elems: Int*): List[Int]', span=sp('List(1, 2, 3)', 'List'))
listApp = N('Apply', taList, '(', l1, ', ', l2, ', ', l3, ')', tpe='List[Int]', span=sp('List(1, 2, 3)'))

selShown = N('Select', 'shown', sym=S['shown'], tpe='[A](a: A)(using s: Show[A]): String', name='shown', span=sps('List(1, 2, 3).shown', 'shown'))
ttListInt = N('TypeTree', 'List[Int]', tpe='List[Int]', span=z('List(1, 2, 3).shown'))
taShown1 = N('TypeApply', selShown, '[', ttListInt, ']', tpe='(a: List[Int])(using s: Show[List[Int]]): String', span=sp('List(1, 2, 3).shown'))
selGInt1 = N('Select', 'Show.given_Show_Int', sym=S['gInt'], tpe='Show[Int]', name='given_Show_Int', span=z('List(1, 2, 3).shown'))
selGList = N('Select', 'Show.given_Show_List', sym=S['gList'], tpe='[A](using sa: Show[A]): Show[List[A]]', name='given_Show_List', span=z('List(1, 2, 3).shown'))
ttInt2 = N('TypeTree', 'Int', tpe='Int', span=z('List(1, 2, 3).shown'))
taGList = N('TypeApply', selGList, '[', ttInt2, ']', tpe='(using sa: Show[Int]): Show[List[Int]]', span=z('List(1, 2, 3).shown'))
gListApp = N('Apply', taGList, '(using ', selGInt1, ')', tpe='Show[List[Int]]', span=z('List(1, 2, 3).shown'))
shown1a = N('Apply', taShown1, '(', listApp, ')', tpe='(using s: Show[List[Int]]): String', span=sp('List(1, 2, 3).shown'))
shown1 = N('Apply', shown1a, '(using ', gListApp, ')', tpe='String', span=sp('List(1, 2, 3).shown'))

selShown2 = N('Select', 'shown', sym=S['shown'], tpe='[A](a: A)(using s: Show[A]): String', name='shown', span=sps('"x".shown', 'shown'))
ttStr = N('TypeTree', 'String', tpe='String', span=z('"x".shown'))
taShown2 = N('TypeApply', selShown2, '[', ttStr, ']', tpe='(a: String)(using s: Show[String]): String', span=sp('"x".shown'))
litX = N('Literal', '"x"', tpe='String("x")', span=sps('"x".shown', '"x"'))
selGStr = N('Select', 'Show.given_Show_String', sym=S['gStr'], tpe='Show[String]', name='given_Show_String', span=z('"x".shown'))
shown2a = N('Apply', taShown2, '(', litX, ')', tpe='(using s: Show[String]): String', span=sp('"x".shown'))
shown2 = N('Apply', shown2a, '(using ', selGStr, ')', tpe='String', span=sp('"x".shown'))

selPlus = N('Select', shown1, '.+', sym=S['plus'], tpe='(x: Any): String', name='+', span=sp('List(1, 2, 3).shown', ' +'))
plusR = N('Apply', selPlus, '(', shown2, ')', tpe='String', span=sp('List(1, 2, 3).shown', '"x".shown'))
renderDef = N('DefDef', 'def render: String =\n      ', plusR, sym=S['render'], tpe='String', name='render', span=sp('def render', '"x".shown'))

# viaConversion: (i: String) + "!"  ==>  given_Conversion_Int_String.apply(i).+("!")
idI = N('Ident', 'i', sym=S['i'], tpe='Int', name='i', span=sps('(i: String)', 'i'))
selConv = N('Select', 'given_Conversion_Int_String.apply', sym=S['gConv'], tpe='(x: Int): String', name='apply', span=sp('(i: String)'))
convApp = N('Apply', selConv, '(', idI, ')', tpe='String', span=sp('(i: String)'))
selPlus2 = N('Select', convApp, '.+', sym=S['plus'], tpe='(x: Any): String', name='+', span=sp('(i: String)', ' +'))
litBang = N('Literal', '"!"', tpe='String("!")', span=sps('+ "!"', '"!"'))
plusV = N('Apply', selPlus2, '(', litBang, ')', tpe='String', span=sp('(i: String)', '"!"'))
viaDef = N('DefDef', 'def viaConversion(i: Int): String =\n      ', plusV, sym=S['viaConversion'], tpe='String', name='viaConversion', span=sp('def viaConversion', '"!"'))

# pickLocal: local given ==> class + lazy val
litLocal = N('Literal', '"local"', tpe='String("local")', span=sps('def show(a: Int) = "local"', '"local"'))
showDef = N('DefDef', 'def show(a: Int): String = ', litLocal, sym=S['show'], tpe='String', name='show', span=sp('def show(a: Int) = "local"'))
CLS_START = 'given Show[Int] with\n      def show(a: Int) = "local"'
def clsSpan(): return sp(CLS_START)
def mkClass(header, *members_and_seps, **kw):
    return N('ClassDef', header, *members_and_seps, sym=S['lcls'], name='given_Show_Int', span=clsSpan(), **kw)
clsT = mkClass('class given_Show_Int extends Object, Show[Int] {\n          ', showDef, '\n        }', tpe='given_Show_Int')
newT = N('New', 'new given_Show_Int()', tpe='given_Show_Int', span=clsSpan())
valT = N('ValDef', 'lazy val given_Show_Int: given_Show_Int = ', newT, sym=S['lval'], tpe='given_Show_Int', name='given_Show_Int', span=clsSpan())
lit42 = N('Literal', '42', tpe='Int(42)', span=sps('42.shown', '42'))
selShown3 = N('Select', 'shown', sym=S['shown'], tpe='[A](a: A)(using s: Show[A]): String', name='shown', span=sps('42.shown', 'shown'))
ttInt3 = N('TypeTree', 'Int', tpe='Int', span=z('42.shown'))
taShown3 = N('TypeApply', selShown3, '[', ttInt3, ']', tpe='(a: Int)(using s: Show[Int]): String', span=sp('42.shown'))
shown3a = N('Apply', taShown3, '(', lit42, ')', tpe='(using s: Show[Int]): String', span=sp('42.shown'))
idLval = N('Ident', 'given_Show_Int', sym=S['lval'], tpe='given_Show_Int', name='given_Show_Int', span=z('42.shown'))
shown3 = N('Apply', shown3a, '(using ', idLval, ')', tpe='String', span=sp('42.shown'))
blockT = N('Block', '{\n        ', clsT, '\n        ', valT, '\n        ', shown3, '\n      }', tpe='String', span=sp('given Show[Int] with\n      def show(a: Int) = "local"', '42.shown'))
pickDef = N('DefDef', 'def pickLocal: String =\n      ', blockT, sym=S['pickLocal'], tpe='String', name='pickLocal', span=sp('def pickLocal', '42.shown'))

modT = N('ModuleDef', 'object Givens extends Object {\n    ', renderDef, '\n    ', viaDef, '\n    ', pickDef, '\n  }', sym=S['Givens'], name='Givens', span=sp('object Givens', '42.shown'))
pkgT = N('PackageDef', 'package <empty> {\n  ', modT, '\n}', sym=S['pkg'], name='<empty>', span=(0, len(SRC)))

# ---- erasure ----------------------------------------------------------------------------------------------------
# render: TypeApply elided, two argument lists merged into one Apply; String.+ unchanged
listAppE = N('Apply', selList, '(', l1, ', ', l2, ', ', l3, ')', tpe='List', span=listApp.span)
derive(ER, [listAppE], [listApp], 'heuristic'); derive(ER, [], [taList], 'elide')
gListAppE = N('Apply', selGList, '(', selGInt1, ')', tpe='Show', span=gListApp.span)
derive(ER, [gListAppE], [gListApp], 'heuristic'); derive(ER, [], [taGList], 'elide')
shown1E = N('Apply', selShown, '(', listAppE, ', ', gListAppE, ')', tpe='String', span=shown1.span)
derive(ER, [shown1E], [shown1, shown1a], 'rewrite'); derive(ER, [], [taShown1], 'elide')
shown2E = N('Apply', selShown2, '(', litX, ', ', selGStr, ')', tpe='String', span=shown2.span)
derive(ER, [shown2E], [shown2, shown2a], 'rewrite'); derive(ER, [], [taShown2], 'elide')
selPlusE = N('Select', shown1E, '.+', sym=S['plus'], tpe='(x: Object): String', name='+', span=selPlus.span)
derive(ER, [selPlusE], [selPlus], 'copy')
plusRE = N('Apply', selPlusE, '(', shown2E, ')', tpe='String', span=plusR.span)
derive(ER, [plusRE], [plusR], 'copy')
renderDefE = renderDef.like('def render: String =\n      ', plusRE); derive(ER, [renderDefE], [renderDef], 'copy')

# viaConversion: Conversion[Int,String].apply(Object): box the Int, cast the result
selBox = N('Select', 'Int.box', sym=S['box'], tpe='(x: Int): Integer', name='box', span=z('(i: String)'))
boxApp = N('Apply', selBox, '(', idI, ')', tpe='Integer', span=z('(i: String)'))
derive(ER, [selBox, boxApp], [], 'synth')
convAppE = N('Apply', selConv, '(', boxApp, ')', tpe='Object', span=convApp.span)
derive(ER, [convAppE], [convApp], 'rewrite')
ttStrE = N('TypeTree', 'String', tpe='String', span=z('(i: String)'))
selCast = N('Select', convAppE, '.$asInstanceOf', tpe='[T]=> T', name='$asInstanceOf', span=convApp.span)
castE = N('TypeApply', selCast, '[', ttStrE, ']', tpe='String', span=convApp.span)
derive(ER, [selCast, castE, ttStrE], [], 'synth')
selPlus2E = N('Select', castE, '.+', sym=S['plus'], tpe='(x: Object): String', name='+', span=selPlus2.span)
derive(ER, [selPlus2E], [selPlus2], 'copy')
plusVE = N('Apply', selPlus2E, '(', litBang, ')', tpe='String', span=plusV.span)
derive(ER, [plusVE], [plusV], 'copy')
viaDefE = viaDef.like('def viaConversion(i: Int): String =\n      ', plusVE); derive(ER, [viaDefE], [viaDef], 'copy')

# pickLocal: erased class gets a bridge; ascription and type args elided
bridgeCall = N('Apply', 'this.show(BoxesRunTime.unboxToInt(a))', tpe='String', span=z(CLS_START))
bridgeDef = N('DefDef', 'def show(a: Object): String = ', bridgeCall, sym=S['bridge'], tpe='String', name='show', span=z(CLS_START))
derive(ER, [bridgeDef, bridgeCall], [], 'synth')
clsE = mkClass('class given_Show_Int extends Object, Show {\n          ', showDef, '\n          ', bridgeDef, '\n        }', tpe='given_Show_Int')
derive(ER, [clsE], [clsT], 'rewrite')
valE = valT.like('lazy val given_Show_Int: given_Show_Int = ', newT); derive(ER, [valE], [valT], 'copy')
shown3E = N('Apply', selShown3, '(', lit42, ', ', idLval, ')', tpe='String', span=shown3.span)
derive(ER, [shown3E], [shown3, shown3a], 'rewrite'); derive(ER, [], [taShown3], 'elide')
blockE = blockT.like('{\n        ', clsE, '\n        ', valE, '\n        ', shown3E, '\n      }')
derive(ER, [blockE], [blockT], 'copy')
pickDefE = pickDef.like('def pickLocal: String =\n      ', blockE); derive(ER, [pickDefE], [pickDef], 'copy')
modE = modT.like('object Givens extends Object {\n    ', renderDefE, '\n    ', viaDefE, '\n    ', pickDefE, '\n  }'); derive(ER, [modE], [modT], 'copy')
pkgE = pkgT.like('package <empty> {\n  ', modE, '\n}'); derive(ER, [pkgE], [pkgT], 'copy')

# ---- lambdaLift: local class hoisted to Givens, renamed given_Show_Int$1 -----------------------------------------
clsL = mkClass('class given_Show_Int$1 extends Object, Show {\n      ', showDef, '\n      ', bridgeDef, '\n    }', tpe='given_Show_Int$1')
newL = N('New', 'new given_Show_Int$1()', tpe='given_Show_Int$1', span=newT.span)
valL = valT.like('lazy val given_Show_Int: given_Show_Int$1 = ', newL)
blockL = blockT.like('{\n        ', valL, '\n        ', shown3E, '\n      }')
derive(LL, [blockL, clsL], [blockE], 'split')
derive(LL, [valL, newL], [valE, newT], 'rewrite')
pickDefL = pickDef.like('def pickLocal: String =\n      ', blockL); derive(LL, [pickDefL], [pickDefE], 'copy')
modL = modT.like('object Givens extends Object {\n    ', renderDefE, '\n    ', viaDefE, '\n    ', pickDefL, '\n    ', clsL, '\n  }')
derive(LL, [modL], [modE], 'copy')
pkgL = pkgT.like('package <empty> {\n  ', modL, '\n}'); derive(LL, [pkgL], [pkgE], 'copy')

# ---- constructors: synthetic <init> for object and lifted class ---------------------------------------------------
ctorBody = N('Block', '{ super.<init>(); () }', tpe='Unit', span=z('object Givens'))
ctorDef = N('DefDef', 'def <init>(): Unit = ', ctorBody, sym=S['GivensInit'], tpe='Unit', name='<init>', span=z('object Givens'))
derive(CT, [ctorDef, ctorBody], [], 'synth')
cctorBody = N('Block', '{ super.<init>(); () }', tpe='Unit', span=clsSpan())
cctorDef = N('DefDef', 'def <init>(): Unit = ', cctorBody, sym=S['clsInit'], tpe='Unit', name='<init>', span=clsSpan())
derive(CT, [cctorDef, cctorBody], [], 'synth')
clsC = mkClass('class given_Show_Int$1 extends Object, Show {\n      ', cctorDef, '\n      ', showDef, '\n      ', bridgeDef, '\n    }', tpe='given_Show_Int$1')
derive(CT, [clsC], [clsL], 'copy')
modC = modT.like('object Givens extends Object {\n    ', ctorDef, '\n    ', renderDefE, '\n    ', viaDefE, '\n    ', pickDefL, '\n    ', clsC, '\n  }'); derive(CT, [modC], [modL], 'copy')
pkgC = pkgT.like('package <empty> {\n  ', modC, '\n}'); derive(CT, [pkgC], [pkgL], 'copy')

ROOTS = {TY: pkgT, INL: pkgT, PM: pkgT, ER: pkgE, LL: pkgL, CT: pkgC}

# ---- events (typer, unit 0) ---------------------------------------------------------------------------------------
evs, edges, stack = [], [], []
class Ev:
    def __init__(s, seq): s.seq = seq
    def __enter__(s): stack.append(s.seq); return s
    def __exit__(s, *a): stack.pop()
def ev(kind, nodes=(), syms=(), span=None, phase=TY, unit=0, **payload):
    seq = len(evs) + 1
    nodes = list(nodes)
    if span is None: span = nodes[0].span if nodes else (0, 0)
    evs.append(dict(t='ev', seq=seq, parent=stack[-1] if stack else None, kind=kind, phase=phase, unit=unit,
                    span=list(span), nodes=[n.id for n in nodes], syms=list(syms), payload=payload))
    return Ev(seq)
def edge(kind, a, b, seq): edges.append(dict(t='edge', kind=kind, **{'from': a, 'to': b}, seq=seq))
def cand(sym_key, ok, why, nodes=()):
    q = ev('Candidate', nodes=nodes, syms=[S[sym_key]], accepted=ok, why=why); return q
def resolve(node, key, tried, imports=(), frm=None, **kw):
    q = ev('Resolve', nodes=[node], syms=[S[key]], name=node.name, scopesTried=tried, importsConsidered=list(imports), **kw)
    if frm: edge('resolves', S[frm], S[key], q.seq)
    return q

with ev('Complete', syms=[S['Givens']], forcedBy=None, reason='compilation unit'):
    with ev('Complete', nodes=[renderDef], syms=[S['render']], forcedBy='Givens', reason='member signature') as q:
        edge('forces', S['Givens'], S['render'], q.seq)
        with ev('Typed', nodes=[renderDef], pt='?'):
            with ev('Typed', nodes=[plusR], pt='String'):
                with ev('Typed', nodes=[shown1], pt='Any'):
                    with ev('Typed', nodes=[listApp], pt='?'):
                        resolve(selList, 'listApply', ['Givens', '<empty>', 'scala.Predef', 'scala'], [], 'render', found='scala.List.apply')
                        ev('Adapt', nodes=[taList], step='insertTypeArgs', result='List.apply[Int]')
                    resolve(selShown, 'shown', ['Givens (member)', '<empty> (top-level extension)'], [], 'render', via='extension method')
                    with ev('Adapt', nodes=[shown1], step='applyImplicit', pt='Show[List[Int]]'):
                        with ev('ImplicitSearch', nodes=[shown1], pt='Show[List[Int]]', scope='Show companion + lexical') as qs:
                            cand('gInt', False, 'Show[Int] does not conform to Show[List[Int]]')
                            cand('gStr', False, 'Show[String] does not conform to Show[List[Int]]')
                            with cand('gList', True, 'Show[List[A]] unifies with Show[List[Int]], A := Int; needs using Show[Int]') as qc:
                                edge('implicit', S['render'], S['gList'], qc.seq)
                                with ev('Complete', syms=[S['gList']], forcedBy='render', reason='candidate signature') as qf:
                                    edge('forces', S['render'], S['gList'], qf.seq)
                                    ev('Complete', syms=[S['Show']], forcedBy='given_Show_List', reason='parent') and None
                                    edge('extends', S['gList'], S['Show'], evs[-1]['seq'])
                                with ev('ImplicitSearch', nodes=[gListApp], pt='Show[Int]', scope='nested search for using sa'):
                                    with cand('gInt', True, 'exact match Show[Int]') as qi:
                                        edge('implicit', S['gList'], S['gInt'], qi.seq)
                                        edge('extends', S['gInt'], S['Show'], qi.seq)
                                    cand('gStr', False, 'Show[String] does not conform to Show[Int]')
                with ev('Typed', nodes=[shown2], pt='Any'):
                    resolve(selShown2, 'shown', ['Givens (member)', '<empty> (top-level extension)'], [], 'render', via='extension method')
                    with ev('ImplicitSearch', nodes=[shown2], pt='Show[String]', scope='Show companion + lexical'):
                        cand('gInt', False, 'Show[Int] does not conform to Show[String]')
                        with cand('gStr', True, 'exact match Show[String]') as qs2:
                            edge('implicit', S['render'], S['gStr'], qs2.seq)
                            edge('extends', S['gStr'], S['Show'], qs2.seq)
                resolve(selPlus, 'plus', ['String members'], [], 'render', found='String.+')
    with ev('Complete', nodes=[viaDef], syms=[S['viaConversion']], forcedBy='Givens', reason='member signature') as q:
        edge('forces', S['Givens'], S['viaConversion'], q.seq)
        with ev('Typed', nodes=[viaDef], pt='?'):
            with ev('Typed', nodes=[plusV], pt='String'):
                with ev('Typed', nodes=[idI], pt='String'):
                    ev('Adapt', nodes=[idI], step='mismatch', found='Int', expected='String')
                    with ev('Adapt', nodes=[idI], step='insertConversion', pt='Conversion[Int, String]'):
                        with ev('ImplicitSearch', nodes=[idI], pt='Conversion[Int, String]', scope='lexical + Int/String companions'):
                            cand('gStr', False, 'Show[String] is not a Conversion')
                            with cand('gConv', True, 'given Conversion[Int, String] in package scope') as qc:
                                edge('implicit', S['viaConversion'], S['gConv'], qc.seq)
                        ev('Report', nodes=[convApp], severity='warning', msg='Use of implicit conversion given_Conversion_Int_String requires import scala.language.implicitConversions')
                resolve(selPlus2, 'plus', ['String members'], [], 'viaConversion', found='String.+')
    with ev('Complete', nodes=[pickDef], syms=[S['pickLocal']], forcedBy='Givens', reason='member signature') as q:
        edge('forces', S['Givens'], S['pickLocal'], q.seq)
        with ev('Typed', nodes=[pickDef], pt='?'):
            with ev('Typed', nodes=[blockT], pt='String'):
                with ev('Complete', nodes=[clsT], syms=[S['lcls']], forcedBy='pickLocal', reason='local class') as qc:
                    edge('forces', S['pickLocal'], S['lcls'], qc.seq)
                    ev('Complete', syms=[S['Show']], forcedBy='given_Show_Int', reason='parent')
                    edge('extends', S['lcls'], S['Show'], evs[-1]['seq'])
                ev('Typed', nodes=[valT], pt='?')
                with ev('Typed', nodes=[shown3], pt='String'):
                    resolve(selShown3, 'shown', ['Givens (member)', '<empty> (top-level extension)'], [], 'pickLocal', via='extension method')
                    with ev('ImplicitSearch', nodes=[shown3], pt='Show[Int]', scope='lexical (local givens) then Show companion'):
                        with cand('lval', True, 'local given in enclosing block wins on nesting level') as qa:
                            edge('implicit', S['pickLocal'], S['lval'], qa.seq)
                        cand('gInt', False, 'also eligible, but outranked: defined at lower nesting level than local given_Show_Int')
                        cand('gStr', False, 'Show[String] does not conform to Show[Int]')

# ---- synthetic project: 4 clusters x 9 symbols --------------------------------------------------------------------------
rng = random.Random(7)
CL = {
    'core': ['Symbol', 'Type', 'Context', 'Denotation', 'Names', 'Flags', 'Scope', 'Constant', 'Periods'],
    'parse': ['Scanner', 'Parser', 'Tokens', 'Trees', 'Positions', 'Source', 'Lexer', 'Desugar', 'Untpd'],
    'io': ['Files', 'Path', 'Reader', 'Writer', 'Cache', 'Config', 'Logger', 'Pickler', 'Classpath'],
    'ui': ['View', 'Page', 'Router', 'Store', 'Theme', 'Table', 'Chart', 'Cursor', 'Layout'],
}
pk = {}
for c in CL: pk[c] = sym('pkg_' + c, c, S['pkg'])
members = {}
idx = 100
for ci, (c, names) in enumerate(CL.items()):
    members[c] = []
    for n in names:
        owner = pk[c]
        if (c, n) == ('parse', 'Trees'): owner = pk['core']      # declared under core, used like parse
        if (c, n) == ('io', 'Pickler'): owner = pk['parse']      # declared under parse, used like io
        S[c + '.' + n] = idx; syms.append(dict(t='sym', id=idx, name=c + '.' + n, owner=owner, createdPhase=TY, createdBy=None))
        syminfo.append(dict(t='syminfo', sym=idx, fromPhase=TY, info='class ' + n, flags='class', owner=owner))
        members[c].append(idx); idx += 1
KINDS = ['resolves'] * 10 + ['forces'] * 5 + ['extends'] * 2 + ['implicit'] * 2 + ['inlined']
out = {}
def addedge(a, b):
    if a != b and (a, b) not in out and (b, a) not in out: out[(a, b)] = rng.choice(KINDS)
for c, ms in members.items():
    for a in ms:
        for b in rng.sample(ms, 3): addedge(a, b)
cross = [('parse', 'core'), ('parse', 'core'), ('parse', 'io'), ('io', 'core'), ('ui', 'io'), ('ui', 'core'), ('ui', 'parse'), ('parse', 'core')]
for ca, cb in cross: addedge(rng.choice(members[ca]), rng.choice(members[cb]))
adj = {}
for (a, b), k in out.items(): adj.setdefault(a, []).append((b, k))
unit_of = {m: ci + 1 for ci, c in enumerate(CL) for m in members[c]}
done = set()
def complete(s, forcedBy):
    done.add(s)
    with ev('Complete', syms=[s], unit=unit_of[s], forcedBy=forcedBy, reason='demanded'):
        for (d, k) in adj.get(s, []):
            if k == 'forces' and d in done: k = 'resolves'
            if k == 'resolves' or k == 'inlined':
                q = ev('Resolve', syms=[d], unit=unit_of[s], name=syms[d - 1 + 0]['name'] if False else str(d), via='inline' if k == 'inlined' else 'ident')
                edge(k, s, d, q.seq)
            elif k == 'forces':
                edge('forces', s, d, len(evs) + 1)
                complete(d, s)
            elif k == 'extends':
                q = ev('Complete', syms=[d], unit=unit_of[s], forcedBy=s, reason='parent'); edge('extends', s, d, q.seq)
                done.add(d)
            else:
                with ev('ImplicitSearch', syms=[], unit=unit_of[s], pt='T' + str(d)):
                    cand_other = rng.choice([m for m in unit_of if m != d])
                    q0 = ev('Candidate', syms=[cand_other], unit=unit_of[s], accepted=False, why='type does not conform')
                    q = ev('Candidate', syms=[d], unit=unit_of[s], accepted=True, why='most specific match')
                    edge('implicit', s, d, q.seq)
roots = list(unit_of); rng.shuffle(roots)
for r in roots:
    if r not in done: complete(r, None)
for p, name in PHASES[1:]:
    ev('PhaseBegin', phase=p, unit=0, name=name)

# fix up Resolve names for synthetic events
symname = {s['id']: s['name'] for s in syms}
for e in evs:
    if e['kind'] == 'Resolve' and e['unit'] > 0: e['payload']['name'] = symname[e['syms'][0]].split('.')[-1]

# ---- output --------------------------------------------------------------------------------------------------------------
lines = [dict(t='meta', compiler='3.8.0-RC1-bin-SNAPSHOT (fixture)', args=['-Xplugin:replay-record', '-Yno-parallel-pickling'],
              units=[dict(id=0, path='local/replay/corpus/Givens.scala')] + [dict(id=ci + 1, path='synthetic/%s/*.scala' % c) for ci, c in enumerate(CL)])]
for p, name in PHASES:
    lines.append(dict(t='phase', id=p, name=name, mini=(name not in ('typer', 'inlining')), megaOf=None if name in ('typer', 'inlining') else name + '-group'))
for tx, k in types.items(): lines.append(dict(t='type', id=k, text=tx))
for n in allnodes:
    lines.append(dict(t='node', id=n.id, kind=n.kind, span=list(n.span), unit=0, children=n.children, sym=n.sym, tpe=n.tpe, name=n.name))
for p, _ in PHASES:
    text, offs = version(ROOTS[p])
    lines.append(dict(t='version', phase=p, unit=0, root=ROOTS[p].id, text=text, offsets=offs))
lines += syms + syminfo + derives + evs + edges

with open(os.path.join(HERE, 'fixture.jsonl'), 'w') as f:
    for r in lines: f.write(json.dumps(r, separators=(',', ':')) + '\n')
txt = open(os.path.join(HERE, 'fixture.jsonl')).read()
with open(os.path.join(HERE, 'fixture.js'), 'w') as f:
    f.write('window.REPLAY_FIXTURE = ' + json.dumps(txt) + ';\n')
print(len(lines), 'records;', len(allnodes), 'nodes;', len(evs), 'events;', len(edges), 'edges;', len(syms), 'syms')
