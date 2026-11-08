# Vendored CXR-Link SDK artifacts

The CXR-Link SDK AARs that `:phone-app:link:transport-rokid` and `:glasses-app:app` compile against are
tracked in this directory because the build pins their exact bytes:

| File | SHA-256 |
| --- | --- |
| `vendor/client-l-1.1.2.aar` | `4E79963FF5AD84E018783C74A17C6BEDBEBD3C6076BAD3C00BCE15250A4DAF25` |
| `vendor/cxr-service-bridge-1.0-20260715.121510-107.aar` | `0A76387E106A51578F82169AEB2DA904372A9569B26AF1E6C87CEBE7A76B94FC` |

`settings.gradle.kts` declares the `edith.vendor.cxr` flatDir repository over this directory, and
`phone-app/link/transport-rokid/build.gradle.kts` re-checks the same digests at configuration time, so a
tampered or truncated artifact fails the build instead of reaching an APK.

The SDK is supplied by Rokid for the CXR-Link integration and remains under Rokid's own terms; the
project's Apache-2.0 license covers EDITH's source only.
