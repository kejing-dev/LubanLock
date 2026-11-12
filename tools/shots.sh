#!/usr/bin/env bash
# QA state captures: tools/shots.sh OUTDIR LANG STATE... (each: goto the state through the debug broadcast, wait, capture)
set -u
OUT="$1"; LANG_="$2"; shift 2
K="$(dirname "$0")/ll.sh"
mkdir -p "$OUT"
"$K" cmd lang "$LANG_"; "$K" cmd welcome
for S in "$@"; do
  "$K" cmd hold off; "$K" cmd stop; "$K" cmd no; "$K" cmd hint off; "$K" cmd explode off; "$K" cmd page play
  case "$S" in
    S14) "$K" cmd page settings ;;
    S15) "$K" cmd page rules ;;
    S16exit) "$K" cmd goto "S16 exit" ;;
    S16best) "$K" cmd goto "S16 best" ;;
    S16lock) "$K" cmd goto "S16 lock" ;;
    S16) "$K" cmd goto "S16 reset" ;;
    S02-*) "$K" cmd lock "${S#S02-}" ;;
    M1-*) "$K" cmd lock "${S#M1-}"; sleep 2; "$K" cmd hint on ;;
    M3-*) "$K" cmd lock "${S#M3-}"; sleep 2; "$K" cmd explode on ;;
    S11M1) "$K" cmd goto S11; sleep 12; "$K" cmd hint on ;;
    S11wrong) "$K" cmd goto S11; sleep 12; "$K" cmd tap 1 ;;
    *) "$K" cmd goto "$S" ;;
  esac
  sleep "${WAIT:-9}"
  case "$S" in S13) sleep 14 ;; S10|S11|S12) sleep 3 ;; esac
  for try in 1 2 3; do "$K" shot "$OUT/$S-$LANG_.png" 2>/dev/null && [ -s "$OUT/$S-$LANG_.png" ] && break; sleep 3; done
  echo "$S $LANG_"
done
