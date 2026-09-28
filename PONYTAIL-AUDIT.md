# Ponytail Audit — Dex-Editor-Android

**Date:** 2026-09-27  
**Scope:** Production Java sources, unit/instrumentation tests, Android manifest/resources, and Gradle dependencies/configuration. This is an over-engineering and simplification audit; correctness, security, and performance findings are outside the Ponytail-audit scope. User-approved cuts were applied; ProGuard configuration was retained by request.

## Ranked cuts

- `delete:` Removed the unreferenced `app/src/main/java/com/custom/` compatibility package (14 files, 1,247 lines); no source outside that package called it, so no replacement was needed. [app/src/main/java/com/custom/]
- `delete:` Removed `ArgumentParser` (103 lines, no callers) and `SketchApplication` (39 lines, neither registered in the manifest nor referenced); no replacement was needed. [app/src/main/java/modder/hub/dexeditor/GraphDot/ArgumentParser.java, app/src/main/java/modder/hub/dexeditor/SketchApplication.java]
- `keep:` Retain the currently inactive ProGuard rules and `proguardFiles` declaration for future release shrinking, as requested. [app/build.gradle:27-28, app/proguard-rules.pro]
- `delete:` Dropped the unused Animatoo and PhotoView direct dependencies; no Java, XML, or resource references were found. [app/build.gradle]

net: -1391 lines, -2 deps possible.
