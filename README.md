# GuestGraph

**The open-source guest identity graph.**

Guest data in hospitality is scattered — PMS, POS, booking engines, loyalty programs, wifi portals, review platforms — each with its own keys and its own version of the truth. GuestGraph ingests guest records from all of them and resolves which records belong to the same person, producing one unified, explainable guest profile: the guest graph.

## What it does

- **Ingest** raw guest records from any source system via a REST API — originals are stored immutably, never lost
- **Resolve** identities deterministically on strong identifiers (email, phone, loyalty ID, external keys), with transitive merging
- **Explain** every merge — ask *"why are these records one guest?"* and get the full decision chain
- **Unmerge** safely when resolution got it wrong — every merge is reversible
- **Query** unified golden profiles and their source records, per tenant

## Status

🚧 **Early development.** The core identity resolution service is being built spec-first — see [`docs/`](docs/) and [`.specify/`](.specify/) for the design and specs.

## Stack

- Java 25 (virtual threads) · Spring Boot 4 · PostgreSQL
- Maven
- Spec-driven development with [spec-kit](https://github.com/github/spec-kit)

## Design principles

1. **Source records are immutable** — the golden profile is derived, the original data is sacred
2. **Every merge is explainable and reversible** — identity resolution you can audit and trust
3. **Tenant-scoped from day one** — one instance serves many brands, properties, or customers
4. **API-first** — everything the engine can do is reachable over the REST API

## Roadmap

1. ✅→🚧 **Core** — identity resolution engine, guest graph, REST API *(current)*
2. **Timeline** — unified per-guest event timeline / journey
3. **Connectors** — ingest from real PMS/POS/booking systems
4. **Probabilistic matching** — fuzzy/ML resolution behind the same strategy interface

## License

[Apache 2.0](LICENSE) — GuestGraph's core is and will remain open source. Managed hosting and commercial services are planned at [guestgraph.io](https://guestgraph.io).
