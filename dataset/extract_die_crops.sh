#!/bin/sh
# Turn labeled phone photos into die crops for a later face classifier.
#
# Source of truth (one pair per photo):
#   android/app/src/test/resources/real/<sha256>.jpg
#   android/app/src/test/resources/real/<sha256>.label
#     face=4
#     dice=12
#
# Output:
#   dataset/face_N/<sha12>_<index>.png   crop of die index, top-to-bottom then left-to-right
#   dataset/labels.csv                    path,face,source,x,y,w,h,read
#
# `face` is the photo label. `read` is what the detector counted on that box.
# A photo is skipped as a failure when the detector's die count disagrees with
# `dice`, so a missed die is not silently labeled.
#
# Drop the next labeled photo in real/ with a sibling .label and run this again.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
for n in 1 2 3 4 5 6; do
  mkdir -p "$root/dataset/face_$n"
done
cd "$root/android"
export DICE_DATASET="$root/dataset"
exec ./gradlew :app:testDebugUnitTest --offline --tests com.srrtracker.RealDicePhotoTest.extractsLabeledDieCrops
