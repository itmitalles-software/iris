# Security policy

## Supported versions

Only the latest release of Apollo gets security fixes. Update to the
[latest release](https://github.com/wutz-io/apollo-hermes-android/releases/latest)
before you report a problem.

## Reporting a vulnerability

Please report vulnerabilities privately through
[GitHub private vulnerability reporting](https://github.com/wutz-io/apollo-hermes-android/security/advisories/new).
Do not open a public issue for them.

Include the Apollo version and build (Settings → About → Version and Build),
the Android version, and the steps to reproduce. Remove host names (including
tailnet names such as `<machine>.<tailnet>.ts.net`), tokens, cookies, passwords
and certificates from logs and screenshots.

You get an answer as soon as possible. Fixes ship as a new release, and the
advisory is published once the release is out.

## Scope

In scope: the Apollo Android app, its build and release workflows, and the
server setup script `scripts/server/setup-hermes-for-apollo.sh`.

Problems in Hermes Agent itself belong to the
[Hermes Agent project](https://github.com/NousResearch/hermes-agent).
