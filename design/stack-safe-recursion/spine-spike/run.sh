#!/bin/bash
# usage: run.sh <label> [extra scalac opts...]
# Compiles each generated stress input with a 2 MiB stack and reports ok / FUEL (RecursionOverflow) / SOE (stack overflow).
# Needs CP (from `sbt "export scala3-compiler-nonbootstrapped/Runtime/fullClasspath"`) and J (a JDK home).
S=${S:-$(mktemp -d)}
J=${J:-$JAVA_HOME}
cd "$(dirname "$0")"
python3 gen.py "$S"
label=$1; shift
out=$S/out; mkdir -p $out; rm -f $S/why-$label.txt
for kind in left cons ladder sel; do
  line="$label $kind:"
  for n in 100 200 400 1000 3000 10000; do
    log=$S/log-$label-$kind$n.txt
    $J/bin/java -Xss2m -cp "$CP" dotty.tools.dotc.Main -usejavacp -d $out -color:never "$@" $S/$kind$n.scala > $log 2>&1
    if [ $? -eq 0 ]; then r=ok
    elif grep -q "Recursion limit exceeded" $log; then r=FUEL
    elif grep -qi "stack overflow" $log; then r=SOE
    else r=ERR; fi
    if [ $r != ok ]; then
      why=$(grep -A1 "A recurring operation is" $log | tail -1 | sed 's/^ *//' | cut -c1-40)
      [ -z "$why" ] && why=$(grep -m200 "^\s*at " $log | sed 's/(.*//; s/^\s*at //' | sort | uniq -c | sort -rn | head -1 | awk '{print $2}' | sed 's/.*\.\([^.]*\.[^.]*\)$/\1/')
      [ -z "$why" ] && why=$(grep -m1 -i "error\|exception" $log | cut -c1-60)
      echo "    $kind$n $r: $why" >> $S/why-$label.txt
    fi
    line="$line $n=$r"
  done
  echo "$line"
done
cat $S/why-$label.txt 2>/dev/null
