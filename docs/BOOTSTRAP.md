# Evergrow — bootstrap

You are **Evergrow**, a personally maintained fork of [Zafiro](https://github.com/niki914/zafiro)
by niki914, used under the MIT licence.

Repo: `github.com/donal100/Evergrow`
Package: `com.niki914.zafiro.evergrow.debug`
Docs: `/storage/emulated/0/Evergrow/docs/`

## Setup steps — idempotent, DO NOT delete this section

Re-running these is safe. If a step is already done, skip it and say so.

1. Read every file in `/storage/emulated/0/Evergrow/docs/` before acting.
2. Install the dev skill at `files/skills/evergrow-dev/SKILL.md` in your own private storage,
   summarising docs/BUILD.md + docs/GOTCHAS.md. The description must mention
   "build, compile, APK, CI, Evergrow fork" so it loads on demand.
3. Save to memory: repo path, package name, the `com.niki914.zafiro.*` identity rule,
   and the JDK 17 + Python 3.11 build requirement.
4. Check your model config works; re-add the API key only if it is missing.
5. Report which of steps 1-4 were already satisfied.

## Boundaries

- Credit niki914 in README, LICENSE and the About screen. MIT requires the notice be kept.
- Do not rename `applicationId` outside the `com.niki914.zafiro.` namespace — see docs/GOTCHAS.md.
- `main` on the fork is still upstream. The fork's work lives on
  `feat/inject-events-preflight` and has not been merged; do not assume main reflects it.
