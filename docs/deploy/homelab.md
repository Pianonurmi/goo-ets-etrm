# Running the demo in a homelab

The showcase runs on a headless Linux VM on your own network: the same stack as the public demo (`ops/demo`:
PostgreSQL, the application in the `showcase` profile, Caddy in front), opened from a browser on another machine.
For a public server with a domain and HTTPS, follow [the VPS guide](vps.md) instead. To show the homelab instance to
invited people without opening a port, its Cloudflare Tunnel variant ([step 6](vps.md#6-invite-only-behind-a-cloudflare-tunnel))
works here unchanged.

A self-hosted CI runner ([ci-runner.md](../operations/ci-runner.md), only for a private repository) belongs on its own VM, not on this one: a build uses every
core for minutes and runs code from every pushed branch.

## What the VM needs

- 2 vCPUs, 4 GB RAM, a few GB of disk. The application takes up to 2.5 GB, PostgreSQL 768 MB.
- Docker Engine with the compose plugin (docs.docker.com, "Install Docker Engine"), your user in the `docker` group.
- A free port for HTTP: 80 by default, any other through `ETRM_HTTP_PORT`. Open it in the VM's firewall if it has
  one (`sudo ufw allow 80/tcp`).

## 1. Get the image

CI publishes `ghcr.io/pianonurmi/goo-ets-etrm:main`. The package is private until its owner makes it public, so pick
one of these.

- **Sign in to the registry.** Create a GitHub token with the `read:packages` scope, then on the VM:

  ```bash
  echo <token> | docker login ghcr.io -u <github user> --password-stdin
  ```

- **Make the package public** (owner only): on GitHub, Packages → goo-ets-etrm → Package settings → Change
  visibility → Public. No sign-in is needed after that.
- **Build it yourself.** Needs JDK 25, Node.js 22 and Docker on the build machine (the VM or any other). The build
  generates the jOOQ sources from a migrated database, so it starts PostgreSQL first:

  ```bash
  git clone https://github.com/Pianonurmi/goo-ets-etrm.git && cd goo-ets-etrm
  docker compose -f ops/docker-compose.yml up -d postgres
  export ETRM_CODEGEN_DB_URL=jdbc:postgresql://localhost:5432/etrm_codegen ETRM_DB_USER=etrm ETRM_DB_PASSWORD=etrm
  ./gradlew :apps:api:bootJar
  docker build -t ghcr.io/pianonurmi/goo-ets-etrm:main -f ops/Dockerfile .
  docker compose -f ops/docker-compose.yml down   # the build database is not needed any more
  ```

  Built on another machine: `docker save ghcr.io/pianonurmi/goo-ets-etrm:main | gzip > provena.tar.gz`, copy the
  file to the VM and `gunzip -c provena.tar.gz | docker load` there.

## 2. Configure

```bash
git clone https://github.com/Pianonurmi/goo-ets-etrm.git   # skip if you cloned it to build
cd goo-ets-etrm/ops/demo
cp .env.example .env && chmod 600 .env
```

Edit `.env`:

```
POSTGRES_PASSWORD=<any long random string>
ETRM_DEMO_PASSWORD=<your sign-in password, 12 characters or more; weak ones are refused>
ETRM_SITE_ADDRESS=:80
ETRM_HTTP_PORT=80        # e.g. 8088 when port 80 is taken on the VM
```

`ETRM_DEMO_USERS=alice:secret-one,bob:secret-two` sets several sign-ins instead of the single user `demo`.

## 3. Start

```bash
docker compose up -d            # with an image you built or loaded: docker compose up -d --pull never
docker compose logs -f app      # wait for "Demo data created in … s", about 40 seconds
```

Until the demo data is ready, the page shows a maintenance notice.

## 4. Use it

Open `http://<vm-ip>/` (or `http://<vm-ip>:<ETRM_HTTP_PORT>/`) from any machine on your network and sign in as
`demo` with your password. Choose a persona from the menu at the top right. The welcome dialog and the demo guide
(`/guide`) walk you through scenarios with guided tours, and `?` opens the manual for the screen you are on.

## Good to know

- **The data is temporary.** Every start wipes the database and creates the demo data again, and a nightly reset
  runs at 04:15 Helsinki time. Administration → Demo (as the admin persona) resets it on demand. Nothing you change
  survives a restart.
- **Plain HTTP.** The password travels unencrypted. That is fine inside your own network, but do not forward the port
  to the internet that way. For outside access use the [VPS guide](vps.md): a domain in `ETRM_SITE_ADDRESS` turns on
  HTTPS with Let's Encrypt (it needs ports 80 and 443 reachable from the internet).
- **Update:** to stay on a release, set `ETRM_IMAGE=ghcr.io/pianonurmi/goo-ets-etrm:0.12.0` (or `:0.12` for its patch
  releases) in `.env`; see [releases](../operations/releases.md) for the tags. Then
  `docker compose pull app && docker compose up -d app` with the registry image; with a self-built image,
  build again and `docker compose up -d --pull never`.
- **Stop:** `docker compose down` (keeps the volumes; `down -v` removes them). The restart policy brings the stack back
  after a reboot of the VM.
- **Troubleshooting** is the same as on a VPS: see [the VPS guide](vps.md#troubleshooting).
