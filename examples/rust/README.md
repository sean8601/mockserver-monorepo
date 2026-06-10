# MockServer Rust Examples

Runnable examples demonstrating the [MockServer Rust client](../../mockserver-client-rust/).

## Prerequisites

- **Rust 1.75+** (and Cargo)
- **MockServer running** on `localhost:1080` (e.g. `docker run -d -p 1080:1080 mockserver/mockserver`)
- Each example's `Cargo.toml` references the local crate via a `path`
  dependency, so no crate publish is needed. Or use the published crate:

  ```toml
  [dependencies]
  mockserver-client = "7.0"
  ```

## Examples

| Folder | Description |
|--------|-------------|
| [create_expectation](create_expectation/) | Create an expectation, send a test request, and verify it was received. |
| [modify_proxied_response](modify_proxied_response/) | Register a RESPONSE breakpoint that modifies a proxied response in-flight. |

Each folder contains a runnable Rust binary and its own `README.md` with
instructions.
