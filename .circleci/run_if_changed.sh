#!/bin/bash

 dir=$1
 testCommand=$2
 branch=$(git rev-parse --abbrev-ref HEAD)

 if git diff --name-only origin/master...$branch  | grep "^${dir}" ; then
   echo "$dir HAS BEEN MODIFIED"
   eval $testCommand
 else
   echo "NO"
 fi
