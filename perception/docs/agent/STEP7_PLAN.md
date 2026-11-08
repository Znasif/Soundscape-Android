# Step 7 Plan: Honesty Audit, Field Test Plan, and Executive Summary

## 1. Objectives
- **Step 7a (Honesty & Portability Audit)**:
  - Audit all tests in `perception/src/test` to ensure zero circular or tautological assertions.
  - Add dated Phase 2 audit notes (`[2026-11-08 Phase 2 Audit Note (Step 7a)]`) to `STEP3_REPORT.md` and `STEP4_REPORT.md` clarifying synthetic/single-block scope vs Step 5 (10 outdoor areas) and Step 6 (6 Apple Hypersim rooms) benchmarks.
  - Update `perception/README.md` with complete CLI commands, layout, and evaluation artifacts through Step 7.
  - Ensure every `.json` manifest in `perception/samples/` includes a non-blank `attribution` field and parses cleanly via `SimEnvironment.fromJson`.
  - Verify zero `java.*` or `javax.*` imports in portable packages (`sim/model`, `sim/geo`, `sim/eval`, `sim/builders`, `sim/sources`), zero committed images/HDF5 files, and zero compiler warnings.
- **Step 7b (Field Test Plan for 2318 Fillmore Street)**:
  - Write `perception/docs/agent/FIELD_TEST_PLAN.md` detailing the step-by-step outdoor & indoor field verification protocol at **2318 Fillmore Street, San Francisco, CA** using `fillmore-2318-route.gpx` and `fillmore-2318-landmarks.gpx`.
  - Define a concrete field verification JSON schema and provide a complete example in `perception/field/fillmore-2026-11-08-example.json`.
  - Add automated unit tests in `Step7AuditAndFieldPlanTest.kt` verifying the field JSON schema and all Step 7a audit invariants.
- **Step 7c (One-Page Executive Summary & Final Report)**:
  - Write `perception/docs/agent/SUMMARY.md` summarizing Steps 0–7, validated vs unvalidated capabilities, cumulative `budget-ledger.json` totals, and the next 3 engineering tasks.
  - Write `perception/docs/agent/STEP7_REPORT.md`, update `PROGRESS.md`, push `argon/step-7`, and open Draft PR #8 (`argon/step-7` -> `argon/step-6`).
