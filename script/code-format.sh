#!/usr/bin/env bash

set -eou pipefail

COMMAND=check
if [ $# -eq 1 ]; then
    COMMAND=$1
fi

case $COMMAND in
    check|fix)
        echo "--[${COMMAND}ing code]--"
        clojure -M:code-format "$COMMAND" src test modules;;
    *)
      echo "usage $0 command"
      echo ""
      echo "where command can be:"
      echo "check - reports on code formatting violations (default)"
      echo "fix   - fixes code formatting violations"
      exit 1;;
esac
