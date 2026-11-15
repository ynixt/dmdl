# Security policy

## Supported versions

dmdl is in early development (0.0.x). Only the latest release and the `edge` image (latest `master`) receive security fixes. Please update before reporting.

## Reporting a vulnerability

**Do not open a public issue for security problems.** Report them privately through one of these channels:

- GitHub: [Report a vulnerability](https://github.com/ynixt/dmdl/security/advisories/new) (preferred)
- E-mail: mail@gabrielsilva.dev

Please include:

- the dmdl version (shown in the navbar) and how it is deployed (Docker image, reverse proxy, own Redis, ...);
- what an attacker can do and the steps to reproduce it;
- any logs or requests that help, with your addon URLs, debrid links, tokens and passwords removed.

dmdl is maintained in spare time. You can expect an acknowledgement within 7 days. Once a fix is released, the advisory is published with credit to you unless you prefer to stay anonymous.

## Scope

In scope: the dmdl backend, web UI, Docker image and the files in this repository.

Out of scope:

- Stremio addons, debrid services, Redis or aria2 themselves (report those to their maintainers), unless dmdl uses them in an unsafe way.
- An instance exposed to the internet before its admin account was created. Whoever opens a fresh instance first creates that account; this is documented in the [README](README.md#2-start-it).
- Attacks that need access to the host, the Docker socket or the `/data` volume.

## Handling secrets as a user

Addon manifest URLs and debrid download links often contain API keys. When you share logs, screenshots or exported settings, remove them first, and rotate a key if it was exposed.
