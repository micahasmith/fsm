# FSM: Clojure-based Finite State Machine & Pipeline Orchestration Framework

A highly modular, thread-safe, and deterministic Finite State Machine (FSM) and data-pipeline orchestration framework written in Clojure. It features an append-only transaction journal, asynchronous scheduling, CLI/Subprocess-based task executors (e.g., executing R scripts), and native Clojure function evaluation.

---

## AI/LLM Co-Processing & Deterministic Metacognition

When paired with an AI or Large Language Model (LLM) agent, this framework transitions from a standard workflow orchestrator into a deterministic sandbox and structured runtime co-processor. At its heart is a design where **every step, execution call, input/output, and transition is forced to serialize as static data directly to disk** (via transaction logs and filesystem-backed run folders).

This architecture unlocks four core capabilities for LLM-driven orchestration:

1. **Deterministic Execution Chains**: LLMs can safely orchestrate long, complex, and nested execution graphs because the FSM acts as a strict, immutable guardrail. By forcing step-by-step verification, it entirely eliminates runtime state drift and ensures agent behaviors conform precisely to the defined transitions.
2. **Meta-Cognitive Feedback Loop**: Because all state, execution logs, and output artifacts are fully materialized on disk, the LLM can introspect, parse, and reason about past runs retrospectively (metacognition). It can inspect what occurred at any step $N$, diagnose issues from raw terminal outputs, and dynamically replan subsequent transitions based on empirical ground truth.
3. **Memoization and Caching**: Complete serialization allows automatic caching and memoization of execution steps. If a run with identical inputs is requested, the system can bypass execution entirely, dramatically reducing both LLM token overhead and compute latency.
4. **Late-Binding & Generalization**: It enables generalizing over data options at a later stage. By treating execution histories as queryable, structured datasets, developers or downstream LLM chains can combine, synthesize, or retroactively extract execution paths across multiple historical runs.

---

## Table of Contents

