# Publishing to Maven Central

## Prerequisites

1. Sonatype Central Portal account: https://central.sonatype.com
2. Namespace `io.agentguard` registered and verified
3. GPG key pair generated and uploaded to a keyserver

## Repository secrets needed

| Secret             | Description                                                               |
|--------------------|---------------------------------------------------------------------------|
| `CENTRAL_USERNAME` | Sonatype Central Portal token username                                    |
| `CENTRAL_PASSWORD` | Sonatype Central Portal token password                                    |
| `GPG_PRIVATE_KEY`  | ASCII-armored GPG private key (`gpg --armor --export-secret-keys KEY_ID`) |
| `GPG_PASSPHRASE`   | GPG key passphrase                                                        |

## Release process

```bash
# 1. Set the release version (removes -SNAPSHOT)
mvn versions:set -DnewVersion=0.1.0
mvn versions:commit

# 2. Build, sign, and verify locally first
mvn clean verify -P release

# 3. Deploy to Maven Central staging
mvn deploy -P release

# 4. Promote (or use Central Portal UI at https://central.sonatype.com)

# 5. Tag and push
git tag v0.1.0
git push origin v0.1.0

# 6. The GitHub Actions release job fires automatically on the v* tag
# 7. Create the GitHub Release (see CHANGELOG.md for notes)

# 8. Bump to next development version
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT
mvn versions:commit
git commit -am "chore: start 0.2.0-SNAPSHOT"
```

## What the `-P release` profile does

- Attaches source JARs (`maven-source-plugin`)
- Attaches Javadoc JARs (`maven-javadoc-plugin`)
- Signs all artifacts with GPG (`maven-gpg-plugin`)

All three are required by Maven Central.

## Modules published

All modules except `examples/*` are published:

- `agent-guard-bom`
- `agent-guard-core`
- `agent-guard-runtime`
- `agent-guard-otel`
- `agent-guard-langchain4j`
- `agent-guard-spring`
- `agent-guard-quarkus`

Examples have `<maven.deploy.skip>true</maven.deploy.skip>` set.
