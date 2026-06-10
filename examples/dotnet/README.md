# MockServer .NET Examples

Runnable examples demonstrating the [MockServer .NET client](../../mockserver-client-dotnet/).

## Prerequisites

- **.NET SDK 8.0+**
- **MockServer running** on `localhost:1080` (e.g. `docker run -d -p 1080:1080 mockserver/mockserver`)
- Each example project references the local client via a `ProjectReference`, so
  no NuGet install is needed. Or use the published package:

  ```bash
  dotnet add package MockServer.Client
  ```

## Examples

| Folder | Description |
|--------|-------------|
| [create_expectation](create_expectation/) | Create an expectation, send a test request, and verify it was received. |
| [modify_proxied_response](modify_proxied_response/) | Register a RESPONSE breakpoint that modifies a proxied response in-flight. |

Each folder contains a runnable .NET console application and its own `README.md`
with instructions.
