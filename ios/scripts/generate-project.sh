#!/bin/bash
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ios_directory="$(cd -- "$script_directory/.." && pwd)"
cd -- "$ios_directory"

xcodegen generate --spec project.yml
resolved_destination="Cadence.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"
if [[ -f Package.resolved ]]; then
    mkdir -p -- "$(dirname -- "$resolved_destination")"
    cp -- Package.resolved "$resolved_destination"
else
    # Do not retain a stale lock file from an earlier generated project.
    rm -f -- "$resolved_destination"
fi
