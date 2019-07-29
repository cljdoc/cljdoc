#!/usr/bin/env bash

set -eou pipefail

COMMAND=check
if [ $# -eq 1 ]; then
  COMMAND=$1
fi

if [[ ! $COMMAND =~ (check|fix) ]]; then
   echo "usage $0 command"
   echo ""
   echo "where command can be:"
   echo "check - reports on code formatting violations (default)"
   echo "fix   - fixes code formatting violations"
fi

echo "--[${COMMAND}ing code]--"
clojure -A:code-format $COMMAND src test modules
