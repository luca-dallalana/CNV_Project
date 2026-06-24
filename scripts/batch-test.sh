#!/bin/bash
set -e

LB="${LB:-localhost}"
BASE="http://$LB:${PORT:-8000}"
REPS=3
WDIR="${WORKLOADS_DIR:-$(dirname "$0")/../workloads}"

if [ ! -d "$WDIR" ]; then
    echo "ERROR: workloads directory not found: $WDIR"
    echo "Set WORKLOADS_DIR to the directory containing the .fasta files."
    exit 1
fi

SEQ_SARS="sars-10k:$(cat $WDIR/sars-10k.fasta)"
SEQ_HUMAN="human-mc-10k:$(cat $WDIR/human-mc-10k.fasta)"
SEQ_KLEBSIELLA="klebsiella-20k:$(cat $WDIR/genome-klebsiella-pneumoniae-20k.fasta)"
SEQ_SALMONELLA_20="salmonella-20k:$(cat $WDIR/genome-salmonella-enterica-20k.fasta)"
SEQ_ECOLI="escherichia-coli-25k:$(cat $WDIR/genome-escherichia-coli-25k.fasta.txt)"
SEQ_SALMONELLA_25="salmonella-25k:$(cat $WDIR/genome-salmonella-enterica-25k.fasta)"
SEQ_HBB_HUMAN="human_HBB:ATGGTGCATCTGACTCCTGAGGAGAAGTCTGCCGTTACTGCCCTGTGGGGCAAGGTGAACGTGGATGAAGTTGGTGGTGAGGCCCTGGGCAGGCTGCTGGTGGTCTACCCTTGGACCCAGAGGTTCTTTGAGTCCTTTGGGGATCTGTCCACTCCTGATGCTGTTATGGGCAACCCTAAGGTGAAGGCTCATGGCAAGAAAGTGCTCGGTGCCTTTAGTGATGGCCTGGCTCACCTGGACAACCTCAAGGGCACCTTTGCCACACTGAGTGAGCTGCACTGTGACAAGCTGCACGTGGATCCTGAGAACTTCAGGCTCCTGGGCAACGTGCTGGTCTGTGTGCTGGCCCATCACTTTGGCAAAGAATTCACCCCACCAGTGCAGGCTGCCTATCAGAAAGTGGTGGCTGGTGTGGCTAATGCCCTGGCCCACAAGTATCACTAA"
SEQ_HBB_CHIMP="chimpanzee_HBB:ATGGTGCACCTGACTCCTGAGGAGAAGTCTGCCGTTACTGCCCTGTGGGGCAAGGTGAACGTGGATGAAGTTGGTGGTGAGGCCCTGGGCAGGCTGCTGGTGGTCTACCCTTGGACCCAGAGGTTCTTTGAGTCCTTTGGGGATCTGTCCACTCCTGATGCTGTTATGGGCAACCCTAAGGTGAAGGCTCATGGCAAGAAAGTGCTCGGTGCCTTTAGTGATGGCCTGGCTCACCTGGACAACCTCAAGGGCACCTTTGCCACACTGAGTGAGCTGCACTGTGACAAGCTGCACGTGGATCCTGAGAACTTCAGGCTCCTGGGCAACGTGCTGGTCTGTGTGCTGGCCCATCACTTTGGCAAAGAATTCACCCCACCAGTGCAGGCTGCCTATCAGAAAGTGGTGGCTGGTGTGGCTAATGCCCTGGCCCACAAGTATCACTAA"

run() {
    local label=$1; shift
    for i in $(seq 1 $REPS); do
        printf "[%s %d/%d] " "$label" "$i" "$REPS"
        curl -s -o /dev/null -w "%{time_total}s\n" "$@"
    done
}

run "fractals-XS" "$BASE/fractals?w=800&h=600&iterations=100"
run "fractals-S"  "$BASE/fractals?w=4000&h=2000&iterations=10"
run "fractals-M"  "$BASE/fractals?w=4000&h=2000&iterations=1000"
run "fractals-L"  "$BASE/fractals?w=6000&h=6000&iterations=100000"

run "grayscott-XS" "$BASE/grayscott?size=256&maxIterations=100&f=0.030&k=0.062&stopOnExtinction=true&seedMode=center"
run "grayscott-S"  "$BASE/grayscott?size=1024&maxIterations=10000&f=0.230&k=0.062&stopOnExtinction=true&seedMode=ring"
run "grayscott-M"  "$BASE/grayscott?size=256&maxIterations=1000&f=0.030&k=0.062&stopOnExtinction=false&seedMode=stripe"
run "grayscott-L"  "$BASE/grayscott?size=256&maxIterations=2500&f=0.030&k=0.062&stopOnExtinction=false&seedMode=stripe"
run "grayscott-XL" "$BASE/grayscott?size=512&maxIterations=5000&f=0.030&k=0.062&stopOnExtinction=false&seedMode=center"

run "dna-XS" "$BASE/dna?minLength=250&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ_HBB_HUMAN"    --data-urlencode "seq2=$SEQ_HBB_CHIMP"
run "dna-S"  "$BASE/dna?minLength=200&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ_SARS"         --data-urlencode "seq2=$SEQ_HUMAN"
run "dna-M"  "$BASE/dna?minLength=500&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ_SARS"         --data-urlencode "seq2=$SEQ_HUMAN"
run "dna-L"  "$BASE/dna?minLength=200&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ_KLEBSIELLA"   --data-urlencode "seq2=$SEQ_SALMONELLA_20"
run "dna-XL" "$BASE/dna?minLength=250&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ_ECOLI"        --data-urlencode "seq2=$SEQ_SALMONELLA_25"
