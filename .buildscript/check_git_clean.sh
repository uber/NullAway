#!/bin/sh -eux

if [ -n "$(git status --porcelain)" ]; then
  echo 'warning: source tree contains uncommitted changes; .gitignore patterns may need to be fixed'
  git status
  false
fi
