#!/bin/bash
# usage: par.sh [maxDim]
for c in white colored mixed large small touching hard; do
  (V=1 node run_detect.js ${1:-640} $c > out_$c.txt 2>&1) &
done
wait
cat out_*.txt | grep -E "^(white|colored|mixed|large|small|touching|hard|avg)" 