- [Overview](#overview)
- [AI/LLM Co-Processing & Deterministic Metacognition](#aillm-co-processing--deterministic-metacognition)
- [Architecture & Module Reference](#architecture--module-reference)
- [Core Concepts & DSL](#core-concepts--dsl)
  - [1. Scopes](#1-scopes)
  - [2. Contexts & Handlers](#2-contexts--handlers)
  - [3. Submitting Runs](#3-submitting-runs)
- [Request/Response and Context Safety](#requestresponse-and-context-safety)
- [The Transaction Journal: Format, Mechanics, and Story](#the-transaction-journal-format-mechanics-and-story)
- [Hierarchical Scopes: Parent/Child Lineage & Context Inheritance](#hierarchical-scopes-parentchild-lineage--context-inheritance)
- [Package Extensions](#package-extensions)
- [Current Status and Next Milestone](#current-status-and-next-milestone)
- [Getting Started & Usage](#getting-started--usage)
- [Testing & Validation](#testing--validation)

---

## Overview

The FSM framework is designed to define, execute, and monitor stateful processing workflows (scopes) and transition systems. It guarantees:
1. **Determinism & Auditability**: Every operation, declaration, or execution step is recorded in an append-only `journal.clj` transaction log. State can be completely rehydrated by replaying this log.
2. **Thread Safety**: State transitions and executions are managed sequentially through a thread-safe Clojure Agent scheduler.
3. **Multi-runtime Support**: Seamlessly executes pure Clojure functions (`:fn`), external CLI commands/scripts (`:cli`, such as R or Python), and topological rendering templates (`:topological`).

---

## Architecture & Module Reference

The codebase is organized into highly focused namespaces under `src/fsm/`:

```
src/fsm/
├── core.clj           # Main application entry point
├── app.clj            # CLI interface, argument parser, and REPL loop
├── dsl.clj            # Macro definitions for FSM declarations
├── engine.clj         # Core FSM state transition rules, guards, and hit tracking
├── executor.clj       # Task runners (CLI processes, Clojure fns, Topological)
├── filesystem.clj     # File/directory utilities and run rehydration
├── forms.clj          # DSL structure normalization and canonicalization
├── global_state.clj   # High-level system-wide state getters & resetting
├── journal.clj        # Journal ingestion, append-only persistence, and rehydration
├── logging.clj        # Timber logging and system instrument configuration
├── messaging.clj      # Asynchronous communication facade (integrates with manifold)
├── runtime.clj        # Invocation formatting, active ledger entries, and runtime state
├── scheduler.clj      # Central Clojure Agent-based sequential queue processor
├── state.clj          # In-memory atoms, default directory/filename settings
└── util.clj           # Helper validations (keyword, ref, schema, timestamp check)
```

### Module Breakdown

*   **`fsm.core`**: Starts the application, parses command line parameters, and invokes the running app.
*   **`fsm.app`**: Houses the FSM CLI interface, argument parser, and interactive REPL mode (`make cmd-repl`).
*   **`fsm.dsl`**: Extends Clojure with an elegant domain-specific language (`define-scope`, `define-context`, `define-handler`, `request`, `select`, `run!`) used to declare state machines and request transitions.
*   **`fsm.engine`**: The operational "brain" of the FSM. Resolves handlers, verifies pre-execution and post-execution `guards`, keeps track of state machine transition rules, and calculates path hits.
*   **`fsm.executor`**: Handles execution of actions specified by scopes. It spawns, streams, and parses standard input/output/error for CLI scripts, invokes native Clojure functions, and handles topological renders.
*   **`fsm.journal`**: Acts as the transaction coordinator. Ingests FSM DSL forms, appends them to the local journal log file, and modifies the system's in-memory status representation.
*   **`fsm.scheduler`**: Coordinates all asynchronous and synchronous flow. It uses a single Clojure Agent `scheduler*` to deserialize inboxes, trigger work, write run artifacts, and update ledgers safely.
*   **`fsm.state`**: Houses the foundational `system*` atom which keeps track of active scope runs, registered handlers, choices, and state machine transitions.
*   **`fsm.messaging`**: Integrates with `manifold` streams/deferreds to establish a robust, non-blocking asynchronous event and inbox communication facade.

---

## Core Concepts & DSL

### 1. Scopes
A **Scope** is an isolated unit of execution (or pipeline step) configured with:
*   `input`/`output` specifications (schemas and file types like `:json`, `:csv`, `:edn`).
*   `guards` (validation functions, e.g. checking file existence or filename suffixes).
*   `template` (the action, such as a Clojure function or an R/Python script).
*   `emit` (the transition event generated upon success).

#### Defining a Scope
```clojure
(define-scope :matrix/profile
  :tags #{:matrix :profile}
  :doc "Profile a CSV matrix artifact and produce a summary."
  :template {:type :cli
             :cli/cmd ["/usr/bin/Rscript" "scripts/profile_matrix.R"]
             :env/FSM_ARTIFACT_PATH :request/artifact
             :env/FSM_OUTPUT_PATH :scope/output}
  :input {:type :file :schema :matrix.schema/csv-input}
  :output {:type :json :schema :matrix.schema/matrix-summary}
  :guards [[:file-exists :artifact] [:path-suffix :artifact ".csv"]]
  :emit :matrix/summary-produced)
```

### 2. Contexts & Handlers
*   **Contexts**: Logical grouping or environment in which Handlers and Scopes execute.
*   **Handlers**: Registered event handlers that listen to events emitted by scopes and orchestrate downstream choices or transition paths.

### 3. Submitting Runs
You run state machines using the `run!` macro:
```clojure
(run! :matrix/profile {:artifact "data/toy.csv"})
```

---

## Request/Response and Context Safety

FSM is designed as a conversational interpreter, not a mutable workflow graph. A caller appends a declaration, request, run, or selection; the runtime derives and appends the corresponding response. This keeps both intent and the system's interpretation of that intent replayable.

The primary flows are:

* **Definitions** declare scopes, contexts, handlers, guards, imports, and permitted choices.
* **Requests** assert an event and its payload. **Runs** invoke an executable scope directly.
* **Responses** record guard results, implications, emitted events, accepted transitions, or a required choice.
* **Selections** resolve a pending choice explicitly; invalid selections are recorded as rejected responses rather than silently changed.

Contexts are first-class safety boundaries. Names are context-local, and cross-context information must be represented as an explicit typed pull. The runtime records a derived immutable context frame for every handler evaluation, including the pulls used, path hits, score breakdown, and total score.

Supported pull types are `:file`, `:global-immutable`, `:process`, `:derived`, `:external`, and `:historical`. Their cost, symbol count, fan-in, path-hit history, and branch ambiguity contribute to the context score. A score above the scope budget blocks automatic progression; multiple valid choices also require an explicit `select` form. This makes the reason a transition was accepted, blocked, or deferred visible in the journal.

---

## The Transaction Journal: Format, Mechanics, and Story

### Format and Mechanics
The FSM framework enforces a strict transaction journal pattern to guarantee complete transparency and absolute execution determinism. The journal is maintained as a plain-text, append-only ledger (located at `resources/scope_runs/root.journal.clj`). 

Every operational step, declaration, invocation, and transition is serialized as a single, valid Clojure S-expression / EDN (Extensible Data Notation) form on its own line. These forms capture:
- **Definitions**: `define-scope`, `define-context`, and `define-handler` forms that build the system topology.
- **Invocations & Requests**: `request` and `run!` forms that trigger transitions.
- **Intermediary and Runtime State**: `context-frame`, `response`, and `select` forms reflecting runtime decisions and execution results.

To append a transaction, the engine serializes canonical Clojure forms to the disk using `pr-str`, ensuring each record is a single line. Loading or rehydration uses `clojure.edn/read-string` line-by-line, parsing forms in a safe, robust, and zero-dependency manner.

### The Story of Perfect Replay
The entire state of the system is modeled as a pure projection of its transaction journal. The in-memory state (stored in `fsm.state/system*`) contains no hidden, ephemeral variables that cannot be derived from this ledger. 

As a result, system recovery and state rehydration are mathematically flawless:
1. Wiping the in-memory system state atom via `fsm.state/reset-state!`.
2. Iterating through the ledger line-by-line.
3. Invoking `journal/ingest-form!` on each parsed EDN expression.

```clojure
(defn load-journal! [journal-path]
  (state/reset-state!)
  (doseq [line (read-journal-lines journal-path)]
    (ingest-form! (edn/read-string line))))
```

This simple replay loop re-establishes the exact definitions, active scope runs, path hits, pending choices, and historical logs, guaranteeing zero runtime state drift. It enables perfect regression testing by using production transaction logs as self-contained mock suites and provides bulletproof auditability for compliance.

### Time-Travel Debugging
Because each transaction has a sequential, deterministic position in the ledger, developers and orchestrating agents can leverage **Time-Travel Debugging**. By processing only the first $N$ forms of the journal, the system can be rehydrated to its exact historical state at point $N$. This lets engineers inspect variable bindings, state-machine choices, and active paths, making it trivial to diagnose race conditions, analyze pipeline deviations, and reproduce production failures locally.

---

## Hierarchical Scopes: Parent/Child Lineage & Context Inheritance

### DSL Nested Tree Syntax
For complex workflows, flat list declarations can quickly become unmanageable. To solve this, the FSM DSL supports declaring nested, hierarchical topologies using the `:children` vector directly inside a parent's `define-scope` declaration:

```clojure
(define-scope :parent/analyze
  :tags #{:analysis}
  :children [(define-scope :parent/profile-columns
               :template {:type :cli :cli/cmd ["Rscript" "profile.R"]})
             (define-scope :parent/render-plots
               :template {:type :cli :cli/cmd ["Rscript" "plot.R"]})])
```

### Tree Flattening Engine
Under the hood, the system's operational core and storage backends are designed strictly around flat, functional collections. The forms compiler uses `fsm.forms/canonicalize-scope-tree` to recursively traverse the hierarchical AST of scopes at compile-time. 

During compilation, the engine:
1. Recursively crawls the tree starting from the root parent.
2. Extracts and flattens each child into its own individual `define-scope` statement.
3. Automatically computes the parent lineage and injects an `:ancestor-scope-ids` vector into the child's payload.

For instance, the nested scope `:parent/profile-columns` gets canonicalized as:
```clojure
(define-scope :parent/profile-columns
  :ancestor-scope-ids [:parent/analyze]
  :template {:type :cli :cli/cmd ["Rscript" "profile.R"]}
  ...)
```

### Context & Inheritance Mechanics
The computed `:ancestor-scope-ids` vector acts as a structural breadcrumb, providing the necessary context for runtime inheritance, scope isolation, and boundary enforcement:

1. **Filesystem Isolation and Directory Nesting**:
   The scheduler maps scope hierarchy directly to the directory tree. When a child scope is instantiated via `fsm.filesystem/instantiate-scope-run!`, the engine constructs child paths nested under the parent's runtime directory:
   $$\text{child-path} = \text{parent-path}/\text{children}/\text{child-run-name}/$$
   Each child directory is fully provisioned with isolated folders (`children`, `pids`, `artifacts`, `runtime`) and writes its own metadata manifest `scope.edn` referencing `:parent-scope-id` and `:parent-run-id`.

2. **Lexical Variable & Context Inheritance**:
   Locally-scoped variable lookups and relative namespaces are resolved by traversing the ancestor breadcrumbs upward. This lets nested scopes inherit variables, inputs, and environment configurations from their parent contexts without redundant declarations.

3. **Hierarchical Resource Constraints & Permission Boundaries**:
   Parent contexts can enforce global boundaries on their descendants. Constraints such as scoring thresholds (`:budget` allocations, which default to 25) and allowed execution steps (`:allows` permissions) propagate down the lineage tree. When validating nested tasks, the engine scans the ancestor lineage up to verify that total scoring costs do not exceed the cumulative budget and that no unauthorized transitions are attempted outside parent boundaries.

---

## Package Extensions

The framework is extended via specialized modules residing under the `packages/` directory:

### 1. Matrix Analysis Package (`packages/matrix/`)
*   Integrates statistical R scripts with the FSM pipeline.
*   **Scripts**:
    *   `profile_matrix.R`: Outlines and profiles numeric/categorical columns in CSV tables.
    *   `matrix_operations.R` & `reflect_operations.R`: Reflects available R algebraic operations into an inventory.
*   **Clojure Functions**:
    *   `matrix.template/build-raw-view`: Aggregates profiled statistics.
    *   `matrix.template/normalize-view`: Normalizes variables and prepares column double/categorical representations.
    *   `matrix.template/enumerate-paths`: Enumerates potential statistical execution paths.

### 2. Code Analysis Package (`packages/code/`)
*   Designed to introspect and profile Clojure source code.
*   **Clojure Functions**:
    *   `code.template/profile-source`: Parses Clojure source files to extract occurrences of forms and call metrics.
    *   `code.template/build-raw-view` & `code.template/normalize-view`: Converts parsed symbols into a dense matrix format.
    *   `code.template/enumerate-paths`: Analyzes and maps sequential call paths across parsed Clojure functions.

---

## Current Status and Next Milestone

The runtime, journal replay, scoped filesystem layout, context scoring, CLI/REPL interface, matrix package, and code package are implemented and covered by unit and end-to-end tests. Both template packages follow the same artifact pipeline:

```text
source artifact -> profile -> raw view -> normalized view -> candidate-path artifact
```

The matrix package profiles CSV input with R, preserves inferred column metadata, normalizes typed values, reflects the available R operation inventory, and enumerates deterministic analysis paths. The code package parses Clojure source into operation occurrences, including source location, enclosing definition, arguments, and adjacency metadata; it then derives code-analysis paths such as call-frequency, namespace-usage, argument-shape, and sequential-adjacency analysis.

The next implementation milestone is to close the candidate-path safety loop:

```text
candidate-path artifact -> choice-required response -> explicit select -> scoped execution -> historical result artifact
```

Candidate paths should remain structured data with stable string IDs (for example, `code.path/profile-call-frequency/str`) rather than being converted into ad hoc dynamic keywords. The runtime should journal the offered candidates, accept or reject a selected ID, instantiate the selected scope only after acceptance, and persist the result alongside the path history. This preserves deterministic replay while leaving execution of each concrete analysis operation explicit and auditable.

---

## Getting Started & Usage

All common operations are automated and packaged into the root-level `Makefile`.

### CLI Application Target Options
You can execute and command the FSM using Clojure directly:
```bash
# General Usage
clojure -M -m fsm.core [options]

# Options
  -j PATH   Journal file path (defaults to resources/scope_runs/root.journal.clj)
  -r PATH   Runtime ledger path (defaults to resources/scope_runs/root.runtime_ledger.clj)
  -w PATH   Scope runs root directory (defaults to resources/scope_runs)
  -e FORM   Evaluate one DSL form and exit
  -i        Start interactive command REPL
```

### Makefile Targets
Run commands via `make <target>`:

```bash
make run                      # Show app usage and options
make repl                     # Start a standard Clojure REPL
make cmd-repl                 # Start the FSM interactive command-line REPL
make lint                     # Lint all files with clj-kondo
make test                     # Run the core unit tests
make e2e                      # Run the comprehensive integration and smoke tests
make clean                    # Remove local run and logging files
```

---

## Testing & Validation

The project maintains extensive unit and end-to-end integration tests under `test/`:

*   **Unit Tests (`test/fsm/messaging_test.clj`)**: Validates the core messaging facade, manifold stream integrations, synchronization blocks, and state tracking.
*   **E2E Integration Tests**:
    *   `e2e-basic`: Smoke test for function-based scope definitions and status snapshots.
    *   `e2e-matrix-*`: Validates R algebraic scripts, profiling outputs, column normalization, and algebraic operations inventory reflection.
    *   `e2e-code-paths`: Validates parsing, raw-view building, and template path analysis for Clojure packages.
    *   `e2e-r`: Executes a complete CSV data parser through external R-script eigenvalue decompositions.

Run all tests before checking in code:
```bash
make e2e
```
