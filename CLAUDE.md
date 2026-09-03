# Repository Guardrails & Build Instructions

## Core Preservation (STRICT)
- Never remove, refactor, or alter the custom Media3 engine, native HDR/Dolby Vision pipeline, or subtitle translation modules.
- Do not modify version catalogs (`gradle/libs.versions.toml`) or `build.gradle.kts` dependencies without explicit instructions.

## Operational Constraints
- Do not write throwaway Python scripts to parse the repository or find symbol trees.
- Do not query GitHub code search via `gh search` or web search. Search git history locally: `git log --all --full-history -- <filename>`.
- Use native file tools (`Read`, `Edit`) rather than complex bash shell pipelines.
- Resolve compiler errors by making targeted, atomic edits.
