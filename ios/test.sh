#!/bin/sh
# Build the package and run its unit tests on this Mac.
# With Xcode: plain `swift test`. With only the Command Line Tools, Swift
# Testing is not on the default search paths, so this script adds them.
set -e
cd "$(dirname "$0")"
if xcodebuild -version >/dev/null 2>&1; then
  exec swift test "$@"
fi
CLT=/Library/Developer/CommandLineTools
F=$CLT/Library/Developer/Frameworks
L=$CLT/Library/Developer/usr/lib
exec swift test -Xswiftc -F$F -Xswiftc -plugin-path -Xswiftc $CLT/usr/lib/swift/host/plugins/testing \
  -Xlinker -F$F -Xlinker -rpath -Xlinker $F -Xlinker -rpath -Xlinker $L "$@"
