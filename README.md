# WG-Chores

A Blazor Server app for managing household chores. .NET 8, SQLite, Entity Framework Core.

## Local Development

**Requirements:** .NET 8 SDK

```bash
cd WG-Chores
dotnet run
```

If using Homebrew's keg-only `dotnet@8`, run with:
`PATH="/opt/homebrew/opt/dotnet@8/bin:$PATH" dotnet run`

App runs at http://localhost:5245. SQLite DB (`wgchores.db`) is created automatically.

## Deploy

```bash
docker-compose up -d
```

App runs at http://localhost:8084. Data is persisted in the `wgchores-data` volume.

> **Note:** `docker-compose.yml` references an external `traefik_proxy` network. Remove the `networks` section if you're not using Traefik.
