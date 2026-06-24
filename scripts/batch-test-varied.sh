#!/bin/bash
set -e

WORKER="${WORKER:-localhost}"
BASE="http://$WORKER:${PORT:-8000}"
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

run1() {
    local label=$1; shift
    printf "[%s] " "$label"
    curl -s -o /dev/null -w "%{time_total}s\n" --max-time 600 "$@"
}

echo "=== Fractals XS variants (driver ~15-31M) ==="
run1 "fractals-XS-v2" "$BASE/fractals?w=640&h=480&iterations=100"
run1 "fractals-XS-v3" "$BASE/fractals?w=1024&h=768&iterations=60"
run1 "fractals-XS-v4" "$BASE/fractals?w=800&h=600&iterations=80"

echo "=== Fractals S variants (driver ~30-50M) ==="
run1 "fractals-S-v2" "$BASE/fractals?w=3000&h=2000&iterations=15"
run1 "fractals-S-v3" "$BASE/fractals?w=2000&h=2000&iterations=20"
run1 "fractals-S-v4" "$BASE/fractals?w=4000&h=3000&iterations=8"

echo "=== Fractals M variants (driver ~3.6-5B) ==="
run1 "fractals-M-v2" "$BASE/fractals?w=3000&h=2000&iterations=1200"
run1 "fractals-M-v3" "$BASE/fractals?w=4000&h=2500&iterations=800"
run1 "fractals-M-v4" "$BASE/fractals?w=5000&h=2000&iterations=700"

echo "=== Fractals L variants (driver ~1.2-1.8T) ==="
run1 "fractals-L-v2" "$BASE/fractals?w=5000&h=5000&iterations=100000"
run1 "fractals-L-v3" "$BASE/fractals?w=4000&h=6000&iterations=100000"
run1 "fractals-L-v4" "$BASE/fractals?w=6000&h=4000&iterations=100000"

echo "=== Grayscott XS variants (driver ~4-8M, early) ==="
run1 "grayscott-XS-v2" "$BASE/grayscott?size=200&maxIterations=150&f=0.030&k=0.062&stopOnExtinction=true&seedMode=center"
run1 "grayscott-XS-v3" "$BASE/grayscott?size=300&maxIterations=75&f=0.035&k=0.060&stopOnExtinction=true&seedMode=center"

echo "=== Grayscott M variants (driver ~65-98M, full) ==="
run1 "grayscott-M-v2" "$BASE/grayscott?size=256&maxIterations=1500&f=0.030&k=0.062&stopOnExtinction=false&seedMode=stripe"
run1 "grayscott-M-v3" "$BASE/grayscott?size=300&maxIterations=900&f=0.030&k=0.062&stopOnExtinction=false&seedMode=center"

echo "=== Grayscott L variants (driver ~160-245M, full) ==="
run1 "grayscott-L-v2" "$BASE/grayscott?size=256&maxIterations=3000&f=0.030&k=0.062&stopOnExtinction=false&seedMode=stripe"
run1 "grayscott-L-v3" "$BASE/grayscott?size=320&maxIterations=2000&f=0.030&k=0.062&stopOnExtinction=false&seedMode=center"

echo "=== Grayscott XL variants (driver ~1.26-1.57B, full) ==="
run1 "grayscott-XL-v2" "$BASE/grayscott?size=512&maxIterations=6000&f=0.030&k=0.062&stopOnExtinction=false&seedMode=center"
run1 "grayscott-XL-v3" "$BASE/grayscott?size=600&maxIterations=3500&f=0.030&k=0.062&stopOnExtinction=false&seedMode=stripe"

echo "=== Grayscott S variants (driver ~9.6-12.6B, early) ==="
run1 "grayscott-S-v2" "$BASE/grayscott?size=1024&maxIterations=12000&f=0.230&k=0.062&stopOnExtinction=true&seedMode=ring"
run1 "grayscott-S-v3" "$BASE/grayscott?size=800&maxIterations=15000&f=0.230&k=0.062&stopOnExtinction=true&seedMode=ring"

echo "=== DNA XS variants (HBB seqs, minLength 150/350) ==="
run1 "dna-XS-v2" "$BASE/dna?minLength=150&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ_HBB_HUMAN" --data-urlencode "seq2=$SEQ_HBB_CHIMP"
run1 "dna-XS-v3" "$BASE/dna?minLength=350&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ_HBB_HUMAN" --data-urlencode "seq2=$SEQ_HBB_CHIMP"

echo "=== DNA S variants (SARS x human, minLength 150/300) ==="
run1 "dna-S-v2" "$BASE/dna?minLength=150&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ_SARS" --data-urlencode "seq2=$SEQ_HUMAN"
run1 "dna-S-v3" "$BASE/dna?minLength=300&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ_SARS" --data-urlencode "seq2=$SEQ_HUMAN"

echo "=== DNA M variants (SARS x human, minLength 400/600) ==="
run1 "dna-M-v2" "$BASE/dna?minLength=400&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ_SARS" --data-urlencode "seq2=$SEQ_HUMAN"
run1 "dna-M-v3" "$BASE/dna?minLength=600&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ_SARS" --data-urlencode "seq2=$SEQ_HUMAN"

echo "=== DNA L variants (Kleb x Salm, minLength 150/300) ==="
run1 "dna-L-v2" "$BASE/dna?minLength=150&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ_KLEBSIELLA" --data-urlencode "seq2=$SEQ_SALMONELLA_20"
run1 "dna-L-v3" "$BASE/dna?minLength=300&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ_KLEBSIELLA" --data-urlencode "seq2=$SEQ_SALMONELLA_20"

echo "=== DNA XL variants (E.coli x Salm, minLength 200/300) ==="
run1 "dna-XL-v2" "$BASE/dna?minLength=200&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ_ECOLI" --data-urlencode "seq2=$SEQ_SALMONELLA_25"
run1 "dna-XL-v3" "$BASE/dna?minLength=300&stopOnFirst=False" -G --data-urlencode "seq1=$SEQ_ECOLI" --data-urlencode "seq2=$SEQ_SALMONELLA_25"

echo "Done."
